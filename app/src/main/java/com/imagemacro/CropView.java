package com.imagemacro;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * 스크린샷 위에서 영역을 선택하는 뷰.
 * - 한 손가락으로 끌기: 사각형 영역 선택
 * - 두 손가락: 확대/축소 + 이동 (작은 글자도 정확히 고를 수 있게, 확대 시 픽셀이 그대로 보임)
 */
public class CropView extends View {

    public interface OnSelectionChangedListener {
        void onSelectionChanged(Rect selection);
    }

    private Bitmap bitmap;
    private final Matrix matrix = new Matrix();
    private final Matrix inverse = new Matrix();
    private final Paint bitmapPaint = new Paint();
    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final ScaleGestureDetector scaleDetector;
    private final float density;
    private final float[] pt = new float[2];
    private final float[] values = new float[9];
    private final RectF tmp = new RectF();

    private RectF selection;            // 이미지 좌표
    private RectF selectionBeforeTouch;
    private boolean selecting;
    private boolean multiTouch;
    private float startImgX;
    private float startImgY;
    private float lastFocusX;
    private float lastFocusY;
    private float fitScale = 1f;
    private OnSelectionChangedListener listener;

    public CropView(Context context) {
        this(context, null);
    }

    public CropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        bitmapPaint.setFilterBitmap(false);
        dimPaint.setColor(0x99000000);
        borderPaint.setColor(Color.rgb(255, 214, 0));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(2 * density);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float current = currentScale();
                float target = current * d.getScaleFactor();
                target = Math.max(fitScale * 0.5f, Math.min(target, Math.max(fitScale * 40f, 20f)));
                float f = target / current;
                matrix.postScale(f, f, d.getFocusX(), d.getFocusY());
                invalidate();
                return true;
            }
        });
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener l) {
        listener = l;
    }

    public void setBitmap(Bitmap b) {
        bitmap = b;
        selection = null;
        fitToView();
        notifySelection();
    }

    /** 이미지 전체가 화면에 들어오도록 맞춤 */
    public void fitToView() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float s = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        fitScale = s;
        matrix.setScale(s, s);
        matrix.postTranslate((getWidth() - bitmap.getWidth() * s) / 2f, (getHeight() - bitmap.getHeight() * s) / 2f);
        invalidate();
    }

    public void selectAll() {
        if (bitmap == null) {
            return;
        }
        selection = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
        invalidate();
        notifySelection();
    }

    public void clearSelection() {
        selection = null;
        invalidate();
        notifySelection();
    }

    /** 선택 영역 (이미지 픽셀 좌표). 없으면 null. */
    public Rect getSelection() {
        if (selection == null || bitmap == null) {
            return null;
        }
        int l = clamp((int) Math.floor(selection.left), 0, bitmap.getWidth());
        int t = clamp((int) Math.floor(selection.top), 0, bitmap.getHeight());
        int r = clamp((int) Math.ceil(selection.right), 0, bitmap.getWidth());
        int b = clamp((int) Math.ceil(selection.bottom), 0, bitmap.getHeight());
        if (r - l <= 0 || b - t <= 0) {
            return null;
        }
        return new Rect(l, t, r, b);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitToView();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);
        if (bitmap == null) {
            return;
        }
        canvas.drawBitmap(bitmap, matrix, bitmapPaint);
        Rect sel = getSelection();
        if (sel != null) {
            tmp.set(sel);
            matrix.mapRect(tmp);
            float w = getWidth();
            float h = getHeight();
            canvas.drawRect(0, 0, w, tmp.top, dimPaint);
            canvas.drawRect(0, tmp.bottom, w, h, dimPaint);
            canvas.drawRect(0, tmp.top, tmp.left, tmp.bottom, dimPaint);
            canvas.drawRect(tmp.right, tmp.top, w, tmp.bottom, dimPaint);
            float half = borderPaint.getStrokeWidth() / 2f;
            tmp.inset(-half, -half); // 테두리가 선택된 픽셀을 가리지 않게 바깥쪽에 그림
            canvas.drawRect(tmp, borderPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bitmap == null) {
            return false;
        }
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                multiTouch = false;
                selecting = true;
                selectionBeforeTouch = selection == null ? null : new RectF(selection);
                toImage(e.getX(), e.getY());
                startImgX = clampF(pt[0], 0, bitmap.getWidth());
                startImgY = clampF(pt[1], 0, bitmap.getHeight());
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (!multiTouch) {
                    // 두 번째 손가락이 닿으면 선택 대신 확대/이동. 방금 바뀐 선택은 되돌린다.
                    multiTouch = true;
                    selecting = false;
                    selection = selectionBeforeTouch;
                    notifySelection();
                }
                lastFocusX = focus(e, -1, true);
                lastFocusY = focus(e, -1, false);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (multiTouch) {
                    if (e.getPointerCount() >= 2) {
                        float fx = focus(e, -1, true);
                        float fy = focus(e, -1, false);
                        matrix.postTranslate(fx - lastFocusX, fy - lastFocusY);
                        lastFocusX = fx;
                        lastFocusY = fy;
                        invalidate();
                    }
                } else if (selecting) {
                    toImage(e.getX(), e.getY());
                    float ex = clampF(pt[0], 0, bitmap.getWidth());
                    float ey = clampF(pt[1], 0, bitmap.getHeight());
                    selection = new RectF(Math.min(startImgX, ex), Math.min(startImgY, ey),
                            Math.max(startImgX, ex), Math.max(startImgY, ey));
                    invalidate();
                    notifySelection();
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                lastFocusX = focus(e, e.getActionIndex(), true);
                lastFocusY = focus(e, e.getActionIndex(), false);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                selecting = false;
                multiTouch = false;
                notifySelection();
                return true;

            default:
                return true;
        }
    }

    private float currentScale() {
        matrix.getValues(values);
        return values[Matrix.MSCALE_X];
    }

    private void toImage(float x, float y) {
        matrix.invert(inverse);
        pt[0] = x;
        pt[1] = y;
        inverse.mapPoints(pt);
    }

    /** 손가락들의 중심점 (skipIndex 손가락은 제외) */
    private static float focus(MotionEvent e, int skipIndex, boolean xAxis) {
        float sum = 0;
        int count = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skipIndex) {
                continue;
            }
            sum += xAxis ? e.getX(i) : e.getY(i);
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private void notifySelection() {
        if (listener != null) {
            listener.onSelectionChanged(getSelection());
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
