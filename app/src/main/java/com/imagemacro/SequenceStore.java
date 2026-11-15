package com.imagemacro;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 순서 저장소.
 *  - 프리셋(A 기존 방식 / A 블록 버전 / B 빈 틀)은 코드에 들어 있고 읽기 전용이다.
 *  - 사용자가 만든 순서는 files/sequences/user_xxx.json 한 파일씩.
 *  - 지금 실행할 순서는 SharedPreferences 에 ID 로 기억한다 (기본값: A컨텐츠 기존 방식 → 업데이트해도 동작이 안 바뀐다).
 */
public final class SequenceStore {

    private static final String PREFS = "sequence_prefs";
    private static final String KEY_ACTIVE = "active";

    private SequenceStore() {
    }

    private static File dir(Context c) {
        File d = new File(c.getFilesDir(), "sequences");
        if (!d.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    private static File fileOf(Context c, String id) {
        return new File(dir(c), id + ".json");
    }

    public static boolean isPresetId(String id) {
        return Presets.A_CLASSIC.equals(id) || Presets.A_BLOCKS.equals(id) || Presets.B_TEMPLATE.equals(id);
    }

    public static String newId() {
        return "user_" + System.currentTimeMillis();
    }

    /** 프리셋 → 내가 만든 순서 (이름순). */
    public static List<Sequence> listAll(Context c) {
        List<Sequence> out = new ArrayList<>();
        MacroSettings s = MacroSettings.load(c);
        out.add(Presets.aClassic());
        out.add(Presets.aBlocks(s.startWaitSeconds));
        out.add(Presets.bTemplate());
        out.addAll(listUser(c));
        return out;
    }

    public static List<Sequence> listUser(Context c) {
        File[] files = dir(c).listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".json"));
        List<Sequence> out = new ArrayList<>();
        if (files == null) {
            return out;
        }
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) {
            Sequence s = read(f);
            if (s != null) {
                out.add(s);
            }
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** 없거나 읽을 수 없으면 null. */
    public static Sequence get(Context c, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        if (Presets.A_CLASSIC.equals(id)) {
            return Presets.aClassic();
        }
        if (Presets.A_BLOCKS.equals(id)) {
            return Presets.aBlocks(MacroSettings.load(c).startWaitSeconds);
        }
        if (Presets.B_TEMPLATE.equals(id)) {
            return Presets.bTemplate();
        }
        File f = fileOf(c, id);
        return f.isFile() ? read(f) : null;
    }

    private static Sequence read(File f) {
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Sequence s = Sequence.fromJson(text);
            String name = f.getName();
            s.id = name.substring(0, name.length() - ".json".length());
            return s;
        } catch (IOException | RuntimeException e) {
            MacroController.get().log("[경고] 순서 파일을 읽을 수 없어요: " + f.getName() + " (" + e.getMessage() + ")");
            return null;
        }
    }

    /** 내가 만든 순서를 저장 (프리셋은 저장할 수 없음). 저장 도중 꺼져도 기존 파일이 깨지지 않게 임시 파일을 거친다. */
    public static void save(Context c, Sequence s) throws IOException {
        if (s.builtIn || s.classic || s.id == null || s.id.isEmpty() || isPresetId(s.id)) {
            throw new IOException("프리셋은 저장할 수 없어요. 복사해서 편집하세요.");
        }
        File out = fileOf(c, s.id);
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(s.toJson(true).getBytes(StandardCharsets.UTF_8));
        }
        if (out.exists() && !out.delete()) {
            throw new IOException("기존 파일을 지울 수 없음");
        }
        if (!tmp.renameTo(out)) {
            throw new IOException("파일 이름을 바꿀 수 없음");
        }
    }

    public static void delete(Context c, String id) {
        if (id == null || isPresetId(id)) {
            return;
        }
        File f = fileOf(c, id);
        if (f.exists() && !f.delete()) {
            MacroController.get().log("[경고] 순서 파일을 지우지 못했어요: " + f.getName());
        }
        if (id.equals(activeId(c))) {
            setActive(c, Presets.A_CLASSIC);
        }
    }

    // ------------------------------------------------------------------
    // 지금 실행할 순서
    // ------------------------------------------------------------------

    public static String activeId(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getString(KEY_ACTIVE, Presets.A_CLASSIC);
    }

    public static void setActive(Context c, String id) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACTIVE, id).apply();
    }

    /** 지금 실행할 순서. 지웠거나 읽을 수 없으면 기존 방식(A컨텐츠)으로 돌아간다. */
    public static Sequence active(Context c) {
        Sequence s = get(c, activeId(c));
        return s != null ? s : Presets.aClassic();
    }
}
