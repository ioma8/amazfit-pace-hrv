package com.tuner.probe;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Process;
import android.os.Vibrator;
import android.util.Log;
import android.view.WindowManager;

/** Guitar tuner: 16 kHz mono PCM16 mic (validated in MIC-FINDINGS.md — the
 *  only rate the watch dmic clocks correctly), 4096-sample Hann-windowed FFT
 *  windows with 50% overlap, pitch -> note + cents, round gauge UI; vibrates
 *  once on entering in-tune. The displayed cents/frequency are smoothed
 *  within a note and the last note is held briefly when the gate drops, so
 *  the needle stays steady on noisy input. */
public class MainActivity extends Activity {
    static final String TAG = "Tuner";
    static final int FS = 16000;
    static final int CHUNK = Tuner.N / 2; // 50% overlap: 2048-sample hop
    /** Gate-drop display hold: keep the last note on screen briefly. */
    static final long HOLD_MS = 300;

    private TunerView view;
    private Tuner tuner;
    private Vibrator vibrator;
    private volatile boolean running = true;
    private Thread worker;

    // display stability state (audio thread only)
    private String lastNote = null;
    private float smoothCents = 0f;
    private float smoothFreq = 0f;
    private boolean wasInTune = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new TunerView(this);
        tuner = new Tuner();
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        setContentView(view);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                capture();
            }
        }, "tuner");
        worker.start();
    }

    void capture() {
        // the worker thread sets its own priority; the UI thread is untouched
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        // AudioRecord config validated by mic-probe (MIC-FINDINGS.md):
        // 16000 Hz is the only rate captured at its true clock on this watch.
        int min = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, FS,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, Tuner.N * 2));
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed");
            view.setError("mic init failed");
            return;
        }
        short[] window = new short[Tuner.N];
        short[] chunk = new short[CHUNK];
        int filled = 0;
        int peak = 0;
        Tuner.Result held = null;
        long heldUntil = 0;
        ar.startRecording();
        while (running) {
            int r = ar.read(chunk, 0, CHUNK);
            if (r <= 0) {
                continue;
            }
            long now = System.currentTimeMillis();
            // level meter from this chunk: saturates at ~55% of int16 full
            // scale so a normal pluck moves most of the bar
            int pk = 0;
            for (int i = 0; i < r; i++) {
                int v = chunk[i] < 0 ? -chunk[i] : chunk[i];
                if (v > pk) pk = v;
            }
            peak = (peak * 3 + pk) / 4;
            float level = Math.min(1f, peak / 18000f);
            // slide the window by exactly the samples read (short reads must
            // not leave stale samples in the window)
            System.arraycopy(window, r, window, 0, Tuner.N - r);
            System.arraycopy(chunk, 0, window, Tuner.N - r, r);
            filled += r;
            if (filled < Tuner.N) {
                continue;
            }
            Tuner.Result res = tuner.analyze(window);
            if (res != null) {
                held = res;
                heldUntil = now + HOLD_MS;
            } else if (held != null && now < heldUntil) {
                res = held; // suppress flicker while the string decays
            }
            if (res == null) {
                view.setIdle(level);
                continue;
            }
            // smooth within a note; reset instantly when the note changes
            if (!res.note.equals(lastNote)) {
                lastNote = res.note;
                smoothCents = res.cents;
                smoothFreq = res.freq;
                wasInTune = false;
            } else {
                smoothCents += 0.5f * (res.cents - smoothCents);
                smoothFreq += 0.5f * (res.freq - smoothFreq);
            }
            // in-tune hysteresis: enter at 2 cents, exit at 4, so the needle
            // and the vibrate tick do not chatter at the boundary
            boolean inTune = wasInTune
                    ? Math.abs(smoothCents) < 4f
                    : Math.abs(smoothCents) < 2f;
            if (inTune && !wasInTune && vibrator != null) {
                vibrator.vibrate(15);
            }
            wasInTune = inTune;
            view.setResult(res.note, res.midi, smoothCents, smoothFreq,
                    inTune, level);
        }
        ar.stop();
        ar.release();
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        finish();
        Process.killProcess(Process.myPid());
    }
}
