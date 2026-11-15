package com.imagemacro;

import com.imagemacro.Sequence.Action;
import com.imagemacro.Sequence.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 사용자가 짠 순서(Sequence)를 실행하는 엔진.
 *
 * 한 바퀴 = 켜져 있는 블록을 위에서부터 한 번씩:
 *   1. 블록의 실행 조건을 확인 (항상 / 이미지가 보일 때 / 안 보일 때 / N번째 판마다)
 *   2. 조건이 맞으면 동작을 차례로 실행 (이미지 클릭, 이미지 대기, 시간 대기, 보이는 동안 클릭)
 *   3. 동작이 실패하면 블록의 '실패했을 때' 설정대로 (건너뛰기 / 처음부터 / 정지)
 * 끝나면 처음부터 반복한다 (정해 둔 바퀴 수만큼 돌았으면 정지).
 *
 * 터치 후 화면 안정 대기, 터치 실패 시 정지, 예외 5회 연속이면 정지 등은 기존 MacroEngine 과 같은 방식이다.
 * 안드로이드 API 에 의존하지 않는 순수 Java 클래스라 PC 에서 테스트할 수 있다.
 */
public final class SequenceEngine implements MacroRunner {

    public static final class Config {
        public double optionThreshold = 0.92;
        public double buttonThreshold = 0.85;
        public int intervalMs = 300;
        public int clickDelayMs = 500;
        /** 이미지를 기다리는 기본 최대 시간(초). 동작에 따로 적지 않았을 때 쓴다. 0 이하면 무한 대기. */
        public int defaultTimeoutSeconds = 120;
        /** 터치 후 화면이 바뀌고 멈출 때까지 기다리는 최대 시간(ms). 0 이면 clickDelayMs 만큼만 기다린다. */
        public int settleMaxMs = 1500;
    }

    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    private static final double CHANGE_DIFF = 8.0;
    private static final double STABLE_DIFF = 3.0;
    private static final long CHANGE_TIMEOUT_MS = 3000;

    /** 터치 실패 (접근성 서비스 꺼짐 등) - 계속해도 의미가 없으므로 엔진을 멈춘다. */
    private static final class TapFailedException extends RuntimeException {
        TapFailedException(String message) {
            super(message);
        }
    }

    private final Vision vision;
    private final MacroEngine.Tapper tapper;
    private final MacroEngine.Listener listener;
    private final Sequence sequence;
    private final Config config;

    private volatile boolean running = true;
    private volatile Thread thread;
    private volatile int completedCycles = 0;
    private long noFrameSinceMs = 0;
    private long lastNoFrameWarnMs = 0;

