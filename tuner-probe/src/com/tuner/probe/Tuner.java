package com.tuner.probe;

/**
 * Pitch detection for a guitar tuner: Hann window, 4096-point FFT at 16 kHz
 * (3.906 Hz bins), peak search 59..660 Hz with parabolic interpolation on
 * log-magnitudes (~0.1 bin accuracy -> ~0.4 Hz), nearest-semitone mapping to
 * note name + cents offset. Pure java.*, host-testable.
 *
 * The strongest spectral bin is not always the fundamental — a bright pluck
 * can put the 2nd/3rd harmonic above the fundamental. A sub-harmonic walk
 * descends from the strongest bin to the fundamental (energy at f/d must be
 * a real component of the peak), so the note name and octave stay correct;
 * a pure sine has no sub-harmonic energy, so its peak is left untouched.
 */
final class Tuner {
    static final int N = 4096;
    static final int FS = 16000;
    static final double DF = FS / (double) N; // 3.90625 Hz per bin
    static final String[] NAMES = {"C", "C#", "D", "D#", "E", "F", "F#",
            "G", "G#", "A", "A#", "B"};

    private final float[] win = new float[N];
    private final float[] re = new float[N];
    private final float[] im = new float[N];
    private final float[] mag = new float[N];

    Tuner() {
        for (int i = 0; i < N; i++) {
            win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
        }
    }

    /** Result of one analysis window; null when no clear fundamental. */
    static final class Result {
        final float freq;   // detected frequency, Hz
        final String note;  // nearest note name, e.g. "A"
        final int midi;     // MIDI number (69 = A4)
        final float cents;  // deviation from the note, -50..+50

        Result(float freq, String note, int midi, float cents) {
            this.freq = freq;
            this.note = note;
            this.midi = midi;
            this.cents = cents;
        }
    }

    /** Analyze a 4096-sample window (16-bit signed PCM). */
    Result analyze(short[] samples) {
        for (int i = 0; i < N; i++) {
            re[i] = samples[i] * win[i];
        }
        java.util.Arrays.fill(im, 0f);
        Fft.fft(re, im);
        // raw magnitudes + gate statistics
        float mean = 0f;
        for (int i = 1; i < N / 2; i++) {
            float m = (float) Math.sqrt(re[i] * re[i] + im[i] * im[i]);
            mag[i] = m;
            mean += m;
        }
        mean /= (N / 2 - 1);
        // guitar band: E2 82.41 Hz up to E4's second harmonic; the lower
        // bound leaves headroom for drop tunings
        int lo = (int) (59.0 / DF);
        int hi = (int) (660.0 / DF);
        int walkLo = (int) (55.0 / DF);
        if (lo < 1) lo = 1;
        if (hi >= N / 2) hi = N / 2 - 1;
        // noise gate: a plucked note stands well above the band's own mean
        // (4x); the absolute floor stops the ratio test alone from passing
        // spurious peaks on near-silence
        float peak = 0f;
        int pi = lo;
        for (int i = lo; i <= hi; i++) {
            if (mag[i] > peak) {
                peak = mag[i];
                pi = i;
            }
        }
        if (peak < 4 * mean || peak < 20f) {
            return null;
        }
        // sub-harmonic walk: if the strongest bin is really the 2nd/3rd/...
        // harmonic of a lower fundamental, descend to that fundamental so the
        // note name and octave are correct. The walk only triggers when the
        // sub-harmonic carries a real share of the peak's energy (>= 30%),
        // which a pure sine never does.
        int bin = pi;
        boolean descended;
        do {
            descended = false;
            for (int d = 2; d <= 5; d++) {
                int sub = bin / d;
                if (sub < walkLo) {
                    break;
                }
                if (mag[sub] > 0.3f * mag[bin]) {
                    bin = sub;
                    descended = true;
                    break;
                }
            }
        } while (descended);
        // log magnitudes for parabolic interpolation
        for (int i = 1; i < N / 2; i++) {
            mag[i] = (float) Math.log(mag[i] + 1e-6);
        }
        // parabolic interpolation on log-magnitude for sub-bin accuracy
        double delta = 0;
        if (bin > walkLo && bin < hi) {
            double a = mag[bin - 1];
            double b = mag[bin];
            double c = mag[bin + 1];
            double denom = a - 2 * b + c;
            if (Math.abs(denom) > 1e-9) {
                delta = 0.5 * (a - c) / denom;
                if (delta > 1) delta = 1;
                if (delta < -1) delta = -1;
            }
        }
        double f = (bin + delta) * DF;
        if (f < 55 || f > 680) {
            return null;
        }
        int midi = (int) Math.round(12 * Math.log(f / 440.0) / Math.log(2)) + 69;
        if (midi < 0) midi = 0;
        if (midi > 127) midi = 127;
        double ref = 440.0 * Math.pow(2, (midi - 69) / 12.0);
        float cents = (float) (1200 * Math.log(f / ref) / Math.log(2));
        return new Result((float) f, NAMES[midi % 12], midi, cents);
    }
}
