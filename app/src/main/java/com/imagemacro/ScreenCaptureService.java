package com.imagemacro;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * MediaProjection 으로 전체 화면을 계속 캡처해 두는 포그라운드 서비스.
 * (윈도우판의 mss 화면 캡처 역할)
 *
 * 회전 대응: 캡처용 가상 화면을 '긴 변 × 긴 변' 정사각형으로 한 번만 만든다.
 * 그러면 세로/가로 어느 쪽이든 실제 화면이 1:1 크기 그대로 정사각형 가운데에 들어가므로
 * (위아래 또는 좌우에 검은 여백) 그 부분만 잘라 쓰면 된다.
 * 예전처럼 회전할 때마다 가상 화면 크기를 바꾸면, 타이밍에 따라 화면이 작게 쪼그라든 채
 * 굳어 버리는 문제가 있었다 (에뮬레이터 테스트와 사용자 기기에서 확인).
 *
 * 화면이 바뀔 때만 새 프레임이 들어오므로, 마지막 프레임을 보관해 두고 엔진이 요청할 때 넘겨준다.
 */
public class ScreenCaptureService extends Service implements FrameSource {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String ACTION_STOP = "com.imagemacro.action.STOP_CAPTURE";

    private static final String CHANNEL_ID = "screen_capture";
    private static final int NOTIFICATION_ID = 1;

    private static volatile ScreenCaptureService instance;

    /** 캡처가 실제로 동작 중이면 서비스 인스턴스, 아니면 null. */
    public static ScreenCaptureService get() {
        return instance;
    }

    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    /** 가상 화면(정사각형) 한 변 길이 */
    private volatile int squareSize;
    private volatile int capDensity;
    /** 지금 실제 화면 크기 (회전에 따라 바뀜) = 잘라 낼 크기 */
    private volatile int contentWidth;
    private volatile int contentHeight;
    private long lastMetricsCheck;

    private final Object frameLock = new Object();
    private Image pendingImage;
    private byte[] frameData;
    private int frameWidth;
    private int frameHeight;
    private boolean stopping;

