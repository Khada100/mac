package com.imagemacro;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 사용자가 순서를 직접 정하는 매크로 한 벌 ("순서").
 *
 *   순서(Sequence) = 블록(Block)들의 목록. 블록 순서가 곧 실행 순서다 (A-B-C-D, A-D-C-B ...).
 *   블록(Block)    = 실행 조건 + 동작(Action)들 + 실패했을 때 할 일.
 *   동작(Action)   = 이미지 찾아 클릭 / 이미지가 나타날 때까지 대기 / 시간 대기 / 이미지가 보이는 동안 계속 클릭.
 *
 * 한 바퀴 = 켜져 있는 블록을 위에서부터 한 번씩 실행. 끝나면 처음부터 반복한다.
 * 안드로이드에 의존하지 않는 순수 Java 라서 PC 에서 테스트할 수 있다.
 */
public final class Sequence {

    public enum ActionType {
        /** 이미지를 찾아서 (최대 N초 기다림) 클릭 */
        TAP_IMAGE,
        /** 이미지가 화면에 나타날 때까지 대기 (클릭 안 함) */
        WAIT_IMAGE,
        /** 정해진 시간(초) 대기 */
        WAIT,
        /** 감시 이미지가 하나라도 보이는 동안 다른 이미지를 계속 클릭 (예: 나쁜 옵션이 보이면 새로고침) */
        TAP_WHILE_VISIBLE
    }

    public enum Cond {
        ALWAYS,
        /** 이미지가 보일 때만 (최대 N초 기다려 봄) */
        IMAGE_VISIBLE,
        /** 이미지가 안 보일 때만 */
        IMAGE_ABSENT,
        /** N번째 판마다 (N, 2N, 3N ... 번째 판에서만) */
        EVERY_N
    }

    public enum OnFail {
        /** 이 블록만 건너뛰고 다음 블록으로 */
        SKIP,
        /** 이번 바퀴를 버리고 첫 블록부터 다시 */
        RESTART,
        /** 매크로 정지 */
        STOP
    }

    /** 이미지 참조 중 '등록된 회피 옵션 전체'를 뜻하는 특수 값 (실행할 때 실제 옵션 목록으로 펼쳐진다). */
    public static final String ALL_OPTIONS = "opt:*";

    public static final class Action {
        public ActionType type = ActionType.TAP_IMAGE;
        /** TAP_IMAGE / WAIT_IMAGE 의 대상 이미지 (예: "btn:start", "opt:빙결", "img:상점"). */
        public String image = "";
        /** TAP_WHILE_VISIBLE 의 감시 이미지들. {@link #ALL_OPTIONS} 를 넣으면 옵션 전체. */
        public List<String> images = new ArrayList<>();
        /** TAP_WHILE_VISIBLE 에서 계속 누를 이미지. */
        public String tapImage = "";
        /**
         * WAIT: 기다릴 초. TAP_IMAGE/WAIT_IMAGE: 이미지를 기다리는 최대 초 (0 = 설정의 기본 대기 시간).
         * TAP_WHILE_VISIBLE: 전체 최대 초 (0 = 무한).
         */
        public int seconds = 0;
        /** TAP_IMAGE: 같은 자리를 몇 번 누를지 (예: + 버튼을 3번). */
        public int count = 1;
        /** 이미지를 못 찾아도 실패로 보지 않고 계속 (예: 있을 때만 닫는 팝업). */
        public boolean optional = false;

        public Action copy() {
            Action a = new Action();
            a.type = type;
            a.image = image;
            a.images = new ArrayList<>(images);
            a.tapImage = tapImage;
            a.seconds = seconds;
            a.count = count;
            a.optional = optional;
            return a;
        }

        public static Action tap(String image) {
            Action a = new Action();
            a.type = ActionType.TAP_IMAGE;
            a.image = image;
            return a;
        }

        public static Action waitImage(String image, int seconds) {
            Action a = new Action();
            a.type = ActionType.WAIT_IMAGE;
            a.image = image;
            a.seconds = seconds;
            return a;
        }

        public static Action waitSeconds(int seconds) {
            Action a = new Action();
            a.type = ActionType.WAIT;
            a.seconds = seconds;
            return a;
        }

        public static Action tapWhileVisible(String watch, String tapImage) {
            Action a = new Action();
            a.type = ActionType.TAP_WHILE_VISIBLE;
            a.images.add(watch);
            a.tapImage = tapImage;
            return a;
        }

