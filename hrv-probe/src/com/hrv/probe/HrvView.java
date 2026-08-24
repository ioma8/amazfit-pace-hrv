package com.hrv.probe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class HrvView extends View {
    private final Paint bg = new Paint();
    private final Paint ringBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringFg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pacerFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pacerStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBig = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textMed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ringRect = new RectF();

    private final List<Float> wave = new ArrayList<Float>();
    private volatile float hr = 0;
    private volatile float rmssd = 0;
    private volatile float score = 0;
    private volatile boolean haveData = false;
    private volatile int seconds = 0;
    private final long startTime = System.nanoTime();

    private final Handler anim = new Handler();
    private final Runnable animRunnable = new Runnable() {
        @Override
        public void run() {
            postInvalidate();
            anim.postDelayed(this, 66);
        }
    };

    public HrvView(Context c) {
        super(c);
        bg.setColor(Color.rgb(8, 8, 12));
        ringBg.setStyle(Paint.Style.STROKE);
        ringBg.setStrokeWidth(9);
        ringBg.setColor(Color.rgb(40, 44, 52));
        ringFg.setStyle(Paint.Style.STROKE);
        ringFg.setStrokeWidth(9);
        ringFg.setStrokeCap(Paint.Cap.ROUND);
        ringFg.setColor(Color.rgb(0, 220, 120));
        pacerFill.setStyle(Paint.Style.FILL);
        pacerFill.setColor(Color.argb(28, 0, 255, 130));
        pacerStroke.setStyle(Paint.Style.STROKE);
        pacerStroke.setStrokeWidth(3);
        pacerStroke.setColor(Color.argb(140, 0, 255, 140));
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(3);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);
        wavePaint.setStrokeJoin(Paint.Join.ROUND);
        wavePaint.setColor(Color.rgb(0, 255, 140));
        textBig.setColor(Color.WHITE);
        textBig.setTextAlign(Paint.Align.CENTER);
        textBig.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        textMed.setColor(Color.WHITE);
        textMed.setTextAlign(Paint.Align.CENTER);
        textMed.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        textSmall.setColor(Color.rgb(150, 160, 170));
        textSmall.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(Color.rgb(110, 120, 130));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim.post(animRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        anim.removeCallbacks(animRunnable);
    }

    public synchronized void pushWave(float v) {
        wave.add(v);
        if (wave.size() > 400) {
            wave.remove(0);
        }
    }

    public void setMetrics(float hr, float rmssd, float score, int seconds) {
        this.hr = hr;
        this.rmssd = rmssd;
        this.score = score;
        this.seconds = seconds;
        this.haveData = true;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float r = Math.min(w, h) / 2f - 15;
        float dp = getResources().getDisplayMetrics().density;

        canvas.drawRect(0, 0, w, h, bg);

        // outer HRV score ring
        ringRect.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(ringRect, 90, 360, false, ringBg);
        if (haveData && score > 0) {
            float sweep = Math.max(4, score / 100f * 360);
            int col = score > 60 ? Color.rgb(0, 220, 120) : (score > 30 ? Color.rgb(230, 180, 40) : Color.rgb(230, 70, 60));
            ringFg.setColor(col);
            canvas.drawArc(ringRect, -90, sweep, false, ringFg);
        }

        // breath pacer: 6 breaths/min (10s cycle), resonant frequency
        double tSec = (System.nanoTime() - startTime) / 1e9;
        double cycle = tSec % 10.0;
        boolean inhale = cycle < 5.0;
        double k = inhale ? cycle / 5.0 : (10.0 - cycle) / 5.0;
        double grow = Math.sin(Math.PI / 2 * k); // 0..1..0 smooth
        float pacerR = (50 + 26 * (float) grow) * dp;
        float pacerCx = cx;
        float pacerCy = cy - 14 * dp;

        // breath label
        labelPaint.setTextSize(14 * dp);
        int remain = (int) Math.ceil(inhale ? 5 - cycle : 10 - cycle);
        canvas.drawText((inhale ? "Breathe in  " : "Breathe out  ") + remain, cx, pacerCy - pacerR - 10 * dp, labelPaint);

        canvas.drawCircle(pacerCx, pacerCy, pacerR, pacerFill);
        canvas.drawCircle(pacerCx, pacerCy, pacerR, pacerStroke);

        // HR inside the pacer circle
        if (haveData && hr > 0) {
            textBig.setTextSize(58 * dp);
            canvas.drawText(String.format("%.0f", hr), cx, pacerCy - 2 * dp, textBig);
            labelPaint.setTextSize(11 * dp);
            canvas.drawText("BPM", cx, pacerCy + 22 * dp, labelPaint);
        } else {
            textBig.setTextSize(26 * dp);
            canvas.drawText("warming up", cx, pacerCy + 2 * dp, textBig);
            labelPaint.setTextSize(11 * dp);
            canvas.drawText(seconds + "s", cx, pacerCy + 24 * dp, labelPaint);
        }

        // HRV score + RMSSD below the pacer
        if (haveData && score > 0) {
            textMed.setTextSize(26 * dp);
            int col = score > 60 ? Color.rgb(0, 220, 120) : (score > 30 ? Color.rgb(230, 180, 40) : Color.rgb(230, 70, 60));
            textMed.setColor(col);
            canvas.drawText(String.format("%.0f%%", score), cx, cy + 34 * dp, textMed);
            textSmall.setTextSize(12 * dp);
            textSmall.setColor(Color.rgb(150, 160, 170));
            canvas.drawText("HRV  RMSSD " + String.format("%.0f", rmssd) + "ms", cx, cy + 52 * dp, textSmall);
        }

        // waveform band
        float waveTop = cy + 62 * dp;
        float waveBottom = cy + 126 * dp;
        float waveX0 = cx - 104 * dp;
        float waveX1 = cx + 104 * dp;
        canvas.drawLine(waveX0, waveTop, waveX1, waveTop, labelPaint);
        canvas.drawLine(waveX0, waveBottom, waveX1, waveBottom, labelPaint);

        synchronized (this) {
            if (wave.size() > 2) {
                float maxAbs = 1;
                for (float v : wave) {
                    float a = Math.abs(v);
                    if (a > maxAbs) maxAbs = a;
                }
                float scale = (waveBottom - waveTop) * 0.42f / maxAbs;
                int n = wave.size();
                float midY = (waveTop + waveBottom) / 2;
                Path p = new Path();
                for (int i = 0; i < n; i++) {
                    float x = waveX0 + (waveX1 - waveX0) * i / (n - 1);
                    float y = midY - wave.get(i) * scale;
                    if (i == 0) p.moveTo(x, y);
                    else p.lineTo(x, y);
                }
                canvas.drawPath(p, wavePaint);
            }
        }

        textSmall.setTextSize(11 * dp);
        canvas.drawText("t+" + seconds + "s", cx, cy + 142 * dp, textSmall);
    }
}
