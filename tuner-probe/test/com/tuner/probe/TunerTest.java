package com.tuner.probe;

import java.util.Random;

/** Host-side validation of the pitch detector against synthesized tones. */
public class TunerTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.printf("%-42s %s%n", name, ok ? "ok" : "FAIL");
    }

    static short[] sine(double freq, double amp, long seed) {
        Random r = new Random(seed);
        short[] s = new short[Tuner.N];
        for (int i = 0; i < Tuner.N; i++) {
            double t = i / (double) Tuner.FS;
            double v = amp * Math.sin(2 * Math.PI * freq * t);
            s[i] = (short) (v * 30000 + r.nextGaussian() * 300); // light noise
        }
        return s;
    }

    public static void main(String[] a) {
        Tuner t = new Tuner();

        // guitar strings, all standard tuning
        check("E2 82.41 Hz", expect(t, sine(82.41, 1.0, 1), "E", 82.41, 0, 3));
        check("A2 110.00 Hz", expect(t, sine(110.00, 1.0, 2), "A", 110.00, 0, 3));
        check("D3 146.83 Hz", expect(t, sine(146.83, 1.0, 3), "D", 146.83, 0, 3));
        check("G3 196.00 Hz", expect(t, sine(196.00, 1.0, 4), "G", 196.00, 0, 3));
        check("B3 246.94 Hz", expect(t, sine(246.94, 1.0, 5), "B", 246.94, 0, 3));
        check("E4 329.63 Hz", expect(t, sine(329.63, 1.0, 6), "E", 329.63, 0, 3));

        // A4 concert pitch and detuning (exact frequencies: f = 440*2^(c/1200))
        check("A4 440.00 Hz", expect(t, sine(440.00, 0.7, 7), "A", 440.00, 0, 2));
        check("A4 +20 cents (445.12)", expect(t, sine(445.12, 0.7, 8), "A", 445.12, 20, 3));
        check("A4 -25 cents (433.66)", expect(t, sine(433.66, 0.7, 9), "A", 433.66, -25, 3));
        check("A3 +4 cents (220.51)", expect(t, sine(220.51, 0.7, 10), "A", 220.51, 4, 2));

        // half-string harmonics shouldn't confuse: strong 2nd harmonic
        short[] harmonic = new short[Tuner.N];
        for (int i = 0; i < Tuner.N; i++) {
            double v = 0.5 * Math.sin(2 * Math.PI * 110 * i / Tuner.FS)
                    + 0.9 * Math.sin(2 * Math.PI * 220 * i / Tuner.FS);
            harmonic[i] = (short) (v * 12000); // peak 1.4*12000 < 32767, no clip
        }
        check("A3 with strong 2nd harmonic", expect(t, harmonic, "A", 220.0, 0, 3));

        // noise only -> no pitch
        Random r = new Random(42);
        short[] noise = new short[Tuner.N];
        for (int i = 0; i < Tuner.N; i++) {
            noise[i] = (short) (r.nextGaussian() * 2000);
        }
        Tuner.Result res = t.analyze(noise);
        check("noise rejected (null)", res == null);

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    static boolean expect(Tuner t, short[] s, String note, double freq,
            double centsTarget, double tol) {
        Tuner.Result r = t.analyze(s);
        if (r == null) {
            return false;
        }
        boolean ok = r.note.equals(note) && Math.abs(r.freq - freq) < 1.5
                && Math.abs(r.cents - centsTarget) < tol;
        System.out.printf("    -> %s %.2f Hz, cents %+.1f%n", r.note, r.freq, r.cents);
        return ok;
    }
}
