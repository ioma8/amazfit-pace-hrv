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

/** Guitar tuner: 16 kHz mono mic (the only rate the dmic clocks), 4096-sample
 *  Hann-windowed FFT windows with 50% overlap, pitch -> note + cents, shown
 *  on a round gauge; vibrates when in tune. */
public class MainActivity extends Activity {
    static final String TAG = "Tuner";
    static final int FS = 16000;
    static final int CHUNK = Tuner.N / 2; // 50% overlap: 2048-sample hop

    private TunerView view;
    private Tuner tuner;
    private volatile boolean running = true;
    private Thread worker;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        view = new TunerView(this);
        tuner = new Tuner();
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
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        int min = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, FS,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, Tuner.N * 2));
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed");
            return;
        }
        short[] window = new short[Tuner.N];
        short[] chunk = new short[CHUNK];
        int filled = 0;
        int peak = 0;
        ar.startRecording();
        while (running) {
            int r = ar.read(chunk, 0, CHUNK);
            if (r <= 0) {
                continue;
            }
            // level meter from this chunk
            int pk = 0;
            for (int i = 0; i < r; i++) {
                int v = chunk[i] < 0 ? -chunk[i] : chunk[i];
                if (v > pk) pk = v;
            }
            peak = (peak * 3 + pk) / 4;
            float level = Math.min(1f, peak / 18000f);
            // shift the window left, append the new chunk
            System.arraycopy(window, CHUNK, window, 0, Tuner.N - CHUNK);
            System.arraycopy(chunk, 0, window, Tuner.N - CHUNK, CHUNK);
            filled += r;
            if (filled < Tuner.N) {
                continue;
            }
            Tuner.Result res = tuner.analyze(window);
            if (res == null) {
                view.setIdle(level);
                continue;
            }
            boolean inTune = Math.abs(res.cents) < 3;
            view.setResult(res.note, res.cents, res.freq, inTune, level);
            if (inTune) {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null) {
                    v.vibrate(15);
                }
            }
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
