package com.imagemacro;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 윈도우판 multi_scale_match 와 같은 방식(그레이스케일 + TM_CCOEFF_NORMED + 다중 배율)으로
 * 화면에서 템플릿을 찾는다. 그래서 윈도우판에서 쓰던 임계값이 그대로 통한다.
 *
 * 엔진 스레드 한 곳에서만 사용해야 한다 (Mat 재사용).
 */
public final class OpenCvVision implements Vision {

    private final FrameSource source;
    private final double processScale;
    private final double[] scales;
    private final Map<String, Mat> templates = new HashMap<>();

    private Mat rgba = new Mat();
    private final Mat fullGray = new Mat();
    private final Mat smallGray = new Mat();
    private final Mat resized = new Mat();
    private final Mat result = new Mat();
    private boolean hasScreen = false;

    /**
     * @param processScale 처리 해상도 비율 (1.0 = 원본). 0.5 로 하면 4배 가까이 빨라지지만 작은 글자 인식이 약해진다.
     * @param scales       템플릿 배율 목록 (윈도우판 기본값 0.95, 1.0, 1.05)
     */
    public OpenCvVision(FrameSource source, double processScale, double[] scales) {
        this.source = source;
        this.processScale = processScale;
        this.scales = scales;
    }

    /**
     * 템플릿 PNG 를 불러와 그레이스케일로 저장.
     *
     * @return 성공하면 null, 실패하면 이유
     */
    public String addTemplate(String id, File file) {
        if (!file.isFile()) {
            return "등록되지 않음";
        }
        Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bmp == null) {
            return "이미지를 읽을 수 없음";
        }
        Bitmap argb = bmp.getConfig() == Bitmap.Config.ARGB_8888 ? bmp : bmp.copy(Bitmap.Config.ARGB_8888, false);
        Mat color = new Mat();
        Utils.bitmapToMat(argb, color);
        Mat gray = new Mat();
        Imgproc.cvtColor(color, gray, Imgproc.COLOR_RGBA2GRAY);
        color.release();

        if (processScale != 1.0) {
            Mat small = new Mat();
            double w = Math.max(1, Math.round(gray.cols() * processScale));
            double h = Math.max(1, Math.round(gray.rows() * processScale));
            Imgproc.resize(gray, small, new Size(w, h), 0, 0, Imgproc.INTER_AREA);
            gray.release();
            gray = small;
        }

        // 거의 단색인 템플릿은 어디에나 매칭되므로 거부
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble std = new MatOfDouble();
        Core.meanStdDev(gray, mean, std);
        double sd = std.toArray()[0];
        mean.release();
        std.release();
        if (sd < 2.0) {
            gray.release();
            return "거의 단색이라 인식에 쓸 수 없음";
        }

        Mat old = templates.put(id, gray);
        if (old != null) {
            old.release();
        }
        return null;
    }

    @Override
    public boolean capture() {
        final boolean[] copied = {false};
        boolean got = source.withLatestFrame((data, width, height) -> {
            if (rgba.rows() != height || rgba.cols() != width || rgba.type() != CvType.CV_8UC4) {
                rgba.release();
                rgba = new Mat(height, width, CvType.CV_8UC4);
            }
            rgba.put(0, 0, data);
            copied[0] = true;
        });
        if (!got || !copied[0]) {
            return false;
        }
        Imgproc.cvtColor(rgba, fullGray, Imgproc.COLOR_RGBA2GRAY);
        if (processScale != 1.0) {
            Imgproc.resize(fullGray, smallGray, new Size(), processScale, processScale, Imgproc.INTER_AREA);
        }
        hasScreen = true;
        return true;
    }

    @Override
    public Match find(String templateId, double threshold) {
        Mat tpl = templates.get(templateId);
        if (tpl == null || !hasScreen) {
            return null;
        }
        Mat screen = processScale != 1.0 ? smallGray : fullGray;

        Match best = null;
        for (double s : scales) {
            int rw = Math.max(1, (int) (tpl.cols() * s));
            int rh = Math.max(1, (int) (tpl.rows() * s));
            if (rh > screen.rows() || rw > screen.cols()) {
                continue;
            }
            Mat t;
            if (rw == tpl.cols() && rh == tpl.rows()) {
                t = tpl;
            } else {
                Imgproc.resize(tpl, resized, new Size(rw, rh));
                t = resized;
            }
            Imgproc.matchTemplate(screen, t, result, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult mm = Core.minMaxLoc(result);
            if (mm.maxVal >= threshold && (best == null || mm.maxVal > best.confidence)) {
                // 윈도우판 get_center 와 동일: 좌상단 + 크기//2
                double cx = mm.maxLoc.x + (rw / 2);
                double cy = mm.maxLoc.y + (rh / 2);
                best = new Match(mm.maxVal,
                        (int) Math.round(cx / processScale),
                        (int) Math.round(cy / processScale),
                        s);
            }
        }
        return best;
    }

    public void close() {
        for (Mat m : templates.values()) {
            m.release();
        }
        templates.clear();
        rgba.release();
        fullGray.release();
        smallGray.release();
        resized.release();
        result.release();
    }
}
