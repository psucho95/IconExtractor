package com.psucho.routertool;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

/** Stacked bars: red = cable link drops, amber = PPPoE-only drops. */
final class BarChartView extends View {

    private int[] a = new int[0], b = new int[0];
    private String[] labels = new String[0];
    private String[] captions = null;
    private int labelEvery = 1;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    BarChartView(Context c) {
        super(c);
        p.setTypeface(Typeface.MONOSPACE);
    }

    /** captions: optional small text above each bar (e.g. downtime), may be null. */
    void set(int[] physical, int[] pppOnly, String[] labels, String[] captions, int labelEvery) {
        this.a = physical;
        this.b = pppOnly;
        this.labels = labels;
        this.captions = captions;
        this.labelEvery = Math.max(1, labelEvery);
        invalidate();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), (int) dp(130));
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = a.length;
        if (n == 0) return;
        int max = 1;
        for (int i = 0; i < n; i++) max = Math.max(max, a[i] + b[i]);
        float left = dp(22), right = getWidth() - dp(4);
        float top = dp(16), bottom = getHeight() - dp(18);
        float slot = (right - left) / n;
        float barW = Math.max(dp(2), slot * 0.62f);

        // y grid: 0 and max
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(dp(9));
        p.setColor(Palette.GRID);
        p.setStrokeWidth(dp(1));
        c.drawLine(left, bottom, right, bottom, p);
        c.drawLine(left, top, right, top, p);
        p.setColor(Palette.DIM);
        p.setTextAlign(Paint.Align.RIGHT);
        c.drawText(String.valueOf(max), left - dp(4), top + dp(3), p);
        c.drawText("0", left - dp(4), bottom, p);

        p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < n; i++) {
            float cx = left + slot * i + slot / 2;
            float h1 = (bottom - top) * a[i] / max;
            float h2 = (bottom - top) * b[i] / max;
            p.setColor(Palette.RED);
            c.drawRect(cx - barW / 2, bottom - h1, cx + barW / 2, bottom, p);
            p.setColor(Palette.AMBER);
            c.drawRect(cx - barW / 2, bottom - h1 - h2, cx + barW / 2, bottom - h1, p);
            if (captions != null && captions[i] != null && !captions[i].isEmpty()) {
                p.setColor(Palette.GREEN);
                p.setTextSize(dp(8.5f));
                c.drawText(captions[i], cx, bottom - h1 - h2 - dp(3), p);
                p.setTextSize(dp(9));
            }
            if (i % labelEvery == 0 && i < labels.length) {
                p.setColor(Palette.DIM);
                c.drawText(labels[i], cx, getHeight() - dp(5), p);
            }
        }
        p.setTextAlign(Paint.Align.LEFT);
    }
}
