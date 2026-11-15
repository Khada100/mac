package com.imagemacro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.imagemacro.Sequence.Action;
import com.imagemacro.Sequence.Block;
import com.imagemacro.Sequence.Cond;
import com.imagemacro.Sequence.OnFail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 블록 순서 엔진을 가짜 게임 화면으로 검증 (PC 에서 실행) */
public class SequenceEngineTest {

    /**
     * B컨텐츠 비슷한 가짜 게임.
     *   스테이지: 배율(multi) / 전투 시작(start) / 물약(potion) 이 보이고, 장비창이 가득 차면 '가득 참'(full) + 상점(shop) 도 보인다.
     *   전투 시작을 누르면 battleMs 뒤에 결과 화면(ok 버튼). ok 를 누르면 장비가 1칸 늘고 스테이지로.
     *   상점(shop) → 판매(sell) → 확인(sellok) → 닫기(close) 를 차례로 누르면 장비가 비워진다.
     */
    static class BattleGame implements Vision, MacroEngine.Tapper {
        final Map<String, Integer> xOf = new HashMap<>();
        final Set<String> visible = new HashSet<>();
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        int capacity;
        int inventory = 0;
        int battles = 0;
        int wrong = 0;
        long battleMs;
        long battleEndAt = 0;

        BattleGame(int capacity, long battleMs) {
            this.capacity = capacity;
            this.battleMs = battleMs;
            String[] refs = {"img:multi", "btn:start", "btn:ok", "img:potion", "img:full", "img:shop",
                    "img:sell", "img:sellok", "img:close", "img:missing"};
            for (int i = 0; i < refs.length; i++) {
                xOf.put(refs[i], 100 + i * 10);
            }
            stage();
        }

        void stage() {
            visible.clear();
            visible.add("img:multi");
            visible.add("btn:start");
            visible.add("img:potion");
            if (inventory >= capacity) {
                visible.add("img:full");
                visible.add("img:shop");
            }
        }

        void only(String ref) {
            visible.clear();
            visible.add(ref);
        }

        public synchronized boolean capture() {
            if (battleEndAt > 0 && System.currentTimeMillis() >= battleEndAt) {
                battleEndAt = 0;
                only("btn:ok");
            }
            return true;
        }

        public synchronized Match find(String id, double th) {
            Integer x = xOf.get(id);
            return x != null && visible.contains(id) ? new Match(0.99, x, 50, 1.0) : null;
        }

        public synchronized boolean tap(int x, int y) {
            String hit = null;
            for (Map.Entry<String, Integer> e : xOf.entrySet()) {
                if (e.getValue() == x && visible.contains(e.getKey())) {
                    hit = e.getKey();
                }
            }
            if (hit == null) {
                wrong++;
                events.add("WRONG@" + x);
                return true;
            }
            events.add(hit.substring(hit.indexOf(':') + 1));
            switch (hit) {
                case "btn:start":
                    visible.clear();
                    battleEndAt = System.currentTimeMillis() + battleMs;
                    break;
                case "btn:ok":
                    battles++;
                    inventory++;
                    stage();
                    break;
                case "img:shop":
                    only("img:sell");
                    break;
                case "img:sell":
                    only("img:sellok");
                    break;
                case "img:sellok":
                    only("img:close");
                    break;
                case "img:close":
                    inventory = 0;
                    stage();
                    break;
                default:
                    break;
            }
            return true;
        }
    }

    static class Log implements MacroEngine.Listener {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        volatile boolean finished;

        public void onLog(String m) {
            lines.add(m);
        }

        public void onStatus(String s) {
        }

        public void onFinished() {
            finished = true;
        }

