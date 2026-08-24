package com.hrv.files;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;

/** Image viewer for the round screen: sampled decode (fast, low memory),
 *  fit-to-circle display, swipe right or back button to close. */
public class ImageViewer extends View {
    public interface Listener { void onClose(); }
    private static final String TAG = "Files";
    private Listener listener;
    public void setListener(Listener l) { listener = l; }

    private final Paint bg = new Paint();
    private final Paint statusText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF dst = new RectF();

    private Bitmap bmp = null;
    private String status = "";
    private float downX = 0, downY = 0;
    private boolean moved = false, swiped = false;

    public ImageViewer(Context c) {
        super(c);
        bg.setColor(Color.rgb(8, 8, 12));
        statusText.setColor(Color.rgb(150, 160, 170));
        statusText.setTextAlign(Paint.Align.CENTER);
        border.setStyle(Paint.Style.STROKE);
        border.setColor(Color.rgb(40, 46, 54));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        float unit = Math.min(w, h) / 300f;
        float r = Math.min(w, h) / 2f - 6 * unit;
        clip.reset();
        clip.addCircle(w / 2f, h / 2f, r, Path.Direction.CW);
        statusText.setTextSize(13 * unit);
        border.setStrokeWidth(2 * unit);
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
            float scale = Math.min(side / bmp.getWidth(), side / bmp.getHeight());
            float bw = bmp.getWidth() * scale, bh = bmp.getHeight() * scale;
            dst.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
            canvas.drawBitmap(bmp, null, dst, null);
            canvas.drawRect(dst, border);
        } else {
            canvas.drawText(status, cx, cy, statusText);
        }
        canvas.restore();
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
                break;
            case MotionEvent.ACTION_UP:
                if (swiped && e.getX() - downX < 0 && listener != null) listener.onClose();
                break;
        }
        return true;
    }
}
