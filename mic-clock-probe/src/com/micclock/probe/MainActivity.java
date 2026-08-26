package com.micclock.probe;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

/** One-shot raw MIC clock calibration: 32 s, 16 kHz mono PCM16, no DSP. */
public final class MainActivity extends Activity {
    private static final int RATE = 16000;
    private static final int SECONDS = 32;
    private TextView status;
    private volatile boolean running = true;
    private PowerManager.WakeLock wake;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(24);
        status.setText("Recording raw MIC\n0 / 32 s");
        setContentView(status);
        wake = ((PowerManager) getSystemService(POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mic-clock");
        wake.acquire();
        new Thread(new Runnable() { @Override public void run() { capture(); } }, "capture").start();
    }

    private void capture() {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, 6400));
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            show("Mic init failed");
            releaseWake();
            return;
        }
        short[] all = new short[RATE * SECONDS];
        short[] chunk = new short[3200];
        int n = 0, lastSecond = -1;
        ar.startRecording();
        while (running && n < all.length) {
            int r = ar.read(chunk, 0, Math.min(chunk.length, all.length - n));
            if (r <= 0) continue;
            System.arraycopy(chunk, 0, all, n, r);
            n += r;
            int second = n / RATE;
            if (second != lastSecond) {
                lastSecond = second;
                show("Recording raw MIC\n" + second + " / " + SECONDS + " s");
            }
        }
        ar.stop();
        ar.release();
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "mic-clock-probe");
            dir.mkdirs();
            File out = new File(dir, "capture.wav");
            writeWav(out, all, n);
            show("Saved " + n + " samples\n" + out.getAbsolutePath());
        } catch (Exception e) {
            show("Save failed: " + e.getMessage());
        }
        releaseWake();
    }

    private void show(final String text) {
        runOnUiThread(new Runnable() { @Override public void run() { status.setText(text); } });
    }

    private static void writeWav(File file, short[] pcm, int n) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        int bytes = n * 2;
        byte[] h = new byte[44];
        put(h, 0, "RIFF"); le32(h, 4, 36 + bytes); put(h, 8, "WAVEfmt ");
        le32(h, 16, 16); le16(h, 20, 1); le16(h, 22, 1); le32(h, 24, RATE);
        le32(h, 28, RATE * 2); le16(h, 32, 2); le16(h, 34, 16);
        put(h, 36, "data"); le32(h, 40, bytes); out.write(h);
        byte[] b = new byte[8192];
        int p = 0;
        while (p < n) {
            int count = Math.min(b.length / 2, n - p);
            for (int i = 0; i < count; i++) {
                short v = pcm[p + i]; b[i * 2] = (byte) v; b[i * 2 + 1] = (byte) (v >> 8);
            }
            out.write(b, 0, count * 2); p += count;
        }
        out.close();
    }

    private static void put(byte[] b, int at, String s) throws Exception {
        byte[] x = s.getBytes("US-ASCII"); System.arraycopy(x, 0, b, at, x.length);
    }
    private static void le16(byte[] b, int at, int v) { b[at]=(byte)v; b[at+1]=(byte)(v>>8); }
    private static void le32(byte[] b, int at, int v) {
        b[at]=(byte)v; b[at+1]=(byte)(v>>8); b[at+2]=(byte)(v>>16); b[at+3]=(byte)(v>>24);
    }
    private void releaseWake() { if (wake != null && wake.isHeld()) wake.release(); }
    @Override protected void onDestroy() { running = false; releaseWake(); super.onDestroy(); }
}
