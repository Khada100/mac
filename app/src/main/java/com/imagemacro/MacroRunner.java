package com.imagemacro;

/** 돌아가는 매크로 하나 (기존 방식 MacroEngine / 블록 방식 SequenceEngine 공통). */
public interface MacroRunner extends Runnable {

    boolean isRunning();

    /** 어느 스레드에서든 호출 가능. 대기 중이면 즉시 깨워서 끝낸다. */
    void stop();
}