    // 진단용
    private volatile long framesReceived;
    private volatile long lastFrameUptime;
    private volatile int acquireErrors;
    private volatile String lastError = "";
    private volatile boolean firstFrameLogged;
    private volatile double marginBrightness = -1;
    private volatile boolean marginWarned;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            MacroController.get().log("[화면 캡처] 시스템이 캡처를 종료했습니다 (화면 잠금 등). 앱에서 다시 시작하세요.");
            stopSelf();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        captureThread = new HandlerThread("screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Android 14+: getMediaProjection() 보다 먼저 mediaProjection 타입으로 포그라운드 시작해야 함
        try {
            startForegroundCompat();
        } catch (RuntimeException e) {
            MacroController.get().log("[화면 캡처] 포그라운드 서비스 시작 실패: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        if (projection != null) {
            return START_NOT_STICKY; // 이미 캡처 중
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = getResultData(intent);
        if (resultData == null) {
            MacroController.get().log("[화면 캡처] 권한 정보가 없어 시작할 수 없습니다.");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                throw new IllegalStateException("MediaProjection 을 받지 못함");
            }
            // Android 14+: createVirtualDisplay 전에 콜백 등록 필수
            projection.registerCallback(projectionCallback, captureHandler);
            createVirtualDisplay();
        } catch (RuntimeException e) {
            MacroController.get().log("[화면 캡처] 시작 실패: " + e.getMessage());
            projection = null;
            stopSelf();
            return START_NOT_STICKY;
        }

        instance = this;
        MacroController.get().log(String.format(Locale.US, "[화면 캡처] 시작 (화면 %d×%d, 캡처 %d×%d 정사각형)",
                contentWidth, contentHeight, squareSize, squareSize));
        MacroController.get().notifyStateChanged();
        return START_NOT_STICKY;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Handler h = captureHandler;
        if (h != null) {
            h.post(() -> updateContentSize(true));
        }
    }

    @Override
    public void onDestroy() {
        boolean wasRunning = instance == this;
        instance = null;
        MacroController.get().stopMacro("화면 캡처가 꺼짐");

        synchronized (frameLock) {
            stopping = true;
            if (pendingImage != null) {
                pendingImage.close();
                pendingImage = null;
            }
            frameData = null;
        }

        final VirtualDisplay vd = virtualDisplay;
        final ImageReader reader = imageReader;
        final MediaProjection mp = projection;
        virtualDisplay = null;
        imageReader = null;
        projection = null;
        captureHandler.post(() -> {
            if (vd != null) {
                vd.release();
            }
            if (reader != null) {
                reader.setOnImageAvailableListener(null, null);
                reader.close();
            }
            if (mp != null) {
                mp.unregisterCallback(projectionCallback);
                mp.stop();
            }
        });
        captureThread.quitSafely();

        if (wasRunning) {
            MacroController.get().log("[화면 캡처] 중지됨");
        }
        MacroController.get().notifyStateChanged();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // 캡처 설정
    // ------------------------------------------------------------------

    private void createVirtualDisplay() {
        DisplayMetrics m = realMetrics();
        contentWidth = m.widthPixels;
        contentHeight = m.heightPixels;
        squareSize = Math.max(m.widthPixels, m.heightPixels);
        capDensity = m.densityDpi;
        ImageReader reader = newReader(squareSize);
        synchronized (frameLock) {
            imageReader = reader;
        }
        virtualDisplay = projection.createVirtualDisplay(
                "ImageMacroCapture",
                squareSize, squareSize, capDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, captureHandler);
    }

    private ImageReader newReader(int size) {
        ImageReader r = ImageReader.newInstance(size, size, PixelFormat.RGBA_8888, 3);
        r.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        return r;
    }

    /**
     * 실제 화면 크기를 다시 읽어 잘라 낼 크기를 맞춘다 (회전 감지).
     * 화면 해상도 자체가 바뀐 드문 경우에만 정사각형 크기를 바꾼다.
     */
    private void updateContentSize(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastMetricsCheck < 300) {
            return;
        }
        lastMetricsCheck = now;
        DisplayMetrics m = realMetrics();
        int w = m.widthPixels;
        int h = m.heightPixels;
        if (w == contentWidth && h == contentHeight) {
            return;
        }
        synchronized (frameLock) {
            contentWidth = w;
            contentHeight = h;
            frameData = null; // 이전 방향의 화면은 버림
            marginWarned = false;
        }
        MacroController.get().log(String.format(Locale.US, "[화면 캡처] 화면 방향 변경 → %d×%d", w, h));

        int need = Math.max(w, h);
        if (need != squareSize) {
            resizeSquare(need, m.densityDpi);
        }
    }

    /** 화면 해상도 변경 같은 드문 경우: 정사각형 크기 조정 (캡처 스레드에서 실행) */
    private void resizeSquare(final int size, final int density) {
        Handler h = captureHandler;
        if (h == null) {
            return;
        }
        h.post(() -> {
            if (projection == null || virtualDisplay == null || stopping || size == squareSize) {
                return;
            }
            ImageReader old = imageReader;
            ImageReader fresh = newReader(size);
            synchronized (frameLock) {
                if (pendingImage != null) {
                    pendingImage.close();
                    pendingImage = null;
                }
                frameData = null;
                imageReader = fresh;
                squareSize = size;
                capDensity = density;
            }
            firstFrameLogged = false;
            virtualDisplay.resize(size, size, density);
            virtualDisplay.setSurface(fresh.getSurface());
            if (old != null) {
                old.setOnImageAvailableListener(null, null);
                old.close();
            }
            MacroController.get().log(String.format(Locale.US, "[화면 캡처] 화면 해상도 변경 → 캡처 %d×%d", size, size));
        });
    }

    private void onImageAvailable(ImageReader reader) {
        Image image;
        try {
            image = reader.acquireNextImage();
        } catch (RuntimeException e) {
            acquireErrors++;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (acquireErrors == 1 || acquireErrors % 200 == 0) {
                MacroController.get().log("[화면 캡처] 프레임 읽기 오류 (" + acquireErrors + "회): " + lastError);
            }
            return;
        }
        if (image == null) {
            return;
        }
        final int imgW = image.getWidth();
        final int imgH = image.getHeight();
        synchronized (frameLock) {
            if (reader != imageReader || stopping) {
                image.close();
                return;
            }
            if (pendingImage != null) {
                pendingImage.close();
            }
            pendingImage = image;
        }
        framesReceived++;
        lastFrameUptime = SystemClock.uptimeMillis();
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            MacroController.get().log(String.format(Locale.US, "[화면 캡처] 화면 수신 시작 (%d×%d)", imgW, imgH));
        }
    }

    // ------------------------------------------------------------------
    // 프레임 제공
    // ------------------------------------------------------------------

    @Override
    public boolean withLatestFrame(FrameSource.Consumer consumer) {
        updateContentSize(false);
        synchronized (frameLock) {
            if (stopping) {
                return false;
            }
            if (pendingImage != null) {
                try {
                    copyImage(pendingImage);
                } catch (RuntimeException e) {
                    frameData = null;
                    lastError = "복사 오류 " + e.getClass().getSimpleName() + ": " + e.getMessage();
                } finally {
                    pendingImage.close();
                    pendingImage = null;
                }
            }
            if (frameData == null) {
                return false;
            }
            consumer.accept(frameData, frameWidth, frameHeight);
            return true;
        }
    }

    @Override
    public String debugInfo() {
        long ago = lastFrameUptime == 0 ? -1 : (SystemClock.uptimeMillis() - lastFrameUptime) / 1000;
        String s = String.format(Locale.US, "수신 %d장(마지막 %s), 화면 %d×%d / 캡처 %d×%d",
                framesReceived, ago < 0 ? "없음" : ago + "초 전", contentWidth, contentHeight, squareSize, squareSize);
        double mb = marginBrightness;
        if (mb >= 0) {
            s += String.format(Locale.US, ", 여백 밝기 %.0f(정상 0)", mb);
        }
        if (acquireErrors > 0 || lastError.startsWith("복사")) {
            s += ", 오류 " + acquireErrors + "회 (" + lastError + ")";
        }
        return s;
    }