    /** @param sequence {@link Sequence#expandOptions} 로 '옵션 전체' 를 이미 펼친 순서 */
    public SequenceEngine(Vision vision, MacroEngine.Tapper tapper, MacroEngine.Listener listener,
                          Sequence sequence, Config config) {
        this.vision = vision;
        this.tapper = tapper;
        this.listener = listener;
        this.sequence = sequence;
        this.config = config;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 끝까지 마친 바퀴 수 */
    public int getCompletedCycles() {
        return completedCycles;
    }

    @Override
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
        int enabled = 0;
        for (Block b : sequence.blocks) {
            if (b.enabled) {
                enabled++;
            }
        }
        if (enabled == 0) {
            log("[오류] 켜져 있는 블록이 없어서 시작할 수 없어요.");
            return;
        }
        log(String.format(Locale.US, "[순서 시작] '%s' · 켜진 블록 %d개 · %s | 옵션 임계값 %.2f, 버튼 임계값 %.2f",
                sequence.name, enabled,
                sequence.loopCount > 0 ? sequence.loopCount + "바퀴 후 정지" : "계속 반복",
                config.optionThreshold, config.buttonThreshold));

        int consecutiveErrors = 0;
        while (running) {
            if (sequence.loopCount > 0 && completedCycles >= sequence.loopCount) {
                log("[완료] 정해 둔 " + sequence.loopCount + "바퀴를 모두 마쳐서 멈춥니다.");
                return;
            }
            try {
                runCycle();
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

    // ------------------------------------------------------------------
    // 한 바퀴 / 블록
    // ------------------------------------------------------------------

    private void runCycle() throws InterruptedException {
        int cycleNo = completedCycles + 1;
        int total = sequence.blocks.size();
        for (int i = 0; i < total && running; i++) {
            Block b = sequence.blocks.get(i);
            if (!b.enabled) {
                continue;
            }
            String label = "블록 " + (i + 1) + "/" + total + " " + b.name;
            if (!awaitFrame()) {
                return;
            }
            if (!conditionMet(b, cycleNo)) {
                log("[건너뜀] " + label + " — 실행 조건 안 맞음 (" + b.describeCond() + ")");
                continue;
            }
            status(label);
            log("[블록 시작] " + label + " (" + cycleNo + "번째 판)");
            boolean ok = runActions(b, label);
            if (!running) {
                return;
            }
            if (ok) {
                continue;
            }
            switch (b.onFail) {
                case RESTART:
                    log("[실패] " + label + " → 이번 바퀴를 버리고 첫 블록부터 다시 시작합니다.");
                    sleep(config.intervalMs);
                    return;
                case STOP:
                    log("[실패] " + label + " → 설정대로 매크로를 정지합니다.");
                    running = false;
                    return;
                default:
                    log("[실패] " + label + " → 이 블록만 건너뛰고 계속합니다.");
                    break;
            }
        }
        if (!running) {
            return;
        }
        completedCycles++;
        log("[한 바퀴 완료] " + completedCycles + "바퀴째");
        sleep(config.intervalMs);
    }

    /** 블록을 실행할지. 조건 이미지가 비어 있으면 안 맞는 것으로 본다. */
    private boolean conditionMet(Block b, int cycleNo) throws InterruptedException {
        switch (b.cond) {
            case EVERY_N:
                return b.condN <= 1 || cycleNo % b.condN == 0;
            case IMAGE_VISIBLE: {
                if (b.condImage.isEmpty()) {
                    return false;
                }
                long deadline = System.currentTimeMillis() + b.condWaitSeconds * 1000L;
                while (running) {
                    if (vision.capture() && vision.find(b.condImage, thresholdFor(b.condImage)) != null) {
                        return true;
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        return false;
                    }
                    sleep(100);
                }
                return false;
            }
            case IMAGE_ABSENT:
                if (b.condImage.isEmpty()) {
                    return false;
                }
                return vision.find(b.condImage, thresholdFor(b.condImage)) == null;
            default:
                return true;
        }
    }

    /** 동작들을 차례로 실행. 하나라도 실패하면 false. */
    private boolean runActions(Block b, String where) throws InterruptedException {
        for (int j = 0; j < b.actions.size() && running; j++) {
            Action a = b.actions.get(j);
            String at = where + " · 동작 " + (j + 1);
            if (!runAction(a, at)) {
                return false;
            }
        }
        return true;
    }

    private boolean runAction(Action a, String at) throws InterruptedException {
        switch (a.type) {
            case WAIT:
                for (int remaining = a.seconds; remaining > 0 && running; remaining--) {
                    status(at + " · " + remaining + "초 대기");
                    sleep(1000);
                }
                return true;

            case WAIT_IMAGE: {
                Match m = waitForImage(a.image, a.seconds, at);
                if (m != null) {
                    return true;
                }
                return notFound(a, at);
            }

            case TAP_IMAGE: {
                Match m = waitForImage(a.image, a.seconds, at);
                if (m == null) {
                    return notFound(a, at);
                }
                int times = Math.max(1, a.count);
                for (int k = 0; k < times && running; k++) {
                    tap(m, Sequence.labelOf(a.image) + (times > 1 ? " (" + (k + 1) + "/" + times + ")" : ""));
                    if (k < times - 1) {
                        sleep(config.clickDelayMs);
                    }
                }
                waitAfterTap(Sequence.labelOf(a.image));
                return true;
            }

            case TAP_WHILE_VISIBLE:
                return tapWhileVisible(a, at);

            default:
                return true;
        }
    }

    /** 이미지를 못 찾았을 때: 선택 사항이면 계속, 아니면 실패. */
    private boolean notFound(Action a, String at) {
        if (a.optional) {
            log("[건너뜀] " + at + " — " + Sequence.labelOf(a.image) + " 이(가) 안 보여서 그냥 계속합니다.");
            return true;
        }
        log("[실패] " + at + " — " + Sequence.labelOf(a.image) + " 을(를) 찾지 못했어요.");
        return false;
    }

    /**
     * 감시 이미지 중 하나라도 보이는 동안 tapImage 를 계속 누른다 (기존 방식의 '새로고침' 반복과 같다).
     * 하나도 안 보이면 성공. 최대 시간(seconds)을 넘기면 실패. seconds 가 0 이면 무한.
     */
    private boolean tapWhileVisible(Action a, String at) throws InterruptedException {
        long deadline = a.seconds > 0 ? System.currentTimeMillis() + a.seconds * 1000L : Long.MAX_VALUE;
        int rounds = 0;
        while (running) {
            if (!awaitFrame()) {
                return true;
            }
            List<String> found = new ArrayList<>();
            for (String ref : a.images) {
                Match m = vision.find(ref, thresholdFor(ref));
                if (m != null) {
                    found.add(Sequence.labelOf(ref));
                    log(String.format(Locale.US, "[발견] %s (신뢰도: %.2f)", Sequence.labelOf(ref), m.confidence));
                }
            }
            if (found.isEmpty()) {
                if (rounds > 0) {
                    log("[확인] " + at + " — 더 이상 안 보여요 (" + rounds + "번 눌렀음)");
                }
                return true;
            }
            Match target = vision.find(a.tapImage, thresholdFor(a.tapImage));
            if (target != null) {
                tap(target, Sequence.labelOf(a.tapImage));
                rounds++;
                status(at + " · " + rounds + "번 눌렀음");
                waitAfterTap(Sequence.labelOf(a.tapImage));
            } else {
                log("[못 찾음] " + Sequence.labelOf(a.tapImage) + " — 다시 찾아봅니다.");
            }
            if (System.currentTimeMillis() > deadline) {
                log("[시간 초과] " + at + " — " + a.seconds + "초가 지나도 계속 보여요.");
                return false;
            }
            sleep(config.intervalMs);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 화면 / 터치 도우미 (기존 MacroEngine 과 같은 방식)
    // ------------------------------------------------------------------

    /** 화면이 들어올 때까지 기다린다. 정지되면 false. */
    private boolean awaitFrame() throws InterruptedException {
        while (running) {
            if (vision.capture()) {
                noFrameSinceMs = 0;
                return true;
            }
            long now = System.currentTimeMillis();
            if (noFrameSinceMs == 0) {
                noFrameSinceMs = now;
            }
            long waited = (now - noFrameSinceMs) / 1000;
            status("화면을 기다리는 중... " + waited + "초");
            if (now - noFrameSinceMs >= 3000 && now - lastNoFrameWarnMs >= 5000) {
                lastNoFrameWarnMs = now;
                log("[경고] 화면을 " + waited + "초째 받지 못하고 있어요 | " + vision.describeSource());
            }
            sleep(500);
        }
        return false;
    }

    /** 이미지가 나타날 때까지 0.1초 간격으로 찾는다. 시간 초과면 null. 정지되면 InterruptedException. */
    private Match waitForImage(String ref, int timeoutSeconds, String at) throws InterruptedException {
        if (ref == null || ref.isEmpty()) {
            log("[오류] " + at + " — 이미지를 고르지 않았어요.");
            return null;
        }
        int effective = timeoutSeconds > 0 ? timeoutSeconds : config.defaultTimeoutSeconds;
        long deadline = effective > 0 ? System.currentTimeMillis() + effective * 1000L : Long.MAX_VALUE;
        status(at + " · " + Sequence.labelOf(ref) + " 찾는 중");
        while (running) {
            if (vision.capture()) {
                Match m = vision.find(ref, thresholdFor(ref));
                if (m != null) {
                    return m;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                Match best = vision.find(ref, -1);
                log(String.format(Locale.US, "[시간 초과] %s 을(를) %d초 동안 못 찾았어요 (현재 화면 최고 신뢰도 %s, 기준 %.2f)",
                        Sequence.labelOf(ref), effective,
                        best == null ? "-" : String.format(Locale.US, "%.2f", best.confidence),
                        thresholdFor(ref)));
                return null;
            }
            sleep(100);
        }
        throw new InterruptedException();
    }

    private double thresholdFor(String ref) {
        return ref != null && ref.startsWith("opt:") ? config.optionThreshold : config.buttonThreshold;
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
            log("[" + label + "] 터치 후 화면 변화가 없어요. 다음으로 넘어갑니다.");
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

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }
}
