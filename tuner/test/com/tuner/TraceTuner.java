package com.tuner;

/**
 * Host trace: run the app's analysis chain (Tuner 4096 Hann / 2048 hop +
 * PitchTracker lock-in/median/hold) over 16k mono WAVs and print the
 * displayed note/cents per frame.
 * Usage: java TraceTuner file.wav [file2.wav ...]
 */
public final class TraceTuner {
    private TraceTuner() {
    }

    public static void main(String[] args) throws Exception {
        Tuner tuner = new Tuner();
        for (String path : args) {
            short[] pcm = Wav.read(path);
            System.out.println("== " + path + " (" + pcm.length + " samples, "
                    + (pcm.length / 16000.0) + "s) ==");
            PitchTracker tr = new PitchTracker();
            int hop = 2048;
            long t0 = System.currentTimeMillis();
            String lastShown = null;
            int lines = 0;
            for (int off = 0; off + Tuner.N <= pcm.length; off += hop) {
                short[] win = new short[Tuner.N];
                System.arraycopy(pcm, off, win, 0, Tuner.N);
                Tuner.Result res = tuner.analyze(win);
                long now = t0 + off * 1000 / 16000; // virtual time
                tr.update(res, now);
                boolean active = tr.isActive(now);
                if (!active) {
                    if (lastShown != null) {
                        System.out.printf("  t=%5.2f idle (was %s)%n",
                                off / 16000.0, lastShown);
                        lastShown = null;
                    }
                    continue;
                }
                lastShown = tr.note();
                if (lines++ < 400) {
                    System.out.printf("  t=%5.2f %-2s%d %+6.1fct %7.2fHz%s%n",
                            off / 16000.0, tr.note(),
                            tr.midi() / 12 - 1, tr.cents(), tr.freq(),
                            res == null ? " (held)" : "");
                }
            }
            System.out.println("  (end)");
        }
    }
}
