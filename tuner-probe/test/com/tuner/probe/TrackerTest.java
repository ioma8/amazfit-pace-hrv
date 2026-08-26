package com.tuner.probe;

/**
 * Host-side validation of PitchTracker stability on real watch-mic guitar
 * recordings (raw 16k mono PCM from mic-probe) plus synthetic edge cases:
 * attack garbage must never display, single frames must not switch notes,
 * octave flips adopt the persistent octave, and the hold masks gate dips.
 */
public class TrackerTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.printf("%-46s %s%n", name, ok ? "ok" : "FAIL");
    }

    /** Simulate the app over a 16k WAV; return displayed notes w/ timestamps. */
    static class Sim {
        final String[] notes = new String[600];
        final float[] cents = new float[600];
        final boolean[] active = new boolean[600];
        int n = 0;

        Sim(PitchTracker tr, Tuner tuner, short[] pcm, long t0) {
            int hop = 2048;
            for (int off = 0; off + Tuner.N <= pcm.length; off += hop) {
                short[] win = new short[Tuner.N];
                System.arraycopy(pcm, off, win, 0, Tuner.N);
                Tuner.Result res = tuner.analyze(win);
                long now = t0 + off * 1000 / 16000;
                tr.update(res, now);
                active[n] = tr.isActive(now);
                notes[n] = tr.note();
                cents[n] = tr.cents();
                n++;
            }
        }
    }

    public static void main(String[] a) throws Exception {
        Tuner tuner = new Tuner();
        long t0 = System.currentTimeMillis();

        // ---- real watch-mic recordings ----
        String[] files = {
                "../captures/mic-probe/mic_16000_20260826_173456_212_raw.wav", // E2
                "../captures/mic-probe/mic_16000_20260826_173510_454_raw.wav", // A2
                "../captures/mic-probe/mic_16000_20260826_173523_761_raw.wav", // E4 x2
        };
        String[] expect = {"E", "A", "E"};
        for (int i = 0; i < files.length; i++) {
            short[] pcm = Wav.read(files[i]);
            Sim sim = new Sim(new PitchTracker(), tuner, pcm, t0);
            boolean saw = false, bad = false;
            int lockedFrames = 0;
            for (int f = 0; f < sim.n; f++) {
                if (!sim.active[f]) {
                    continue;
                }
                if (!saw && sim.notes[f] != null) {
                    saw = true;
                }
                if (saw && sim.notes[f] != null) {
                    lockedFrames++;
                    if (!sim.notes[f].equals(expect[i])) {
                        bad = true;
                    }
                }
            }
            check("recording " + (i + 1) + " (" + expect[i] + "): only "
                    + expect[i] + " ever displayed", saw && !bad && lockedFrames > 20);
            // cents stability: once locked, stay within 50ct of the median
            if (saw && !bad) {
                float sum = 0;
                int cnt = 0;
                for (int f = 0; f < sim.n; f++) {
                    if (sim.active[f] && sim.notes[f] != null) {
                        sum += sim.cents[f];
                        cnt++;
                    }
                }
                float med = sum / cnt;
                float maxDev = 0;
                for (int f = 0; f < sim.n; f++) {
                    if (sim.active[f] && sim.notes[f] != null) {
                        float d = Math.abs(sim.cents[f] - med);
                        if (d > maxDev) maxDev = d;
                    }
                }
                check("recording " + (i + 1) + ": needle within 50ct of median (max "
                        + (int) maxDev + "ct)", maxDev < 50);
            }
        }

        // ---- synthetic: attack garbage never displays ----
        PitchTracker tr = new PitchTracker();
        long now = t0;
        Random1 r = new Random1(7);
        // 10 frames of random garbage, then 6 frames of steady A4
        for (int i = 0; i < 12; i++) {
            tr.update(fakeResult(r.nextName(), r.nextCents()), now);
            now += 128;
        }
        // 12 frames x 128ms = 1.54s > the 1.5s attack window: garbage alone
        // must never have displayed anything
        check("attack garbage: nothing displayed", !tr.isActive(now - 1));
        // steady A4 now: the first A4 frame must not lock by itself
        tr.update(fakeResult("A", 3f), now);
        now += 128;
        check("single A4 frame does not lock", !tr.isActive(now - 1));
        tr.update(fakeResult("A", 3f), now);
        now += 128;
        check("two A4 frames lock", tr.isActive(now - 1) && "A".equals(tr.note()));
        for (int i = 0; i < 8; i++) {
            tr.update(fakeResult("A", 3f), now);
            now += 128;
        }
        check("steady A4 locks after attack window", tr.isActive(now - 1)
                && "A".equals(tr.note()));

        // ---- synthetic: single wrong frame does not switch ----
        tr = new PitchTracker();
        now = t0;
        for (int i = 0; i < 16; i++) {
            tr.update(fakeResult("A", 0f), now);
            now += 128;
        }
        String before = tr.note();
        tr.update(fakeResult("F", 40f), now); // one-off outlier
        now += 128;
        tr.update(fakeResult("A", 1f), now);
        now += 128;
        check("single-frame outlier does not switch note",
                before != null && before.equals(tr.note()));

        // ---- synthetic: persistent octave flip is adopted ----
        tr = new PitchTracker();
        now = t0;
        for (int i = 0; i < 16; i++) {
            tr.update(fakeResult("E", 0f), now);
            now += 128;
        }
        int beforeMidi = tr.midi();
        for (int i = 0; i < 3; i++) {
            tr.update(fakeResult("E", -1f, 2), now); // same class, E2 not E4
            now += 128;
        }
        check("persistent octave flip adopted", tr.midi() != beforeMidi);

        // ---- synthetic: hold masks a brief gate drop ----
        tr = new PitchTracker();
        now = t0;
        for (int i = 0; i < 16; i++) {
            tr.update(fakeResult("A", 0f), now);
            now += 128;
        }
        for (int i = 0; i < 3; i++) {
            tr.update(null, now); // gate drops
            now += 128;
        }
        check("hold keeps note through gate drop", tr.isActive(now - 1)
                && "A".equals(tr.note()));
        now += PitchTracker.HOLD_MS + 128;
        tr.update(null, now);
        check("hold expires to idle", !tr.isActive(now));

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        if (failures > 0) System.exit(1);
    }

    static Tuner.Result fakeResult(String note, float cents) {
        return fakeResult(note, cents, 4);
    }

    static Tuner.Result fakeResult(String note, float cents, int octave) {
        // pick a midi whose note name matches the given octave
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        int midi = (octave + 1) * 12;
        for (int i = 0; i < 12; i++) {
            if (names[i].equals(note)) {
                midi += i;
                break;
            }
        }
        double f = 440.0 * Math.pow(2, (midi - 69) / 12.0 + cents / 1200.0);
        return new Tuner.Result((float) f, note, midi, cents);
    }

    /** Tiny deterministic PRNG for the garbage names. */
    static final class Random1 {
        private long s;

        Random1(long seed) {
            s = seed;
        }

        int next(int bound) {
            s = s * 6364136223846793005L + 1442695040888963407L;
            return (int) ((s >>> 33) % bound);
        }

        String nextName() {
            String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
            return names[next(12)];
        }

        float nextCents() {
            return (next(100) - 50);
        }
    }
}