        /** 사람이 읽는 한 줄 설명. */
        public String describe() {
            switch (type) {
                case TAP_IMAGE:
                    return "클릭: " + labelOf(image)
                            + (count > 1 ? " × " + count + "번" : "")
                            + (seconds > 0 ? " (최대 " + seconds + "초 대기)" : "")
                            + (optional ? " (없으면 건너뜀)" : "");
                case WAIT_IMAGE:
                    return "나타날 때까지 대기: " + labelOf(image)
                            + (seconds > 0 ? " (최대 " + seconds + "초)" : "")
                            + (optional ? " (안 나와도 계속)" : "");
                case WAIT:
                    return seconds + "초 대기";
                case TAP_WHILE_VISIBLE:
                    StringBuilder sb = new StringBuilder("보이는 동안 클릭: ");
                    sb.append(images.isEmpty() ? "(감시 이미지 없음)" : joinLabels(images));
                    sb.append(" 이 보이면 → ").append(labelOf(tapImage)).append(" 클릭");
                    if (seconds > 0) {
                        sb.append(" (최대 ").append(seconds).append("초)");
                    }
                    return sb.toString();
                default:
                    return type.name();
            }
        }
    }

    public static final class Block {
        public String name = "새 블록";
        public boolean enabled = true;
        public Cond cond = Cond.ALWAYS;
        /** IMAGE_VISIBLE / IMAGE_ABSENT 의 기준 이미지. */
        public String condImage = "";
        /** EVERY_N 의 N. */
        public int condN = 2;
        /** IMAGE_VISIBLE: 이미지가 나타나길 최대 몇 초 기다려 볼지 (0 = 지금 한 번만 확인). */
        public int condWaitSeconds = 0;
        public OnFail onFail = OnFail.SKIP;
        public List<Action> actions = new ArrayList<>();

        public Block copy() {
            Block b = new Block();
            b.name = name;
            b.enabled = enabled;
            b.cond = cond;
            b.condImage = condImage;
            b.condN = condN;
            b.condWaitSeconds = condWaitSeconds;
            b.onFail = onFail;
            for (Action a : actions) {
                b.actions.add(a.copy());
            }
            return b;
        }

        public String describeCond() {
            switch (cond) {
                case IMAGE_VISIBLE:
                    return labelOf(condImage) + " 이(가) 보일 때만"
                            + (condWaitSeconds > 0 ? " (최대 " + condWaitSeconds + "초 기다림)" : "");
                case IMAGE_ABSENT:
                    return labelOf(condImage) + " 이(가) 안 보일 때만";
                case EVERY_N:
                    return condN <= 1 ? "매 판마다" : condN + "번째 판마다";
                default:
                    return "항상 실행";
            }
        }

        public String describeOnFail() {
            switch (onFail) {
                case RESTART:
                    return "처음(첫 블록)부터 다시";
                case STOP:
                    return "매크로 정지";
                default:
                    return "이 블록만 건너뛰고 계속";
            }
        }
    }

    /** 저장소에서의 이름(파일 이름 줄기). 프리셋은 고정 ID. */
    public String id = "";
    public String name = "새 순서";
    /** 몇 바퀴 돌고 멈출지 (0 = 계속). */
    public int loopCount = 0;
    public List<Block> blocks = new ArrayList<>();
    /** 프리셋 여부 (읽기 전용, 복사해서 편집). 저장되지 않는다. */
    public boolean builtIn = false;
    /** 블록이 아니라 기존 MacroEngine 으로 실행하는 'A컨텐츠 (기존 방식)'. 저장되지 않는다. */
    public boolean classic = false;

    public Sequence copy() {
        Sequence s = new Sequence();
        s.id = id;
        s.name = name;
        s.loopCount = loopCount;
        s.builtIn = builtIn;
        s.classic = classic;
        for (Block b : blocks) {
            s.blocks.add(b.copy());
        }
        return s;
    }

    // ------------------------------------------------------------------
    // 이미지 참조
    // ------------------------------------------------------------------

    /** 이 순서가 쓰는 이미지 참조들 (중복 없음, 빈 값과 {@link #ALL_OPTIONS} 는 제외). */
    public List<String> imageRefs() {
        List<String> out = new ArrayList<>();
        for (Block b : blocks) {
            if (!b.enabled) {
                continue;
            }
            addRef(out, b.condImage);
            for (Action a : b.actions) {
                addRef(out, a.image);
                addRef(out, a.tapImage);
                for (String s : a.images) {
                    addRef(out, s);
                }
            }
        }
        return out;
    }

