package com.hrv.probe;

/** Small, causal display filter; analysis always uses the untouched PPG stream. */
public final class PpgWaveform {
    private final float[] baseline = new float[40];
    private int count;
    private int cursor;
    private float previous;

    public float filter(float sample) {
        baseline[cursor] = sample;
        cursor = (cursor + 1) % baseline.length;
        if (count < baseline.length) count++;
        float mean = 0;
        for (int i = 0; i < count; i++) mean += baseline[i];
        mean /= count;
        float highPass = sample - mean;
        float display = previous * 0.35f + highPass * 0.65f;
        previous = display;
        return display;
    }
}
