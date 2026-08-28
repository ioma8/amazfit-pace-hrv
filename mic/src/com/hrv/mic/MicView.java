package com.hrv.mic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import com.hrv.common.Waves;

import java.util.ArrayList;
import java.util.List;

/** Round-screen (320x300) capture UI: live waveform, duration, REC/STOP
 *  buttons, save status. Geometry scaled to the 300px circular face like
 *  HrvView, not Android density. */
public class MicView extends View {
    public interface Listener { void onRecord(); void onStop(); }
    private Listener listener;
    public void setListener(Listener l) { listener = l; }

    private final Paint bg = new Paint();
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBig = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint recBtn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint recBtnDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stopBtn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stopBtnDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnTextDim = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Float> wave = new ArrayList<Float>();
    private volatile boolean recording = false;
    private volatile int seconds = 0;
    private volatile String status = "Ready";
    private final Handler anim = new Handler();
    private final Runnable tick = new Runnable() {
        @Override public void run() { postInvalidate(); anim.postDelayed(this, 66); }
    };

    public MicView(Context c) {
        super(c);
        bg.setColor(Color.rgb(8, 8, 12));
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(3);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);
        wavePaint.setStrokeJoin(Paint.Join.ROUND);
        wavePaint.setColor(Color.rgb(0, 230, 140));
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1.5f);
        grid.setColor(Color.rgb(40, 46, 54));
        textBig.setColor(Color.WHITE);
        textBig.setTextAlign(Paint.Align.CENTER);
        textBig.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        textSmall.setColor(Color.rgb(140, 150, 160));
        textSmall.setTextAlign(Paint.Align.CENTER);
        recBtn.setStyle(Paint.Style.FILL);
        recBtn.setColor(Color.rgb(230, 60, 50));
        recBtnDim.setStyle(Paint.Style.FILL);
        recBtnDim.setColor(Color.rgb(80, 40, 40));
        stopBtn.setStyle(Paint.Style.FILL);
        stopBtn.setColor(Color.rgb(235, 90, 70));
        stopBtnDim.setStyle(Paint.Style.FILL);
        stopBtnDim.setColor(Color.rgb(50, 50, 58));
        btnText.setColor(Color.WHITE);
        btnText.setTextAlign(Paint.Align.CENTER);
        btnTextDim.setColor(Color.rgb(120, 120, 130));
        btnTextDim.setTextAlign(Paint.Align.CENTER);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); anim.post(tick); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); anim.removeCallbacks(tick); }

    public void setRecording(boolean r) { recording = r; postInvalidate(); }
    public void setStatus(String s) { status = s; postInvalidate(); }
    public void setSeconds(int s) { seconds = s; }
    public synchronized void pushWave(float v) { Waves.push(wave, v, 600);}

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float unit = Math.min(w, h) / 300f;
        // visible circle: radius 150u around (cx,cy) — everything must fit inside
        canvas.drawRect(0, 0, w, h, bg);

        // waveform band (corners within the circle)
        float top = cy - 92 * unit, bottom = cy - 12 * unit;
        float x0 = cx - 100 * unit, x1 = cx + 100 * unit;
        float midY = (top + bottom) / 2;
        canvas.drawLine(x0, midY, x1, midY, grid);
        canvas.drawLine(x0, top, x1, top, grid);
        canvas.drawLine(x0, bottom, x1, bottom, grid);
        wavePaint.setStrokeWidth(2.5f * unit);
        synchronized (this) {
            if (wave.size() > 2) {
                float rms = Waves.rms(wave);
                float limit = Waves.limit(rms);
                float scale = Waves.scale(rms, bottom - top);
                int n = wave.size();
                Path p = new Path();
                for (int i = 0; i < n; i++) {
                    float x = x0 + (x1 - x0) * i / (n - 1);
                    float v = Waves.clamp(wave.get(i), limit);
                    float y = midY - v * scale;
                    if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
                }
                canvas.drawPath(p, wavePaint);
            }
        }

        // duration
        textBig.setTextSize(46 * unit);
        canvas.drawText(String.format("%02d:%02d", seconds / 60, seconds % 60), cx, cy + 20 * unit, textBig);
        textSmall.setTextSize(12 * unit);
        canvas.drawText("duration", cx, cy + 38 * unit, textSmall);

        // status
        int sc = recording ? Color.rgb(0, 220, 120) : Color.rgb(140, 150, 160);
        textSmall.setColor(sc);
        textSmall.setTextSize(13 * unit);
        canvas.drawText(status, cx, cy + 58 * unit, textSmall);

        // buttons: centers 80u below center, 80u apart from center — fully inside the circle
        float by = cy + 80 * unit, r = 32 * unit;
        float rx = cx - 80 * unit, sx = cx + 80 * unit;
        canvas.drawCircle(rx, by, r, recording ? recBtnDim : recBtn);
        canvas.drawCircle(sx, by, r, recording ? stopBtn : stopBtnDim);
        btnText.setTextSize(16 * unit);
        btnTextDim.setTextSize(16 * unit);
        if (recording) { btnTextDim.setColor(Color.rgb(120, 120, 130)); canvas.drawText("REC", rx, by + 6 * unit, btnTextDim); }
        else { btnText.setColor(Color.WHITE); canvas.drawText("REC", rx, by + 6 * unit, btnText); }
        if (recording) { btnText.setColor(Color.WHITE); canvas.drawText("STOP", sx, by + 6 * unit, btnText); }
        else { btnTextDim.setColor(Color.rgb(90, 90, 100)); canvas.drawText("STOP", sx, by + 6 * unit, btnTextDim); }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            float unit = Math.min(getWidth(), getHeight()) / 300f;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float by = cy + 80 * unit, r = 40 * unit;
            float rx = cx - 80 * unit, sx = cx + 80 * unit;
            float dx = e.getX() - rx, dy = e.getY() - by;
            if (dx * dx + dy * dy < r * r) { if (listener != null && !recording) listener.onRecord(); return true; }
            dx = e.getX() - sx; dy = e.getY() - by;
            if (dx * dx + dy * dy < r * r) { if (listener != null && recording) listener.onStop(); return true; }
        }
        return true;
    }
}
