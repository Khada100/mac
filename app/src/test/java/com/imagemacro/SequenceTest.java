package com.imagemacro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.imagemacro.Sequence.Action;
import com.imagemacro.Sequence.ActionType;
import com.imagemacro.Sequence.Block;
import com.imagemacro.Sequence.Cond;
import com.imagemacro.Sequence.OnFail;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 순서 모델 / JSON / 검사 (PC 에서 실행) */
public class SequenceTest {

    @Test
    public void jsonRoundTripKeepsEverything() {
        Sequence s = Presets.bTemplate();
        s.name = "내 B 순서 \"따옴표\"와 한글";
        s.loopCount = 7;
        Block b = s.blocks.get(2);
        b.condImage = "img:가득참";
        b.onFail = OnFail.STOP;
        b.actions.get(0).image = "img:상점";
        b.actions.get(0).count = 3;
        b.actions.get(0).optional = true;
        b.actions.get(0).seconds = 9;

        Sequence back = Sequence.fromJson(s.toJson(true));
        assertEquals(s.toJson(false), back.toJson(false));
        assertEquals("내 B 순서 \"따옴표\"와 한글", back.name);
        assertEquals(7, back.loopCount);
        assertEquals(Cond.IMAGE_VISIBLE, back.blocks.get(2).cond);
        assertEquals(OnFail.STOP, back.blocks.get(2).onFail);
        assertEquals(3, back.blocks.get(2).actions.get(0).count);
        assertTrue(back.blocks.get(2).actions.get(0).optional);
    }

    @Test
    public void fromJsonToleratesMissingAndUnknownValues() {
        Sequence s = Sequence.fromJson("{\"name\":\"x\",\"blocks\":[{\"cond\":\"NOPE\",\"onFail\":\"???\","
                + "\"actions\":[{\"type\":\"FLY\"},{\"type\":\"WAIT\",\"seconds\":-5,\"count\":0}]}]}");
        assertEquals("x", s.name);
        assertEquals(1, s.blocks.size());
        Block b = s.blocks.get(0);
        assertEquals(Cond.ALWAYS, b.cond);
        assertEquals(OnFail.SKIP, b.onFail);
        assertTrue(b.enabled);
        assertEquals(ActionType.TAP_IMAGE, b.actions.get(0).type);
        assertEquals(0, b.actions.get(1).seconds);
        assertEquals(1, b.actions.get(1).count);
    }

    @Test
    public void fromJsonRejectsBrokenJson() {
        for (String bad : new String[]{"", "{", "[1,2]", "{\"name\":}", "not json", "{\"a\":1} extra"}) {
            try {
                Sequence.fromJson(bad);
                fail("예외가 나야 함: " + bad);
            } catch (IllegalArgumentException expected) {
                // 정상
            }
        }
    }

    @Test
    public void jsonHandlesEscapesAndUnicode() {
        Object o = Json.parse("{\"a\":\"줄\\n바꿈 \\u0041 \\\"q\\\"\",\"n\":[1,2.5,-3e2,true,null]}");
        assertEquals("줄\n바꿈 A \"q\"", Json.str(Json.asMap(o), "a", ""));
        List<Object> n = Json.asList(Json.asMap(o).get("n"));
        assertEquals(5, n.size());
        assertEquals(-300.0, (Double) n.get(2), 0.0);
        assertEquals("[1,2.5,\"x\\ty\"]", Json.stringify(Arrays.<Object>asList(1, 2.5, "x\ty"), false));
    }

    @Test
    public void presetABlocksIsValidAndUsesStartWait() {
        Sequence a = Presets.aBlocks(40);
        assertTrue(a.validate().isEmpty());
        assertEquals(3, a.blocks.size());
        assertEquals(40, a.blocks.get(1).actions.get(1).seconds);
        assertTrue(a.usesAllOptions());
        assertEquals(Arrays.asList("btn:reload", "btn:start", "btn:collect1", "btn:collect2"), a.imageRefs());
    }

    @Test
    public void presetBTemplateAsksForEveryMissingImage() {
        Sequence b = Presets.bTemplate();
        List<String> problems = b.validate();
        // 배율 1 + 전투 3 + 판매 조건 1 + 판매 3 + 물약 1
        assertEquals(9, problems.size());
        assertTrue(problems.get(0).contains("블록 1"));
    }

    @Test
    public void validateCatchesBrokenBlocksButIgnoresDisabledOnes() {
        Sequence s = new Sequence();
        assertEquals(1, s.validate().size()); // 켜진 블록 없음

        Block empty = new Block();
        empty.name = "비었음";
        s.blocks.add(empty);
        assertTrue(s.validate().get(0).contains("동작이 하나도 없어요"));

        empty.enabled = false;
        Block ok = new Block();
        ok.actions.add(Action.waitSeconds(3));
        s.blocks.add(ok);
        assertTrue(s.validate().isEmpty());

        ok.cond = Cond.IMAGE_ABSENT;
        assertEquals(1, s.validate().size());
        ok.condImage = "img:x";
        ok.actions.add(Action.waitSeconds(0));
        assertEquals(1, s.validate().size());

        Sequence classic = Presets.aClassic();
        assertTrue(classic.validate().isEmpty());
    }

    @Test
    public void expandOptionsReplacesWildcardAndKeepsOriginal() {
        Sequence a = Presets.aBlocks(10);
        Sequence e = a.expandOptions(Arrays.asList("opt:빙결", "opt:감속"));
        assertEquals(Arrays.asList("opt:빙결", "opt:감속"), e.blocks.get(0).actions.get(0).images);
        assertEquals(Arrays.asList(Sequence.ALL_OPTIONS), a.blocks.get(0).actions.get(0).images);

        Sequence none = a.expandOptions(Arrays.<String>asList());
        assertTrue(none.blocks.get(0).actions.get(0).images.isEmpty());
    }

    @Test
    public void copyIsDeep() {
        Sequence a = Presets.aBlocks(10);
        Sequence c = a.copy();
        c.blocks.get(1).actions.get(1).seconds = 99;
        c.blocks.remove(0);
        assertEquals(10, a.blocks.get(1).actions.get(1).seconds);
        assertEquals(3, a.blocks.size());
        assertFalse(c.blocks.size() == a.blocks.size());
    }

    @Test
    public void labelsAreReadable() {
        assertEquals("버튼 '전투 시작'", Sequence.labelOf("btn:start"));
        assertEquals("옵션 '빙결'", Sequence.labelOf("opt:빙결"));
        assertEquals("이미지 '상점'", Sequence.labelOf("img:상점"));
        assertEquals("회피 옵션 전체", Sequence.labelOf(Sequence.ALL_OPTIONS));
        assertEquals("(선택 안 함)", Sequence.labelOf(""));
    }
}
