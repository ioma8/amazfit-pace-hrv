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
import java.util.Date;

/** One-shot mic capture with UI: REC/STOP buttons, live waveform, duration,
 *  save status. Records 16 kHz mono PCM (the only rate the dmic clocks
 *  correctly) and streams it straight to a raw WAV on disk in real time
 *  (/sdcard/mic-probe/) — no RAM buffering, so recording length is bounded by
 *  disk, not heap; the header is re-patched every second, so even a killed
 *  recording leaves a readable file.
 *
 *  On any self-exit (back, home after the grace, screen off) the file is
 *  finalized — sizes patched and fsynced — before the activity closes.
 *
 *  While the activity is up, NotifBlocker cancels incoming notifications so
 *  nothing can steal focus. A pause only exits the probe after EXIT_GRACE_MS —
 *  transient steals (a notification popup that slips through) resume the app;
 *  home/back still exit it. */
public class MainActivity extends Activity {
    static final String TAG = "MicProbe";
    static final int FS = 16000;
    static final int CHUNK = 3200; // 200 ms
    static final long EXIT_GRACE_MS = 3000;

    private MicView view;
    private volatile boolean recording = false;
    private volatile Thread worker = null;
    private volatile String recTs = null;
    private PowerManager.WakeLock wl;         // partial, during recording
    private PowerManager.WakeLock screenLock; // keeps screen on while app runs
    private final Handler handler = new Handler();
    private final Runnable exitRunnable = new Runnable() {
        @Override public void run() { exitApp(); }
    };

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
        handler.removeCallbacks(exitRunnable);
        screenLock.acquire();
        NotifBlocker.setArmed(true);
        view.setStatus(notifStatus());
    }

    @Override public void onBackPressed() {
        exitApp();
    }

    @Override protected void onPause() {
        super.onPause();
        // home / screen-off: exit after the grace so transient focus steals
        // (notification popup) don't end the session; onResume cancels this.
        handler.postDelayed(exitRunnable, EXIT_GRACE_MS);
    }

    /** Stop everything and finish: back button or a pause that outlasts the
     *  grace period (home, screen off) exits the probe. The process stays alive
     *  so the notification listener stays bound across sessions. */
    void exitApp() {
        NotifBlocker.setArmed(false);
        recording = false;
        Thread t = worker;
        if (t != null) {
            try { t.join(3000); } catch (InterruptedException ignored) {}
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
        view.setSeconds(0);
        view.setStatus("Recording...");
        wl.acquire();
        worker = new Thread(new Runnable() { @Override public void run() { capture(); } });
        worker.start();
    }

    void capture() {
        int min = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.MIC, FS,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, CHUNK * 2));
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed");
            view.setStatus("Mic init failed");
            recording = false;
            view.setRecording(false);
            wl.release();
            return;
        }
        short[] chunk = new short[CHUNK];
        WavStreamer wav = null;   // lazy-open on first audio: zero signal leaves no file
        String name = null;
        long sumSq = 0;
        int peak = 0;
        boolean failed = false;
        int frames = 0, lastSec = -1;
        ar.startRecording();
        try {
            while (recording) {
                int r = ar.read(chunk, 0, CHUNK);
                if (r <= 0) continue;
                if (wav == null) {
                    File dir = new File(Environment.getExternalStorageDirectory(), "mic-probe");
                    dir.mkdirs();
                    name = "mic_16000_" + recTs + "_raw.wav";
                    try {
                        wav = new WavStreamer(new File(dir, name).getAbsolutePath(), FS);
                    } catch (Exception e) {
                        Log.e(TAG, "open failed", e);
                        failed = true;
                        recording = false;
                        break;
                    }
                }
                try {
                    wav.write(chunk, r);
                } catch (Exception e) {
                    Log.e(TAG, "stream write failed", e);
                    failed = true;
                    recording = false;
                    break;
                }
                frames += r;
                for (int i = 0; i < r; i += 5) view.pushWave(chunk[i]);
                for (int i = 0; i < r; i++) {
                    long s = chunk[i];
                    sumSq += s * s;
                    int a = Math.abs(chunk[i]);
                    if (a > peak) peak = a;
                }
                int sec = frames / FS;
                if (sec != lastSec) {
                    lastSec = sec;
                    view.setSeconds(sec);
                    try { wav.refreshHeader(); } catch (Exception ignored) {}
                }
            }
        } finally {
            ar.stop();
            ar.release();
            recording = false;
            view.setRecording(false);
            if (wav != null) {
                try { wav.close(); } catch (Exception e) { Log.e(TAG, "close failed", e); }
            }
        }
        if (failed) {
            view.setStatus("Save failed");
            wl.release();
            return;
        }
        if (frames == 0) {
            view.setStatus("No signal captured");
            wl.release();
            return;
        }
        double rms = Math.sqrt(sumSq / (double) frames);
        Log.i(TAG, "raw frames=" + frames + " rms=" + (int) rms + " peak=" + peak
                + " peakDb=" + String.format("%.1f", 20 * Math.log10(peak / 32767.0)));
        view.setStatus("Saved: " + name);
        wl.release();
    }

    @Override protected void onDestroy() {
        NotifBlocker.setArmed(false);
        recording = false;
        Thread t = worker;
        if (t != null) {
            try { t.join(3000); } catch (InterruptedException ignored) {}
        }
        if (wl != null && wl.isHeld()) wl.release();
        if (screenLock != null && screenLock.isHeld()) screenLock.release();
        super.onDestroy();
    }
}
