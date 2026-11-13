package com.imagemacro;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import java.util.*;
import java.util.concurrent.atomic.*;

/** 가짜 게임 화면으로 매크로 엔진의 동작 순서/예외 처리/화면 안정 대기를 검증 (PC 에서 실행) */
public class MacroEngineTest {
    enum S { STAGE, BATTLE, RESULT1, RESULT2 }
    static final String[] OPTS = {"감속","빙결","연격","이속","치명","필살기","회피"};
    static final String[] OTHER = {"기절","받는피해","공격력","공속","둔화"};

    static class Game implements Vision, MacroEngine.Tapper {
        final Random rnd = new Random(42);
        volatile S state = S.STAGE;
        Set<String> mods = new HashSet<>();
        long battleEnd;
        long battleMs;
        boolean battleNeverEnds = false;
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        int badReloads = 0, badStarts = 0, wrongTaps = 0;
        boolean tapFails = false;
        long transitionMs = 0; Runnable pending; long applyAt; int version, markVersion, doubleTaps = 0;
        Game(long battleMs) { this.battleMs = battleMs; newStage(); }
        void schedule(Runnable r) { if (pending != null) doubleTaps++; if (transitionMs <= 0) { r.run(); version++; } else { pending = r; applyAt = System.currentTimeMillis() + transitionMs; } }
        public synchronized void markScreen() { markVersion = version; }
        public synchronized double diffFromMark() { return version != markVersion ? 255 : 0; }
        void newStage() {
            mods.clear();
            List<String> pool = new ArrayList<>(); pool.addAll(Arrays.asList(OPTS)); pool.addAll(Arrays.asList(OTHER));
            Collections.shuffle(pool, rnd);
            // 2 modifiers per stage -> decent chance of clean stage
            mods.addAll(pool.subList(0, 2));
            state = S.STAGE;
        }
        boolean hasBad() { for (String o : OPTS) if (mods.contains(o)) return true; return false; }
        public synchronized boolean capture() {
            if (pending != null && System.currentTimeMillis() >= applyAt) { pending.run(); pending = null; version++; }
            if (state == S.BATTLE && !battleNeverEnds && System.currentTimeMillis() >= battleEnd) state = S.RESULT1;
            return true;
        }
        public synchronized Match find(String id, double th) {
            switch (state) {
                case STAGE:
                    if (id.startsWith("opt:") && mods.contains(id.substring(4))) return new Match(0.99, 500, 500, 1.0);
                    if (id.equals(MacroEngine.RELOAD)) return new Match(1.0, 100, 100, 1.0);
                    if (id.equals(MacroEngine.START)) return new Match(0.99, 200, 200, 1.0);
                    return null;
                case RESULT1: return id.equals(MacroEngine.COLLECT1) ? new Match(0.97, 300, 300, 1.0) : null;
                case RESULT2: return id.equals(MacroEngine.COLLECT2) ? new Match(0.98, 400, 400, 1.0) : null;
                default: return null;
            }
        }
        public synchronized boolean tap(int x, int y) {
            if (tapFails) return false;
            if (x == 100 && state == S.STAGE) { if (!hasBad()) badReloads++; events.add("reload"); schedule(this::newStage); }
            else if (x == 200 && state == S.STAGE) { if (hasBad()) badStarts++; events.add("start"); schedule(() -> { state = S.BATTLE; battleEnd = System.currentTimeMillis() + battleMs; }); }
            else if (x == 300 && state == S.RESULT1) { events.add("c1"); schedule(() -> state = S.RESULT2); }
            else if (x == 400 && state == S.RESULT2) { events.add("c2"); schedule(this::newStage); }
            else { wrongTaps++; events.add("WRONG@"+x); }
            return true;
        }
    }

    static class L implements MacroEngine.Listener {
        final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        volatile boolean finished; volatile String status = "";
        public void onLog(String m) { logs.add(m); }
        public void onStatus(String s) { status = s; }
        public void onFinished() { finished = true; }
    }

