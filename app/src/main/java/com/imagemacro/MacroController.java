package com.imagemacro;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 앱 전체에서 하나만 쓰는 중앙 관리자.
 * - 매크로 엔진 시작/정지
 * - 로그/상태 보관 및 화면(메인 화면, 플로팅 버튼)에 전달
 * - 이미지 등록용 화면 캡처
 */
public final class MacroController {

    public interface Listener {
        /** 메인 스레드에서 호출됨 */
        void onLog(String line);

        /** 메인 스레드에서 호출됨 (매크로/캡처/접근성 상태가 바뀜) */
        void onStateChanged();
    }

    private static final MacroController INSTANCE = new MacroController();
    private static final int MAX_LOG = 300;

    public static MacroController get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private boolean openCvReady;
    private volatile MacroEngine engine;
    private Thread engineThread;
    private volatile String status = "정지";

    private MacroController() {
    }

    public void init(Context ctx) {
        openCvReady = loadOpenCv();
        log(openCvReady ? "[준비] OpenCV 로드 완료" : "[오류] OpenCV 라이브러리를 불러오지 못했습니다.");
    }

    private static boolean loadOpenCv() {
        try {
            System.loadLibrary("opencv_java4");
            return true;
        } catch (Throwable ignored) {
            // 아래 대안 시도
        }
        // OpenCV 버전에 따라 initLocal() / initDebug() 중 있는 것을 호출
        try {
            Class<?> loader = Class.forName("org.opencv.android.OpenCVLoader");
            for (String name : new String[]{"initLocal", "initDebug"}) {
                try {
                    Method m = loader.getMethod(name);
                    Object r = m.invoke(null);
                    if (Boolean.TRUE.equals(r)) {
                        return true;
                    }
                } catch (NoSuchMethodException ignored) {
                    // 다음 이름 시도
                }
            }
        } catch (Throwable ignored) {
            // 실패
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 매크로 시작 / 정지
    // ------------------------------------------------------------------

    public boolean isMacroRunning() {
        MacroEngine e = engine;
        return e != null && e.isRunning();
    }

    public synchronized void startMacro(Context ctx) {
        if (isMacroRunning()) {
            return;
        }
        if (!openCvReady) {
            log("[오류] OpenCV 가 준비되지 않아 시작할 수 없습니다.");
            return;
        }
        ScreenCaptureService capture = ScreenCaptureService.get();
        if (capture == null) {
            log("[오류] 화면 캡처가 꺼져 있습니다. 앱에서 '화면 캡처 시작'을 먼저 누르세요.");
            return;
        }
        if (MacroAccessibilityService.get() == null) {
            log("[오류] 접근성 서비스가 꺼져 있어 터치할 수 없습니다.");
            return;
        }

        MacroSettings s = MacroSettings.load(ctx);
        final OpenCvVision vision = new OpenCvVision(capture, s.processScale, s.parseScales());

        List<String> problems = new ArrayList<>();
        for (int i = 0; i < ImageStore.BUTTON_KEYS.length; i++) {
            String key = ImageStore.BUTTON_KEYS[i];
            String err = vision.addTemplate("btn:" + key, ImageStore.buttonFile(ctx, key));
            if (err != null) {
                problems.add(ImageStore.BUTTON_LABELS[i] + " - " + err);
            }
        }
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (File f : ImageStore.listTemplates(ctx)) {
            String name = ImageStore.nameOf(f);
            String err = vision.addTemplate("opt:" + name, f);
            if (err == null) {
                ids.add("opt:" + name);
                names.add(name);
            } else {
                log("[경고] 옵션 이미지 건너뜀: " + name + " - " + err);
            }
        }
        if (names.isEmpty()) {
            problems.add("회피할 옵션 이미지 - 1개 이상 등록 필요");
        }
        if (!problems.isEmpty()) {
            log("[오류] 시작할 수 없습니다. 아래 이미지를 먼저 등록하세요:");
            for (String p : problems) {
                log("   · " + p);
            }
            vision.close();
            notifyStateChanged();
            return;
        }

        MacroEngine.Tapper tapper = (x, y) -> {
            MacroAccessibilityService a = MacroAccessibilityService.get();
            return a != null && a.tap(x, y);
        };
        MacroEngine.Listener listener = new MacroEngine.Listener() {
            @Override
            public void onLog(String message) {
                log(message);
            }

            @Override
            public void onStatus(String st) {
                setStatus(st);
            }

            @Override
            public void onFinished() {
                if (!isMacroRunning()) {
                    setStatus("정지");
                }
                notifyStateChanged();
            }
        };

        final MacroEngine e = new MacroEngine(vision, tapper, listener, ids, names, s.toEngineConfig());
        engine = e;
        engineThread = new Thread(() -> {
            try {
                e.run();
            } finally {
                vision.close();
            }
        }, "macro-engine");
        engineThread.start();
        log("=== 매크로를 시작합니다 ===");
        notifyStateChanged();
    }

    public synchronized void stopMacro(String reason) {
        MacroEngine e = engine;
        if (e != null && e.isRunning()) {
            e.stop();
            log("=== 매크로 정지: " + reason + " ===");
        }
        notifyStateChanged();
    }

    // ------------------------------------------------------------------
    // 이미지 등록용 캡처
    // ------------------------------------------------------------------

    /** 플로팅 버튼 📷 : 플로팅 버튼을 잠깐 숨기고 현재 화면을 찍어 자르기 화면을 연다. */
    public void captureForRegistration(final MacroAccessibilityService svc) {
        final ScreenCaptureService capture = ScreenCaptureService.get();
        if (capture == null) {
            log("[등록] 화면 캡처가 꺼져 있습니다.");
            return;
        }
        if (isMacroRunning()) {
            stopMacro("이미지 등록을 위해 정지");
        }
        svc.setOverlayVisible(false);
        main.postDelayed(() -> new Thread(() -> {
            final File out = ImageStore.captureFile(svc);
            boolean saved;
            try {
                saved = capture.saveLatestFrame(out);
            } catch (Throwable t) {
                saved = false;
            }
            final boolean ok = saved;
            main.post(() -> {
                svc.setOverlayVisible(true);
                if (!ok) {
                    log("[등록] 화면을 가져오지 못했습니다. 잠시 후 다시 눌러 주세요.");
                    return;
                }
                Intent i = new Intent(svc, CropActivity.class);
                i.putExtra(CropActivity.EXTRA_IMAGE_PATH, out.getAbsolutePath());
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    svc.startActivity(i);
                } catch (RuntimeException ex) {
                    log("[등록] 자르기 화면을 열 수 없습니다: " + ex.getMessage());
                }
            });
        }, "register-capture").start(), 350);
    }

    // ------------------------------------------------------------------
    // 로그 / 상태
    // ------------------------------------------------------------------

    public void log(String message) {
        final String line;
        synchronized (logs) {
            line = timeFormat.format(new Date()) + " " + message;
            logs.addLast(line);
            while (logs.size() > MAX_LOG) {
                logs.removeFirst();
            }
        }
        main.post(() -> {
            for (Listener l : listeners) {
                l.onLog(line);
            }
        });
    }

    /** 최신 로그가 위로 오도록 최대 maxLines 줄. */
    public String getLogText(int maxLines) {
        StringBuilder sb = new StringBuilder();
        synchronized (logs) {
            Iterator<String> it = logs.descendingIterator();
            int n = 0;
            while (it.hasNext() && n < maxLines) {
                if (n > 0) {
                    sb.append('\n');
                }
                sb.append(it.next());
                n++;
            }
        }
        return sb.toString();
    }

    public String getLastLog() {
        synchronized (logs) {
            String last = logs.peekLast();
            return last == null ? "" : last;
        }
    }

    public String getStatus() {
        return status;
    }

    private void setStatus(String s) {
        if (!s.equals(status)) {
            status = s;
            notifyStateChanged();
        }
    }

    public void notifyStateChanged() {
        main.post(() -> {
            for (Listener l : listeners) {
                l.onStateChanged();
            }
        });
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }
}
