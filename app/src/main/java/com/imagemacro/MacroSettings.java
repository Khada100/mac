package com.imagemacro;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** 매크로 설정 (SharedPreferences 저장). */
public final class MacroSettings {

    private static final String PREFS = "macro_settings";

    /** 회피 옵션 글자 임계값. 윈도우판 0.85 에서 '기절 시간'이 '빙결 시간'으로 오인식돼서 기본값을 올림. */
    public double optionThreshold = 0.92;
    /** 버튼 임계값 (윈도우판 기본값과 동일). */
    public double buttonThreshold = 0.85;
    public int startWaitSeconds = 40;
    public int intervalMs = 300;
    public int clickDelayMs = 500;
    /** collect 버튼 최대 대기(초). 0 = 무한 대기 (윈도우판 동작). */
    public int buttonTimeoutSeconds = 120;
    /** 터치 후 화면이 바뀌고 멈출 때까지 기다리는 최대 시간(ms). 0 = 윈도우판처럼 고정 대기만. */
    public int settleMaxMs = 1500;
    public String scales = "0.95,1.0,1.05";
    /** 처리 해상도 비율. 1.0 = 원본. */
    public double processScale = 1.0;

    public static MacroSettings load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        MacroSettings s = new MacroSettings();
        s.optionThreshold = parseDouble(p.getString("optionThreshold", null), s.optionThreshold);
        s.buttonThreshold = parseDouble(p.getString("buttonThreshold", null), s.buttonThreshold);
        s.startWaitSeconds = parseInt(p.getString("startWaitSeconds", null), s.startWaitSeconds);
        s.intervalMs = parseInt(p.getString("intervalMs", null), s.intervalMs);
        s.clickDelayMs = parseInt(p.getString("clickDelayMs", null), s.clickDelayMs);
        s.buttonTimeoutSeconds = parseInt(p.getString("buttonTimeoutSeconds", null), s.buttonTimeoutSeconds);
        s.settleMaxMs = parseInt(p.getString("settleMaxMs", null), s.settleMaxMs);
        s.scales = p.getString("scales", s.scales);
        s.processScale = parseDouble(p.getString("processScale", null), s.processScale);
        s.sanitize();
        return s;
    }

    public void save(Context ctx) {
        sanitize();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("optionThreshold", String.valueOf(optionThreshold))
                .putString("buttonThreshold", String.valueOf(buttonThreshold))
                .putString("startWaitSeconds", String.valueOf(startWaitSeconds))
                .putString("intervalMs", String.valueOf(intervalMs))
                .putString("clickDelayMs", String.valueOf(clickDelayMs))
                .putString("buttonTimeoutSeconds", String.valueOf(buttonTimeoutSeconds))
                .putString("settleMaxMs", String.valueOf(settleMaxMs))
                .putString("scales", scales)
                .putString("processScale", String.valueOf(processScale))
                .apply();
    }

    /** 이상한 값이 들어와도 매크로가 망가지지 않도록 범위를 맞춘다. */
    public void sanitize() {
        optionThreshold = clamp(optionThreshold, 0.5, 0.999);
        buttonThreshold = clamp(buttonThreshold, 0.5, 0.999);
        startWaitSeconds = (int) clamp(startWaitSeconds, 0, 3600);
        intervalMs = (int) clamp(intervalMs, 0, 60000);
        clickDelayMs = (int) clamp(clickDelayMs, 0, 60000);
        buttonTimeoutSeconds = (int) clamp(buttonTimeoutSeconds, 0, 3600);
        settleMaxMs = (int) clamp(settleMaxMs, 0, 10000);
        processScale = clamp(processScale, 0.25, 1.0);
        if (scales == null || scales.trim().isEmpty()) {
            scales = "0.95,1.0,1.05";
        }
    }

    public double[] parseScales() {
        List<Double> out = new ArrayList<>();
        for (String part : scales.split(",")) {
            try {
                double v = Double.parseDouble(part.trim());
                if (v >= 0.2 && v <= 5.0) {
                    out.add(v);
                }
            } catch (NumberFormatException ignored) {
                // 무시
            }
        }
        if (out.isEmpty()) {
            out.add(1.0);
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    public MacroEngine.Config toEngineConfig() {
        MacroEngine.Config c = new MacroEngine.Config();
        c.optionThreshold = optionThreshold;
        c.buttonThreshold = buttonThreshold;
        c.startWaitSeconds = startWaitSeconds;
        c.intervalMs = intervalMs;
        c.clickDelayMs = clickDelayMs;
        c.buttonTimeoutSeconds = buttonTimeoutSeconds;
        c.settleMaxMs = settleMaxMs;
        return c;
    }

    public static double parseDouble(String s, double def) {
        if (s == null) {
            return def;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static int parseInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return (int) Math.round(Double.parseDouble(s.trim()));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
