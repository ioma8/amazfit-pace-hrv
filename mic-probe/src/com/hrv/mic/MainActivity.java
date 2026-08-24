package com.hrv.mic;

import android.app.Activity;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/** One-shot mic capture probe: records N seconds at every rate the audio HAL
 *  declares (8000/11025/16000/44100 Hz, mono PCM16) and writes WAV files to
 *  /sdcard/mic-probe/ for later analysis. Stats go to logcat tag "MicProbe". */
public class MainActivity extends Activity {
    static final String TAG = "MicProbe";
    static final int[] RATES = {8000, 11025, 16000, 44100};
    static final int CAPTURE_MS = 5000;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        new Thread(new Runnable() { public void run() { probe(); finish(); } }).start();
    }

    void probe() {
        PowerManager.WakeLock wl = ((PowerManager) getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mic");
        wl.acquire();
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "mic-probe");
            dir.mkdirs();
            for (int rate : RATES) {
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) { Log.i(TAG, "rate=" + rate + " unsupported (minBuf=" + min + ")"); continue; }
                record(dir, rate, min);
            }
        } catch (Throwable t) {
            Log.e(TAG, "ERR", t);
        } finally {
            wl.release();
        }
        Log.i(TAG, "DONE");
    }

    void record(File dir, int rate, int minBuf) {
        int buf = Math.max(minBuf, rate / 5); // at least 200 ms
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf);
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.i(TAG, "rate=" + rate + " init failed");
            ar.release();
            return;
        }
        int frames = rate * CAPTURE_MS / 1000;
        short[] pcm = new short[frames];
        ar.startRecording();
        int n = 0;
        while (n < frames) {
            int r = ar.read(pcm, n, frames - n);
            if (r <= 0) break;
            n += r;
        }
        ar.stop();
        ar.release();
        if (n <= 0) { Log.i(TAG, "rate=" + rate + " no frames captured"); return; }
        File f = new File(dir, "mic_" + rate + ".wav");
        try {
            writeWav(f, rate, pcm, n);
        } catch (Exception e) {
            Log.e(TAG, "rate=" + rate + " write failed", e);
            return;
        }
        long sum = 0; int lo = Short.MAX_VALUE, hi = Short.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int v = pcm[i];
            sum += (long) v * v;
            if (v < lo) lo = v;
            if (v > hi) hi = v;
        }
        double rms = Math.sqrt(sum / (double) n);
        Log.i(TAG, String.format("rate=%d frames=%d/%d rms=%.1f range=%d..%d -> %s",
                rate, n, frames, rms, lo, hi, f.getAbsolutePath()));
    }

    void writeWav(File f, int rate, short[] pcm, int n) throws Exception {
        int data = n * 2;
        byte[] hdr = new byte[44];
        put(hdr, 0, "RIFF"); put(hdr, 4, 36 + data); put(hdr, 8, "WAVE");
        put(hdr, 12, "fmt "); put(hdr, 16, 16); put(hdr, 20, (short) 1); put(hdr, 22, (short) 1);
        put(hdr, 24, rate); put(hdr, 28, rate * 2); put(hdr, 32, (short) 2); put(hdr, 34, (short) 16);
        put(hdr, 36, "data"); put(hdr, 40, data);
        byte[] b = new byte[data];
        for (int i = 0; i < n; i++) { b[i * 2] = (byte) pcm[i]; b[i * 2 + 1] = (byte) (pcm[i] >> 8); }
        FileOutputStream out = new FileOutputStream(f);
        out.write(hdr);
        out.write(b);
        out.close();
    }

    static void put(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    static void put(byte[] b, int o, int v)   { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); b[o + 2] = (byte) (v >> 16); b[o + 3] = (byte) (v >> 24); }
    static void put(byte[] b, int o, short v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
}
