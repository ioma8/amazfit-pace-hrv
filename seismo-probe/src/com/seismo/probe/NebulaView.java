package com.seismo.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Process;
import android.util.Log;
import android.view.View;

/**
 * Integer fixed-point fBm nebula, Carmack-style fast + console-grade scheduling:
 * - the fBm field is baked ONCE into a byte map (64 KB, L2-resident),
 *   8 texels/unit, 32-unit period, wrapped sampling;
 * - the four warp layers (w/q/c/s) are sampled on a half-resolution grid
 *   (12x11) and bilinearly upscaled in the final color pass;
 * - rendering runs on a DEDICATED thread at THREAD_PRIORITY_URGENT_AUDIO so it
 *   preempts the launcher's widget redraws on this single core;
 * - the loop is GC-free: zero per-frame allocations (single preallocated
 *   Rect, no logging in the hot path);
 * - render-bound pacing at ~60 Hz; tilt steers the flow, the field evolves
 *   on its own.
 */
public class NebulaView extends View {
    private static final int BW = 24;
    private static final int BH = 22;
    private static final int GW = BW >> 1;
    private static final int GH = BH >> 1;
    private static final int TEX = 256;
    private static final int TEX_MASK = TEX - 1;
    private static final float INV_BW = 1f / BW;
    private static final float INV_BH = 1f / BH;

