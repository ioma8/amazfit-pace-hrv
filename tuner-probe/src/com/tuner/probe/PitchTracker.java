package com.tuner.probe;

/**
 * Display-state tracking on top of the raw per-frame pitch detector.
 *
 * The raw analyzer flails during a pluck attack (broadband noise -> random
 * notes every 128 ms frame) and in the decay tail (gate flicker), so the
 * displayed note must not follow single frames:
 *
 *  - lock-in: the displayed note changes only after LOCK_FRAMES consecutive
 *    frames report the same pitch class; scattered attack garbage never
 *    shows.
 *  - attack suppression: a pluck's first ~1.5 s is spectrally unreliable
 *    (transient resonances can persist as steady wrong notes, e.g. a
 *    106 Hz peak during a low-E pluck). For ATTACK_MS after the signal
 *    starts (first frame after idle, or after a >=200 ms gate-null gap)
 *    frames are ignored entirely — no locking, no candidate accumulation.
 *  - octave adoption: a persistent same-pitch-class candidate may carry a
 *    different octave (harmonic lock vs fundamental); the display adopts
 *    the persistent octave. Cents are octave-invariant for harmonics, so
 *    the needle barely moves on octave flips.
 *  - median of the last MED_N matched frames smooths the needle against
 *    single-frame cents outliers (low E2 has ~3.9 Hz FFT bins = ~80 ct).
 *  - hold: the displayed note survives HOLD_MS after the gate drops, so
 *    brief dips between plucks do not flicker to idle.
 *
 * Pure Java: host-testable without Android.
 */
final class PitchTracker {
    static final int LOCK_FRAMES = 3;   // fresh lock from idle
    static final int CHANGE_FRAMES = 4; // different pitch class while showing
    static final long HOLD_MS = 800;
    static final long ATTACK_MS = 1500;
    private static final int MED_N = 3;
    private static final long NULL_GAP_REARM_MS = 200;

    // displayed state (what the UI shows)
    private String note = null;   // pitch class, null = idle
    private int midi = 0;
    private float cents = 0f;
    private float freq = 0f;
    private long lastMatchMs = 0;

    // candidate accumulation
    private String candNote = null;
    private int candMidi = 0;
    private float candCents = 0f;
    private float candFreq = 0f;
    private int candCount = 0;

    private final float[] medCents = new float[MED_N];
    private int medN = 0;

    private long suppressUntilMs = Long.MIN_VALUE;
    private boolean idleArmPending = false;
    private long lastNullMs = Long.MIN_VALUE;

    /** Feed one detector result (or null when the gate drops). */
    void update(Tuner.Result res, long nowMs) {
        if (note != null && nowMs - lastMatchMs >= HOLD_MS) {
            // display hold expired: back to idle
            note = null;
            candNote = null;
            candCount = 0;
        }
        if (res == null) {
            candCount = 0;
            if (lastNullMs == Long.MIN_VALUE) {
                lastNullMs = nowMs;
            } else if (nowMs - lastNullMs >= NULL_GAP_REARM_MS
                    && nowMs >= suppressUntilMs) {
                // signal gap long enough: next signal is a fresh pluck
                suppressUntilMs = nowMs + ATTACK_MS;
                lastNullMs = Long.MIN_VALUE;
                candNote = null;
                candCount = 0;
            }
            return;
        }
        lastNullMs = Long.MIN_VALUE;
        if (nowMs < suppressUntilMs) {
            return; // attack window: ignore frames entirely
        }
        if (note == null && !idleArmPending) {
            // fresh signal after idle: suppress the first ATTACK_MS once
            suppressUntilMs = nowMs + ATTACK_MS;
            idleArmPending = true;
            candNote = null;
            candCount = 0;
            return;
        }
        if (!res.note.equals(candNote)) {
            candNote = res.note;
            candCount = 0;
        }
        // track the latest frame of the current candidate so a persistent
        // same-name octave change is visible to the lock path
        candMidi = res.midi;
        candCents = res.cents;
        candFreq = res.freq;
        candCount++;
        // A lone resonance (e.g. the ~208 Hz body/mic ring) can dominate a
        // few frames with no harmonic relation to the string: demand more
        // persistence for a pitch-class change than for the first lock.
        int need = (note == null || note.equals(candNote))
                ? LOCK_FRAMES : CHANGE_FRAMES;
        if (candCount >= need) {
            if (note == null || !note.equals(candNote)) {
                // fresh lock: adopt note + octave, restart the smoother
                note = candNote;
                midi = candMidi;
                cents = candCents;
                freq = candFreq;
                medN = 0;
                idleArmPending = false;
            } else if (midi != candMidi) {
                // same pitch class, persistent octave change
                midi = candMidi;
                medN = 0;
            }
            lastMatchMs = nowMs;
        }
        // smoother feed: only frames on the displayed pitch class
        if (res.note.equals(note)) {
            medCents[medN % MED_N] = res.cents;
            medN++;
            if (medN >= MED_N) {
                cents = median();
                freq = (float) (440.0 * Math.pow(2,
                        (midi - 69) / 12.0 + cents / 1200.0));
            }
        }
    }

    /** True while a note is being displayed (within the hold window). */
    boolean isActive(long nowMs) {
        return note != null && nowMs - lastMatchMs < HOLD_MS;
    }

    String note() {
        return note;
    }

    int midi() {
        return midi;
    }

    float cents() {
        return cents;
    }

    float freq() {
        return freq;
    }

    private float median() {
        float a = medCents[0], b = medCents[1], c = medCents[2];
        if (a > b) { float t = a; a = b; b = t; }
        if (b > c) { float t = b; b = c; c = t; }
        if (a > b) { float t = a; a = b; b = t; }
        return b;
    }
}
