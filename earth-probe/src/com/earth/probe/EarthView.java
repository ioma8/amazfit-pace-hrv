package com.earth.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.util.Random;

/**
 * Render loop, render3d-probe style: dedicated thread at
 * THREAD_PRIORITY_URGENT_AUDIO, half-resolution bitmap upscaled bilinear,
 * 16 ms pacing, GC-free hot loop, FPS logged every 60 frames.
 *
 * Per frame the engine's sun direction is set from the real solar azimuth and
 * elevation (SkyMath) at the current location, rotated into camera space, so
 * the terminator tracks the actual sky; the globe spins on its tilted axis.
 * A static star field is painted around the globe silhouette.
 */
public class EarthView extends android.view.View {
    private static final String TAG = "Earth3D";
    private static final int RW = 160;
    private static final int RH = 150;
    private static final long FRAME_MS = 16;

    private final Engine3d engine;
    private final Mesh mesh;
    private final boolean loadFailed;
    private final Bitmap bitmap;
    private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();
    private final float[] starX = new float[45];
    private final float[] starY = new float[45];
    private final int[] starC = new int[45];

    private volatile double lat = 49.82; // Ostrava fallback
    private volatile double lon = 18.26;
    private volatile boolean hasGps = false;
    private volatile float fps = 0f;
    private volatile boolean running = true;
    private Thread thread;

    public EarthView(Context c) {
        super(c);
        Mesh m = null;
        boolean failed = false;
        try {
            m = Mesh.sphere(Mesh.loadLand(
                    c.getResources().openRawResource(R.raw.land)));
        } catch (IOException e) {
            Log.e(TAG, "landmask load failed", e);
            failed = true;
        }
        mesh = m;
        loadFailed = failed;
        engine = new Engine3d(RW, RH, mesh != null ? mesh.triCount : 1);
        bitmap = Bitmap.createBitmap(RW, RH, Bitmap.Config.ARGB_8888);
        text.setColor(0xFF7A8896);
        text.setTextSize(9f * c.getResources().getDisplayMetrics().density);
        text.setTextAlign(Paint.Align.CENTER);
        genStars();
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                long fpsStart = 0;
                int frames = 0;
                while (running) {
                    long t0 = System.currentTimeMillis();
                    if (!loadFailed) {
                        setSun();
                        engine.frame(mesh);
                        paintStars();
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
                                + " px=" + engine.statsPixels + " gps=" + hasGps);
                        fpsStart = now;
                    }
                }
            }
        }, "earth3d");
        thread.start();
    }

    void setLocation(double lat, double lon, boolean gps) {
        this.lat = lat;
        this.lon = lon;
        this.hasGps = gps;
    }

    /** Real sun direction -> camera space. World: +Y up, +X east, -Z north;
     *  camera sits at -Z looking +Z (south), pitched down by PITCH. */
    private void setSun() {
        long now = System.currentTimeMillis();
        double[] p = SkyMath.sunPosition(now, lat, lon);
        double el = p[0];
        double az = p[1];
        float wx = (float) (Math.cos(el) * Math.sin(az)); // east
        float wy = (float) Math.sin(el);                  // up
        float wz = (float) (-Math.cos(el) * Math.cos(az)); // south
        engine.setSunWorld(wx, wy, wz);
    }

    /** Static star field outside the globe silhouette (screen radius ~62). */
    private void genStars() {
        Random r = new Random(42);
        for (int i = 0; i < starX.length; i++) {
            double a = r.nextDouble() * 2 * Math.PI;
            double d = 66 + r.nextDouble() * 34;
            starX[i] = (float) (80 + Math.cos(a) * d);
            starY[i] = (float) (75 + Math.sin(a) * d);
            int b = 120 + r.nextInt(136);
            starC[i] = 0xFF000000 | (b << 16) | (b << 8) | (b + 20 > 255 ? 255 : b + 20);
        }
    }

    private void paintStars() {
        int[] px = engine.pixels;
        int wl = engine.w;
        for (int i = 0; i < starX.length; i++) {
            int x = (int) starX[i];
            int y = (int) starY[i];
            if (x >= 0 && x < wl && y >= 0 && y < engine.h) {
                px[y * wl + x] = starC[i];
            }
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        dst.set(0, 0, w, h);
        cv.drawBitmap(bitmap, null, dst, filter);
        float d = getResources().getDisplayMetrics().density;
        if (loadFailed) {
            cv.drawText("landmask load failed", w / 2f, h / 2f, text);
        } else {
            cv.drawText(String.format("%.0f fps · %d tris", fps, engine.statsTris),
                    w / 2f, h - 8 * d, text);
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
