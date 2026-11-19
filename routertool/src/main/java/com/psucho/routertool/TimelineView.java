package com.psucho.routertool;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Lanes (cable / PPPoE / PC port) over time; red = down, grey = no data. Phone time zone. */
final class TimelineView extends View {

    private LogData data;
    private long from, to;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ZoneId zone = ZoneId.systemDefault();

    private static final long[] STEPS = {
            3600, 2 * 3600, 3 * 3600, 6 * 3600, 12 * 3600, 86400, 2 * 86400, 7 * 86400, 14 * 86400, 30 * 86400};

    TimelineView(Context c) {
        super(c);
    }

    void set(LogData d, long from, long to) {
        this.data = d;
        this.from = from;
        this.to = to;
        requestLayout();
        invalidate();
    }

    private int lanesCount() {
        if (data == null) return 2;
        return data.lanes.get(LogData.LANE_PC).isEmpty()
                && data.coverageStart[LogData.LANE_PC] == Long.MAX_VALUE ? 2 : 3;
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onMeasure(int w, int h) {
        int lanes = lanesCount();
        int height = (int) (dp(8) + lanes * (dp(24) + dp(10)) + dp(30));
        setMeasuredDimension(MeasureSpec.getSize(w), height);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (data == null || to <= from) return;
        float labelW = dp(78);
        float left = labelW, right = getWidth() - dp(6);
        float laneH = dp(24), gap = dp(10);
        float top = dp(8);
        int lanes = lanesCount();
        float span = to - from;
        p.setTypeface(Typeface.MONOSPACE);

        for (int l = 0; l < lanes; l++) {
            float y = top + l * (laneH + gap);
            // label
            p.setStyle(Paint.Style.FILL);
            p.setColor(Palette.DIM);
            p.setTextSize(dp(10.5f));
            c.drawText(LogData.LANE_NAMES[l], 0, y + laneH * 0.68f, p);

            // no-data part
            p.setColor(Palette.NODATA);
            c.drawRect(left, y, right, y + laneH, p);

            // covered part = up (green)
            long cov = data.coverageStart[l];
            if (cov != Long.MAX_VALUE && cov < to) {
                float x0 = x(Math.max(cov, from), left, right, span);
                p.setColor(Palette.UP);
                c.drawRect(x0, y, right, y + laneH, p);
            }

            // downs (red), at least 2 px wide so short flaps are visible
            p.setColor(Palette.RED);
            List<LogData.Down> downs = data.lanes.get(l);
            for (LogData.Down d : downs) {
                if (d.end < from || d.start > to) continue;
                float a = x(Math.max(d.start, from), left, right, span);
                float b = x(Math.min(d.end, to), left, right, span);
                if (b - a < dp(1.5f)) b = a + dp(1.5f);
                c.drawRect(a, y, b, y + laneH, p);
            }

            // provider's scheduled session resets on the PPPoE lane
            if (l == LogData.LANE_PPP) {
                p.setColor(Palette.AMBER);
                for (long t : data.providerResets) {
                    if (t < from || t > to) continue;
                    float xx = x(t, left, right, span);
                    Path tri = new Path();
                    tri.moveTo(xx - dp(4), y - dp(1));
                    tri.lineTo(xx + dp(4), y - dp(1));
                    tri.lineTo(xx, y + dp(6));
                    tri.close();
                    c.drawPath(tri, p);
                }
            }

            // frame
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(Palette.DIM);
            c.drawRect(new RectF(left, y, right, y + laneH), p);
        }

        // time axis
        float axisY = top + lanes * (laneH + gap) - gap + dp(4);
        long step = STEPS[STEPS.length - 1];
        float maxTicks = Math.max(2, (right - left) / dp(64));
        for (long s : STEPS) {
            if (span / s <= maxTicks) { step = s; break; }
        }
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(step >= 86400 ? "dd.MM" : "HH:mm");
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("dd.MM");
        ZonedDateTime z = Instant.ofEpochSecond(from).atZone(zone);
        if (step >= 86400) z = z.truncatedTo(ChronoUnit.DAYS);
        else {
            int h = (int) (step / 3600);
            z = z.truncatedTo(ChronoUnit.HOURS);
            z = z.withHour((z.getHour() / h) * h);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTextSize(dp(9.5f));
        int guard = 0;
        String lastDay = "";
        while (z.toEpochSecond() <= to && guard++ < 200) {
            long t = z.toEpochSecond();
            if (t >= from) {
                float xx = x(t, left, right, span);
                p.setColor(Palette.GRID);
                p.setStrokeWidth(dp(1));
                c.drawLine(xx, top, xx, axisY, p);
                p.setColor(Palette.DIM);
                String lbl = z.format(fmt);
                String day = z.format(dayFmt);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText(lbl, xx, axisY + dp(11), p);
                if (step < 86400 && !day.equals(lastDay) && (z.getHour() < step / 3600 || lastDay.isEmpty())) {
                    c.drawText(day, xx, axisY + dp(22), p);
                }
                lastDay = day;
                p.setTextAlign(Paint.Align.LEFT);
            }
            z = z.plusSeconds(step);
        }
    }

    private float x(long t, float left, float right, float span) {
        return left + (right - left) * ((t - from) / span);
    }
}
