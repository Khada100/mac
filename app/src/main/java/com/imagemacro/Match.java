package com.imagemacro;

/** 템플릿 매칭 결과. x, y 는 실제 화면 좌표 기준 중심점. */
public final class Match {
    public final double confidence;
    public final int x;
    public final int y;
    public final double scale;

    public Match(double confidence, int x, int y, double scale) {
        this.confidence = confidence;
        this.x = x;
        this.y = y;
        this.scale = scale;
    }
}
