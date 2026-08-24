package com.hrv.files;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;

/** Image viewer for the round screen: sampled decode (fast, low memory),
 *  fit-to-circle display. Tap cycles zoom 1x -> 2x -> 1x; drag pans when
 *  zoomed (clamped to keep the image covering the view); swipe right or the
 *  back button closes. */
public class ImageViewer extends View {
    public interface Listener { void onClose(); }
    private static final String TAG = "Files";
    private Listener listener;
    public void setListener(Listener l) { listener = l; }

    private final Paint bg = new Paint();
    private final Paint statusText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF dst = new RectF();

    private Bitmap bmp = null;
    private String status = "";
    private float zoom = 1f;
    private float panX = 0, panY = 0;
    private float downX = 0, downY = 0;
    private boolean moved = false, swiped = false;

    public ImageViewer(Context c) {
        super(c);
        bg.setColor(Color.rgb(8, 8, 12));
        statusText.setColor(Color.rgb(150, 160, 170));
        statusText.setTextAlign(Paint.Align.CENTER);
        border.setStyle(Paint.Style.STROKE);
        border.setColor(Color.rgb(40, 46, 54));
        zoomText.setColor(Color.argb(200, 0, 230, 140));
        zoomText.setTextAlign(Paint.Align.RIGHT);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float unit = Math.min(w, h) / 300f;
        float r = Math.min(w, h) / 2f - 6 * unit;
        clip.reset();
        clip.addCircle(w / 2f, h / 2f, r, Path.Direction.CW);
        statusText.setTextSize(13 * unit);
        border.setStrokeWidth(2 * unit);
        zoomText.setTextSize(18 * unit);
    }

    public void load(File f) {
        long t0 = System.currentTimeMillis();
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
            if (bmp == null) { status = "cannot decode"; }
            else Log.i(TAG, "image " + f.getName() + " " + bounds.outWidth + "x" + bounds.outHeight
                    + " sample=" + sample + " in " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable e) {
            bmp = null;
            status = "cannot decode";
        }
        postInvalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float unit = Math.min(w, h) / 300f;
        float cx = w / 2f, cy = h / 2f;
        canvas.drawRect(0, 0, w, h, bg);
        canvas.save();
        canvas.clipPath(clip);
        if (bmp != null) {
            float r = Math.min(w, h) / 2f - 8 * unit;
            float side = (float) (r * Math.sqrt(2)) - 12 * unit; // largest square inside circle
            float fit = Math.min(side / bmp.getWidth(), side / bmp.getHeight());
            float scale = fit * zoom;
            float dw = bmp.getWidth() * scale, dh = bmp.getHeight() * scale;
            // clamp pan so the zoomed image always covers the view box
            float maxX = Math.max(0, (dw - side) / 2), maxY = Math.max(0, (dh - side) / 2);
            if (panX > maxX) panX = maxX;
            if (panX < -maxX) panX = -maxX;
            if (panY > maxY) panY = maxY;
            if (panY < -maxY) panY = -maxY;
            dst.set(cx - dw / 2 + panX, cy - dh / 2 + panY, cx + dw / 2 + panX, cy + dh / 2 + panY);
            canvas.drawBitmap(bmp, null, dst, null);
            canvas.drawRect(dst, border);
            if (zoom > 1f) {
                canvas.drawText((int) zoom + "x", w - 20 * unit, 34 * unit, zoomText);
            }
        } else {
            canvas.drawText(status, cx, cy, statusText);
        }
        canvas.restore();
    }

    void toggleZoom() {
        if (zoom > 1f) { zoom = 1f; panX = 0; panY = 0; }
        else { zoom = 2f; panX = 0; panY = 0; }
        postInvalidate();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (bmp != null) { bmp.recycle(); bmp = null; }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        float unit = Math.min(getWidth(), getHeight()) / 300f;
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                moved = false;
                swiped = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (Math.abs(dx) > 40 * unit && Math.abs(dx) > 1.5f * Math.abs(dy)) swiped = true;
                if (Math.abs(dy) > 14 * unit) moved = true;
                if (zoom > 1f && !swiped && moved) {
                    panX += dx;
                    panY += dy;
                    postInvalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (swiped && e.getX() - downX < 0 && listener != null) {
                    listener.onClose();
                } else if (!moved) {
                    toggleZoom();
                }
                break;
        }
        return true;
    }
}
