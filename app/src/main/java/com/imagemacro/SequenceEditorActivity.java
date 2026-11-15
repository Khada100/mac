package com.imagemacro;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.imagemacro.Sequence.Action;
import com.imagemacro.Sequence.ActionType;
import com.imagemacro.Sequence.Block;
import com.imagemacro.Sequence.Cond;
import com.imagemacro.Sequence.OnFail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 순서 편집 화면 (한 화면에서 '순서 전체' 와 '블록 하나' 를 오간다).
 * 바꿀 때마다 바로 저장하므로 저장 버튼이 없다. 순서(블록 위아래)는 ▲▼ 버튼으로 바꾼다.
 */
public class SequenceEditorActivity extends Activity {

    public static final String EXTRA_ID = "sequenceId";

    private interface Callback<T> {
        void done(T value);
    }

    private Sequence seq;
    /** -1 이면 순서 전체 화면, 0 이상이면 그 번째 블록 화면 */
    private int viewing = -1;
    private LinearLayout content;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        seq = SequenceStore.get(this, getIntent().getStringExtra(EXTRA_ID));
        if (seq == null || seq.builtIn || seq.classic) {
            Toast.makeText(this, "편집할 수 없는 순서예요. 복사해서 고치세요.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setTitle(seq.name);

        scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(32));
        scroll.addView(content, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 편집 화면이 게임 화면으로 착각돼 터치되지 않도록 매크로 정지
        MacroController c = MacroController.get();
        if (c.isMacroRunning()) {
            c.stopMacro("순서 편집 화면이 열림");
        }
    }

    @Override
    public void onBackPressed() {
        if (viewing >= 0) {
            viewing = -1;
            render();
        } else {
            super.onBackPressed();
        }
    }

    private void render() {
        content.removeAllViews();
        if (viewing >= 0 && viewing < seq.blocks.size()) {
            renderBlock(viewing);
        } else {
            viewing = -1;
            renderSequence();
        }
        scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void persist() {
        try {
            SequenceStore.save(this, seq);
        } catch (IOException e) {
            toast("저장하지 못했어요: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 순서 전체 화면
    // ------------------------------------------------------------------

    private void renderSequence() {
        setTitle(seq.name);
        addLabel("순서 이름", true);
        addRowWithButton(seq.name, "바꾸기", v -> askText("순서 이름", seq.name, value -> {
            seq.name = value.isEmpty() ? seq.name : value;
            persist();
            render();
        }));

        addLabel("반복", true);
        addRowWithButton(seq.loopCount > 0 ? seq.loopCount + "바퀴 돌고 정지" : "멈출 때까지 계속 반복", "바꾸기",
                v -> askNumber("몇 바퀴 돌고 멈출까요? (0 = 계속)", seq.loopCount, 0, value -> {
                    seq.loopCount = value;
                    persist();
                    render();
                }));

        List<String> problems = seq.validate();
        if (!problems.isEmpty()) {
            TextView warn = new TextView(this);
            StringBuilder sb = new StringBuilder("⚠ 시작하기 전에 고쳐야 할 곳 " + problems.size() + "개");
            for (String p : problems) {
                sb.append("\n · ").append(p);
            }
            warn.setText(sb.toString());
            warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            warn.setTextColor(0xFFB71C1C);
            warn.setBackgroundColor(0xFFFDECEA);
            warn.setPadding(dp(10), dp(8), dp(10), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(12);
            content.addView(warn, lp);
        }

        addLabel("블록 (위에서 아래로 차례로 실행)", true);
        TextView hint = new TextView(this);
        hint.setText("한 바퀴 = 켜진 블록을 위에서부터 한 번씩. ▲▼ 로 순서를 바꾸고, 체크를 끄면 그 블록은 건너뛰어요.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setTextColor(0xFF616161);
        content.addView(hint);

        for (int i = 0; i < seq.blocks.size(); i++) {
            addBlockCard(i);
        }
        Button add = new Button(this);
        add.setText("+ 블록 추가");
        add.setOnClickListener(v -> {
            Block b = new Block();
            b.name = "새 블록 " + (seq.blocks.size() + 1);
            seq.blocks.add(b);
            persist();
            viewing = seq.blocks.size() - 1;
            render();
        });
        content.addView(add);
    }

    private void addBlockCard(final int index) {
        final Block b = seq.blocks.get(index);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(6), dp(10), dp(6));
        card.setBackgroundColor(b.enabled ? 0xFFF2F2F2 : 0xFFE0E0E0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);

        CheckBox cb = new CheckBox(this);
        cb.setText((index + 1) + ". " + b.name);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cb.setChecked(b.enabled);
        cb.setOnCheckedChangeListener((btn, checked) -> {
            b.enabled = checked;
            persist();
            render();
        });
        card.addView(cb);

        TextView info = new TextView(this);
        info.setText(b.describeCond() + " · 동작 " + b.actions.size() + "개");
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setTextColor(0xFF424242);
        card.addView(info);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(smallButton("▲", index > 0, v -> {
            Collections.swap(seq.blocks, index, index - 1);
            persist();
            render();
        }));
        buttons.addView(smallButton("▼", index < seq.blocks.size() - 1, v -> {
            Collections.swap(seq.blocks, index, index + 1);
            persist();
            render();
        }));
        buttons.addView(smallButton("편집", true, v -> {
            viewing = index;
            render();
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(smallButton("복제", true, v -> {
            Block c = b.copy();
            c.name = b.name + " 복사";
            seq.blocks.add(index + 1, c);
            persist();
            render();
        }));
        buttons.addView(smallButton("삭제", true, v -> new AlertDialog.Builder(this)
                .setMessage("'" + b.name + "' 블록을 지울까요?")
                .setPositiveButton("삭제", (d, w) -> {
                    seq.blocks.remove(index);
                    persist();
                    render();
                })
                .setNegativeButton("취소", null)
                .show()));
        card.addView(buttons);
        content.addView(card);
    }

    // ------------------------------------------------------------------
    // 블록 화면
    // ------------------------------------------------------------------

    private void renderBlock(final int index) {
        final Block b = seq.blocks.get(index);
        setTitle("블록 " + (index + 1) + " · " + b.name);

        Button back = new Button(this);
        back.setText("← 블록 목록으로");
        back.setOnClickListener(v -> {
            viewing = -1;
            render();
        });
        content.addView(back);

        addLabel("블록 이름", true);
        addRowWithButton(b.name, "바꾸기", v -> askText("블록 이름", b.name, value -> {
            b.name = value.isEmpty() ? b.name : value;
            persist();
            render();
        }));

        CheckBox enabled = new CheckBox(this);
        enabled.setText("이 블록 사용 (끄면 건너뜀)");
        enabled.setChecked(b.enabled);
        enabled.setOnCheckedChangeListener((btn, checked) -> {
            b.enabled = checked;
            persist();
        });
        content.addView(enabled);

        addLabel("언제 실행할까요 (실행 조건)", true);
        addRowWithButton(b.describeCond(), "바꾸기", v -> editCond(b));

        addLabel("동작이 실패하면 (이미지를 못 찾았을 때)", true);
        addRowWithButton(b.describeOnFail(), "바꾸기", v -> editOnFail(b));

        addLabel("동작 (위에서 아래로 차례로)", true);
        if (b.actions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("아직 없음 — 아래 [+ 동작 추가] 로 넣으세요.");
            content.addView(empty);
        }
        for (int i = 0; i < b.actions.size(); i++) {
            addActionCard(b, i);
        }
        Button add = new Button(this);
        add.setText("+ 동작 추가");
        add.setOnClickListener(v -> chooseActionType(b));
        content.addView(add);
    }

    private void addActionCard(final Block b, final int index) {
        final Action a = b.actions.get(index);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(6), dp(10), dp(6));
        card.setBackgroundColor(0xFFF2F2F2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText((index + 1) + ". " + a.describe());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        card.addView(t);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(smallButton("▲", index > 0, v -> {
            Collections.swap(b.actions, index, index - 1);
            persist();
            render();
        }));
        buttons.addView(smallButton("▼", index < b.actions.size() - 1, v -> {
            Collections.swap(b.actions, index, index + 1);
            persist();
            render();
        }));
        buttons.addView(smallButton("편집", true, v -> editAction(b, index, false)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(smallButton("삭제", true, v -> {
            b.actions.remove(index);
            persist();
            render();
        }));
        card.addView(buttons);
        content.addView(card);
    }

    private void editCond(final Block b) {
        final Cond[] conds = {Cond.ALWAYS, Cond.IMAGE_VISIBLE, Cond.IMAGE_ABSENT, Cond.EVERY_N};
        String[] labels = {
                "항상 실행",
                "어떤 이미지가 보일 때만 (예: 장비창이 가득 찼을 때만 판매)",
                "어떤 이미지가 안 보일 때만",
                "N번째 판마다 (예: 10판마다 물약 충전)"
        };
        new AlertDialog.Builder(this)
                .setTitle("실행 조건")
                .setItems(labels, (d, which) -> {
                    final Cond c = conds[which];
                    if (c == Cond.ALWAYS) {
                        b.cond = c;
                        persist();
                        render();
                    } else if (c == Cond.EVERY_N) {
                        askNumber("몇 번째 판마다 실행할까요? (예: 10)", Math.max(2, b.condN), 1, value -> {
                            b.cond = c;
                            b.condN = value;
                            persist();
                            render();
                        });
                    } else {
                        pickImage(ref -> {
                            b.cond = c;
                            b.condImage = ref;
                            if (c == Cond.IMAGE_VISIBLE) {
                                askNumber("이미지가 나타나길 최대 몇 초 기다릴까요? (0 = 지금 한 번만 확인)",
                                        b.condWaitSeconds, 0, value -> {
                                            b.condWaitSeconds = value;
                                            persist();
                                            render();
                                        });
                            } else {
                                persist();
                                render();
                            }
                        });
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void editOnFail(final Block b) {
        final OnFail[] fails = {OnFail.SKIP, OnFail.RESTART, OnFail.STOP};
        String[] labels = {
                "이 블록만 건너뛰고 다음 블록으로",
                "이번 바퀴를 버리고 첫 블록부터 다시",
                "매크로 정지"
        };
        new AlertDialog.Builder(this)
                .setTitle("동작이 실패하면")
                .setItems(labels, (d, which) -> {
                    b.onFail = fails[which];
                    persist();
                    render();
                })
                .show();
    }

    // ------------------------------------------------------------------
    // 동작 추가 / 편집
    // ------------------------------------------------------------------

    private void chooseActionType(final Block b) {
        final ActionType[] types = {ActionType.TAP_IMAGE, ActionType.WAIT_IMAGE, ActionType.WAIT,
                ActionType.TAP_WHILE_VISIBLE};
        String[] labels = {
                "이미지를 찾아서 클릭",
                "이미지가 나타날 때까지 대기",
                "몇 초 기다리기",
                "이미지가 보이는 동안 다른 이미지를 계속 클릭"
        };
        new AlertDialog.Builder(this)
                .setTitle("어떤 동작을 넣을까요")
                .setItems(labels, (d, which) -> {
                    Action a = new Action();
                    a.type = types[which];
                    if (a.type == ActionType.WAIT) {
                        a.seconds = 3;
                    }
                    b.actions.add(a);
                    persist();
                    editAction(b, b.actions.size() - 1, true);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /** @param isNew 방금 추가한 동작이면 취소할 때 다시 지운다 */
    private void editAction(final Block b, final int index, final boolean isNew) {
        final Action a = b.actions.get(index).copy();
        final LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText editSeconds = new EditText(this);
        final EditText editCount = new EditText(this);
        final CheckBox optional = new CheckBox(this);

        switch (a.type) {
            case TAP_IMAGE:
            case WAIT_IMAGE: {
                form.addView(formLabel(a.type == ActionType.TAP_IMAGE ? "클릭할 이미지" : "기다릴 이미지"));
                final Button pick = new Button(this);
                pick.setText(Sequence.labelOf(a.image));
                pick.setOnClickListener(v -> pickImage(ref -> {
                    a.image = ref;
                    pick.setText(Sequence.labelOf(ref));
                }));
                form.addView(pick);
                if (a.type == ActionType.TAP_IMAGE) {
                    form.addView(formLabel("같은 자리를 몇 번 누를까요"));
                    numberField(editCount, a.count);
                    form.addView(editCount);
                }
                form.addView(formLabel("이미지를 최대 몇 초 기다릴까요 (0 = 설정의 기본 대기 시간)"));
                numberField(editSeconds, a.seconds);
                form.addView(editSeconds);
                optional.setText(a.type == ActionType.TAP_IMAGE
                        ? "이미지가 없으면 실패로 보지 않고 넘어감 (예: 있을 때만 닫는 팝업)"
                        : "안 나타나도 실패로 보지 않고 계속");
                optional.setChecked(a.optional);
                form.addView(optional);
                break;
            }
            case WAIT: {
                form.addView(formLabel("기다릴 시간 (초)"));
                numberField(editSeconds, a.seconds);
                form.addView(editSeconds);
                break;
            }
            case TAP_WHILE_VISIBLE: {
                form.addView(formLabel("감시할 이미지 (하나라도 보이면 아래 이미지를 클릭)"));
                final Button watch = new Button(this);
                watch.setText(watchText(a));
                watch.setOnClickListener(v -> pickWatchImages(a, () -> watch.setText(watchText(a))));
                form.addView(watch);
                form.addView(formLabel("계속 클릭할 이미지 (예: 새로고침 버튼)"));
                final Button tap = new Button(this);
                tap.setText(Sequence.labelOf(a.tapImage));
                tap.setOnClickListener(v -> pickImage(ref -> {
                    a.tapImage = ref;
                    tap.setText(Sequence.labelOf(ref));
                }));
                form.addView(tap);
                form.addView(formLabel("최대 몇 초까지 반복할까요 (0 = 안 보일 때까지 계속)"));
                numberField(editSeconds, a.seconds);
                form.addView(editSeconds);
                break;
            }
            default:
                break;
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(form);
        new AlertDialog.Builder(this)
                .setTitle(isNew ? "동작 설정" : "동작 고치기")
                .setView(sv)
                .setPositiveButton("확인", (d, w) -> {
                    a.seconds = readInt(editSeconds, a.seconds);
                    if (a.type == ActionType.TAP_IMAGE) {
                        a.count = Math.max(1, readInt(editCount, a.count));
                    }
                    if (a.type == ActionType.TAP_IMAGE || a.type == ActionType.WAIT_IMAGE) {
                        a.optional = optional.isChecked();
                    }
                    b.actions.set(index, a);
                    persist();
                    render();
                })
                .setNegativeButton("취소", (d, w) -> {
                    if (isNew) {
                        b.actions.remove(index);
                        persist();
                    }
                    render();
                })
                .setOnCancelListener(d -> {
                    if (isNew) {
                        b.actions.remove(index);
                        persist();
                    }
                    render();
                })
                .show();
    }

    private static String watchText(Action a) {
        if (a.images.isEmpty()) {
            return "(선택 안 함)";
        }
        StringBuilder sb = new StringBuilder();
        for (String r : a.images) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(Sequence.labelOf(r));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 이미지 고르기
    // ------------------------------------------------------------------

    private void pickImage(final Callback<String> cb) {
        final List<String> refs = ImageStore.allRefs(this);
        String[] labels = new String[refs.size()];
        for (int i = 0; i < refs.size(); i++) {
            labels[i] = refLabel(refs.get(i));
        }
        if (refs.isEmpty()) {
            toast("고를 이미지가 없어요.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("이미지 고르기 (❌ = 아직 등록 안 됨)")
                .setItems(labels, (d, which) -> cb.done(refs.get(which)))
                .setNegativeButton("취소", null)
                .show();
    }

    private void pickWatchImages(final Action a, final Runnable changed) {
        final List<String> refs = new ArrayList<>();
        refs.add(Sequence.ALL_OPTIONS);
        refs.addAll(ImageStore.allRefs(this));
        for (String r : a.images) {
            if (!refs.contains(r)) {
                refs.add(r);
            }
        }
        String[] labels = new String[refs.size()];
        final boolean[] checked = new boolean[refs.size()];
        for (int i = 0; i < refs.size(); i++) {
            labels[i] = Sequence.ALL_OPTIONS.equals(refs.get(i))
                    ? "회피 옵션 전체 (등록한 옵션 이미지 모두)"
                    : refLabel(refs.get(i));
            checked[i] = a.images.contains(refs.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("감시할 이미지 고르기")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("확인", (d, w) -> {
                    a.images = new ArrayList<>();
                    for (int i = 0; i < refs.size(); i++) {
                        if (checked[i]) {
                            a.images.add(refs.get(i));
                        }
                    }
                    changed.run();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private String refLabel(String ref) {
        File f = ImageStore.fileForRef(this, ref);
        boolean registered = f != null && f.isFile();
        return (registered ? "✅ " : "❌ ") + Sequence.labelOf(ref);
    }

    // ------------------------------------------------------------------
    // 입력 도우미
    // ------------------------------------------------------------------

    private void askText(String title, String current, final Callback<String> cb) {
        final EditText input = new EditText(this);
        input.setText(current);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(pad(input))
                .setPositiveButton("확인", (d, w) -> cb.done(input.getText().toString().trim()))
                .setNegativeButton("취소", null)
                .show();
    }

    private void askNumber(String title, int current, final int min, final Callback<Integer> cb) {
        final EditText input = new EditText(this);
        numberField(input, current);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(pad(input))
                .setPositiveButton("확인", (d, w) -> {
                    int v = readInt(input, current);
                    cb.done(Math.max(min, v));
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private static void numberField(EditText e, int value) {
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setSelectAllOnFocus(true);
        e.setMaxLines(1);
    }

    private static int readInt(EditText e, int def) {
        return MacroSettings.parseInt(e.getText().toString(), def);
    }

    // ------------------------------------------------------------------
    // 화면 조립 도우미
    // ------------------------------------------------------------------

    private void addLabel(String text, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(null, bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        t.setTextColor(0xFF1E3A5F);
        t.setPadding(0, dp(16), 0, dp(2));
        content.addView(t);
    }

    private void addRowWithButton(String text, String buttonText, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button b = new Button(this);
        b.setText(buttonText);
        b.setOnClickListener(l);
        row.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(row);
    }

    private Button smallButton(String text, boolean enabled, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setEnabled(enabled);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setOnClickListener(l);
        return b;
    }

    private TextView formLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(0xFF616161);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private View pad(View v) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
