package com.hrv.probe;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;

import com.huami.watch.klvp.KlvpStream;

import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "HrvProbe";
    private static final int WIN = 1400; // ~55s window at 25.2 Hz

    private final Object sessionLock = new Object();
    private final HrvSamples samples = new HrvSamples(WIN);
    private final PpgWaveform waveform = new PpgWaveform();

    private HrvView view;
    private volatile boolean running;
    private boolean stopReported;
    private boolean hrEnableAttempted;
    private Thread sessionThread;
    private SensorManager sensorManager;
    private SensorEventListener listener;
    private HandlerThread sensorThread;
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        view = new HrvView(this);
        setContentView(view);
        running = true;
        sessionThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runHrv();
            }
        }, "hrv-session");
        sessionThread.start();
    }

    @Override
    protected void onPause() {
        stopAll();
        if (!isFinishing()) finish(); // launcher re-entry always creates a fresh session
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopAll();
        super.onDestroy();
    }

    private void stopAll() {
        running = false;
        Thread worker;
        synchronized (sessionLock) {
            worker = sessionThread;
        }
        if (worker != null && worker != Thread.currentThread()) worker.interrupt();
        cleanupResources();

        boolean report;
        synchronized (sessionLock) {
            report = !stopReported;
            stopReported = true;
        }
        if (report) {
            log("--- stopped: LED off ---");
        }
    }
    private void runHrv() {
        boolean failed = false;
        try {
            if (!acquireWakeLock()) return;
            log("=== V20 LIVE HRV (lifecycle-safe) ===");
            log("model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT);
            if (!enableHeartMeasurement()) return;
            if (!sleepWhileRunning(3000)) return;
            if (!registerPpg()) return;

            long start = System.nanoTime();
            int lastLog = 0;
            while (sleepWhileRunning(2000)) {
                int seconds = (int) ((System.nanoTime() - start) / 1e9);
                HrvAnalyzer.Result result = analyze();
                if (result != null) {
                    view.setMetrics(result.hr, result.rmssdMs, result.score,
                        result.scoreAvailable, seconds);
                } else {
                    view.setMetrics(0, 0, 0, false, seconds);
                }
                if (seconds - lastLog >= 15) {
                    lastLog = seconds;
                    if (result == null) {
                        log("t+" + seconds + "s collecting (n=" + samples.size() + ")");
                    } else {
                        String score = result.scoreAvailable
                            ? String.format("%.0f%%", result.score) : "building";
                        log(String.format(
                            "t+%ds HR=%.1f RMSSD=%.1fms SDNN=%.1fms score=%s clean=%d/%d",
                            seconds, result.hr, result.rmssdMs, result.sdnnMs, score,
                            result.cleanCount, result.totalCount));
                    }
                }
            }
        } catch (Throwable error) {
            failed = running;
            log("session failed: " + error);
        } finally {
            running = false;
            cleanupResources();
            synchronized (sessionLock) {
                if (sessionThread == Thread.currentThread()) sessionThread = null;
            }
            if (failed && !isFinishing()) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) finish();
                    }
                });
            }
        }
    }

    private boolean acquireWakeLock() {
        PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (manager == null) throw new IllegalStateException("PowerManager unavailable");
        PowerManager.WakeLock candidate = manager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "hrvprobe");
        synchronized (sessionLock) {
            if (!running) return false;
            wakeLock = candidate;
            wakeLock.acquire();
            return true;
        }
    }

    private boolean enableHeartMeasurement() {
        synchronized (sessionLock) {
            if (!running) return false;
            hrEnableAttempted = true;
            log("--- allDayHR=true ---");
            KlvpStream.sendRequestToSensorHub('a', (short) 0, (byte) 0, (byte) 1,
                (byte) 0, (short) 4, new byte[]{(byte) 0xD0, 0x02, 0x01});
            return true;
        }
    }

    private boolean registerPpg() {
        SensorManager manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (manager == null) throw new IllegalStateException("SensorManager unavailable");
        List<Sensor> sensors = manager.getSensorList(65538);
        if (sensors.isEmpty()) throw new IllegalStateException("PPG sensor unavailable");
        final Sensor ppg = sensors.get(0);
        final HandlerThread thread = new HandlerThread("ppg");
        final SensorEventListener candidate = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                float value = event.values[0];
                samples.add(value, System.nanoTime());
                view.pushWave(waveform.filter(value));
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };

        synchronized (sessionLock) {
            if (!running) return false;
            sensorManager = manager;
            sensorThread = thread;
            listener = candidate;
            thread.start();
            boolean registered = manager.registerListener(
                candidate, ppg, 2000, new Handler(thread.getLooper()));
            log("ppg register=" + registered);
            if (!registered) throw new IllegalStateException("PPG registration failed");
            return true;
        }
    }

    private void cleanupResources() {
        synchronized (sessionLock) {
            if (listener != null && sensorManager != null) {
                try {
                    sensorManager.unregisterListener(listener);
                } catch (Throwable error) {
                    log("PPG unregister failed: " + error);
                }
            }
            listener = null;
            sensorManager = null;

            if (sensorThread != null) {
                try {
                    sensorThread.quitSafely();
                } catch (Throwable error) {
                    log("PPG thread shutdown failed: " + error);
                }
                sensorThread = null;
            }

            if (hrEnableAttempted) {
                try {
                    KlvpStream.sendRequestToSensorHub('a', (short) 0, (byte) 0, (byte) 1,
                        (byte) 0, (short) 4, new byte[]{(byte) 0xD0, 0x02, 0x00});
                } catch (Throwable error) {
                    log("LED shutdown failed: " + error);
                }
                hrEnableAttempted = false;
            }

            if (wakeLock != null) {
                try {
                    if (wakeLock.isHeld()) wakeLock.release();
                } catch (Throwable error) {
                    log("wake lock release failed: " + error);
                }
                wakeLock = null;
            }
        }
    }

    private HrvAnalyzer.Result analyze() {
        if (samples.size() < 305) return null;
        HrvSamples.Snapshot snapshot = samples.tail(WIN);
        return HrvAnalyzer.analyze(snapshot.values, snapshot.times);
    }

    private boolean sleepWhileRunning(long milliseconds) {
        if (!running) return false;
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
        return running;
    }

    private void log(String message) {
        Log.i(TAG, message);
    }
}
