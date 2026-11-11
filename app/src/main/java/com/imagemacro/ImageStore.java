package com.imagemacro;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 등록한 이미지 저장소 (앱 전용 폴더).
 *   files/buttons/start.png, reload.png, collect1.png, collect2.png   - 윈도우판 button/ 폴더
 *   files/templates/이름.png                                          - 윈도우판 templates/ 폴더
 */
public final class ImageStore {

    public static final String[] BUTTON_KEYS = {"start", "reload", "collect1", "collect2"};
    public static final String[] BUTTON_LABELS = {
            "전투 시작 버튼 (start)",
            "새로고침 버튼 (reload)",
            "확인 버튼 1 - 먼저 누를 것 (collect1)",
            "확인 버튼 2 - 나중에 누를 것 (collect2)"
    };

    private ImageStore() {
    }

    public static File templatesDir(Context c) {
        return dir(c, "templates");
    }

    public static File buttonsDir(Context c) {
        return dir(c, "buttons");
    }

    private static File dir(Context c, String name) {
        File d = new File(c.getFilesDir(), name);
        if (!d.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static File buttonFile(Context c, String key) {
        return new File(buttonsDir(c), key + ".png");
    }

    public static File templateFile(Context c, String name) {
        return new File(templatesDir(c), sanitize(name) + ".png");
    }

    /** 화면 캡처/갤러리 이미지를 자르기 화면으로 넘길 때 쓰는 임시 파일. */
    public static File captureFile(Context c) {
        return new File(c.getCacheDir(), "capture.png");
    }

    public static List<File> listTemplates(Context c) {
        File[] files = templatesDir(c).listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
        List<File> out = new ArrayList<>();
        if (files != null) {
            out.addAll(Arrays.asList(files));
        }
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    public static String nameOf(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    /** 파일 이름으로 쓸 수 없는 문자를 정리. */
    public static String sanitize(String name) {
        String s = name == null ? "" : name.trim().replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        if (s.length() > 40) {
            s = s.substring(0, 40);
        }
        return s;
    }

    public static void savePng(Bitmap bmp, File out) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("폴더를 만들 수 없음: " + parent);
        }
        File tmp = new File(out.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                throw new IOException("PNG 저장 실패");
            }
        }
        if (out.exists() && !out.delete()) {
            throw new IOException("기존 파일을 지울 수 없음");
        }
        if (!tmp.renameTo(out)) {
            throw new IOException("파일 이름을 바꿀 수 없음");
        }
    }
}
