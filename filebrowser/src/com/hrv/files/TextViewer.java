package com.hrv.files;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import android.util.Log;

/** Simplistic scrollable text reader for the round screen. Drag to scroll,
 *  swipe right (physical panel direction) or back button to close. */
public class TextViewer extends View {
    public interface Listener { void onClose(); }
    private static final String TAG = "Files";
    private Listener listener;
    public void setListener(Listener l) { listener = l; }

    private final Paint bg = new Paint();
    private final Paint header = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sep = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();

    private String[] rawLines = new String[0];
    private String[] lines = new String[0];
    private String headerText = "";
    private float scroll = 0;
    private float lineH = 20;
    private float downY = 0, downX = 0, scrollStart = 0;
    private boolean moved = false, swiped = false, wrapPending = false;

    public TextViewer(Context c) {
        super(c);
        bg.setColor(Color.rgb(8, 8, 12));
        header.setColor(Color.rgb(150, 160, 170));
        header.setTextAlign(Paint.Align.LEFT);
        sep.setColor(Color.rgb(35, 40, 48));
        textPaint.setColor(Color.rgb(210, 215, 220));
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTypeface(Typeface.MONOSPACE);
        statusText.setColor(Color.rgb(110, 120, 130));
        statusText.setTextAlign(Paint.Align.CENTER);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float unit = Math.min(w, h) / 300f;
        float r = Math.min(w, h) / 2f - 6 * unit;
        clip.reset();
        clip.addCircle(w / 2f, h / 2f, r, Path.Direction.CW);
        header.setTextSize(14 * unit);
        textPaint.setTextSize(13 * unit);
        statusText.setTextSize(12 * unit);
        lineH = 19 * unit;
        sep.setStrokeWidth(2 * unit);
        wrapPending = true;
    }

    public void load(File f) {
        headerText = f.getName();
        try {
            FileInputStream in = new FileInputStream(f);
            int n = (int) Math.min(f.length(), 512 * 1024);
            byte[] data = new byte[n];
            int read = 0;
            while (read < n) {
                int r = in.read(data, read, n - read);
                if (r <= 0) break;
                read += r;
            }
            in.close();
            String s;
            try { s = new String(data, 0, read, "UTF-8"); }
            catch (Exception e) { s = new String(data, 0, read); }
            rawLines = s.split("\n", -1);
            for (int i = 0; i < rawLines.length; i++) rawLines[i] = rawLines[i].replace("\r", "");
        } catch (Exception e) {
            rawLines = new String[]{"<cannot read: " + e.getMessage() + ">"};
        }
        scroll = 0;
        wrapPending = true;
        postInvalidate();
    }

    void wrap() {
        if (!wrapPending) return;
        wrapPending = false;
        long t0 = System.currentTimeMillis();
        float maxW = getWidth() - 44 * Math.min(getWidth(), getHeight()) / 300f;
        ArrayList<String> out = new ArrayList<String>();
        for (String raw : rawLines) {
            if (raw.length() == 0) { out.add(""); continue; }
            int start = 0;
            while (start < raw.length()) {
                int count = textPaint.breakText(raw, start, raw.length(), true, maxW, null);
                if (count <= 0) count = 1;
                out.add(raw.substring(start, start + count));
                start += count;
            }
        }
        lines = out.toArray(new String[0]);
        Log.i(TAG, "wrapped " + rawLines.length + " lines -> " + lines.length
                + " in " + (System.currentTimeMillis() - t0) + "ms");
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float unit = Math.min(w, h) / 300f;
        float cx = w / 2f;
        canvas.drawRect(0, 0, w, h, bg);
        canvas.save();
        canvas.clipPath(clip);
        wrap();

        canvas.drawText(headerText, 24 * unit, 30 * unit, header);
        canvas.drawLine(16 * unit, 42 * unit, w - 16 * unit, 42 * unit, sep);

        float top = 50 * unit;
        float bottom = h / 2f + Math.min(w, h) / 2f - 6 * unit - 14 * unit;
        float maxScroll = Math.max(0, lines.length * lineH - (bottom - top));
        if (scroll > maxScroll) scroll = maxScroll;
        if (scroll < 0) scroll = 0;

        int first = (int) (scroll / lineH);
        int last = (int) Math.min(lines.length - 1, (scroll + (bottom - top)) / lineH);
        for (int i = first; i <= last; i++) {
            float y = top + i * lineH - scroll;
            canvas.drawText(lines[i], 22 * unit, y, textPaint);
        }

        canvas.drawText(scroll > 0 ? (first + 1) + "/" + lines.length : "", cx, h - 8 * unit, statusText);
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float unit = Math.min(getWidth(), getHeight()) / 300f;
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                scrollStart = scroll;
                moved = false;
                swiped = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (Math.abs(dx) > 40 * unit && Math.abs(dx) > 1.5f * Math.abs(dy)) swiped = true;
                if (Math.abs(dy) > 14 * unit) moved = true;
                if (!swiped && moved) scroll = Math.max(0, scrollStart - dy);
                postInvalidate();
                break;
            case MotionEvent.ACTION_UP:
                if (swiped && e.getX() - downX < 0 && listener != null) listener.onClose();
                postInvalidate();
                break;
        }
        return true;
    }
}
