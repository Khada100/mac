package com.imagemacro;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** 메인 화면: 권한, 화면 캡처 시작, 이미지 등록/관리, 설정, 로그. (윈도우판 GUI 창 역할) */
public class MainActivity extends Activity implements MacroController.Listener {

    private static final int REQ_CAPTURE = 1;
    private static final int REQ_IMPORT = 2;
    private static final int REQ_NOTIFY = 3;

    private TextView textAccess;
    private TextView textCapture;
    private TextView textStatus;
    private TextView textLog;
    private Button btnAccess;
    private Button btnNotify;
    private Button btnCapture;
    private Button btnMacro;
    private LinearLayout listButtons;
    private LinearLayout listOptions;
    private LinearLayout settingsBox;

    private EditText editOptionThreshold;
    private EditText editButtonThreshold;
    private EditText editWait;
    private EditText editInterval;
    private EditText editClickDelay;
    private EditText editTimeout;
    private EditText editScales;
    private EditText editProcessScale;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textAccess = findViewById(R.id.textAccess);
        textCapture = findViewById(R.id.textCapture);
        textStatus = findViewById(R.id.textStatus);
        textLog = findViewById(R.id.textLog);
        btnAccess = findViewById(R.id.btnAccess);
        btnNotify = findViewById(R.id.btnNotify);
        btnCapture = findViewById(R.id.btnCapture);
        btnMacro = findViewById(R.id.btnMacro);
        listButtons = findViewById(R.id.listButtons);
        listOptions = findViewById(R.id.listOptions);
        settingsBox = findViewById(R.id.settingsBox);

