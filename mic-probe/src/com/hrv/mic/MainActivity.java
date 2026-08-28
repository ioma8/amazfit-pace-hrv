package com.hrv.mic;

import android.app.Activity;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

/** One-shot mic capture with UI: REC/STOP buttons, live waveform, duration,
 *  save status. Records 16 kHz mono PCM (the only rate the dmic clocks
 *  correctly) and saves a raw WAV to
 *  /sdcard/mic-probe/.
 *
 *  While the activity is up, NotifBlocker cancels incoming notifications so
 *  nothing can steal focus. A pause only exits the probe after KILL_GRACE_MS —
 *  transient steals (a notification popup that slips through) resume the app;
 *  home/back still exit it. */
public class MainActivity extends Activity {
    static final String TAG = "MicProbe";
    static final int FS = 16000;
    static final int CHUNK = 3200; // 200 ms
    static final long KILL_GRACE_MS = 3000;

    private MicView view;
    private volatile boolean recording = false;
    private volatile Thread worker = null;
    private volatile String recTs = null;
    private PowerManager.WakeLock wl;         // partial, during recording
    private PowerManager.WakeLock screenLock; // keeps screen on while app runs
    private final Handler handler = new Handler();
    private final Runnable killRunnable = new Runnable() {
        @Override public void run() { killApp(); }
    };

    static class ShortBuf {
        short[] d = new short[1 << 16];
        int n = 0;
        void add(short[] c, int len) {
            if (n + len > d.length) d = Arrays.copyOf(d, Math.max(d.length * 2, n + len));
            System.arraycopy(c, 0, d, n, len);
            n += len;
        }
        short[] toArray() { return Arrays.copyOf(d, n); }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mic");
        screenLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "mic-screen");
        view = new MicView(this);
        view.setListener(new MicView.Listener() {
            @Override public void onRecord() { startRecording(); }
            @Override public void onStop() { recording = false; }
        });
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(killRunnable);
        screenLock.acquire();
        NotifBlocker.setArmed(true);
        view.setStatus(notifStatus());
    }

    @Override public void onBackPressed() {
        killApp();
    }

    @Override protected void onPause() {
        super.onPause();
        // home / screen-off: kill after the grace so transient focus steals
        // (notification popup) don't end the session; onResume cancels this.
        handler.postDelayed(killRunnable, KILL_GRACE_MS);
    }

    /** Stop everything and finish: back button or a pause that outlasts the
     *  grace period (home, screen off) exits the probe. The process stays alive
     *  so the notification listener stays bound across sessions. */
    void killApp() {
        NotifBlocker.setArmed(false);
        recording = false;
        Thread t = worker;
        if (t != null) {
            try { t.join(1000); } catch (InterruptedException ignored) {}
        }
        if (wl != null && wl.isHeld()) wl.release();
        if (screenLock != null && screenLock.isHeld()) screenLock.release();
        finish();
    }

    /** "Notif block ON", or a hint when the grant is missing or the listener
     *  is not bound (this ROM has no Settings UI for it). */
    String notifStatus() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        boolean granted = false;
        if (enabled != null) {
            String pkg = getPackageName();
            String cls = NotifBlocker.class.getName();
            for (String cn : enabled.split(":")) {
                String c = cn.trim();
                if (c.equals(pkg + "/." + NotifBlocker.class.getSimpleName())
                        || c.equals(pkg + "/" + cls)) {
                    granted = true;
                    break;
                }
            }
        }
        if (!granted) {
            return "Grant: adb shell settings put secure enabled_notification_listeners "
                    + getPackageName() + "/.NotifBlocker";
        }
        if (!NotifBlocker.connected) {
            return "Grant set but listener not bound - re-run grant or reboot";
        }
        return "Notif block ON";
    }

    void startRecording() {
        if (recording) return;
        recording = true;
        recTs = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
        view.setRecording(true);
        view.setProcessing(false);
        view.setSeconds(0);
        view.setStatus("Recording...");
        wl.acquire();
        worker = new Thread(new Runnable() { @Override public void run() { capture(); } });
        worker.start();
    }

    void capture() {
        final ShortBuf buf = new ShortBuf();
        int min = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, FS,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 2));
        int frames = 0;
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed");
            view.setStatus("Mic init failed");
            recording = false;
            view.setRecording(false);
            wl.release();
            return;
        }
        short[] chunk = new short[CHUNK];
        ar.startRecording();
        int lastSec = -1;
        while (recording) {
            int r = ar.read(chunk, 0, CHUNK);
            if (r <= 0) continue;
            buf.add(chunk, r);
            frames += r;
            for (int i = 0; i < r; i += 5) view.pushWave(chunk[i]);
            int sec = frames / FS;
            if (sec != lastSec) { lastSec = sec; view.setSeconds(sec); }
        }
        ar.stop();
        ar.release();
        recording = false;
        view.setRecording(false);
        if (frames == 0) {
            view.setStatus("No signal captured");
            wl.release();
            return;
        }
        short[] samples = buf.toArray();
        view.setProcessing(true);
        view.setStatus("Processing...");
        String ts = recTs;
        File dir = new File(Environment.getExternalStorageDirectory(), "mic-probe");
        dir.mkdirs();
        try {
            File raw = new File(dir, "mic_16000_" + ts + "_raw.wav");
            WavWriter.writeWav(raw.getAbsolutePath(), samples, FS);
            logLevel("raw", samples);
            view.setStatus("Saved: " + raw.getName());
        } catch (Exception e) {
            Log.e(TAG, "save failed", e);
            view.setStatus("Save failed");
        } finally {
            view.setProcessing(false);
            wl.release();
        }
    }

    void logLevel(String tag, short[] s) {
        long sum = 0; int peak = 0;
        for (int i = 0; i < s.length; i++) {
            sum += (long) s[i] * s[i];
            int a = Math.abs(s[i]);
            if (a > peak) peak = a;
        }
        double rms = Math.sqrt(sum / (double) s.length);
        Log.i(TAG, tag + " frames=" + s.length + " rms=" + (int) rms
                + " peak=" + peak + " peakDb=" + String.format("%.1f", 20 * Math.log10(peak / 32767.0)));
    }

    @Override protected void onDestroy() {
        NotifBlocker.setArmed(false);
        recording = false;
        Thread t = worker;
        if (t != null) {
            try { t.join(1500); } catch (InterruptedException ignored) {}
        }
        if (wl != null && wl.isHeld()) wl.release();
        if (screenLock != null && screenLock.isHeld()) screenLock.release();
        super.onDestroy();
    }
}
