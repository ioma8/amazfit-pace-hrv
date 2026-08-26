package com.earth.probe;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * NOAA-style solar calculations (declination/equation of time, elevation,
 * azimuth, sunrise/sunset/solar noon) and a synodic moon-phase model.
 * Pure java.* — no Android dependencies, host-testable.
 *
 * Reference: NOAA Solar Calculator equations (Meeus approximation);
 * moon phase epoch: new moon 2000-01-06 18:14 UTC.
 */
final class SkyMath {
    private SkyMath() {
    }

    static final double DEG = Math.PI / 180.0;
    static final double SYNODIC = 29.530588853; // days
    static final double NEW_MOON_EPOCH_DAYS = 10962.7597222;
    static final double SUNSET_ZENITH = 90.833; // standard refraction + radius

    /** Equation of time (minutes) and solar declination (rad) for a UTC day. */
    static double[] sunEq(double dayOfYear, double utcMinutes) {
        double gamma = 2 * Math.PI / 365
                * (dayOfYear - 1 + (utcMinutes - 12 * 60) / 1440.0);
        double eqtime = 229.18 * (0.000075 + 0.001868 * Math.cos(gamma)
                - 0.032077 * Math.sin(gamma) - 0.014615 * Math.cos(2 * gamma)
                - 0.040849 * Math.sin(2 * gamma));
        double decl = 0.006918 - 0.399912 * Math.cos(gamma)
                + 0.070257 * Math.sin(gamma) - 0.006758 * Math.cos(2 * gamma)
                + 0.000907 * Math.sin(2 * gamma) - 0.002697 * Math.cos(3 * gamma)
                + 0.00148 * Math.sin(3 * gamma);
        return new double[]{eqtime, decl};
    }

    /** Sun elevation (rad) and azimuth (rad, 0 = north, clockwise) at a moment. */
    static double[] sunPosition(long epochMs, double latDeg, double lonDeg) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(epochMs);
        int doy = c.get(Calendar.DAY_OF_YEAR);
        double utcMin = c.get(Calendar.HOUR_OF_DAY) * 60
                + c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60.0
                + c.get(Calendar.MILLISECOND) / 60000.0;
        double[] eq = sunEq(doy, utcMin);
        double eqtime = eq[0];
        double decl = eq[1];
        double lat = latDeg * DEG;
        double haDeg = 15.0 * (utcMin + eqtime + 4.0 * lonDeg) / 60.0 - 180.0;
        double ha = haDeg * DEG;
        double cosZen = Math.sin(lat) * Math.sin(decl)
                + Math.cos(lat) * Math.cos(decl) * Math.cos(ha);
        double el = Math.asin(clamp(cosZen));
        double sinEl = Math.sin(el);
        double cosEl = Math.cos(el);
        double cosAz = clamp((Math.sin(decl) - sinEl * Math.sin(lat))
                / (cosEl * Math.cos(lat)));
        double az = Math.acos(cosAz); // 0..180 from north
        if (Math.sin(ha) > 0) {
            az = 2 * Math.PI - az; // afternoon: azimuth west of south
        }
        return new double[]{el, az};
    }

    /** Solar event times (epoch ms) for one date: sunrise, solar noon, sunset. */
    static long[] sunEvents(long anyMsThatDay, double latDeg, double lonDeg) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(anyMsThatDay);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long utcMidnight = c.getTimeInMillis();
        double[] eq = sunEq(c.get(Calendar.DAY_OF_YEAR), 12 * 60);
        double eqtime = eq[0];
        double decl = eq[1];
        double lat = latDeg * DEG;
        double cosHa0 = (Math.cos(SUNSET_ZENITH * DEG) - Math.sin(lat) * Math.sin(decl))
                / (Math.cos(lat) * Math.cos(decl));
        long[] out = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        if (cosHa0 < -1 || cosHa0 > 1) {
            return out; // polar day/night: no rise/set
        }
        double ha0 = Math.acos(clamp(cosHa0)) / DEG;
        double riseUtc = 720 - 4 * (lonDeg + ha0) - eqtime;
        double noonUtc = 720 - 4 * lonDeg - eqtime;
        double setUtc = 720 - 4 * (lonDeg - ha0) - eqtime;
        out[0] = utcMidnight + (long) (riseUtc * 60000.0);
        out[1] = utcMidnight + (long) (noonUtc * 60000.0);
        out[2] = utcMidnight + (long) (setUtc * 60000.0);
        return out;
    }

    /** Moon phase 0..1 (0/1 = new, 0.5 = full) and illuminated fraction. */
    static double[] moonPhase(long epochMs) {
        double days = epochMs / 86400000.0 - NEW_MOON_EPOCH_DAYS;
        double p = days / SYNODIC;
        p -= Math.floor(p);
        double illum = 0.5 - 0.5 * Math.cos(2 * Math.PI * p);
        return new double[]{p, illum};
    }

    static double clamp(double v) {
        return v < -1 ? -1 : v > 1 ? 1 : v;
    }
}
