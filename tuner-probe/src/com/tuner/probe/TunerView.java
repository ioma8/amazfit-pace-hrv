package com.tuner.probe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Round tuner UI: a circular cents dial (-50..+50) around a large note letter
 * with a superscript octave, a needle, a frequency readout, an input-level
 * bar, and a "pluck a string" idle hint. Pure Canvas; the audio thread calls
 * setResult()/setIdle() and invalidates.
 */
public class TunerView extends View {
    private static final int CX = 160;
    private static final int CY = 158; // dial center, slightly below screen center
    private static final float DIAL_R = 95f;

    private final Paint bg = new Paint();
    private final Paint dial = new Paint();
    private final Paint dialGood = new Paint();
    private final Paint tick = new Paint();
    private final Paint needle = new Paint();
    private final Paint pivot = new Paint();
    private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint octave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint freqText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint status = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint levelBg = new Paint();
    private final Paint levelFill = new Paint();
    private final Paint neck = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dialRect = new RectF();

    private final int green;
    private final int gray;
    private final int light;

    private volatile String noteName = "--";
    private volatile int octaveNum = -1;
    private volatile float cents = 0f;
    private volatile float freq = 0f;
    private volatile boolean inTune = false;
    private volatile boolean hasPitch = false;
    private volatile float level = 0f; // 0..1 input level

    public TunerView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        green = 0xFF3FA34D;
        gray = 0xFF8A97A3;
        light = 0xFFE8ECEF;
        bg.setColor(0xFF070B10);
        dial.setColor(0xFF22303E);
        dial.setStyle(Paint.Style.STROKE);
        dial.setStrokeWidth(3f);
        dialGood.setColor(green);
        dialGood.setStyle(Paint.Style.STROKE);
        dialGood.setStrokeWidth(6f);
        tick.setColor(0xFF3A5064);
        tick.setStrokeWidth(2f);
        needle.setColor(0xFFF5A623);
        needle.setStrokeWidth(3.5f);
        needle.setStrokeCap(Paint.Cap.ROUND);
        pivot.setColor(0xFFF5A623);
        note.setTextAlign(Paint.Align.LEFT);
        note.setTextSize(52f * d);
        octave.setTextAlign(Paint.Align.LEFT);
        octave.setTextSize(17f * d);
        octave.setColor(gray);
        freqText.setTextAlign(Paint.Align.CENTER);
        freqText.setTextSize(11f * d);
        freqText.setColor(gray);
        status.setTextAlign(Paint.Align.CENTER);
        status.setTextSize(13f * d);
        status.setColor(gray);
        levelBg.setColor(0xFF16222E);
        levelFill.setColor(0xFF4E8A3E);
        neck.setTextAlign(Paint.Align.CENTER);
        neck.setTextSize(8f * d);
        neck.setColor(0xFF55606A);
        dialRect.set(CX - DIAL_R, CY - DIAL_R, CX + DIAL_R, CY + DIAL_R);
    }

    void setResult(String note, int midi, float cents, float freq,
            boolean inTune, float level) {
        this.noteName = note.replace('#', '\u266F'); // ♯
        this.octaveNum = midi / 12 - 1;
        this.cents = cents;
        this.freq = freq;
        this.inTune = inTune;
        this.hasPitch = true;
        this.level = level;
        postInvalidate();
    }

    void setIdle(float level) {
        this.noteName = "--";
        this.octaveNum = -1;
        this.freq = 0f;
        this.inTune = false;
        this.hasPitch = false;
        this.level = level;
        postInvalidate();
    }

    private static float ang(float cents) {
        return 270f + cents * 2.4f; // -50 -> 150deg, 0 -> up, +50 -> 30deg
    }

    private static float rad(float deg) {
        return deg * (float) Math.PI / 180f;
    }

    @Override
    protected void onDraw(Canvas cv) {
        cv.drawColor(bg.getColor());

        // circular cents dial: 240 degrees, gap at the bottom
        cv.drawArc(dialRect, 150f, 240f, false, dial);
        // green in-tune zone centered at 0 cents
        cv.drawArc(dialRect, 258f, 24f, false, dialGood);
        // ticks every 10 cents
        for (int c = -50; c <= 50; c += 10) {
            float a = ang(c);
            float cos = (float) Math.cos(rad(a));
            float sin = (float) Math.sin(rad(a));
            cv.drawLine(CX + cos * (DIAL_R - 9), CY + sin * (DIAL_R - 9),
                    CX + cos * (DIAL_R - 3), CY + sin * (DIAL_R - 3), tick);
        }
        // endpoint labels
        tick.setTextAlign(Paint.Align.CENTER);
        tick.setTextSize(9f * getResources().getDisplayMetrics().density);
        tick.setColor(gray);
        float a50 = ang(-50);
        cv.drawText("-50", CX + (float) Math.cos(rad(a50)) * (DIAL_R - 22),
                CY + (float) Math.sin(rad(a50)) * (DIAL_R - 22) + 3, tick);
        a50 = ang(50);
        cv.drawText("+50", CX + (float) Math.cos(rad(a50)) * (DIAL_R - 22),
                CY + (float) Math.sin(rad(a50)) * (DIAL_R - 22) + 3, tick);
        tick.setTextAlign(Paint.Align.LEFT);

        // needle
        float na = ang(hasPitch ? cents : 0f);
        float ncos = (float) Math.cos(rad(na));
        float nsin = (float) Math.sin(rad(na));
        cv.drawLine(CX, CY, CX + ncos * (DIAL_R - 14), CY + nsin * (DIAL_R - 14),
                needle);
        cv.drawCircle(CX, CY, 4f, pivot);

        // note letter + superscript octave, centered as a block
        note.setColor(inTune ? green : light);
        String ns = hasPitch ? noteName : "--";
        float noteW = note.measureText(ns);
        float octW = octaveNum >= 0 ? octave.measureText(Integer.toString(octaveNum)) : 0f;
        float blockW = noteW + octW;
        float startX = CX - blockW / 2f;
        float noteY = 118f;
        cv.drawText(ns, startX, noteY, note);
        if (octaveNum >= 0) {
            cv.drawText(Integer.toString(octaveNum), startX + noteW, noteY - 14f,
                    octave);
        }

        // frequency readout
        if (hasPitch) {
            cv.drawText(String.format("%.1f Hz", freq), CX, noteY + 24f, freqText);
        }

        // status line
        status.setColor(inTune ? green : gray);
        if (!hasPitch) {
            cv.drawText("pluck a string", CX, noteY + 52f, status);
        } else if (inTune) {
            cv.drawText("in tune", CX, noteY + 52f, status);
        } else {
            cv.drawText(String.format("%+.1f cents", cents), CX, noteY + 52f, status);
        }

        // input level bar
        float barY = noteY + 78f;
        float barW = 110f;
        cv.drawRect(CX - barW / 2, barY, CX + barW / 2, barY + 4f, levelBg);
        cv.drawRect(CX - barW / 2, barY, CX - barW / 2 + barW * level, barY + 4f,
                levelFill);

        // neck diagram hint
        cv.drawText("E A D G B E", CX, noteY + 100f, neck);
    }
}
