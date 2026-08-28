package com.render3d;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Process;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.hrv.common.Engine3d;
import com.hrv.common.Mesh;

import java.io.IOException;

/**
 * Render loop, seismo style: dedicated thread at
 * THREAD_PRIORITY_URGENT_AUDIO, render into a half-resolution bitmap,
 * upscale with bilinear filtering, 16 ms pacing, GC-free hot loop, FPS
 * logged every 60 frames. Stats are drawn as a small status line.
 *
 * Touch: dragging stops the auto-spin and rotates the model freely around
 * Y (horizontal drag) and X (vertical drag); releasing holds the model still
 * for Engine3d.RESUME_DELAY_MS, then the spin resumes from the dragged
 * orientation.
 */
public class RenderView extends View {
    private static final String TAG = "Render3D";
    private static final int RW = 160; // internal render width  (0.5x of 320)
    private static final int RH = 150; // internal render height (0.5x of 300)
    private static final long FRAME_MS = 16;
    private static final float DRAG_RAD_PER_PX = 0.010f;

    private final Engine3d engine;
    private final Mesh mesh;
    private final boolean loadFailed;
    private final Bitmap bitmap;
    private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();

    private volatile float fps = 0f;
    private volatile boolean running = true;
    private float lastX;
    private float lastY;
    private int activePointer = -1;
    private Thread thread;

    public RenderView(Context c) {
        super(c);
        Mesh m = null;
        boolean failed = false;
        try {
            m = Mesh.load(c.getResources().openRawResource(R.raw.teapot));
        } catch (IOException e) {
            Log.e(TAG, "model load failed", e);
            failed = true;
        }
        mesh = m;
        loadFailed = failed;
        engine = new Engine3d(RW, RH, mesh != null ? mesh.triCount : 1);
        bitmap = Bitmap.createBitmap(RW, RH, Bitmap.Config.ARGB_8888);
        text.setColor(0xFF9AA5AD);
        text.setTextSize(10f * c.getResources().getDisplayMetrics().density);
        text.setTextAlign(Paint.Align.CENTER);
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                long fpsStart = 0;
                int frames = 0;
                while (running) {
                    long t0 = System.currentTimeMillis();
                    if (!loadFailed) {
                        engine.frame(mesh);
                        bitmap.setPixels(engine.pixels, 0, RW, 0, 0, RW, RH);
                    }
                    postInvalidate();
                    long elapsed = System.currentTimeMillis() - t0;
                    long sleep = FRAME_MS - elapsed;
                    if (sleep > 1) {
                        try {
                            Thread.sleep(sleep);
                        } catch (InterruptedException ignored) {
                        }
                    }
                    frames++;
                    if (fpsStart == 0) {
                        fpsStart = System.currentTimeMillis();
                    }
                    if (frames % 60 == 0) {
                        long now = System.currentTimeMillis();
                        fps = 60000f / (now - fpsStart);
                        Log.i(TAG, "fps=" + fps + " tris=" + engine.statsTris
                                + " px=" + engine.statsPixels);
                        fpsStart = now;
                    }
                }
            }
        }, "render3d");
        thread.start();
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        dst.set(0, 0, w, h);
        cv.drawBitmap(bitmap, null, dst, filter);
        float d = getResources().getDisplayMetrics().density;
        if (loadFailed) {
            cv.drawText("model load failed", w / 2f, h / 2f, text);
        } else {
            cv.drawText(String.format("%.0f fps · %d tris · %dk px",
                    fps, engine.statsTris, engine.statsPixels / 1000),
                    w / 2f, h - 10 * d, text);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointer = ev.getPointerId(0);
                lastX = ev.getX();
                lastY = ev.getY();
                engine.touchDown();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                // the tracked finger lifted while another is still down
                if (activePointer >= 0
                        && ev.getPointerId(ev.getActionIndex()) == activePointer) {
                    engine.releaseTouch();
                    activePointer = -1;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (activePointer >= 0) {
                    int idx = ev.findPointerIndex(activePointer);
                    if (idx >= 0) {
                        float x = ev.getX(idx);
                        float y = ev.getY(idx);
                        engine.rotateBy(-(x - lastX) * DRAG_RAD_PER_PX,
                                -(y - lastY) * DRAG_RAD_PER_PX);
                        lastX = x;
                        lastY = y;
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                engine.releaseTouch();
                activePointer = -1;
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
