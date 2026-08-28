package com.hrv.common;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.provider.Settings;
import android.view.WindowManager;

/**
 * Shared probe-activity base. Every probe is a single-screen one-shot on the
 * watch: it sets up its view, keeps the screen on, optionally forces maximum
 * brightness, optionally holds a partial wakelock, and exits on pause.
 *
 * Two exit behaviours, picked in onCreate:
 *  - hardKillOnPause(): onPause finishes and kills the process — the classic
 *    probe convention (a probe never resumes). onExitCleanup() runs first.
 *  - useGraceExit(): onPause schedules an exit after EXIT_GRACE_MS so a
 *    transient focus steal (a notification popup) does not end the session;
 *    onResume cancels it, back exits immediately, and the process stays
 *    alive (Android 5.1 never re-binds a killed notification listener).
 * Calling neither leaves EXIT_NONE: no exit-on-pause at all (filebrowser
 * navigates on back; mic-clock is a passive one-shot) — the base only keeps
 * the screen on.
 *
 * onExitCleanup() MUST be idempotent: it runs from the pause/back path and
 * again from onDestroy. exitNow() is guarded against re-entry, so a pause
 * landing between finish() and onDestroy cannot re-run teardown.
 */
public abstract class ProbeActivity extends Activity {
    protected static final long EXIT_GRACE_MS = 3000;

    private static final int EXIT_NONE = 0;
    private static final int EXIT_HARD_KILL = 1;
    private static final int EXIT_GRACE = 2;

    private final Handler handler = new Handler();
    private final Runnable exitRunnable = new Runnable() {
        @Override public void run() { exitNow(); }
    };

    private int exitMode = EXIT_NONE;
    private boolean exiting = false;
    private PowerManager.WakeLock wakeLock;
    private int savedBrightness = -1;
    private int savedMode = -1;
    private boolean brightnessOk = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ---- exit behaviour ----

    protected void hardKillOnPause() {
        exitMode = EXIT_HARD_KILL;
    }

    protected void useGraceExit() {
        exitMode = EXIT_GRACE;
    }

    /** Real-time audio/render priority for worker threads. */
    protected void setUrgentAudioPriority() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
    }

    /** Teardown before finishing: join workers, release resources, disarm
     *  notification blocking. MUST be idempotent. */
    protected void onExitCleanup() {
    }

    protected void exitNow() {
        if (exiting) return;
        exiting = true;
        handler.removeCallbacks(exitRunnable);
        onExitCleanup();
        if (brightnessOk) restoreBrightness();
        finish();
        if (exitMode == EXIT_HARD_KILL) Process.killProcess(Process.myPid());
    }

    @Override protected void onPause() {
        super.onPause();
        if (exiting) return;
        if (exitMode == EXIT_HARD_KILL) {
            exitNow();
        } else if (exitMode == EXIT_GRACE) {
            handler.postDelayed(exitRunnable, EXIT_GRACE_MS);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (exitMode == EXIT_GRACE) handler.removeCallbacks(exitRunnable);
    }

    @Override public void onBackPressed() {
        exitNow();
    }

    @Override protected void onDestroy() {
        exitNow();
        super.onDestroy();
    }

    // ---- wakelock ----

    protected void acquireWakeLock(String tag) {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag);
        }
        wakeLock.acquire();
    }

    protected void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ---- brightness ----

    /** Remember the user's brightness setting, then force maximum. */
    protected void saveAndMaxBrightness() {
        try {
            savedBrightness = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);
            savedMode = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 255);
            brightnessOk = true;
        } catch (SecurityException e) {
            brightnessOk = false;
        }
    }

    /** Put the user's brightness back (probes kill themselves on pause). */
    private void restoreBrightness() {
        if (!brightnessOk) {
            return;
        }
        try {
            if (savedMode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    && savedBrightness >= 0) {
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, savedBrightness);
            }
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, savedMode);
        } catch (SecurityException ignored) {
        }
    }
}