        boolean has(String part) {
            synchronized (lines) {
                for (String l : lines) {
                    if (l.contains(part)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    static SequenceEngine.Config fastConfig() {
        SequenceEngine.Config c = new SequenceEngine.Config();
        c.intervalMs = 5;
        c.clickDelayMs = 5;
        c.settleMaxMs = 0;
        c.defaultTimeoutSeconds = 2;
        return c;
    }

    static Block block(String name, Action... actions) {
        Block b = new Block();
        b.name = name;
        b.actions.addAll(Arrays.asList(actions));
        return b;
    }

    static Sequence seq(Block... blocks) {
        Sequence s = new Sequence();
        s.name = "테스트";
        s.blocks.addAll(Arrays.asList(blocks));
        return s;
    }

    /** 엔진을 돌리고 cycles 바퀴를 채우면(또는 timeoutMs 가 지나면) 멈춘다. */
    static SequenceEngine run(Vision v, MacroEngine.Tapper t, Log log, Sequence s, SequenceEngine.Config c,
                              int cycles, long timeoutMs) throws Exception {
        SequenceEngine e = new SequenceEngine(v, t, log, s, c);
        Thread th = new Thread(e);
        th.start();
        long until = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < until && th.isAlive() && e.getCompletedCycles() < cycles) {
            Thread.sleep(10);
        }
        e.stop();
        th.join(3000);
        assertFalse("엔진 스레드가 정지해야 함", th.isAlive());
        assertTrue(log.finished);
        return e;
    }

    static Block multiBlock() {
        return block("배율", Action.tap("img:multi"));
    }

    static Block battleBlock() {
        Block b = block("전투", Action.tap("btn:start"), Action.waitImage("btn:ok", 5), Action.tap("btn:ok"));
        b.onFail = OnFail.RESTART;
        return b;
    }

    static Block sellBlock() {
        Block b = block("판매", Action.tap("img:shop"), Action.tap("img:sell"), Action.tap("img:sellok"),
                Action.tap("img:close"));
        b.cond = Cond.IMAGE_VISIBLE;
        b.condImage = "img:full";
        return b;
    }

    static Block potionBlock(int everyN) {
        Block b = block("물약", Action.tap("img:potion"));
        b.cond = Cond.EVERY_N;
        b.condN = everyN;
        return b;
    }

    @Test
    public void bContentFlowSellsOnlyWhenFullAndDrinksPotionEveryNthRound() throws Exception {
        BattleGame g = new BattleGame(2, 30);
        Log log = new Log();
        SequenceEngine e = run(g, g, log, seq(multiBlock(), battleBlock(), sellBlock(), potionBlock(3)),
                fastConfig(), 6, 20000);

        assertTrue("6바퀴 이상: " + e.getCompletedCycles(), e.getCompletedCycles() >= 6);
        assertEquals("엉뚱한 터치 없음: " + g.events, 0, g.wrong);
        // 2판마다 가득 참 → 판매는 2, 4, 6번째 판 / 물약은 3, 6번째 판
        List<String> expected = Arrays.asList(
                "multi", "start", "ok",
                "multi", "start", "ok", "shop", "sell", "sellok", "close",
                "multi", "start", "ok", "potion",
                "multi", "start", "ok", "shop", "sell", "sellok", "close",
                "multi", "start", "ok",
                "multi", "start", "ok", "shop", "sell", "sellok", "close", "potion");
        assertEquals(expected, new ArrayList<>(g.events).subList(0, expected.size()));
    }

    @Test
    public void reorderingBlocksChangesTheOrderOfActions() throws Exception {
        // 판매 블록을 맨 앞으로 (ADCB 처럼): 가득 찬 판은 '판매 → 배율 → 전투' 순서가 된다
        BattleGame g = new BattleGame(2, 30);
        Log log = new Log();
        run(g, g, log, seq(sellBlock(), multiBlock(), battleBlock()), fastConfig(), 3, 20000);

        List<String> expected = Arrays.asList(
                "multi", "start", "ok",
                "multi", "start", "ok",
                "shop", "sell", "sellok", "close", "multi", "start", "ok");
        assertEquals(expected, new ArrayList<>(g.events).subList(0, expected.size()));
        assertEquals(0, g.wrong);
    }

    @Test
    public void imageAbsentConditionRunsOnlyWhileTheImageIsGone() throws Exception {
        BattleGame g = new BattleGame(2, 30);
        Block noFull = block("가득 안 찼을 때만 물약", Action.tap("img:potion"));
        noFull.cond = Cond.IMAGE_ABSENT;
        noFull.condImage = "img:full";
        Log log = new Log();
        run(g, g, log, seq(noFull, battleBlock(), sellBlock()), fastConfig(), 4, 20000);

        // 1판째(비어 있음) 물약 O, 2판째(1칸) 물약 O, 3판째(가득) 물약 X + 판매 뒤 4판째(비어 있음) 물약 O
        List<String> expected = Arrays.asList(
                "potion", "start", "ok",
                "potion", "start", "ok", "shop", "sell", "sellok", "close",
                "potion", "start", "ok");
        assertEquals(expected, new ArrayList<>(g.events).subList(0, expected.size()));
    }

    @Test
    public void tapCountPressesTheSameSpotSeveralTimes() throws Exception {
        BattleGame g = new BattleGame(99, 30);
        Action plus = Action.tap("img:multi");
        plus.count = 3;
        run(g, g, new Log(), seq(block("배율 +3", plus)), fastConfig(), 1, 5000);
        assertEquals(Arrays.asList("multi", "multi", "multi"), new ArrayList<>(g.events).subList(0, 3));
    }

    @Test
    public void disabledBlocksAreSkipped() throws Exception {
        BattleGame g = new BattleGame(99, 30);
        Block off = multiBlock();
        off.enabled = false;
        run(g, g, new Log(), seq(off, block("물약", Action.tap("img:potion"))), fastConfig(), 3, 5000);
        assertFalse(g.events.contains("multi"));
        assertTrue(g.events.contains("potion"));
    }

    @Test
    public void loopCountStopsByItself() throws Exception {
        BattleGame g = new BattleGame(99, 30);
        Log log = new Log();
        Sequence s = seq(multiBlock());
        s.loopCount = 3;
        SequenceEngine e = new SequenceEngine(g, g, log, s, fastConfig());
        Thread t = new Thread(e);
        t.start();
        t.join(5000);
        assertFalse(t.isAlive());
        assertEquals(3, e.getCompletedCycles());
        assertEquals(3, g.events.size());
        assertTrue(log.has("3바퀴를 모두 마쳐서 멈춥니다"));
    }

    @Test
    public void onFailRestartDropsTheRoundAndNeverRunsLaterBlocks() throws Exception {
        BattleGame g = new BattleGame(99, 30);
        Block fails = block("없는 버튼", Action.tap("img:missing"));
        fails.actions.get(0).seconds = 1;
        fails.onFail = OnFail.RESTART;
        Log log = new Log();
        SequenceEngine e = new SequenceEngine(g, g, log, seq(multiBlock(), fails, block("물약", Action.tap("img:potion"))),
                fastConfig());
        Thread t = new Thread(e);
        t.start();
        Thread.sleep(2600);
        e.stop();
        t.join(3000);
        assertEquals("한 바퀴도 끝내지 못함", 0, e.getCompletedCycles());
        assertFalse("뒤 블록은 실행되지 않음", g.events.contains("potion"));
        assertTrue("첫 블록은 다시 실행됨", Collections.frequency(g.events, "multi") >= 2);
        assertTrue(log.has("첫 블록부터 다시"));
        assertTrue(log.has("시간 초과"));
    }

    @Test
    public void onFailSkipContinuesWithTheNextBlock() throws Exception {
        BattleGame g = new BattleGame(99, 30);
        Block fails = block("없는 버튼", Action.tap("img:missing"));
        fails.actions.get(0).seconds = 1;
        fails.onFail = OnFail.SKIP;
        run(g, g, new Log(), seq(fails, block("물약", Action.tap("img:potion"))), fastConfig(), 1, 5000);
        assertTrue(g.events.contains("potion"));
    }

    @Test
    public void onFailStopStopsTheMacro() throws Exception {
        BattleGame g = new BattleGame(99, 30);
        Block fails = block("없는 버튼", Action.tap("img:missing"));
        fails.actions.get(0).seconds = 1;
        fails.onFail = OnFail.STOP;
        Log log = new Log();
        SequenceEngine e = new SequenceEngine(g, g, log, seq(fails, block("물약", Action.tap("img:potion"))), fastConfig());
        Thread t = new Thread(e);
        t.start();
        t.join(4000);
        assertFalse("스스로 정지", t.isAlive());
        assertFalse(e.isRunning());
        assertFalse(g.events.contains("potion"));
        assertTrue(log.has("설정대로 매크로를 정지합니다"));
    }

    @Test
    public void optionalActionsDoNotFailTheBlock() throws Exception {
        BattleGame g = new BattleGame(99, 30);
        Action popup = Action.tap("img:missing");
        popup.optional = true;
        popup.seconds = 1;
        Block b = block("있을 때만 닫기", popup, Action.tap("img:potion"));
        b.onFail = OnFail.STOP;
        run(g, g, new Log(), seq(b), fastConfig(), 1, 5000);
        assertTrue("옵션 동작이 없어도 다음 동작 실행", g.events.contains("potion"));
    }

    @Test
    public void tapFailureStopsTheEngineWithAnErrorLog() throws Exception {
        BattleGame g = new BattleGame(99, 30);
        Log log = new Log();
        SequenceEngine e = new SequenceEngine(g, (x, y) -> false, log, seq(multiBlock()), fastConfig());
        Thread t = new Thread(e);
        t.start();
        t.join(3000);
        assertFalse(t.isAlive());
        assertTrue(log.has("터치 실패"));
    }

    @Test
    public void stopDuringLongWaitReactsQuickly() throws Exception {
        BattleGame g = new BattleGame(99, 30);
        Log log = new Log();
        SequenceEngine e = new SequenceEngine(g, g, log, seq(block("긴 대기", Action.waitSeconds(60))), fastConfig());
        Thread t = new Thread(e);
        t.start();
        Thread.sleep(300);
        long t0 = System.currentTimeMillis();
        e.stop();
        t.join(3000);
        assertFalse(t.isAlive());
        assertTrue("정지까지 " + (System.currentTimeMillis() - t0) + "ms", System.currentTimeMillis() - t0 < 500);
    }

    @Test
    public void emptyOrAllDisabledSequenceRefusesToRun() throws Exception {
        BattleGame g = new BattleGame(99, 30);
        Log log = new Log();
        Block off = multiBlock();
        off.enabled = false;
        SequenceEngine e = new SequenceEngine(g, g, log, seq(off), fastConfig());
        Thread t = new Thread(e);
        t.start();
        t.join(2000);
        assertFalse(t.isAlive());
        assertTrue(log.has("켜져 있는 블록이 없어서"));
        assertTrue(g.events.isEmpty());
    }

    @Test
    public void brokenVisionStopsAfterFiveWarnings() throws Exception {
        Log log = new Log();
        Vision broken = new Vision() {
            public boolean capture() {
                throw new IllegalStateException("boom");
            }

            public Match find(String id, double th) {
                return null;
            }
        };
        SequenceEngine e = new SequenceEngine(broken, (x, y) -> true, log, seq(multiBlock()), fastConfig());
        Thread t = new Thread(e);
        t.start();
        t.join(8000);
        assertFalse(t.isAlive());
        int warnings = 0;
        synchronized (log.lines) {
            for (String l : log.lines) {
                if (l.startsWith("[경고]")) {
                    warnings++;
                }
            }
        }
        assertEquals(5, warnings);
    }

    // ------------------------------------------------------------------
    // 'A컨텐츠 (블록 버전)' 이 기존 MacroEngine 과 같은 동작을 하는지 (기존 가짜 게임 재사용)
    // ------------------------------------------------------------------

    @Test
    public void aBlocksPresetBehavesLikeTheClassicEngine() throws Exception {
        MacroEngineTest.Game g = new MacroEngineTest.Game(300);
        Sequence a = Presets.aBlocks(1).expandOptions(optionRefs());
        MacroEngineTest.L l = new MacroEngineTest.L();
        SequenceEngine e = new SequenceEngine(g, g, adapt(l), a, fastConfig2());
        Thread t = new Thread(e);
        t.start();
        long until = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < until && e.getCompletedCycles() < 4) {
            Thread.sleep(20);
        }
        e.stop();
        t.join(3000);

        assertFalse(t.isAlive());
        assertTrue("4바퀴 이상: " + e.getCompletedCycles(), e.getCompletedCycles() >= 4);
        assertEquals("나쁜 옵션이 없는데 새로고침: " + g.events, 0, g.badReloads);
        assertEquals("나쁜 옵션이 있는데 전투 시작", 0, g.badStarts);
        assertEquals("엉뚱한 터치", 0, g.wrongTaps);
        List<String> ev = new ArrayList<>(g.events);
        for (int i = 0; i + 2 < ev.size(); i++) {
            if (ev.get(i).equals("start")) {
                assertEquals("start 뒤에는 c1", "c1", ev.get(i + 1));
                assertEquals("c1 뒤에는 c2", "c2", ev.get(i + 2));
            }
        }
    }

    @Test
    public void aBlocksPresetDoesNotDoubleTapWhenScreenTransitionsAreSlow() throws Exception {
        MacroEngineTest.Game g = new MacroEngineTest.Game(300);
        g.transitionMs = 400;
        Sequence a = Presets.aBlocks(1).expandOptions(optionRefs());
        SequenceEngine.Config c = fastConfig2();
        c.settleMaxMs = 1500;
        SequenceEngine e = new SequenceEngine(g, g, adapt(new MacroEngineTest.L()), a, c);
        Thread t = new Thread(e);
        t.start();
        long until = System.currentTimeMillis() + 25000;
        while (System.currentTimeMillis() < until && e.getCompletedCycles() < 2) {
            Thread.sleep(20);
        }
        e.stop();
        t.join(3000);
        assertTrue(e.getCompletedCycles() >= 2);
        assertEquals("화면 전환 중 중복 터치", 0, g.doubleTaps);
        assertEquals(0, g.badReloads + g.badStarts + g.wrongTaps);
    }

    private static List<String> optionRefs() {
        List<String> refs = new ArrayList<>();
        for (String o : MacroEngineTest.OPTS) {
            refs.add("opt:" + o);
        }
        return refs;
    }

    private static SequenceEngine.Config fastConfig2() {
        SequenceEngine.Config c = fastConfig();
        c.defaultTimeoutSeconds = 5;
        return c;
    }

    private static MacroEngine.Listener adapt(MacroEngineTest.L l) {
        return l;
    }
}
