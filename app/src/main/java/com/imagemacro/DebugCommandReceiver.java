package com.imagemacro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * PC 의 adb 로 매크로를 시작/정지하는 명령 (자동 테스트용).
 *   adb shell am broadcast -n com.imagemacro/.DebugCommandReceiver -a com.imagemacro.action.DEBUG_START
 *   adb shell am broadcast -n com.imagemacro/.DebugCommandReceiver -a com.imagemacro.action.DEBUG_STOP
 * android.permission.DUMP 권한이 있는 쪽(adb 셸, 시스템)만 보낼 수 있어서 다른 앱은 사용할 수 없다.
 */
public class DebugCommandReceiver extends BroadcastReceiver {

    public static final String ACTION_START = "com.imagemacro.action.DEBUG_START";
    public static final String ACTION_STOP = "com.imagemacro.action.DEBUG_STOP";
    /** 앱이 지금 보고 있는 화면을 files/debug_frame.png 로 저장 (실제 화면과 비교용) */
    public static final String ACTION_SNAPSHOT = "com.imagemacro.action.DEBUG_SNAPSHOT";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        MacroController c = MacroController.get();
        if (ACTION_START.equals(action)) {
            c.log("[adb] 매크로 시작 명령");
            c.startMacro(context.getApplicationContext());
        } else if (ACTION_STOP.equals(action)) {
            c.stopMacro("adb 정지 명령");
        } else if (ACTION_SNAPSHOT.equals(action)) {
            final ScreenCaptureService cap = ScreenCaptureService.get();
            final java.io.File out = new java.io.File(context.getFilesDir(), "debug_frame.png");
            if (cap == null) {
                c.log("[adb] 스냅샷 실패: 화면 캡처가 꺼져 있음");
                return;
            }
            new Thread(() -> {
                boolean ok = cap.saveLatestFrame(out);
                MacroController.get().log(ok ? "[adb] 스냅샷 저장" : "[adb] 스냅샷 실패: 화면 없음");
            }, "debug-snapshot").start();
        }
    }
}
