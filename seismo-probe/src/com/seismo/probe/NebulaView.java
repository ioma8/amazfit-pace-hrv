package com.seismo.probe;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.util.Log;
import android.view.View;

/**
 * CPU domain-warped fBm nebula, optimized for the MIPS core (fast integer floor,
 * 64x60 field upscaled bilinearly, no sqrt). Tilt steers the flow; the field
 * also evolves on its own (slow sway, warp breathing, palette pulse) — all
 * drift terms are bounded so coordinates never blow up over long runs.
 */
public class NebulaView extends View {
    private static final int BW = 64;
    private static final int BH = 60;
    private static final float INV_BW = 1f / BW;
    private static final float INV_BH = 1f / BH;
    private static final float SCALE = 2.4f;
    private static final float NOISE_SCALE = 1f / 255f;

    private final int[] perm = new int[512];
    private final int[] pixels = new int[BW * BH];
    private final Bitmap bitmap = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888);
    private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Handler handler = new Handler();

    private volatile float flowX, flowY, swirl;
    private float time;
    private long last;
    private int frameCount;
    private long fpsStart;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            invalidate();
            handler.postDelayed(this, 16);
            frameCount++;
            if (fpsStart == 0) fpsStart = System.currentTimeMillis();
            if (frameCount % 30 == 0) {
                long now = System.currentTimeMillis();
                float fps = 30000f / (now - fpsStart);
                fpsStart = now;
                Log.i("SeismoProbe", "fps=" + fps);
            }
        }
    };

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
        handler.post(tick);
    }

    public void setFlow(float fx, float fy, float sw) {
        flowX = fx;
        flowY = fy;
        swirl = sw;
    }

    /** Value noise with integer-floor (no Math.floor JNI), coordinates >= -4096. */
    private float noise(float x, float y) {
        int ix = (int) (x + 4096f) - 4096;
        int iy = (int) (y + 4096f) - 4096;
        float fx = x - ix;
        float fy = y - iy;
        float u = fx * fx * (3f - 2f * fx);
        float v = fy * fy * (3f - 2f * fy);
        int iy0 = iy & 255;
        int iy1 = (iy + 1) & 255;
        int a = (ix & 255) + perm[iy0];
        int b = ((ix + 1) & 255) + perm[iy0];
        int c = (ix & 255) + perm[iy1];
        int d = ((ix + 1) & 255) + perm[iy1];
        float p00 = perm[a];
        float p10 = perm[b];
        float p01 = perm[c];
        float p11 = perm[d];
        float m = p00 + (p10 - p00) * u;
        float n = p01 + (p11 - p01) * u;
        return (m + (n - m) * v) * NOISE_SCALE;
    }

    private float fbm(float x, float y) {
        float v = 0f, a = 0.5f;
        for (int i = 0; i < 3; i++) {
            v += a * noise(x, y);
            x = x * 2.03f + 1.7f;
            y = y * 2.03f + 9.2f;
            a *= 0.5f;
        }
        return v;
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
        // self-evolution: bounded sway + warp breathing + palette pulse
        float driftX = clamp(flowX * t * 0.12f, -14f, 14f) + 1.6f * (float) Math.sin(t * 0.05f);
        float driftY = clamp(flowY * t * 0.12f, -14f, 14f) + 1.6f * (float) Math.cos(t * 0.043f);
        float woff = 0.35f * (float) Math.sin(t * 0.12f);
        float tc = 0.6f * (float) Math.sin(t * 0.09f);
        float ts = 0.8f * (float) Math.sin(t * 0.06f);
        float boost = 1f + 0.08f * (float) Math.sin(t * 0.25f);
        float ang = swirl * t * 0.5f;
        float ca = (float) Math.cos(ang);
        float sa = (float) Math.sin(ang);
        int i = 0;
        for (int y = 0; y < BH; y++) {
            float fy = y * INV_BH;
            float cy = fy - 0.5f;
            for (int x = 0; x < BW; x++) {
                float fx = x * INV_BW;
                float px = fx * SCALE + driftX;
                float py = fy * SCALE + driftY;
                float w = fbm(px, py);
                float q = fbm(px + w + 1.7f + woff, py + w + 9.2f + woff);
                float c = fbm(px + 1.6f * q + tc, py + 1.6f * q + tc);
                float cx = fx - 0.5f;
                float rx = (cx * ca - cy * sa) * 2.2f + ts;
                float ry = (cx * sa + cy * ca) * 2.2f + ts;
                float s = fbm(rx, ry);
                float r = 0.15f * c + 0.55f * s + 0.2f * w * 0.4f + 0.5f * c * s * 0.5f;
                float g = 0.25f * c + 0.25f * s + 0.5f * w * 0.4f + 0.7f * c * s * 0.5f;
                float b = 0.80f * c + 0.90f * s + 0.7f * w * 0.4f + 1.0f * c * s * 0.5f;
                float d2 = (cx * cx + cy * cy) * 3.24f;
                float vt = (d2 - 0.0625f) * 0.79365f;
                vt = vt < 0f ? 0f : vt > 1f ? 1f : vt;
                vt = vt * vt * (3f - 2f * vt);
                float vig = (0.6f + 0.4f * (1f - vt)) * boost;
                r = Math.min(1f, r * vig);
                g = Math.min(1f, g * vig);
                b = Math.min(1f, b * vig);
                pixels[i++] = 0xff000000
                        | ((int) (r * 255f) << 16)
                        | ((int) (g * 255f) << 8)
                        | (int) (b * 255f);
            }
        }
        bitmap.setPixels(pixels, 0, BW, 0, 0, BW, BH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(8, 10, 13));
        int w = getWidth();
        int h = getHeight();
        canvas.drawBitmap(bitmap, null,
                new android.graphics.Rect(0, 0, w, h), filter);
    }
}
