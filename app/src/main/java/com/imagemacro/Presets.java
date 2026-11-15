package com.imagemacro;

import com.imagemacro.Sequence.Action;
import com.imagemacro.Sequence.Block;
import com.imagemacro.Sequence.Cond;
import com.imagemacro.Sequence.OnFail;

/**
 * 기본으로 들어 있는 순서(프리셋). 읽기 전용이고, 고치려면 복사해서 편집한다.
 *
 *  - A컨텐츠 (기존 방식): 지금까지 쓰던 MacroEngine 그대로. 업데이트해도 동작이 바뀌지 않는다.
 *  - A컨텐츠 (블록 버전): 같은 동작을 블록으로 풀어 놓은 것. 복사해서 순서/조건을 마음대로 바꿀 수 있다.
 *  - B컨텐츠 (빈 틀): 배율 선택 → 전투 → (장비창이 가득 차면) 판매 → 물약 충전. 이미지는 직접 골라야 한다.
 */
public final class Presets {

    public static final String A_CLASSIC = "preset_a_classic";
    public static final String A_BLOCKS = "preset_a_blocks";
    public static final String B_TEMPLATE = "preset_b";

    private Presets() {
    }

    public static Sequence aClassic() {
        Sequence s = new Sequence();
        s.id = A_CLASSIC;
        s.name = "A컨텐츠 (기존 방식)";
        s.builtIn = true;
        s.classic = true;
        return s;
    }

    /** @param startWaitSeconds 전투 시작 후 대기 시간 (앱 설정의 값) */
    public static Sequence aBlocks(int startWaitSeconds) {
        Sequence s = new Sequence();
        s.id = A_BLOCKS;
        s.name = "A컨텐츠 (블록 버전)";
        s.builtIn = true;

        Block check = new Block();
        check.name = "옵션 검사 (나쁜 옵션이 보이면 새로고침)";
        check.actions.add(Action.tapWhileVisible(Sequence.ALL_OPTIONS, "btn:reload"));
        s.blocks.add(check);

        Block start = new Block();
        start.name = "전투 시작";
        start.onFail = OnFail.RESTART;
        start.actions.add(Action.tap("btn:start"));
        start.actions.add(Action.waitSeconds(Math.max(1, startWaitSeconds)));
        s.blocks.add(start);

        Block collect = new Block();
        collect.name = "결과 확인";
        collect.onFail = OnFail.RESTART;
        collect.actions.add(Action.tap("btn:collect1"));
        collect.actions.add(Action.tap("btn:collect2"));
        s.blocks.add(collect);
        return s;
    }

    public static Sequence bTemplate() {
        Sequence s = new Sequence();
        s.id = B_TEMPLATE;
        s.name = "B컨텐츠 (빈 틀)";
        s.builtIn = true;

        Block multiplier = new Block();
        multiplier.name = "배율 선택";
        multiplier.actions.add(Action.tap(""));
        s.blocks.add(multiplier);

        Block battle = new Block();
        battle.name = "전투 실행";
        battle.onFail = OnFail.RESTART;
        battle.actions.add(Action.tap(""));
        battle.actions.add(Action.waitImage("", 300));
        battle.actions.add(Action.tap(""));
        s.blocks.add(battle);

        Block sell = new Block();
        sell.name = "상점 판매 (장비창이 가득 찼을 때)";
        sell.cond = Cond.IMAGE_VISIBLE;
        sell.condWaitSeconds = 2;
        sell.actions.add(Action.tap(""));
        sell.actions.add(Action.tap(""));
        sell.actions.add(Action.tap(""));
        s.blocks.add(sell);

        Block potion = new Block();
        potion.name = "물약 충전 (10판마다)";
        potion.cond = Cond.EVERY_N;
        potion.condN = 10;
        potion.actions.add(Action.tap(""));
        s.blocks.add(potion);
        return s;
    }
}
