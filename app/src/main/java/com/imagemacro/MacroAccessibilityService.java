package com.imagemacro;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 접근성 서비스.
 * 1) 화면 좌표 터치 (윈도우판 pyautogui.click 역할)
 * 2) 게임 위에 떠 있는 조작 버튼 (시작/정지, 📷 이미지 등록, ⚙ 앱 열기)
 *
 * 접근성 오버레이(TYPE_ACCESSIBILITY_OVERLAY)를 쓰므로 '다른 앱 위에 표시' 권한이 따로 필요 없다.
 */
public class MacroAccessibilityService extends AccessibilityService implements MacroController.Listener {

    private static volatile MacroAccessibilityService instance;

    /** 접근성 서비스가 켜져 있으면 인스턴스, 아니면 null. */
    public static MacroAccessibilityService get() {
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View overlay;
    private WindowManager.LayoutParams overlayParams;
    private TextView btnToggle;
    private TextView textStatus;
    private TextView textLog;
    private int savedX = 24;
    private int savedY = 160;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        MacroController.get().addListener(this);
        MacroController.get().log("[접근성] 서비스 연결됨 (터치 가능)");
        MacroController.get().notifyStateChanged();
        refreshOverlay();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 사용하지 않음 (터치와 오버레이만 사용)
    }

    @Override
    public void onInterrupt() {
        // 사용하지 않음
    }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        if (instance != this) {
            return;
        }
        instance = null;
        MacroController.get().removeListener(this);
        MacroController.get().stopMacro("접근성 서비스가 꺼짐");
        removeOverlay();
        MacroController.get().log("[접근성] 서비스 꺼짐");
        MacroController.get().notifyStateChanged();
    }

    // ------------------------------------------------------------------
    // 터치
    // ------------------------------------------------------------------

    /** 화면 좌표 터치. 어느 스레드에서 호출해도 된다 (메인 스레드 제외 권장). */
    public boolean tap(final int x, final int y) {
        if (x < 0 || y < 0) {
            return false;
        }
        final AtomicBoolean ok = new AtomicBoolean(false);
        final CountDownLatch latch = new CountDownLatch(1);
        main.post(() -> {
            try {
                Path path = new Path();
                path.moveTo(x, y);
                GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 60);
                GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
                ok.set(dispatchGesture(gesture, null, null));
            } catch (RuntimeException e) {
                ok.set(false);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    // ------------------------------------------------------------------
    // 플로팅 버튼
    // ------------------------------------------------------------------

    @Override
    public void onLog(String line) {
        if (textLog != null) {
            textLog.setText(stripTime(line));
        }
    }

    @Override
    public void onStateChanged() {
        refreshOverlay();
    }

    /** 화면 캡처가 켜져 있을 때만 플로팅 버튼을 보여준다. */
    private void refreshOverlay() {
        boolean want = instance == this && ScreenCaptureService.get() != null;
        if (want && overlay == null) {
            addOverlay();
        } else if (!want && overlay != null) {
            removeOverlay();
        }
        if (overlay != null) {
            MacroController c = MacroController.get();
            boolean running = c.isMacroRunning();
            btnToggle.setText(running ? "■ 정지" : "▶ 시작");
            btnToggle.setBackgroundResource(running ? R.drawable.overlay_btn_stop : R.drawable.overlay_btn_start);
            textStatus.setText(running ? c.getStatus() : "대기 중 · ▶로 시작, 📷로 이미지 등록");
            if (textLog.getText().length() == 0) {
                textLog.setText(stripTime(c.getLastLog()));
            }
        }
    }

    public void setOverlayVisible(boolean visible) {
        if (overlay != null) {
            overlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void addOverlay() {
        View v = LayoutInflater.from(this).inflate(R.layout.overlay, null);
        btnToggle = v.findViewById(R.id.overlayToggle);
        textStatus = v.findViewById(R.id.overlayStatus);
        textLog = v.findViewById(R.id.overlayLog);
        TextView btnCapture = v.findViewById(R.id.overlayCapture);
        TextView btnApp = v.findViewById(R.id.overlayApp);
        TextView handle = v.findViewById(R.id.overlayHandle);

        btnToggle.setOnClickListener(view -> {
            MacroController c = MacroController.get();
            if (c.isMacroRunning()) {
                c.stopMacro("사용자가 정지");
            } else {
                c.startMacro(this);
            }
        });
        btnCapture.setOnClickListener(view -> MacroController.get().captureForRegistration(this));
        btnApp.setOnClickListener(view -> {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        });
        handle.setOnTouchListener(new DragListener());

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = savedX;
        lp.y = savedY;
        try {
            windowManager.addView(v, lp);
            overlay = v;
            overlayParams = lp;
        } catch (RuntimeException e) {
            overlay = null;
            overlayParams = null;
            MacroController.get().log("[접근성] 플로팅 버튼을 띄울 수 없습니다: " + e.getMessage());
        }
    }

    private void removeOverlay() {
        if (overlay != null) {
            if (overlayParams != null) {
                savedX = overlayParams.x;
                savedY = overlayParams.y;
            }
            try {
                windowManager.removeView(overlay);
            } catch (RuntimeException ignored) {
                // 이미 제거됨
            }
        }
        overlay = null;
        overlayParams = null;
        btnToggle = null;
        textStatus = null;
        textLog = null;
    }

    private static String stripTime(String line) {
        // "HH:mm:ss 메시지" -> "메시지"
        if (line != null && line.length() > 9 && line.charAt(2) == ':' && line.charAt(8) == ' ') {
            return line.substring(9);
        }
        return line == null ? "" : line;
    }

    /** ✥ 손잡이를 끌어서 플로팅 버튼 위치 이동 */
    private final class DragListener implements View.OnTouchListener {
        private float downX;
        private float downY;
        private int startX;
        private int startY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (overlay == null || overlayParams == null) {
                return false;
            }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = overlayParams.x;
                    startY = overlayParams.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    overlayParams.x = startX + (int) (e.getRawX() - downX);
                    overlayParams.y = startY + (int) (e.getRawY() - downY);
                    try {
                        windowManager.updateViewLayout(overlay, overlayParams);
                    } catch (RuntimeException ignored) {
                        // 무시
                    }
                    return true;
                default:
                    return true;
            }
        }
    }
}
