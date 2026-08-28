package com.sunface;
import com.hrv.common.SkyMath;

import java.util.Calendar;
import java.util.TimeZone;

/** Host-side validation of SkyMath against known ephemeris ground truths. */
public class SkyTest {
    static int failures = 0;

    static void check(String name, double got, double want, double tol) {
        boolean ok = Math.abs(got - want) <= tol;
        if (!ok) failures++;
        System.out.printf("%-46s %s (got %.4f, want %.4f±%.4f)%n",
                name, ok ? "ok" : "FAIL", got, want, tol);
    }

    static long utc(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    public static void main(String[] a) {
        // moon phases against known eclipse dates. Mean-motion model: the
        // new-moon instant drifts up to ~7 h over decades (lunar eccentricity),
        // so ±0.01 phase (~7h) is the model envelope. Phase is cyclic:
        // 0.9942 = 0.0058 before new moon.
        double[] p = SkyMath.moonPhase(utc(2024, 4, 8, 18, 21));
        check("phase(total eclipse 2024-04-08 18:21 UTC) ~ new",
                Math.min(p[0], 1 - p[0]), 0.0, 0.01);
        check("illum at new", p[1], 0.0, 0.02);
        p = SkyMath.moonPhase(utc(2024, 3, 25, 7, 0));
        check("phase(penumbral full 2024-03-25 07:00 UTC) ~ full", p[0], 0.5, 0.01);
        p = SkyMath.moonPhase(utc(2000, 1, 6, 18, 14));
        check("phase(epoch new moon) = 0", p[0], 0.0, 0.005);
        p = SkyMath.moonPhase(utc(2026, 8, 26, 12, 0));
        System.out.printf("  -> 2026-08-26 phase=%.3f illum=%.1f%%%n", p[0], p[1] * 100);

        // sun events: Ostrava 49.82N 18.26E
        long[] ev = SkyMath.sunEvents(utc(2026, 8, 26, 0, 0), 49.82, 18.26);
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Prague"));
        c.setTimeInMillis(ev[0]);
        int riseH = c.get(Calendar.HOUR_OF_DAY), riseM = c.get(Calendar.MINUTE);
        c.setTimeInMillis(ev[1]);
        int noonH = c.get(Calendar.HOUR_OF_DAY), noonM = c.get(Calendar.MINUTE);
        c.setTimeInMillis(ev[2]);
        int setH = c.get(Calendar.HOUR_OF_DAY), setM = c.get(Calendar.MINUTE);
        System.out.printf("  Ostrava 2026-08-26 (Europe/Prague): rise %02d:%02d  noon %02d:%02d  set %02d:%02d%n",
                riseH, riseM, noonH, noonM, setH, setM);
        // symmetry: noon - rise == set - noon (within 2.5 min)
        double r = (ev[1] - ev[0]) / 60000.0;
        double s = (ev[2] - ev[1]) / 60000.0;
        check("rise->noon == noon->set (min)", r, s, 2.5);
        check("daylight ~13h53m in late Aug (min)", r + s, 833.0, 25.0);

        // winter sanity: 2017-01-28 Ostrava sunrise ~07:31 CET, sunset ~16:38 CET
        ev = SkyMath.sunEvents(utc(2017, 1, 28, 0, 0), 49.82, 18.26);
        c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Prague"));
        c.setTimeInMillis(ev[0]);
        double riseMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        c.setTimeInMillis(ev[2]);
        double setMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        check("2017-01-28 rise ~07:31 CET (min)", riseMin, 451.0, 12.0);
        check("2017-01-28 set  ~16:38 CET (min)", setMin, 998.0, 12.0);

        // noon elevation = 90 - lat + decl; azimuth due south at noon
        ev = SkyMath.sunEvents(utc(2026, 8, 26, 0, 0), 49.82, 18.26);
        double[] pos = SkyMath.sunPosition(ev[1], 49.82, 18.26);
        double declDeg = SkyMath.sunEq(238, 12 * 60)[1] / SkyMath.DEG;
        check("elevation at solar noon = 90-lat+decl", pos[0] / SkyMath.DEG,
                90.0 - 49.82 + declDeg, 0.1);
        check("azimuth at solar noon ~180 (south)", pos[1] / SkyMath.DEG, 180.0, 1.0);
        // sun below horizon at midnight
        pos = SkyMath.sunPosition(utc(2026, 8, 26, 0, 0), 49.82, 18.26);
        check("elevation at local midnight < 0", pos[0] / SkyMath.DEG, -20.0, 15.0);

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
