package com.micclock;

import android.media.AudioRecord;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.widget.TextView;

import com.hrv.common.MicAudio;
import com.hrv.common.ProbeActivity;
import com.hrv.common.WavWriter;

import java.io.File;

/** One-shot raw MIC clock calibration: 32 s, 16 kHz mono PCM16, no DSP. */
public final class MainActivity extends ProbeActivity {
    private static final int SECONDS = 32;
    private TextView status;
    private volatile boolean running = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(24);
        status.setText("Recording raw MIC\n0 / 32 s");
        setContentView(status);
        acquireWakeLock("mic-clock");
        new Thread(new Runnable() { @Override public void run() { capture(); } }, "capture").start();
    }

    private void capture() {
        AudioRecord ar = MicAudio.open(6400);
        if (ar == null) {
            show("Mic init failed");
            releaseWakeLock();
            return;
        }
        short[] all = new short[MicAudio.FS * SECONDS];
        short[] chunk = new short[3200];
        int n = 0, lastSecond = -1;
        ar.startRecording();
        while (running && n < all.length) {
            int r = ar.read(chunk, 0, Math.min(chunk.length, all.length - n));
            if (r <= 0) continue;
            System.arraycopy(chunk, 0, all, n, r);
            n += r;
            int second = n / MicAudio.FS;
            if (second != lastSecond) {
                lastSecond = second;
                show("Recording raw MIC\n" + second + " / " + SECONDS + " s");
            }
        }
        ar.stop();
        ar.release();
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "mic-clock");
            dir.mkdirs();
            File out = new File(dir, "capture.wav");
            WavWriter.writeAll(out.getAbsolutePath(), MicAudio.FS, all, n);
            show("Saved " + n + " samples\n" + out.getAbsolutePath());
        } catch (Exception e) {
            show("Save failed: " + e.getMessage());
        }
        releaseWakeLock();
    }

    private void show(final String text) {
        runOnUiThread(new Runnable() { @Override public void run() { status.setText(text); } });
    }

    @Override protected void onExitCleanup() { running = false; releaseWakeLock(); }
}
