package com.tuner.probe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Round tuner UI: big note letter in the center, a semicircular cents gauge
 * (-50..+50) with a needle, frequency readout, and a "pluck a string" hint
 * when no pitch is detected. Pure Canvas; the audio thread calls setResult()
 * and invalidates.
 */
public class TunerView extends View {
    private static final int CX = 160;
    private static final int CY = 150;

    private final Paint bg = new Paint();
    private final Paint ring = new Paint();
    private final Paint arc = new Paint();
    private final Paint arcGood = new Paint();
    private final Paint tick = new Paint();
    private final Paint needle = new Paint();
    private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tiny = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF gaugeRect = new RectF();

    private volatile String noteName = "--";
    private volatile float cents = 0f;
    private volatile float freq = 0f;
    private volatile boolean hasPitch = false;
    private volatile boolean inTune = false;
    private volatile float level = 0f; // 0..1 input level

    public TunerView(Context c) {
        super(c);
        bg.setColor(0xFF070B10);
        ring.setColor(0xFF16222E);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f);
        arc.setColor(0xFF2A3B4C);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(3f);
        arcGood.setColor(0xFF4E8A3E);
        arcGood.setStyle(Paint.Style.STROKE);
        arcGood.setStrokeWidth(3f);
        tick.setColor(0xFF3A5064);
        tick.setStrokeWidth(2f);
        needle.setColor(0xFFF5A623);
        needle.setStrokeWidth(3f);
        needle.setStrokeCap(Paint.Cap.ROUND);
        note.setColor(0xFFE8ECEF);
        note.setTextAlign(Paint.Align.CENTER);
        note.setTextSize(64f * c.getResources().getDisplayMetrics().density);
        small.setColor(0xFF8A97A3);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(13f * c.getResources().getDisplayMetrics().density);
        tiny.setColor(0xFF8A97A3);
        tiny.setTextAlign(Paint.Align.CENTER);
        tiny.setTextSize(9f * c.getResources().getDisplayMetrics().density);
        gaugeRect.set(CX - 92, CY - 62, CX + 92, CY + 62);
    }

    void setResult(String note, float cents, float freq, boolean inTune, float level) {
        this.noteName = note;
        this.cents = cents;
        this.freq = freq;
        this.inTune = inTune;
        this.level = level;
        postInvalidate();
    }

    void setIdle(float level) {
        this.noteName = "--";
        this.freq = 0;
        this.inTune = false;
        this.level = level;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        cv.drawColor(bg.getColor());
        float d = getResources().getDisplayMetrics().density;

        // input level arc at the bottom of the gauge
        cv.drawArc(gaugeRect, 180 + 40 * (1 - level), 40 * level, false, arcGood);

        // cents gauge: -50..+50 semicircle
        float sweep = 180f;
        cv.drawArc(gaugeRect, 180f, sweep, false, arc);
        // green in-tune zone -5..+5
        cv.drawArc(gaugeRect, 175f, 10f, false, arcGood);
        // ticks every 10 cents
        for (int c = -50; c <= 50; c += 10) {
            double a = Math.toRadians(180 + c + 50); // 0..180 -> -50..+50
            float r0 = 94f;
            float r1 = 98f;
            cv.drawLine(CX + (float) Math.cos(a) * r0, CY + (float) Math.sin(a) * r0,
                    CX + (float) Math.cos(a) * r1, CY + (float) Math.sin(a) * r1, tick);
        }
        // needle
        double a = Math.toRadians(180 + cents + 50);
        cv.drawLine(CX, CY, CX + (float) Math.cos(a) * 82f,
                CY + (float) Math.sin(a) * 82f, needle);

        // note + freq
        cv.drawText(noteName, CX, CY + 14, note);
        String status;
        if (!hasPitch) {
            status = "pluck a string";
        } else if (inTune) {
            status = "in tune";
        } else {
            status = String.format("%+.1f cents", cents);
        }
        cv.drawText(status, CX, CY + 52, small);
        if (freq > 0) {
            cv.drawText(String.format("%.1f Hz", freq), CX, CY + 76, tiny);
        }
        // neck diagram: string names E A D G B E
        cv.drawText("E A D G B E", CX, CY + 102, tiny);
    }
}
