package com.imagemacro;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 윈도우판 image_finder_gui.py 의 run_macro_loop 를 그대로 옮긴 매크로 본체.
 *
 * 동작 순서 (윈도우판과 동일):
 *   1. 회피 옵션 템플릿 검색
 *   2. 하나라도 발견 -> reload 클릭 -> 1번으로
 *   3. 하나도 없음   -> start 검색 -> 클릭 -> N초 대기
 *   4. collect1 클릭 -> collect2 클릭 -> 1번으로
 *
 * 윈도우판 대비 개선:
 *   - 옵션/버튼 임계값 분리 (옵션 글자 오탐 방지)
 *   - collect 버튼 대기 시간 제한 (무한 대기 방지)
 *   - 예외가 나도 조용히 죽지 않고 로그를 남기고 재시도
 *   - 엔진마다 자기 running 플래그를 가지므로 정지 후 바로 시작해도 두 개가 동시에 돌지 않음
 *
 * 안드로이드 API 에 의존하지 않는 순수 Java 클래스라 PC 에서도 테스트할 수 있다.
 */
public final class MacroEngine implements Runnable {

    public static final String START = "btn:start";
    public static final String RELOAD = "btn:reload";
    public static final String COLLECT1 = "btn:collect1";
    public static final String COLLECT2 = "btn:collect2";

    private static final int MAX_CONSECUTIVE_ERRORS = 5;

    public interface Tapper {
        /** 화면 좌표 (x, y) 를 터치. 터치 요청에 실패하면 false. */
        boolean tap(int x, int y);
    }

    public interface Listener {
        void onLog(String message);

        void onStatus(String status);

        void onFinished();
    }

    public static final class Config {
        public double optionThreshold = 0.92;
        public double buttonThreshold = 0.85;
        public int startWaitSeconds = 40;
        public int intervalMs = 300;
        public int clickDelayMs = 500;
        /** collect 버튼을 기다리는 최대 시간(초). 0 이하면 윈도우판처럼 무한 대기. */
        public int buttonTimeoutSeconds = 120;
        /**
         * 터치 후 화면이 바뀌고 멈출 때까지 기다리는 최대 시간(ms).
         * 화면 전환 중에 다시 검사해서 새로고침을 두 번 누르거나 엉뚱한 곳을 누르는 것을 막는다.
         * 0 이면 윈도우판처럼 clickDelayMs 만큼만 기다린다.
         */
        public int settleMaxMs = 1500;
    }

    /** 터치 후 이만큼 바뀌면 '화면이 바뀌었다'고 본다 (칸별 평균 밝기 차이). */
    private static final double CHANGE_DIFF = 8.0;
    /** 연속 두 화면 차이가 이보다 작으면 '멈췄다'고 본다. */
    private static final double STABLE_DIFF = 3.0;
    /** 터치 후 화면 변화를 기다리는 최대 시간. */
    private static final long CHANGE_TIMEOUT_MS = 3000;

    /** 터치 실패 (접근성 서비스 꺼짐 등) - 계속해도 의미가 없으므로 엔진을 멈춘다. */
    private static final class TapFailedException extends RuntimeException {
        TapFailedException(String message) {
            super(message);
        }
    }

    private final Vision vision;
    private final Tapper tapper;
    private final Listener listener;
    private final List<String> optionIds;
    private final List<String> optionNames;
    private final Config config;

    private volatile boolean running = true;
    private volatile Thread thread;
    private int reloadCount = 0;
    private int cycleCount = 0;

    public MacroEngine(Vision vision, Tapper tapper, Listener listener,
                       List<String> optionIds, List<String> optionNames, Config config) {
        if (optionIds.size() != optionNames.size()) {
            throw new IllegalArgumentException("optionIds/optionNames size mismatch");
        }
        this.vision = vision;
        this.tapper = tapper;
        this.listener = listener;
        this.optionIds = new ArrayList<>(optionIds);
        this.optionNames = new ArrayList<>(optionNames);
        this.config = config;
    }

    public boolean isRunning() {
        return running;
    }

    public int getReloadCount() {
        return reloadCount;
    }

    public int getCycleCount() {
        return cycleCount;
    }

    /** 어느 스레드에서든 호출 가능. 대기 중이면 즉시 깨워서 끝낸다. */
    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void run() {
        thread = Thread.currentThread();
        try {
            if (running) {
                loop();
            }
        } catch (InterruptedException e) {
            // stop() 으로 깨어난 정상 종료
        } catch (TapFailedException e) {
            log("[오류] " + e.getMessage());
        } catch (Throwable t) {
            log("[오류] 매크로가 예기치 않게 멈췄습니다: " + describe(t));
        } finally {
            running = false;
            thread = null;
            listener.onStatus("정지");
            listener.onFinished();
        }
    }

