package com.hrv.common;

import android.content.Context;
import android.view.View;

/**
 * Round-screen view base, following the tuner's viewport scheme: fixed
 * logical coordinates over the watch's 320x300 round panel — the visible
 * circle is centered at (160,160) with r=152 and the bottom edge is
 * flat-cut — and text sizes scaled by display density. Geometry is drawn in
 * fixed pixels (density ~1.5 on this panel); only text needs scaling.
 */
public abstract class RoundView extends View {
    public static final int CX = 160;   // logical circle center
    public static final int CY = 160;   // true circle center (r=160, bottom flat-cut)
    public static final float R = 152f; // dial/face radius

    protected final float density;

    public RoundView(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
    }

    /** Density-scaled text size (matches sp semantics on this panel). */
    protected float sp(float v) {
        return v * density;
    }
}
