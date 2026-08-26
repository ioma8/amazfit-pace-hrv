package com.earth.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Process;
import android.util.Log;
import android.view.MotionEvent;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.Random;

/**
 * Render loop, render3d-probe style: dedicated thread at
 * THREAD_PRIORITY_URGENT_AUDIO, half-resolution bitmap upscaled bilinear,
 * 16 ms pacing, GC-free hot loop, FPS logged every 60 frames.
 *
 * The Blue Marble texture (res/drawable-nodpi/earth.jpg) is decoded once via
 * BitmapFactory and downscaled to the engine's texture size. Per frame the
 * geocentric sun vector is computed from UTC solar declination and subsolar
 * longitude, so the current shadow stays fixed to the geographic texture
 * while the globe stays still until a touch drag rotates it. A static star
 * field is painted around the globe silhouette.
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
    private float lastX;
    private float lastY;
    private int activePointer = -1;

    private volatile double lat = 49.82; // Ostrava fallback
    private volatile double lon = 18.26;
    private volatile boolean hasGps = false;
    private volatile float fps = 0f;
    private volatile boolean running = true;
    private Thread thread;

    public EarthView(Context c) {
        super(c);
        mesh = Mesh.sphere();
        engine = new Engine3d(RW, RH, mesh.triCount);
        boolean failed = false;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4; // 5400x2700 -> 1350x675, avoids a huge bitmap
            Bitmap big = BitmapFactory.decodeResource(
                    c.getResources(), R.drawable.earth, o);
            if (big == null) {
                throw new IllegalStateException("decode returned null");
            }
            Bitmap tex = Bitmap.createScaledBitmap(big,
                    Engine3d.TW, Engine3d.TH, true);
            if (tex != big) {
                big.recycle();
            }
            int[] px = new int[Engine3d.TW * Engine3d.TH];
            tex.getPixels(px, 0, Engine3d.TW, 0, 0, Engine3d.TW, Engine3d.TH);
            tex.recycle();
            for (int i = 0; i < px.length; i++) {
                px[i] &= 0xFFFFFF;
            }
            engine.setTexture(px);
        } catch (Exception e) {
            Log.e(TAG, "texture load failed", e);
            failed = true;
        }
        loadFailed = failed;
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

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointer = ev.getPointerId(0);
                lastX = ev.getX();
                lastY = ev.getY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (activePointer >= 0) {
                    int index = ev.findPointerIndex(activePointer);
                    if (index >= 0) {
                        float x = ev.getX(index);
                        float y = ev.getY(index);
                        engine.rotateBy(-(x - lastX) * 0.010f,
                                -(y - lastY) * 0.010f);
                        lastX = x;
                        lastY = y;
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                activePointer = -1;
                return true;
            default:
                return true;
        }
    }

    void setLocation(double lat, double lon, boolean gps) {
        this.lat = lat;
        this.lon = lon;
        this.hasGps = gps;
    }

    /** Compute the geocentric direction to the Sun in Earth coordinates.
     *  +Y is geographic north, +X is east at 90E, and +Z is the 180E
     *  meridian. The vector is independent of the viewer's GPS location;
     *  UTC determines the solar declination and subsolar longitude. */
    private void setSun() {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        utc.setTimeInMillis(now);
        double utcMin = utc.get(Calendar.HOUR_OF_DAY) * 60
                + utc.get(Calendar.MINUTE)
                + utc.get(Calendar.SECOND) / 60.0
                + utc.get(Calendar.MILLISECOND) / 60000.0;
        double[] eq = SkyMath.sunEq(utc.get(Calendar.DAY_OF_YEAR), utcMin);
        double decl = eq[1];
        double hourAngleDeg = (utcMin + eq[0]) / 4.0 - 180.0;
        double subsolarLon = -hourAngleDeg * SkyMath.DEG;
        float cosDecl = (float) Math.cos(decl);
        engine.setSunWorld(
                cosDecl * (float) Math.sin(subsolarLon),
                (float) Math.sin(decl),
                -cosDecl * (float) Math.cos(subsolarLon));
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
            cv.drawText("texture load failed", w / 2f, h / 2f, text);
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
