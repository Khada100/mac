package com.imagemacro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

/**
 * 순서 목록 화면: 프리셋(읽기 전용)과 내가 만든 순서를 보고, 지금 실행할 순서를 고르고,
 * 새로 만들거나 복사하거나 지우거나 JSON 으로 주고받는다.
 */
public class SequenceListActivity extends Activity {

    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("순서 고르기 / 만들기");

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 이 화면에도 게임 버튼이 있을 수 있으니 매크로가 여기를 터치하지 않도록 정지
        MacroController c = MacroController.get();
        if (c.isMacroRunning()) {
            c.stopMacro("순서 화면이 열림");
        }
        render();
    }

    private void render() {
        list.removeAllViews();

        TextView hint = new TextView(this);
        hint.setText("'사용' 을 누르면 그 순서가 다음 매크로 시작부터 쓰여요. 프리셋은 고칠 수 없고 '복사해서 고치기' 로 내 것을 만들어요.");
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setTextColor(0xFF616161);
        hint.setPadding(0, 0, 0, dp(8));
        list.addView(hint);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(button("+ 새 순서", v -> createNew()), weight());
        top.addView(button("JSON 가져오기", v -> importJson()), weight());
        list.addView(top);

        String activeId = SequenceStore.activeId(this);
        List<Sequence> all = SequenceStore.listAll(this);
        boolean activeFound = false;
        for (Sequence s : all) {
            if (s.id.equals(activeId)) {
                activeFound = true;
            }
        }
        for (Sequence s : all) {
            boolean active = s.id.equals(activeId) || (!activeFound && Presets.A_CLASSIC.equals(s.id));
            addRow(s, active);
        }
    }

    private void addRow(final Sequence s, boolean active) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.setBackgroundColor(active ? 0xFFE3F2E5 : 0xFFF2F2F2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText((active ? "✅ " : "") + s.name + (s.builtIn ? "  (프리셋)" : ""));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText(s.summary() + presetNote(s));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(0xFF424242);
        card.addView(sub);

        List<String> problems = s.validate();
        if (!s.classic && !problems.isEmpty()) {
            TextView warn = new TextView(this);
            warn.setText("⚠ 채우거나 고쳐야 할 곳 " + problems.size() + "개 — 편집에서 확인하세요");
            warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            warn.setTextColor(0xFFB71C1C);
            card.addView(warn);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.START);
        buttons.addView(button(active ? "사용 중" : "사용", v -> use(s)), weight());
        if (s.builtIn) {
            buttons.addView(button("복사해서 고치기", v -> copyAndEdit(s)), weight());
        } else {
            buttons.addView(button("편집", v -> edit(s)), weight());
            buttons.addView(button("⋯", v -> more(s)), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        card.addView(buttons);
        list.addView(card);
    }

    private static String presetNote(Sequence s) {
        if (s.classic) {
            return "\n지금까지 쓰던 방식 그대로. 버튼 4개 + 옵션 이미지가 필요해요.";
        }
        if (Presets.B_TEMPLATE.equals(s.id)) {
            return "\n빈 틀이에요. 복사한 뒤 각 이미지 칸을 채워야 시작할 수 있어요.";
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 동작
    // ------------------------------------------------------------------

    private void use(Sequence s) {
        SequenceStore.setActive(this, s.id);
        MacroController.get().log("[순서] '" + s.name + "' 선택");
        List<String> problems = s.validate();
        if (!s.classic && !problems.isEmpty()) {
            toast("선택했지만 아직 시작할 수 없어요. 편집에서 비어 있는 곳을 채워 주세요.");
        } else {
            toast("'" + s.name + "' 순서를 사용합니다. (다음 시작부터 적용)");
        }
        render();
    }

    private void createNew() {
        final EditText input = new EditText(this);
        input.setHint("순서 이름 (예: B컨텐츠 내 방식)");
        new AlertDialog.Builder(this)
                .setTitle("새 순서 만들기")
                .setView(pad(input))
                .setPositiveButton("만들기", (d, w) -> {
                    Sequence s = new Sequence();
                    s.id = SequenceStore.newId();
                    s.name = nameOrDefault(input.getText().toString(), "새 순서");
                    Sequence.Block b = new Sequence.Block();
                    b.name = "새 블록";
                    s.blocks.add(b);
                    saveAndEdit(s);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void copyAndEdit(Sequence preset) {
        // '기존 방식' 은 블록이 없으므로, 같은 흐름을 블록으로 풀어 놓은 버전을 복사한다.
        Sequence src = preset.classic
                ? Presets.aBlocks(MacroSettings.load(this).startWaitSeconds)
                : preset;
        Sequence s = src.copy();
        s.id = SequenceStore.newId();
        s.name = src.name + " (내 것)";
        s.builtIn = false;
        s.classic = false;
        saveAndEdit(s);
    }

    private void saveAndEdit(Sequence s) {
        try {
            SequenceStore.save(this, s);
        } catch (IOException e) {
            toast("저장하지 못했어요: " + e.getMessage());
            return;
        }
        edit(s);
    }

    private void edit(Sequence s) {
        Intent i = new Intent(this, SequenceEditorActivity.class);
        i.putExtra(SequenceEditorActivity.EXTRA_ID, s.id);
        startActivity(i);
    }

    private void more(final Sequence s) {
        String[] items = {"이름 바꾸기", "복제", "JSON 복사 (내보내기)", "삭제"};
        new AlertDialog.Builder(this)
                .setTitle(s.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            rename(s);
                            break;
                        case 1:
                            duplicate(s);
                            break;
                        case 2:
                            exportJson(s);
                            break;
                        default:
                            confirmDelete(s);
                            break;
                    }
                })
                .show();
    }

    private void rename(final Sequence s) {
        final EditText input = new EditText(this);
        input.setText(s.name);
        new AlertDialog.Builder(this)
                .setTitle("이름 바꾸기")
                .setView(pad(input))
                .setPositiveButton("확인", (d, w) -> {
                    s.name = nameOrDefault(input.getText().toString(), s.name);
                    persist(s);
                    render();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void duplicate(Sequence s) {
        Sequence c = s.copy();
        c.id = SequenceStore.newId();
        c.name = s.name + " 복사";
        persist(c);
        render();
    }

    private void exportJson(Sequence s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            toast("클립보드를 쓸 수 없어요.");
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("순서", s.toJson(true)));
        toast("JSON 을 복사했어요. 메신저 등에 붙여넣어 공유할 수 있어요. (이미지 파일은 포함되지 않아요)");
    }

    private void importJson() {
        final EditText input = new EditText(this);
        input.setHint("여기에 붙여넣기");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(5);
        input.setGravity(Gravity.TOP);
        // 클립보드에 순서 JSON 이 있으면 미리 채워 준다
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (t != null && t.toString().trim().startsWith("{")) {
                input.setText(t);
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("순서 JSON 가져오기")
                .setView(pad(input))
                .setPositiveButton("가져오기", (d, w) -> {
                    try {
                        Sequence s = Sequence.fromJson(input.getText().toString());
                        s.id = SequenceStore.newId();
                        s.builtIn = false;
                        s.classic = false;
                        persist(s);
                        toast("'" + s.name + "' 가져왔어요. 이미지는 직접 등록해야 해요.");
                        render();
                    } catch (RuntimeException e) {
                        toast("읽지 못했어요: " + e.getMessage());
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void confirmDelete(final Sequence s) {
        new AlertDialog.Builder(this)
                .setMessage("'" + s.name + "' 순서를 지울까요?")
                .setPositiveButton("삭제", (d, w) -> {
                    SequenceStore.delete(this, s.id);
                    MacroController.get().log("[순서] '" + s.name + "' 삭제");
                    render();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    // ------------------------------------------------------------------

    private void persist(Sequence s) {
        try {
            SequenceStore.save(this, s);
        } catch (IOException e) {
            toast("저장하지 못했어요: " + e.getMessage());
        }
    }

    private static String nameOrDefault(String raw, String def) {
        String n = raw == null ? "" : raw.trim();
        return n.isEmpty() ? def : n;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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