    /** 최신 화면을 PNG 로 저장 (이미지 등록용). */
    public boolean saveLatestFrame(File out) {
        final Bitmap[] holder = new Bitmap[1];
        boolean ok = withLatestFrame((data, width, height) -> {
            Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(ByteBuffer.wrap(data));
            holder[0] = b;
        });
        Bitmap bmp = holder[0];
        if (!ok || bmp == null) {
            return false;
        }
        bmp.setHasAlpha(false);
        try {
            ImageStore.savePng(bmp, out);
            return true;
        } catch (IOException e) {
            MacroController.get().log("[등록] 캡처 저장 실패: " + e.getMessage());
            return false;
        } finally {
            bmp.recycle();
        }
    }

    /**
     * frameLock 안에서 호출. 정사각형 캡처에서 실제 화면 부분(가운데)만 잘라 복사.
     * 안드로이드는 화면을 비율을 지켜 가운데 맞춤으로 넣으므로 위치는 ((한 변 - 폭) / 2, (한 변 - 높이) / 2).
     */
    private void copyImage(Image img) {
        Image.Plane plane = img.getPlanes()[0];
        ByteBuffer buf = plane.getBuffer();
        int imgW = img.getWidth();
        int imgH = img.getHeight();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();

        int w = contentWidth;
        int h = contentHeight;
        if (w <= 0 || h <= 0 || w > imgW || h > imgH) {
            w = imgW;
            h = imgH;
        }
        int left = (imgW - w) / 2;
        int top = (imgH - h) / 2;
        int rowBytes = w * 4;
        if (frameData == null || frameData.length != rowBytes * h) {
            frameData = new byte[rowBytes * h];
        }
        if (pixelStride == 4) {
            for (int y = 0; y < h; y++) {
                buf.position((top + y) * rowStride + left * 4);
                buf.get(frameData, y * rowBytes, rowBytes);
            }
        } else {
            for (int y = 0; y < h; y++) {
                int src = (top + y) * rowStride + left * pixelStride;
                int dst = y * rowBytes;
                for (int x = 0; x < w; x++) {
                    int p = src + x * pixelStride;
                    frameData[dst++] = buf.get(p);
                    frameData[dst++] = buf.get(p + 1);
                    frameData[dst++] = buf.get(p + 2);
                    frameData[dst++] = buf.get(p + 3);
                }
            }
        }
        frameWidth = w;
        frameHeight = h;
        checkMargins(buf, imgW, imgH, rowStride, pixelStride, left, top);
    }

    /**
     * 진단: 여백(잘라 낸 바깥쪽)은 원래 검은색이어야 한다. 밝으면 화면 위치가 예상과 다르다는 뜻.
     * 여백 가운데 줄 몇 개만 드문드문 확인 (가벼움).
     */
    private void checkMargins(ByteBuffer buf, int imgW, int imgH, int rowStride, int pixelStride, int left, int top) {
        long sum = 0;
        int count = 0;
        if (top > 0) {
            int[] rows = {top / 2, imgH - 1 - top / 2};
            for (int r : rows) {
                for (int x = 0; x < imgW; x += 16) {
                    int p = r * rowStride + x * pixelStride;
                    sum += (buf.get(p) & 0xff) + (buf.get(p + 1) & 0xff) + (buf.get(p + 2) & 0xff);
                    count += 3;
                }
            }
        }
        if (left > 0) {
            int[] cols = {left / 2, imgW - 1 - left / 2};
            for (int c : cols) {
                for (int y = 0; y < imgH; y += 16) {
                    int p = y * rowStride + c * pixelStride;
                    sum += (buf.get(p) & 0xff) + (buf.get(p + 1) & 0xff) + (buf.get(p + 2) & 0xff);
                    count += 3;
                }
            }
        }
        if (count == 0) {
            marginBrightness = -1;
            return;
        }
        double mean = sum / (double) count;
        marginBrightness = mean;
        if (mean > 30 && !marginWarned) {
            marginWarned = true;
            MacroController.get().log(String.format(Locale.US,
                    "[화면 캡처] 경고: 캡처 화면 위치가 예상과 달라요 (여백 밝기 %.0f). 캡처를 중지했다가 다시 시작해 보세요.", mean));
        }
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics realMetrics() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        DisplayMetrics m = new DisplayMetrics();
        d.getRealMetrics(m);
        return m;
    }

    // ------------------------------------------------------------------
    // 알림 / 포그라운드
    // ------------------------------------------------------------------

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "화면 캡처", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("매크로가 화면을 캡처하는 동안 표시됩니다.");
            nm.createNotificationChannel(ch);
        }
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ScreenCaptureService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_macro)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("화면 캡처 중 · 게임 위 떠 있는 버튼으로 조작")
                .setContentIntent(openApp)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_macro), "캡처 중지", stop).build())
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent getResultData(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        }
        return intent.getParcelableExtra(EXTRA_RESULT_DATA);
    }
}