    private void loop() throws InterruptedException {
        log(String.format(Locale.US,
                "[설정 완료] 감시 템플릿 %d개 (%s) | 옵션 임계값 %.2f, 버튼 임계값 %.2f",
                optionNames.size(), join(optionNames), config.optionThreshold, config.buttonThreshold));

        int consecutiveErrors = 0;
        while (running) {
            try {
                step();
                consecutiveErrors = 0;
            } catch (InterruptedException | TapFailedException e) {
                throw e;
            } catch (RuntimeException e) {
                consecutiveErrors++;
                log("[경고] 처리 중 오류 (" + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS + "): " + describe(e));
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    log("[오류] 오류가 계속되어 매크로를 정지합니다.");
                    return;
                }
                sleep(1000);
            }
        }
    }

    /** 메인 루프 한 바퀴. */
    private void step() throws InterruptedException {
        // 1. 화면 캡처
        if (!vision.capture()) {
            status("화면을 기다리는 중...");
            sleep(500);
            return;
        }

        // 2. 회피 옵션 템플릿 검색
        status("옵션 검사 중");
        List<String> found = new ArrayList<>();
        for (int i = 0; i < optionIds.size() && running; i++) {
            Match m = vision.find(optionIds.get(i), config.optionThreshold);
            if (m != null) {
                found.add(optionNames.get(i));
                log(String.format(Locale.US, "[템플릿 발견] %s (신뢰도: %.2f)", optionNames.get(i), m.confidence));
            }
        }
        if (!running) {
            return;
        }

        // 3. 템플릿 발견 -> reload 클릭 -> 재검색
        if (!found.isEmpty()) {
            log("[상태] 템플릿 감지됨 (" + join(found) + ") -> reload 검색");
            Match reload = vision.find(RELOAD, config.buttonThreshold);
            if (reload != null) {
                tap(reload, "RELOAD");
                reloadCount++;
                status("새로고침 " + reloadCount + "회");
                waitAfterTap("RELOAD");
            } else {
                log("[RELOAD 못 찾음] 재검색 진행");
            }
            sleep(config.intervalMs);
            return;
        }

        // 4. 템플릿 없음 -> start 검색
        Match start = vision.find(START, config.buttonThreshold);
        if (start == null) {
            status("시작 버튼을 찾는 중");
            sleep(config.intervalMs);
            return;
        }
        tap(start, "START");
        log("[START] " + config.startWaitSeconds + "초 대기 시작");

        // 5. N초 대기 (1초 단위로 쪼개서 정지에 바로 반응)
        for (int remaining = config.startWaitSeconds; remaining > 0 && running; remaining--) {
            status("전투 대기 " + remaining + "초");
            sleep(1000);
        }
        if (!running) {
            return;
        }

        // 6. collect1 -> collect2
        log("[대기 완료] collect1 검색 중...");
        if (!findAndTap(COLLECT1, "COLLECT1")) {
            return;
        }
        log("[COLLECT1 완료] collect2 검색 중...");
        if (!findAndTap(COLLECT2, "COLLECT2")) {
            return;
        }
        cycleCount++;
        log("[모든 과정 완료] templates 재검색으로 돌아갑니다. (완료 " + cycleCount + "회, 새로고침 누적 " + reloadCount + "회)");
        sleep(config.intervalMs);
    }

    /** 버튼이 나타날 때까지 0.1초 간격으로 찾아서 클릭. 시간 초과/정지 시 false. */
    private boolean findAndTap(String templateId, String label) throws InterruptedException {
        long deadline = config.buttonTimeoutSeconds > 0
                ? System.currentTimeMillis() + config.buttonTimeoutSeconds * 1000L
                : Long.MAX_VALUE;
        status(label + " 찾는 중");
        while (running) {
            if (vision.capture()) {
                Match m = vision.find(templateId, config.buttonThreshold);
                if (m != null) {
                    tap(m, label);
                    waitAfterTap(label);
                    return true;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                log("[" + label + " 시간 초과] " + config.buttonTimeoutSeconds
                        + "초 동안 못 찾아서 처음 단계(옵션 검사)로 돌아갑니다.");
                return false;
            }
            sleep(100);
        }
        return false;
    }

    private void tap(Match m, String label) throws InterruptedException {
        vision.markScreen(); // 터치 직전 화면 기억 (터치 후 바뀌었는지 비교용)
        boolean ok = tapper.tap(m.x, m.y);
        if (!running) {
            throw new InterruptedException();
        }
        if (!ok) {
            throw new TapFailedException("터치 실패: 접근성 서비스가 꺼져 있거나 응답하지 않습니다.");
        }
        log(String.format(Locale.US, "[%s 클릭] 위치=(%d, %d) 신뢰도=%.2f", label, m.x, m.y, m.confidence));
    }

    /**
     * 터치 후 대기: clickDelayMs 만큼 기다린 뒤, 화면이 실제로 바뀌고(최대 3초) 멈출 때까지(최대 settleMaxMs) 기다린다.
     * 화면 전환 도중의 화면을 다시 검사해서 같은 버튼을 두 번 누르거나 엉뚱한 곳을 누르는 것을 막는다.
     */
    private void waitAfterTap(String label) throws InterruptedException {
        sleep(config.clickDelayMs);
        if (config.settleMaxMs <= 0) {
            return;
        }
        long changeDeadline = System.currentTimeMillis() + CHANGE_TIMEOUT_MS;
        boolean changed = false;
        while (running) {
            if (vision.capture() && vision.diffFromMark() >= CHANGE_DIFF) {
                changed = true;
                break;
            }
            if (System.currentTimeMillis() > changeDeadline) {
                break;
            }
            sleep(100);
        }
        if (!running) {
            return;
        }
        if (!changed) {
            log("[" + label + "] 터치 후 화면 변화가 없어요. 다음 검사로 넘어갑니다.");
            return;
        }
        long settleDeadline = System.currentTimeMillis() + config.settleMaxMs;
        int stableCount = 0;
        while (running && System.currentTimeMillis() < settleDeadline) {
            sleep(150);
            if (vision.capture() && vision.lastFrameDiff() < STABLE_DIFF) {
                if (++stableCount >= 2) {
                    return;
                }
            } else {
                stableCount = 0;
            }
        }
    }

    private void sleep(long ms) throws InterruptedException {
        if (ms > 0) {
            Thread.sleep(ms);
        }
        if (!running) {
            throw new InterruptedException();
        }
    }

    private void log(String message) {
        listener.onLog(message);
    }

    private void status(String s) {
        listener.onStatus(s);
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }
}