    static MacroEngine.Config cfg(int waitSec, int timeoutSec) {
        MacroEngine.Config c = new MacroEngine.Config();
        c.startWaitSeconds = waitSec; c.intervalMs = 5; c.clickDelayMs = 5; c.buttonTimeoutSeconds = timeoutSec;
        return c;
    }
    static List<String> ids() { List<String> l = new ArrayList<>(); for (String o : OPTS) l.add("opt:" + o); return l; }
    static List<String> names() { return Arrays.asList(OPTS); }
    static int fails = 0;
    static void check(boolean cond, String msg) { System.out.println((cond ? "  PASS " : "  FAIL ") + msg); if (!cond) fails++; }

    @Test
    public void scenarios() throws Exception {
        fails = 0;
        // Test 1: normal cycles
        System.out.println("Test 1: 정상 흐름 (reload / start / collect1 / collect2)");
        Game g = new Game(1300); L l = new L();
        MacroEngine e = new MacroEngine(g, g, l, ids(), names(), cfg(1, 3));
        Thread t = new Thread(e); t.start();
        long until = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < until && e.getCycleCount() < 3) Thread.sleep(20);
        e.stop(); t.join(2000);
        check(!t.isAlive() && l.finished, "stop() 후 스레드 종료 + onFinished 호출");
        check(e.getCycleCount() >= 3, "완료 사이클 3회 이상: " + e.getCycleCount());
        check(g.badReloads == 0, "나쁜 옵션이 없을 때 reload 누른 횟수 0: " + g.badReloads);
        check(g.badStarts == 0, "나쁜 옵션이 있을 때 start 누른 횟수 0: " + g.badStarts);
        check(g.wrongTaps == 0, "엉뚱한 터치 0: " + g.wrongTaps);
        // order check: after each start -> c1 -> c2
        List<String> ev = new ArrayList<>(g.events); boolean order = true;
        for (int i = 0; i < ev.size(); i++) if (ev.get(i).equals("start")) {
            if (i + 2 < ev.size() && !(ev.get(i+1).equals("c1") && ev.get(i+2).equals("c2"))) order = false;
        }
        check(order, "start 뒤에는 항상 c1 -> c2 순서");
        System.out.println("  events: " + ev.subList(0, Math.min(ev.size(), 20)) + (ev.size() > 20 ? "..." : ""));

        // Test 2: timeout on collect1
        System.out.println("Test 2: 전투가 안 끝나면 collect 대기 시간 초과 후 처음으로");
        g = new Game(1000); g.battleNeverEnds = true; l = new L();
        // force a clean stage
        g.mods.clear(); g.mods.add("기절"); g.mods.add("둔화");
        e = new MacroEngine(g, g, l, ids(), names(), cfg(0, 1));
        t = new Thread(e); t.start();
        Thread.sleep(2500);
        boolean timedOut = false; for (String s : new ArrayList<>(l.logs)) if (s.contains("시간 초과")) timedOut = true;
        check(timedOut, "시간 초과 로그 출력");
        check(e.isRunning(), "시간 초과 후에도 엔진은 계속 동작");
        e.stop(); t.join(2000);
        check(!t.isAlive(), "정지 정상");

        // Test 3: tap failure stops engine
        System.out.println("Test 3: 접근성 꺼짐(터치 실패) 시 오류 로그 후 정지");
        g = new Game(1000); g.tapFails = true; l = new L();
        e = new MacroEngine(g, g, l, ids(), names(), cfg(1, 1));
        t = new Thread(e); t.start(); t.join(3000);
        boolean tapErr = false; for (String s : new ArrayList<>(l.logs)) if (s.contains("터치 실패")) tapErr = true;
        check(!t.isAlive() && !e.isRunning(), "엔진이 스스로 정지");
        check(tapErr, "터치 실패 로그 출력");

        // Test 4: stop during the 40s wait responds fast
        System.out.println("Test 4: 40초 대기 중 정지 버튼 즉시 반응");
        g = new Game(100000); l = new L(); g.mods.clear(); g.mods.add("기절");
        e = new MacroEngine(g, g, l, ids(), names(), cfg(40, 0));
        t = new Thread(e); t.start(); Thread.sleep(300);
        long t0 = System.currentTimeMillis(); e.stop(); t.join(3000);
        long dt = System.currentTimeMillis() - t0;
        check(!t.isAlive() && dt < 500, "정지까지 " + dt + "ms");