        btnAccess.setOnClickListener(v -> showAccessibilityGuide());
        btnNotify.setOnClickListener(v -> requestNotificationPermission());
        btnCapture.setOnClickListener(v -> toggleCapture());
        btnMacro.setOnClickListener(v -> toggleMacro());
        findViewById(R.id.btnImport).setOnClickListener(v -> pickImage());
        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> saveSettings());

        buildSettings();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MacroController c = MacroController.get();
        c.addListener(this);
        if (c.isMacroRunning()) {
            // 앱 화면을 게임으로 착각해 터치하지 않도록 정지
            c.stopMacro("앱 화면이 열림 (게임 화면에서 ▶로 다시 시작하세요)");
        }
        refreshStatus();
        refreshImages();
        textLog.setText(c.getLogText(150));
    }

    @Override
    protected void onPause() {
        MacroController.get().removeListener(this);
        super.onPause();
    }

    @Override
    public void onLog(String line) {
        textLog.setText(MacroController.get().getLogText(150));
    }

    @Override
    public void onStateChanged() {
        refreshStatus();
    }

    // ------------------------------------------------------------------
    // 상태 표시
    // ------------------------------------------------------------------

    private void refreshStatus() {
        boolean access = MacroAccessibilityService.get() != null;
        textAccess.setText(access
                ? "✅ 접근성 서비스 켜짐 (자동 터치 가능)"
                : "❌ 접근성 서비스 꺼짐 → 아래 버튼을 눌러 켜 주세요");
        btnAccess.setText(access ? "접근성 설정 열기" : "접근성 서비스 켜기");

        if (Build.VERSION.SDK_INT >= 33) {
            boolean granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            btnNotify.setVisibility(granted ? View.GONE : View.VISIBLE);
        } else {
            btnNotify.setVisibility(View.GONE);
        }

        boolean capturing = ScreenCaptureService.get() != null;
        textCapture.setText(capturing ? "✅ 화면 캡처 실행 중" : "⏹ 화면 캡처 꺼짐");
        btnCapture.setText(capturing ? "화면 캡처 중지" : "화면 캡처 시작");

        MacroController c = MacroController.get();
        boolean running = c.isMacroRunning();
        btnMacro.setText(running ? "매크로 정지" : "매크로 시작");
        btnMacro.setEnabled(running || (capturing && access));
        textStatus.setText("매크로 상태: " + c.getStatus());
    }

    private void refreshImages() {
        listButtons.removeAllViews();
        for (int i = 0; i < ImageStore.BUTTON_KEYS.length; i++) {
            addImageRow(listButtons, ImageStore.BUTTON_LABELS[i], ImageStore.buttonFile(this, ImageStore.BUTTON_KEYS[i]));
        }
        listOptions.removeAllViews();
        List<File> options = ImageStore.listTemplates(this);
        if (options.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("아직 없음 — 게임 화면에서 📷 등록 버튼으로 추가하세요.");
            empty.setPadding(0, dp(4), 0, dp(4));
            listOptions.addView(empty);
        }
        for (File f : options) {
            addImageRow(listOptions, ImageStore.nameOf(f), f);
        }
    }

    private void addImageRow(LinearLayout parent, final String label, final File file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
        thumb.setBackgroundColor(0xFF2B2B2B);
        boolean exists = file.isFile();
        String info = "❌ 미등록";
        if (exists) {
            Bitmap b = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (b != null) {
                thumb.setImageBitmap(b);
                info = "✅ " + b.getWidth() + "×" + b.getHeight() + " px";
            }
        }
        row.addView(thumb, new LinearLayout.LayoutParams(dp(120), dp(40)));

        TextView text = new TextView(this);
        text.setText(label + "\n" + info);
        text.setPadding(dp(10), 0, dp(6), 0);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (exists) {
            Button del = new Button(this);
            del.setText("삭제");
            del.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("'" + label + "' 이미지를 삭제할까요?")
                    .setPositiveButton("삭제", (d, w) -> {
                        if (!file.delete()) {
                            toast("삭제하지 못했습니다.");
                        }
                        MacroController.get().log("[등록] " + label + " 삭제");
                        refreshImages();
                    })
                    .setNegativeButton("취소", null)
                    .show());
            row.addView(del, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        parent.addView(row);
    }

    // ------------------------------------------------------------------
    // 권한
    // ------------------------------------------------------------------

    private void showAccessibilityGuide() {
        new AlertDialog.Builder(this)
                .setTitle("접근성 서비스 켜기")
                .setMessage("1) [설정 열기] → '설치된 앱' (또는 '설치된 서비스') → '이미지 매크로' → 사용 켜기\n\n"
                        + "2) 회색으로 막혀서 켜지지 않으면 (APK 파일로 직접 설치한 경우, Android 13 이상):\n"
                        + "   [앱 정보] → 오른쪽 위 ⋮ → '제한된 설정 허용' 후 다시 1)번\n\n"
                        + "※ 이 앱은 화면 터치만 하고, 화면 내용을 외부로 보내지 않습니다.")
                .setPositiveButton("설정 열기", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (RuntimeException e) {
                        toast("접근성 설정을 열 수 없습니다.");
                    }
                })
                .setNeutralButton("앱 정보", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException e) {
                        toast("앱 정보를 열 수 없습니다.");
                    }
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshStatus();
    }

    // ------------------------------------------------------------------
    // 화면 캡처 / 매크로
    // ------------------------------------------------------------------

    private void toggleCapture() {
        if (ScreenCaptureService.get() != null) {
            stopService(new Intent(this, ScreenCaptureService.class));
            refreshStatus();
            return;
        }
        if (MacroAccessibilityService.get() == null) {
            toast("먼저 접근성 서비스를 켜 주세요. (게임 위에 조작 버튼을 띄우고 터치하는 데 필요)");
            showAccessibilityGuide();
            return;
        }
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent;
        if (Build.VERSION.SDK_INT >= 34) {
            // '앱 하나만 공유' 대신 항상 전체 화면을 캡처 (좌표가 어긋나지 않게)
            intent = mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            intent = mpm.createScreenCaptureIntent();
        }
        try {
            startActivityForResult(intent, REQ_CAPTURE);
        } catch (RuntimeException e) {
            toast("화면 캡처 권한 창을 열 수 없습니다: " + e.getMessage());
        }
    }

    private void toggleMacro() {
        MacroController c = MacroController.get();
        if (c.isMacroRunning()) {
            c.stopMacro("사용자가 정지");
            return;
        }
        c.startMacro(this);
        if (c.isMacroRunning()) {
            toast("매크로 시작! 이제 게임 화면으로 이동하세요.");
        } else {
            toast("시작하지 못했습니다. 아래 로그를 확인하세요.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                toast("화면 캡처 권한이 거부되었습니다.");
                return;
            }
            Intent svc = new Intent(this, ScreenCaptureService.class);
            svc.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
            try {
                startForegroundService(svc);
                toast("화면 캡처 시작! 게임으로 이동해서 떠 있는 버튼(▶ / 📷)을 사용하세요.");
            } catch (RuntimeException e) {
                toast("화면 캡처를 시작할 수 없습니다: " + e.getMessage());
            }
        } else if (requestCode == REQ_IMPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importImage(data.getData());
            }
        }
    }

    // ------------------------------------------------------------------
    // 갤러리에서 가져오기
    // ------------------------------------------------------------------

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "스크린샷 선택"), REQ_IMPORT);
        } catch (RuntimeException e) {
            toast("갤러리를 열 수 없습니다.");
        }
    }

    private void importImage(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp == null) {
                toast("이미지를 읽을 수 없습니다.");
                return;
            }
            File out = ImageStore.captureFile(this);
            ImageStore.savePng(bmp, out);
            bmp.recycle();
            Intent crop = new Intent(this, CropActivity.class);
            crop.putExtra(CropActivity.EXTRA_IMAGE_PATH, out.getAbsolutePath());
            startActivity(crop);
        } catch (IOException | RuntimeException e) {
            toast("가져오기 실패: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 설정
    // ------------------------------------------------------------------

    private void buildSettings() {
        settingsBox.removeAllViews();
        MacroSettings s = MacroSettings.load(this);
        int decimal = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        int integer = InputType.TYPE_CLASS_NUMBER;
        editOptionThreshold = addSetting("옵션 인식 임계값 (0.5~0.999, 높을수록 엄격 · 오인식되면 올리기)",
                String.valueOf(s.optionThreshold), decimal);
        editButtonThreshold = addSetting("버튼 인식 임계값 (윈도우판 기본값 0.85)",
                String.valueOf(s.buttonThreshold), decimal);
        editWait = addSetting("전투 시작 후 대기 (초)", String.valueOf(s.startWaitSeconds), integer);
        editInterval = addSetting("검색 간격 (ms)", String.valueOf(s.intervalMs), integer);
        editClickDelay = addSetting("클릭 후 대기 (ms) · 새로고침이 두 번씩 눌리면 늘리기",
                String.valueOf(s.clickDelayMs), integer);
        editTimeout = addSetting("확인 버튼 최대 대기 (초, 0 = 무한 대기)", String.valueOf(s.buttonTimeoutSeconds), integer);
        editScales = addSetting("검색 배율 (쉼표로 구분)", s.scales, InputType.TYPE_CLASS_TEXT);
        editProcessScale = addSetting("처리 해상도 비율 (1.0 = 원본 · 0.5 = 빠르지만 작은 글자 인식 약함)",
                String.valueOf(s.processScale), decimal);
    }

    private EditText addSetting(String label, String value, int inputType) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setPadding(0, dp(10), 0, 0);
        settingsBox.addView(t);
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(inputType);
        e.setMaxLines(1);
        settingsBox.addView(e);
        return e;
    }

    private void saveSettings() {
        MacroSettings s = new MacroSettings();
        s.optionThreshold = MacroSettings.parseDouble(editOptionThreshold.getText().toString(), s.optionThreshold);
        s.buttonThreshold = MacroSettings.parseDouble(editButtonThreshold.getText().toString(), s.buttonThreshold);
        s.startWaitSeconds = MacroSettings.parseInt(editWait.getText().toString(), s.startWaitSeconds);
        s.intervalMs = MacroSettings.parseInt(editInterval.getText().toString(), s.intervalMs);
        s.clickDelayMs = MacroSettings.parseInt(editClickDelay.getText().toString(), s.clickDelayMs);
        s.buttonTimeoutSeconds = MacroSettings.parseInt(editTimeout.getText().toString(), s.buttonTimeoutSeconds);
        s.scales = editScales.getText().toString().trim();
        s.processScale = MacroSettings.parseDouble(editProcessScale.getText().toString(), s.processScale);
        s.save(this);
        buildSettings(); // 범위를 벗어난 값이 보정된 결과를 다시 표시
        toast("설정 저장됨 (매크로를 다시 시작하면 적용됩니다)");
        MacroController.get().log("[설정] 저장됨");
    }

    // ------------------------------------------------------------------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }
}
