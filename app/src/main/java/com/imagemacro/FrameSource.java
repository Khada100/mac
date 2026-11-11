package com.imagemacro;

/** 최신 화면 프레임(RGBA 바이트)을 제공하는 쪽. ScreenCaptureService 가 구현한다. */
public interface FrameSource {

    interface Consumer {
        /** rgba 배열은 콜백 안에서만 유효하다 (밖으로 들고 나가지 말 것). */
        void accept(byte[] rgba, int width, int height);
    }

    /** 최신 프레임이 있으면 consumer 를 호출하고 true, 없으면 false. */
    boolean withLatestFrame(Consumer consumer);
}
