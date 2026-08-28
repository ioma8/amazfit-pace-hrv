package com.sunface;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.view.View;
import com.hrv.common.SkyMath;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Round watch-face: 24h dial with the daylight arc (sunrise..sunset) and a
 * sun position dot, current time/date in the center, moon phase top-left,
 * sun event times at the bottom. Redraws every 30 s; location comes from
 * the GPS fix (fallback: Ostrava default). Pure Canvas, no render thread.
 */
public class SunFaceView extends View {
    private static final int CX = 160; // screen 320x300
    private static final int CY = 150;
    private static final int RING_R = 108;

    private final Paint ring = new Paint();
    private final Paint tick = new Paint();
    private final Paint tickBig = new Paint();
    private final Paint dayArc = new Paint();
    private final Paint nightArc = new Paint();
    private final Paint dot = new Paint();
    private final Paint dimDot = new Paint();
    private final Paint clock = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dateP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tiny = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moonEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Bitmap moonBmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);

    private volatile double lat = 49.82; // Ostrava fallback
    private volatile double lon = 18.26;
    private volatile boolean hasGps = false;

    private final Handler h = new Handler();
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            invalidate();
            h.postDelayed(this, 30000);
        }
    };

    public SunFaceView(Context c) {
        super(c);
        ring.setColor(0xFF16222E);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f);
        tick.setColor(0xFF2A3B4C);
        tick.setStrokeWidth(2f);
        tickBig.setColor(0xFF3A5064);
        tickBig.setStrokeWidth(3f);
        dayArc.setColor(0xFFF5A623);
        dayArc.setStyle(Paint.Style.STROKE);
        dayArc.setStrokeWidth(3f);
        nightArc.setColor(0xFF1C2A38);
        nightArc.setStyle(Paint.Style.STROKE);
        nightArc.setStrokeWidth(3f);
        dot.setColor(0xFFFFC966);
        dimDot.setColor(0xFF4A5866);
        clock.setColor(0xFFE8ECEF);
        clock.setTextAlign(Paint.Align.CENTER);
        clock.setTextSize(40f * c.getResources().getDisplayMetrics().density);
        dateP.setColor(0xFF8A97A3);
        dateP.setTextAlign(Paint.Align.CENTER);
        dateP.setTextSize(10f * c.getResources().getDisplayMetrics().density);
        small.setColor(0xFF8A97A3);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(10f * c.getResources().getDisplayMetrics().density);
        tiny.setColor(0xFF8A97A3);
        tiny.setTextAlign(Paint.Align.LEFT);
        tiny.setTextSize(8f * c.getResources().getDisplayMetrics().density);
        moonEdge.setColor(0xFFDCE3E8);
        moonEdge.setStyle(Paint.Style.STROKE);
        moonEdge.setStrokeWidth(1.5f);
        h.postDelayed(ticker, 30000);
    }

    void setLocation(double lat, double lon, boolean gps) {
        this.lat = lat;
        this.lon = lon;
        this.hasGps = gps;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        h.removeCallbacks(ticker);
    }

    @Override
    protected void onDraw(Canvas cv) {
        long now = System.currentTimeMillis();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);

        cv.drawColor(0xFF070B10);

        // 24h ring with ticks
        cv.drawCircle(CX, CY, RING_R, ring);
        for (int h = 0; h < 24; h += 2) {
            double a = h / 24.0 * 2 * Math.PI - Math.PI / 2;
            boolean big = h % 6 == 0;
            float r0 = RING_R - (big ? 13 : 9);
            cv.drawLine(CX + (float) Math.cos(a) * r0, CY + (float) Math.sin(a) * r0,
                    CX + (float) Math.cos(a) * (RING_R + 2), CY + (float) Math.sin(a) * (RING_R + 2),
                    big ? tickBig : tick);
            if (big) {
                cv.drawText(Integer.toString(h), CX + (float) Math.cos(a) * (RING_R + 15),
                        CY + (float) Math.sin(a) * (RING_R + 15) + 4, tiny);
            }
        }

        // daylight / night arcs
        long[] ev = SkyMath.sunEvents(now, lat, lon);
        boolean polar = ev[0] == Long.MIN_VALUE;
        if (!polar) {
            float r0 = angOf(ev[0]);
            float s0 = angOf(ev[2]);
            cv.drawArc(new RectF(CX - RING_R, CY - RING_R, CX + RING_R, CY + RING_R),
                    r0, normDeg(s0 - r0), false, dayArc);
            cv.drawArc(new RectF(CX - RING_R, CY - RING_R, CX + RING_R, CY + RING_R),
                    s0, normDeg(r0 - s0), false, nightArc);
        } else {
            cv.drawCircle(CX, CY, RING_R, nightArc);
        }

        // sun position dot
        float sunA = angOf(now);
        double[] pos = SkyMath.sunPosition(now, lat, lon);
        boolean up = pos[0] > 0;
        Paint dp = up ? dot : dimDot;
        cv.drawCircle(CX + (float) Math.cos(sunA) * RING_R,
                CY + (float) Math.sin(sunA) * RING_R, up ? 5f : 3.5f, dp);

        // clock + date
        String hm = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE));
        cv.drawText(hm, CX, CY - 18, clock);
        cv.drawText(String.format("%ta %td %tb", cal, cal, cal),
                CX, CY + 10, dateP);

        // moon phase (bitmap with exact per-pixel terminator)
        drawMoon(cv, now);

        // event line
        if (polar) {
            cv.drawText("polar day/night", CX, CY + 108, small);
        } else {
            cv.drawText(fmt(ev[0]) + "  " + fmt(ev[2]) + "  noon " + fmt(ev[1]),
                    CX, CY + 108, small);
        }

        // source badge
        cv.drawText(hasGps ? "GPS" : "OSTRAVA", CX - 138, CY - 118, tiny);
    }

    private float angOf(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        int min = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        return (float) (min / 1440.0 * 360.0 - 90.0);
    }

    private static float normDeg(float d) {
        return d < 0 ? d + 360 : d;
    }

    private static String fmt(long ms) {
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.setTimeInMillis(ms);
        return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** Per-pixel moon disc: lit iff dx is beyond the terminator curve
     *  x_b(y) = s * r * sqrt(1-(dy/r)^2), s = cos(2*pi*phase). Exact terminator,
     *  handles waxing and waning with one formula. */
    private void drawMoon(Canvas cv, long now) {
        double[] p = SkyMath.moonPhase(now);
        double s = Math.cos(2 * Math.PI * p[0]);
        int[] px = new int[64 * 64];
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                double dx = x - 32 + 0.5;
                double dy = y - 32 + 0.5;
                double d = Math.sqrt(dx * dx + dy * dy);
                if (d > 21) {
                    px[y * 64 + x] = 0;
                } else {
                    double t = s * 20 * Math.sqrt(Math.max(0, 1 - (dy / 20) * (dy / 20)));
                    boolean lit = s > 0 ? dx > t : dx < t;
                    px[y * 64 + x] = lit ? 0xFFDCE3E8 : 0xFF1A2330;
                }
            }
        }
        moonBmp.setPixels(px, 0, 64, 0, 0, 64, 64);
        cv.drawBitmap(moonBmp, CX - 88 - 32, CY - 100 - 32, null);
        cv.drawCircle(CX - 88, CY - 100, 20, moonEdge);
        cv.drawText(String.format("%.0f%%", p[1] * 100), CX - 88, CY - 100 + 33, tiny);
    }
}
