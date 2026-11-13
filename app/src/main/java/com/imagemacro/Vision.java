package com.imagemacro;

/**
 * 화면 캡처 + 템플릿 매칭을 추상화한 인터페이스.
 * 실제 앱에서는 OpenCvVision 이 구현하고, 테스트에서는 가짜 구현을 쓴다.
 */
public interface Vision {

    /** 최신 화면을 가져온다. 아직 화면이 없으면 false. */
    boolean capture();

    /**
     * 마지막으로 가져온 화면에서 템플릿을 찾는다.
     *
     * @param templateId 템플릿 ID (예: "btn:start", "opt:빙결")
     * @param threshold  이 값 이상일 때만 결과를 돌려준다 (TM_CCOEFF_NORMED 기준 0~1)
     * @return 찾은 위치(화면 실제 좌표 기준 중심점), 못 찾으면 null
     */
    Match find(String templateId, double threshold);

    /** 마지막으로 가져온 화면을 '터치 직전 화면'으로 기억한다. */
    default void markScreen() {
    }

    /**
     * 마지막으로 가져온 화면이 markScreen() 때와 얼마나 다른지.
     * 화면을 격자로 나눠 칸별 평균 밝기 차이(0~255) 중 가장 큰 값. 일부 글자만 바뀌어도 잡힌다.
     */
    default double diffFromMark() {
        return 255;
    }

    /** 마지막 두 번의 capture() 화면 차이 (같은 방식). 작으면 화면이 멈춰 있는 것. */
    default double lastFrameDiff() {
        return 0;
    }

    /** 진단용: 마지막 화면 정보 (예: "2340×1080 밝기 87±42") */
    default String describeScreen() {
        return "";
    }

    /** 진단용: 마지막 화면 밝기의 표준편차. 거의 0 이면 검은 화면/단색. 모르면 -1 */
    default double screenStdDev() {
        return -1;
    }

    /** 진단용: 화면 캡처 쪽 상태 (프레임 수신 수, 오류 등) */
    default String describeSource() {
        return "";
    }
}
