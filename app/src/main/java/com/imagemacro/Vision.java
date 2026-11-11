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
}