    private final int[] perm = new int[512];
    private final int[] smooth = new int[256];
    private final byte[] field = new byte[TEX * TEX];
    private final int[] wg = new int[GH * GW];
    private final int[] qg = new int[GH * GW];
    private final int[] cg = new int[GH * GW];
    private final int[] sg = new int[GH * GW];
    private final int[] pixels = new int[BW * BH];
    private final int[] vig = new int[BW * BH];
    private final int[] baseX = new int[BW];
    private final int[] baseY = new int[BH];
    private final int[] cxU = new int[BW];
    private final int[] cyU = new int[BH];
    private final Bitmap bitmap = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888);
    private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();

    private volatile float flowX, flowY, swirl;
    private float smX, smY, smS;
    private float time;
    private long last;
    private volatile boolean running = true;
    private Thread renderThread;

    public NebulaView(Context context) {
        super(context);
        java.util.Random random = new java.util.Random(0xC0FFEE);
        int[] base = new int[256];
        for (int i = 0; i < 256; i++) base[i] = i;
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int t = base[i];
            base[i] = base[j];
            base[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = base[i & 255];
        for (int i = 0; i < 256; i++) {
            float t = i / 255f;
            smooth[i] = (int) (t * t * (3f - 2f * t) * 255f);
        }
        for (int y = 0; y < TEX; y++) {
            int uy = y * 32 - 4096;
            for (int x = 0; x < TEX; x++) {
                field[y * TEX + x] = (byte) fbm(uy, x * 32 - 4096);
            }
        }
        for (int x = 0; x < BW; x++) {
            baseX[x] = (int) (x * INV_BW * 2.4f * 256f);
            cxU[x] = (int) ((x * INV_BW - 0.5f) * 2.2f * 256f);
        }
        for (int y = 0; y < BH; y++) {
            baseY[y] = (int) (y * INV_BH * 2.4f * 256f);
            cyU[y] = (int) ((y * INV_BH - 0.5f) * 2.2f * 256f);
        }
        for (int y = 0; y < BH; y++) {
            float cy = y * INV_BH - 0.5f;
            for (int x = 0; x < BW; x++) {
                float cx = x * INV_BW - 0.5f;
                float d = (float) Math.sqrt(cx * cx + cy * cy) * 1.8f;
                float t = (d - 0.25f) / 0.9f;
                t = t < 0f ? 0f : t > 1f ? 1f : t;
                t = t * t * (3f - 2f * t);
                vig[y * BW + x] = (int) ((0.6f + 0.4f * (1f - t)) * 255f);
            }
        }
        renderThread = new Thread(new Runnable() {
            @Override public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                long fpsStart = 0;
                int frames = 0;
                while (running) {
                    long t0 = System.currentTimeMillis();
                    render();
                    postInvalidate();
                    long sleep = 16 - (System.currentTimeMillis() - t0);
                    if (sleep > 1) {
                        try {
                            Thread.sleep(sleep);
                        } catch (InterruptedException ignored) { }
                    }
                    frames++;
                    if (fpsStart == 0) fpsStart = System.currentTimeMillis();
                    if (frames % 60 == 0) {
                        long now = System.currentTimeMillis();
                        Log.i("SeismoProbe", "fps=" + (60000f / (now - fpsStart)));
                        fpsStart = now;
                    }
                }
            }
        });
        renderThread.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    public void setFlow(float fx, float fy, float sw) {
        flowX = fx;
        flowY = fy;
        swirl = sw;
    }

    /** All-integer value noise; coordinates in 1/256 units. */
    private int noiseInt(int x, int y) {
        int ix = x >> 8;
        int iy = y >> 8;
        int fx = x & 255;
        int fy = y & 255;
        int u = smooth[fx];
        int v = smooth[fy];
        int y0 = iy & 255;
        int y1 = (iy + 1) & 255;
        int a = perm[(ix & 255) + perm[y0]];
        int b = perm[((ix + 1) & 255) + perm[y0]];
        int c = perm[(ix & 255) + perm[y1]];
        int d = perm[((ix + 1) & 255) + perm[y1]];
        int m = a + ((b - a) * u >> 8);
        int n = c + ((d - c) * u >> 8);
        return m + ((n - m) * v >> 8);
    }

    /** 3-octave fBm, all integer (2.03x+1.7 / 2.03y+9.2 octave offsets). */
    private int fbm(int x, int y) {
        int v = 0;
        int a = 128;
        for (int i = 0; i < 3; i++) {
            v += a * noiseInt(x, y) >> 8;
            x = (x * 520 >> 8) + 435;
            y = (y * 520 >> 8) + 2355;
            a >>= 1;
        }
        return v > 255 ? 255 : v;
    }

    /** Bilinear sample of the baked byte field; coords in 1/256 units, wrapped. */
    private int sample(int u, int v) {
        int ix = u >> 5;
        int iy = v >> 5;
        int fx = u & 31;
        int fy = v & 31;
        int x0 = ix & TEX_MASK;
        int x1 = (ix + 1) & TEX_MASK;
        int y0 = (iy & TEX_MASK) * TEX;
        int y1 = ((iy + 1) & TEX_MASK) * TEX;
        int a = (field[y0 + x0] & 0xFF) + (((field[y0 + x1] & 0xFF) - (field[y0 + x0] & 0xFF)) * fx >> 5);
        int b = (field[y1 + x0] & 0xFF) + (((field[y1 + x1] & 0xFF) - (field[y1 + x0] & 0xFF)) * fx >> 5);
        return a + ((b - a) * fy >> 5);
    }

    /** Bilinear on a half-res grid; gx/gy with 5-bit fracs, edge-clamped. */
    private static int gridSample(int[] g, int gx, int gx1, int gy, int gy1, int fx, int fy) {
        int y0 = gy * GW;
        int y1 = gy1 * GW;
        int a = g[y0 + gx] + ((g[y0 + gx1] - g[y0 + gx]) * fx >> 5);
        int b = g[y1 + gx] + ((g[y1 + gx1] - g[y1 + gx]) * fx >> 5);
        return a + ((b - a) * fy >> 5);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private void render() {
        long now = System.currentTimeMillis();
        float dt = last == 0 ? 16f : Math.min(100f, now - last);
        last = now;
        time += dt / 1000f;
        float t = time;
                smX += (flowX - smX) * 0.15f;
        smY += (flowY - smY) * 0.15f;
        smS += (swirl - smS) * 0.15f;
        // tilt drives the flow 10x more gently than before, low-pass filtered
        float driftX = clamp(smX * t * 0.012f, -3f, 3f) + 1.6f * (float) Math.sin(t * 0.05f);
        float driftY = clamp(smY * t * 0.012f, -3f, 3f) + 1.6f * (float) Math.cos(t * 0.043f);
        float woff = 0.35f * (float) Math.sin(t * 0.12f);
        float tc = 0.6f * (float) Math.sin(t * 0.09f);
        float ts = 0.8f * (float) Math.sin(t * 0.06f);
        float boost = 1f + 0.08f * (float) Math.sin(t * 0.25f);
        float ang = smS * t * 0.05f;
        int ca16 = (int) ((float) Math.cos(ang) * 65536f);
        int sa16 = (int) ((float) Math.sin(ang) * 65536f);
        int driftXU = (int) (driftX * 256f);
        int driftYU = (int) (driftY * 256f);
        int wOffU = (int) ((1.7f + woff) * 256f);
        int qOffU = (int) ((9.2f + woff) * 256f);
        int tcU = (int) (tc * 256f);
        int tsU = (int) (ts * 256f);
        int boostQ = (int) (boost * 256f);

        int gi = 0;
        for (int gy = 0; gy < GH; gy++) {
            int py = baseY[gy << 1] + driftYU;
            int cy = cyU[gy << 1];
            for (int gx = 0; gx < GW; gx++) {
                int px = baseX[gx << 1] + driftXU;
                int cx = cxU[gx << 1];
                int w = sample(px, py);
                int wq = w * 410 >> 8;
                int q = sample(px + wq + wOffU, py + wq + qOffU);
                int qq = q * 410 >> 8;
                int c = sample(px + qq + tcU, py + qq + tcU);
                int rx = ((cx * ca16 - cy * sa16) >> 16) + tsU;
                int ry = ((cx * sa16 + cy * ca16) >> 16) + tsU;
                int s = sample(rx, ry);
                wg[gi] = w;
                qg[gi] = q;
                cg[gi] = c;
                sg[gi] = s;
                gi++;
            }
        }

        int i = 0;
        for (int y = 0; y < BH; y++) {
            int gy = y >> 1;
            int gy1 = gy + 1 < GH ? gy + 1 : gy;
            int fy = (y & 1) << 4;
            for (int x = 0; x < BW; x++) {
                int gx = x >> 1;
                int gx1 = gx + 1 < GW ? gx + 1 : gx;
                int fx = (x & 1) << 4;
                int w = gridSample(wg, gx, gx1, gy, gy1, fx, fy);
                int q = gridSample(qg, gx, gx1, gy, gy1, fx, fy);
                int c = gridSample(cg, gx, gx1, gy, gy1, fx, fy);
                int s = gridSample(sg, gx, gx1, gy, gy1, fx, fy);
                int cs = (c * s) >> 8;
                int r = (38 * c + 140 * s + 20 * w + 64 * cs) >> 8;
                int g = (64 * c + 64 * s + 51 * w + 90 * cs) >> 8;
                int b = (204 * c + 230 * s + 71 * w + 128 * cs) >> 8;
                int f = (vig[i] * boostQ) >> 8;
                r = r * f >> 8;
                g = g * f >> 8;
                b = b * f >> 8;
                pixels[i++] = 0xff000000 | ((r > 255 ? 255 : r) << 16)
                        | ((g > 255 ? 255 : g) << 8) | (b > 255 ? 255 : b);
            }
        }
        bitmap.setPixels(pixels, 0, BW, 0, 0, BW, BH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(8, 10, 13));
        dst.set(0, 0, getWidth(), getHeight());
        canvas.drawBitmap(bitmap, null, dst, filter);
    }
}
