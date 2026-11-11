package com.imagemacro;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * 이미지 등록 화면: 캡처한 화면(또는 갤러리 스크린샷)에서 영역을 잘라
 * 버튼 이미지(start/reload/collect1/collect2) 또는 회피 옵션 이미지로 저장한다.
 * 한 장의 캡처에서 여러 개를 연달아 저장할 수 있다.
 */
public class CropActivity extends Activity {

    public static final String EXTRA_IMAGE_PATH = "imagePath";

    private Bitmap source;
    private CropView cropView;
    private Spinner spinner;
    private EditText editName;
    private TextView textInfo;
    private int savedCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);

        String path = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        source = path == null ? null : BitmapFactory.decodeFile(path);
        if (source == null) {
            Toast.makeText(this, "이미지를 열 수 없습니다.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        // 캡처한 화면 방향(보통 게임은 가로)에 맞춰 표시
        setRequestedOrientation(source.getWidth() > source.getHeight()
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);

        cropView = findViewById(R.id.cropView);
        spinner = findViewById(R.id.spinnerTarget);
        editName = findViewById(R.id.editName);
        textInfo = findViewById(R.id.textInfo);

        cropView.setOnSelectionChangedListener(this::updateInfo);
        cropView.setBitmap(source);

        String[] items = new String[ImageStore.BUTTON_KEYS.length + 1];
        items[0] = "회피 옵션 (새로 추가)";
        for (int i = 0; i < ImageStore.BUTTON_KEYS.length; i++) {
            items[i + 1] = ImageStore.BUTTON_LABELS[i];
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                editName.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 없음
            }
        });

        findViewById(R.id.btnSave).setOnClickListener(v -> save());
        findViewById(R.id.btnAll).setOnClickListener(v -> cropView.selectAll());
        findViewById(R.id.btnFit).setOnClickListener(v -> cropView.fitToView());
        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 자르기 화면에도 게임 버튼이 보이므로, 매크로가 여기를 터치하지 않도록 정지
        MacroController c = MacroController.get();
        if (c.isMacroRunning()) {
            c.stopMacro("이미지 등록 화면이 열림");
        }
    }

    private void updateInfo(Rect r) {
        if (textInfo == null) {
            return;
        }
        if (r == null) {
            textInfo.setText("한 손가락: 영역 선택   ·   두 손가락: 확대 / 이동");
        } else {
            textInfo.setText(String.format(Locale.US, "선택 %d×%d px  (x=%d, y=%d)   ·   두 손가락으로 확대해서 정확히 맞추세요",
                    r.width(), r.height(), r.left, r.top));
        }
    }

    private void save() {
        Rect r = cropView.getSelection();
        if (r == null || r.width() < 4 || r.height() < 4) {
            toast("한 손가락으로 끌어서 영역을 먼저 선택하세요 (최소 4×4 픽셀).");
            return;
        }
        int pos = spinner.getSelectedItemPosition();
        File target;
        String label;
        if (pos <= 0) {
            String name = ImageStore.sanitize(editName.getText().toString());
            if (name.isEmpty()) {
                toast("옵션 이름을 입력하세요 (예: 빙결).");
                editName.requestFocus();
                return;
            }
            target = ImageStore.templateFile(this, name);
            label = "회피 옵션 '" + name + "'";
        } else {
            String key = ImageStore.BUTTON_KEYS[pos - 1];
            target = ImageStore.buttonFile(this, key);
            label = ImageStore.BUTTON_LABELS[pos - 1];
        }

        Bitmap crop = Bitmap.createBitmap(source, r.left, r.top, r.width(), r.height());
        if (isFlat(crop)) {
            toast("선택한 영역이 거의 한 가지 색이라 인식에 쓸 수 없어요. 글자나 모양이 들어가게 선택하세요.");
            return;
        }
        boolean existed = target.exists();
        try {
            ImageStore.savePng(crop, target);
        } catch (IOException e) {
            toast("저장 실패: " + e.getMessage());
            return;
        }
        savedCount++;
        MacroController.get().log(String.format(Locale.US, "[등록] %s 저장 (%d×%d)%s",
                label, r.width(), r.height(), existed ? " - 기존 이미지 교체" : ""));
        toast(label + " 저장됨 (" + savedCount + "개째). 계속 다른 영역을 저장하거나 [닫기]를 누르세요.");
        if (pos <= 0) {
            editName.setText("");
        }
        cropView.clearSelection();
    }

    /** 밝기 표준편차가 아주 작으면 단색으로 판단 */
    private static boolean isFlat(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        double sum = 0;
        double sq = 0;
        for (int c : px) {
            double g = 0.299 * ((c >> 16) & 0xff) + 0.587 * ((c >> 8) & 0xff) + 0.114 * (c & 0xff);
            sum += g;
            sq += g * g;
        }
        double mean = sum / px.length;
        double var = sq / px.length - mean * mean;
        return var < 4.0;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
