package com.hrv.common;

import java.util.List;

/** Waveform math shared by the round-screen probes (capped wave-buffer trim,
 *  RMS autoscale). Pure java.* — host-testable. */
public final class Waves {
    private Waves() {
    }

    /** Trim-then-append for a capped wave buffer. */
    public static void push(List<Float> wave, float v, int cap) {
        wave.add(v);
        if (wave.size() > cap) wave.remove(0);
    }

    /** RMS over the buffer (seeded like the original views: rms starts at 1). */
    public static float rms(List<Float> wave) {
        float rms = 1;
        for (float v : wave) rms += v * v;
        return (float) Math.sqrt(rms / wave.size());
    }

    /** Autoscale clamp limit (±2.5x RMS, at least 1). */
    public static float limit(float rms) {
        return Math.max(1, rms * 2.5f);
    }

    /** Autoscale factor for a band height (draws at 0.42 of the band). */
    public static float scale(float rms, float band) {
        return band * 0.42f / limit(rms);
    }

    public static float clamp(float v, float limit) {
        return Math.max(-limit, Math.min(limit, v));
    }
}
