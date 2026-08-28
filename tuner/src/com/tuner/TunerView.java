package com.tuner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import com.hrv.common.RoundView;

/**
 * Round tuner UI: a circular cents dial (-50..+50) around a large note letter
 * with a superscript octave, a needle, a frequency readout, an input-level
 * bar, and a "pluck a string" idle hint. Pure Canvas; the audio thread calls
 * setResult()/setIdle()/setError() and invalidates.
 */
public class TunerView extends RoundView {
    private final Paint bg = new Paint();
    private final Paint dial = new Paint();
    private final Paint dialGood = new Paint();
    private final Paint tick = new Paint();
    private final Paint tickLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    private volatile String error = null;

    public TunerView(Context c) {
        super(c);
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
        tickLabel.setColor(gray);
        tickLabel.setTextAlign(Paint.Align.CENTER);
        tickLabel.setTextSize(sp(10f));
        needle.setColor(0xFFF5A623);
        needle.setStrokeWidth(3.5f);
        needle.setStrokeCap(Paint.Cap.ROUND);
        pivot.setColor(0xFFF5A623);
        note.setTextAlign(Paint.Align.LEFT);
        note.setTextSize(sp(88f));
        octave.setTextAlign(Paint.Align.LEFT);
        octave.setTextSize(sp(24f));
        octave.setColor(gray);
        freqText.setTextAlign(Paint.Align.CENTER);
        freqText.setTextSize(sp(13f));
        freqText.setColor(gray);
        status.setTextAlign(Paint.Align.CENTER);
        status.setTextSize(sp(14f));
        status.setColor(gray);
        levelBg.setColor(0xFF16222E);
        levelFill.setColor(0xFF4E8A3E);
        neck.setTextAlign(Paint.Align.CENTER);
        neck.setTextSize(sp(9f));
        neck.setColor(0xFF55606A);
        dialRect.set(RoundView.CX - RoundView.R, RoundView.CY - RoundView.R, RoundView.CX + RoundView.R, RoundView.CY + RoundView.R);
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

    void setError(String msg) {
        this.error = msg;
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
            cv.drawLine(RoundView.CX + cos * (RoundView.R - 9), RoundView.CY + sin * (RoundView.R - 9),
                    RoundView.CX + cos * (RoundView.R - 3), RoundView.CY + sin * (RoundView.R - 3), tick);
        }
        // endpoint labels
        float a50 = ang(-50);
        cv.drawText("-50", RoundView.CX + (float) Math.cos(rad(a50)) * (RoundView.R - 22),
                RoundView.CY + (float) Math.sin(rad(a50)) * (RoundView.R - 22) + 3, tickLabel);
        a50 = ang(50);
        cv.drawText("+50", RoundView.CX + (float) Math.cos(rad(a50)) * (RoundView.R - 22),
                RoundView.CY + (float) Math.sin(rad(a50)) * (RoundView.R - 22) + 3, tickLabel);

        // needle
        float na = ang(hasPitch ? cents : 0f);
        float ncos = (float) Math.cos(rad(na));
        float nsin = (float) Math.sin(rad(na));
        cv.drawLine(RoundView.CX, RoundView.CY, RoundView.CX + ncos * (RoundView.R - 14), RoundView.CY + nsin * (RoundView.R - 14),
                needle);
        cv.drawCircle(RoundView.CX, RoundView.CY, 4f, pivot);

        // note letter + superscript octave, centered as a block
        note.setColor(inTune ? green : light);
        String ns = hasPitch ? noteName : "--";
        float noteW = note.measureText(ns);
        float octW = octaveNum >= 0 ? octave.measureText(Integer.toString(octaveNum)) : 0f;
        float blockW = noteW + octW;
        float startX = RoundView.CX - blockW / 2f;
        float noteY = 132f;
        cv.drawText(ns, startX, noteY, note);
        if (octaveNum >= 0) {
            cv.drawText(Integer.toString(octaveNum), startX + noteW, noteY - 18f,
                    octave);
        }

        // frequency readout
        if (hasPitch) {
            cv.drawText(String.format("%.1f Hz", freq), RoundView.CX, noteY + 30f, freqText);
        }

        // status line (or hard error); idle shows nothing here, the "--"
        // note already signals "no pitch"
        status.setColor(inTune ? green : gray);
        if (error != null) {
            status.setColor(0xFFC76B5E);
            cv.drawText(error, RoundView.CX, noteY + 60f, status);
        } else if (hasPitch) {
            cv.drawText(inTune ? "in tune" : String.format("%+.1f cents", cents),
                    RoundView.CX, noteY + 60f, status);
        }

        // input level bar
        float barY = noteY + 86f;
        float barW = 110f;
        cv.drawRect(RoundView.CX - barW / 2, barY, RoundView.CX + barW / 2, barY + 4f, levelBg);
        cv.drawRect(RoundView.CX - barW / 2, barY, RoundView.CX - barW / 2 + barW * level, barY + 4f,
                levelFill);

        // neck diagram hint
        cv.drawText("E A D G B E", RoundView.CX, noteY + 108f, neck);
    }
}
