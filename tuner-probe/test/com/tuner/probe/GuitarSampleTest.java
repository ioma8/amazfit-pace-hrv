package com.tuner.probe;

import java.util.HashMap;
import java.util.Map;

/**
 * Runs the pitch detector over real single-note acoustic-guitar plucks and
 * checks the detected note matches the sample's labeled note. Samples are the
 * University of Iowa Musical Instrument Samples (acoustic guitar), mirrored
 * by the tonejs-instruments repo and resampled to 16 kHz mono PCM16.
 *
 * The note name (pitch class) is the tuner's contract: it is what is shown
 * and what tuning depends on. The exact octave can legitimately differ when
 * the recording's fundamental is weak — the acoustic low E's 2nd harmonic
 * often dominates — which does not affect the pitch class.
 */
public class GuitarSampleTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.printf("%-34s %s%n", name, ok ? "ok" : "FAIL");
    }

    public static void main(String[] a) throws Exception {
        Tuner t = new Tuner();
        String[] names = {"E2", "A2", "D3", "G3", "B3", "E4"};
        String[] notes = {"E", "A", "D", "G", "B", "E"};
        for (int k = 0; k < names.length; k++) {
            short[] pcm = Wav.read("test/guitar-samples/" + names[k] + ".wav");
            Map<String, Integer> hist = new HashMap<>();
            double fSum = 0;
            int det = 0;
            short[] win = new short[Tuner.N];
            for (int off = 0; off + Tuner.N <= pcm.length; off += Tuner.N / 2) {
                System.arraycopy(pcm, off, win, 0, Tuner.N);
                Tuner.Result r = t.analyze(win);
                if (r != null) {
                    det++;
                    hist.merge(r.note, 1, Integer::sum);
                    fSum += r.freq;
                }
            }
            String top = "-";
            int topCount = 0;
            for (Map.Entry<String, Integer> e : hist.entrySet()) {
                if (e.getValue() > topCount) {
                    top = e.getKey();
                    topCount = e.getValue();
                }
            }
            check(names[k] + " -> " + notes[k], det > 10 && top.equals(notes[k]));
            System.out.printf("    %s: %d windows, top note %s (%d), mean %.1f Hz, %s%n",
                    names[k], det, top, topCount, det > 0 ? fSum / det : 0, hist);
        }
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