    /** 켜진 블록 어딘가에서 '옵션 전체' 를 쓰는지. */
    public boolean usesAllOptions() {
        for (Block b : blocks) {
            if (!b.enabled) {
                continue;
            }
            for (Action a : b.actions) {
                if (a.images.contains(ALL_OPTIONS)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@link #ALL_OPTIONS} 를 실제 옵션 이미지 참조들로 바꾼 복사본. */
    public Sequence expandOptions(List<String> optionRefs) {
        Sequence s = copy();
        for (Block b : s.blocks) {
            for (Action a : b.actions) {
                if (a.images.contains(ALL_OPTIONS)) {
                    List<String> expanded = new ArrayList<>();
                    for (String r : a.images) {
                        if (ALL_OPTIONS.equals(r)) {
                            for (String o : optionRefs) {
                                if (!expanded.contains(o)) {
                                    expanded.add(o);
                                }
                            }
                        } else if (!expanded.contains(r)) {
                            expanded.add(r);
                        }
                    }
                    a.images = expanded;
                }
            }
        }
        return s;
    }

    private static void addRef(List<String> out, String ref) {
        if (ref != null && !ref.isEmpty() && !ALL_OPTIONS.equals(ref) && !out.contains(ref)) {
            out.add(ref);
        }
    }

    // ------------------------------------------------------------------
    // 검사
    // ------------------------------------------------------------------

    /** 시작 전에 고쳐야 할 문제들 (없으면 빈 목록). 꺼진 블록은 검사하지 않는다. */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        if (classic) {
            return problems;
        }
        int enabled = 0;
        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            if (!b.enabled) {
                continue;
            }
            enabled++;
            String where = "블록 " + (i + 1) + " '" + b.name + "'";
            if (b.actions.isEmpty()) {
                problems.add(where + ": 동작이 하나도 없어요");
            }
            if ((b.cond == Cond.IMAGE_VISIBLE || b.cond == Cond.IMAGE_ABSENT) && b.condImage.isEmpty()) {
                problems.add(where + ": 실행 조건에 쓸 이미지를 골라 주세요");
            }
            if (b.cond == Cond.EVERY_N && b.condN < 1) {
                problems.add(where + ": N번째 판마다의 N은 1 이상이어야 해요");
            }
            for (int j = 0; j < b.actions.size(); j++) {
                Action a = b.actions.get(j);
                String aw = where + " · 동작 " + (j + 1);
                switch (a.type) {
                    case TAP_IMAGE:
                    case WAIT_IMAGE:
                        if (a.image.isEmpty()) {
                            problems.add(aw + ": 이미지를 골라 주세요");
                        }
                        break;
                    case WAIT:
                        if (a.seconds < 1) {
                            problems.add(aw + ": 대기 시간은 1초 이상이어야 해요");
                        }
                        break;
                    case TAP_WHILE_VISIBLE:
                        if (a.images.isEmpty()) {
                            problems.add(aw + ": 감시할 이미지를 골라 주세요");
                        }
                        if (a.tapImage.isEmpty()) {
                            problems.add(aw + ": 누를 이미지를 골라 주세요");
                        }
                        break;
                    default:
                        break;
                }
            }
        }
        if (enabled == 0) {
            problems.add("켜져 있는 블록이 없어요");
        }
        return problems;
    }

    public String summary() {
        if (classic) {
            return "옵션 검사 → (나쁜 옵션이면 새로고침) → 전투 시작 → 대기 → 확인 1 → 확인 2";
        }
        int on = 0;
        for (Block b : blocks) {
            if (b.enabled) {
                on++;
            }
        }
        return "블록 " + blocks.size() + "개" + (on != blocks.size() ? " (켜짐 " + on + "개)" : "")
                + " · " + (loopCount > 0 ? loopCount + "바퀴 후 정지" : "계속 반복");
    }

    // ------------------------------------------------------------------
    // 이미지 참조 → 사람이 읽는 이름
    // ------------------------------------------------------------------

    public static String labelOf(String ref) {
        if (ref == null || ref.isEmpty()) {
            return "(선택 안 함)";
        }
        if (ALL_OPTIONS.equals(ref)) {
            return "회피 옵션 전체";
        }
        int colon = ref.indexOf(':');
        if (colon < 0) {
            return ref;
        }
        String kind = ref.substring(0, colon);
        String name = ref.substring(colon + 1);
        switch (kind) {
            case "btn":
                switch (name) {
                    case "start":
                        return "버튼 '전투 시작'";
                    case "reload":
                        return "버튼 '새로고침'";
                    case "collect1":
                        return "버튼 '확인 1'";
                    case "collect2":
                        return "버튼 '확인 2'";
                    default:
                        return "버튼 '" + name + "'";
                }
            case "opt":
                return "옵션 '" + name + "'";
            case "img":
                return "이미지 '" + name + "'";
            default:
                return ref;
        }
    }

    private static String joinLabels(List<String> refs) {
        StringBuilder sb = new StringBuilder();
        for (String r : refs) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(labelOf(r));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // JSON (저장 / 내보내기 / 가져오기)
    // ------------------------------------------------------------------

    public String toJson(boolean pretty) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("name", name);
        root.put("loopCount", loopCount);
        List<Object> bl = new ArrayList<>();
        for (Block b : blocks) {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("name", b.name);
            bm.put("enabled", b.enabled);
            bm.put("cond", b.cond.name());
            bm.put("condImage", b.condImage);
            bm.put("condN", b.condN);
            bm.put("condWaitSeconds", b.condWaitSeconds);
            bm.put("onFail", b.onFail.name());
            List<Object> al = new ArrayList<>();
            for (Action a : b.actions) {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("type", a.type.name());
                am.put("image", a.image);
                am.put("images", new ArrayList<Object>(a.images));
                am.put("tapImage", a.tapImage);
                am.put("seconds", a.seconds);
                am.put("count", a.count);
                am.put("optional", a.optional);
                al.add(am);
            }
            bm.put("actions", al);
            bl.add(bm);
        }
        root.put("blocks", bl);
        return Json.stringify(root, pretty);
    }

    /**
     * JSON 에서 순서를 만든다. 없는 항목은 기본값, 모르는 값(종류 이름 등)은 기본값으로 처리해서
     * 다른 버전에서 내보낸 파일도 최대한 읽는다. JSON 문법이 틀리면 IllegalArgumentException.
     */
    public static Sequence fromJson(String json) {
        Object parsed = Json.parse(json);
        if (!(parsed instanceof Map)) {
            throw new IllegalArgumentException("순서 JSON 은 { ... } 형태여야 합니다");
        }
        Map<String, Object> root = Json.asMap(parsed);
        Sequence s = new Sequence();
        s.name = Json.str(root, "name", "가져온 순서");
        s.loopCount = Math.max(0, Json.integer(root, "loopCount", 0));
        for (Object bo : Json.asList(root.get("blocks"))) {
            Map<String, Object> bm = Json.asMap(bo);
            Block b = new Block();
            b.name = Json.str(bm, "name", "블록");
            b.enabled = Json.bool(bm, "enabled", true);
            b.cond = enumOf(Cond.class, Json.str(bm, "cond", ""), Cond.ALWAYS);
            b.condImage = Json.str(bm, "condImage", "");
            b.condN = Json.integer(bm, "condN", 2);
            b.condWaitSeconds = Math.max(0, Json.integer(bm, "condWaitSeconds", 0));
            b.onFail = enumOf(OnFail.class, Json.str(bm, "onFail", ""), OnFail.SKIP);
            for (Object ao : Json.asList(bm.get("actions"))) {
                Map<String, Object> am = Json.asMap(ao);
                Action a = new Action();
                a.type = enumOf(ActionType.class, Json.str(am, "type", ""), ActionType.TAP_IMAGE);
                a.image = Json.str(am, "image", "");
                for (Object io : Json.asList(am.get("images"))) {
                    if (io instanceof String) {
                        a.images.add((String) io);
                    }
                }
                a.tapImage = Json.str(am, "tapImage", "");
                a.seconds = Math.max(0, Json.integer(am, "seconds", 0));
                a.count = Math.max(1, Json.integer(am, "count", 1));
                a.optional = Json.bool(am, "optional", false);
                b.actions.add(a);
            }
            s.blocks.add(b);
        }
        return s;
    }

    private static <T extends Enum<T>> T enumOf(Class<T> type, String name, T def) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
