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

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        MacroController c = MacroController.get();
        if (ACTION_START.equals(action)) {
            c.log("[adb] 매크로 시작 명령");
            c.startMacro(context.getApplicationContext());
        } else if (ACTION_STOP.equals(action)) {
            c.stopMacro("adb 정지 명령");
        }
    }
}