        // Test 5: exceptions in vision are retried then stop
        System.out.println("Test 5: 화면 처리 예외가 계속되면 5회 후 정지 (조용히 죽지 않음)");
        l = new L();
        Vision broken = new Vision() { public boolean capture() { throw new IllegalStateException("boom"); } public Match find(String id, double th) { return null; } };
        e = new MacroEngine(broken, (x, y) -> true, l, ids(), names(), cfg(1, 1));
        t = new Thread(e); t.start(); t.join(8000);
        int warn = 0; for (String s : new ArrayList<>(l.logs)) if (s.startsWith("[경고]")) warn++;
        check(!t.isAlive() && warn == 5, "경고 로그 " + warn + "회 후 정지");

        // Test 6: 화면 전환이 느릴 때 (터치 후 0.7초 뒤에 화면이 바뀜)
        System.out.println("Test 6: 화면 전환이 느려도 같은 버튼을 두 번 누르지 않음 (화면 안정 대기)");
        g = new Game(1200); g.transitionMs = 700; l = new L();
        MacroEngine.Config c6 = cfg(1, 5); c6.settleMaxMs = 1500;
        e = new MacroEngine(g, g, l, ids(), names(), c6);
        t = new Thread(e); t.start();
        until = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < until && e.getCycleCount() < 2) Thread.sleep(20);
        e.stop(); t.join(3000);
        check(e.getCycleCount() >= 2, "완료 사이클 2회 이상: " + e.getCycleCount());
        check(g.doubleTaps == 0, "화면 전환 중 중복 터치 0: " + g.doubleTaps);
        check(g.badReloads == 0 && g.badStarts == 0 && g.wrongTaps == 0, "잘못된 터치 0");

        System.out.println("Test 7: (비교) 안정 대기를 끄면 윈도우판처럼 중복 터치가 생김");
        g = new Game(1200); g.transitionMs = 700; l = new L();
        MacroEngine.Config c7 = cfg(1, 5); c7.settleMaxMs = 0;
        e = new MacroEngine(g, g, l, ids(), names(), c7);
        t = new Thread(e); t.start(); Thread.sleep(4000); e.stop(); t.join(3000);
        check(g.doubleTaps > 0, "안정 대기 끄면 중복 터치 발생: " + g.doubleTaps + "회 (버그 재현)");

        System.out.println("Test 8: 시작 직후 진단 로그 (화면 정보 + 템플릿별 최고 신뢰도)");
        g = new Game(100000); l = new L(); g.mods.clear(); g.mods.add("기절");
        e = new MacroEngine(g, g, l, ids(), names(), cfg(40, 0));
        t = new Thread(e); t.start(); Thread.sleep(600); e.stop(); t.join(3000);
        boolean diag = false;
        for (String s : new ArrayList<>(l.logs)) if (s.startsWith("[진단]") && s.contains("최고 신뢰도") && s.contains("시작 0.99")) diag = true;
        check(diag, "진단 로그에 템플릿별 최고 신뢰도 표시");

        System.out.println("Test 9: 화면이 안 들어오면 3초 뒤 경고 로그 (조용히 멈춰 있지 않음)");
        Vision noFrames = new Vision() {
            public boolean capture() { return false; }
            public Match find(String id, double th) { return null; }
            public String describeSource() { return "수신 0장"; }
        };
        l = new L();
        e = new MacroEngine(noFrames, (x, y) -> true, l, ids(), names(), cfg(1, 1));
        t = new Thread(e); t.start(); Thread.sleep(4300); e.stop(); t.join(3000);
        boolean warnNoFrame = false;
        for (String s : new ArrayList<>(l.logs)) if (s.contains("받지 못하고") && s.contains("수신 0장")) warnNoFrame = true;
        check(warnNoFrame, "화면 미수신 경고 + 캡처 상태 표시");
        check(l.status.startsWith("정지") || l.status.contains("화면을 기다리는 중"), "상태 표시: " + l.status);

        System.out.println(fails == 0 ? "\nALL PASS" : "\nFAILURES: " + fails);
        assertEquals("실패한 검사 수", 0, fails);
    }
}
