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

import com.huami.watch.klvp.KlvpResponse;
import com.huami.watch.klvp.KlvpStream;
import com.huami.watch.klvp.WakelockCallback;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "HrvProbe";
    private static final int WIN = 1400; // ~55s window at 25.4 Hz
    private final StringBuilder sb = new StringBuilder();
    private HrvView view;
    private volatile boolean running = true;
    private boolean cleaned = false;
    private final HrvSamples samples = new HrvSamples();
    private SensorManager sm;
    private SensorEventListener listener;
    private HandlerThread ht;
    private PowerManager.WakeLock wl;
    private final PpgWaveform waveform = new PpgWaveform();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        view = new HrvView(this);
        setContentView(view);
        new Thread(new Runnable() {
            public void run() {
                runHrv();
            }
        }).start();
    }

    @Override
    protected void onPause() {
        stopAll();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        stopAll();
        super.onDestroy();
    }

    // idempotent: stops measurement + hub LED on any exit path (back/home/button)
    private synchronized void stopAll() {
        if (cleaned) return;
        cleaned = true;
        running = false;
        try {
            if (listener != null && sm != null) sm.unregisterListener(listener);
        } catch (Throwable t) {
        }
        try {
            if (ht != null) ht.quitSafely();
        } catch (Throwable t) {
        }
        try {
            KlvpStream.sendRequestToSensorHub('a', (short) 0, (byte) 0, (byte) 1, (byte) 0, (short) 4,
                new byte[]{(byte) 0xD0, 0x02, 0x00});
        } catch (Throwable t) {
        }
        try {
            if (wl != null && wl.isHeld()) wl.release();
        } catch (Throwable t) {
        }
        log("--- stopped: LED off ---");
        writeOut();
    }

    private void runHrv() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hrvprobe");
        wl.acquire();
        log("=== V19 LIVE HRV (morphology-calibrated) ===");
        log("model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT);

        Thread responder = new Thread(new Runnable() {
            public void run() {
                try {
                    KlvpResponse[] rs = KlvpStream.readResponses(new WakelockCallback() {
                        public void WakelockCallback() {
                        }
                    });
                    if (rs != null) {
                        for (KlvpResponse r : rs) log("  [klvp] " + r);
                    }
                } catch (Throwable t) {
                    log("  [klvp] read EXC: " + t);
                }
            }
        }, "klvp-reader");
        responder.setDaemon(true);
        responder.start();

        log("--- allDayHR=true ---");
        try {
            KlvpStream.sendRequestToSensorHub('a', (short) 0, (byte) 0, (byte) 1, (byte) 0, (short) 4,
                new byte[]{(byte) 0xD0, 0x02, 0x01});
        } catch (Throwable t) {
            log("  klvp send EXC: " + t);
        }
        sleep(3000);

        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        ht = new HandlerThread("ppg");
        ht.start();
        Handler h = new Handler(ht.getLooper());
        List<Sensor> ppgs = sm.getSensorList(65538);
        Sensor ppg = ppgs.get(0);

        listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent e) {
                float v = e.values[0];
                samples.add(v, System.nanoTime());
                view.pushWave(waveform.filter(v));
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
            }
        };
        boolean ok = sm.registerListener(listener, ppg, 2000, h);
        log("ppg register=" + ok);

        long start = System.nanoTime();
        int lastLog = 0;
        while (running) {
            sleep(2000);
            if (!running) break;
            int sec = (int) ((System.nanoTime() - start) / 1e9);
            float[] metrics = analyze();
            if (metrics != null) {
                view.setMetrics(metrics[0], metrics[1], metrics[3], sec);
            } else {
                view.setMetrics(0, 0, 0, sec);
            }
            if (sec - lastLog >= 15) {
                lastLog = sec;
                if (metrics != null) {
                    log(String.format("t+%ds HR=%.1f RMSSD=%.1fms SDNN=%.1fms score=%.0f%% clean=%d/%d",
                            sec, metrics[0], metrics[1], metrics[2], metrics[3],
                            (int) metrics[4], (int) metrics[5]));
                } else {
                    log("t+" + sec + "s collecting (n=" + samples.size() + ")");
                }
            }
        }
    }

    private float[] analyze() {
        if (samples.size() < 305) return null;
        HrvSamples.Snapshot snapshot = samples.tail(WIN);
        HrvAnalyzer.Result result = HrvAnalyzer.analyze(snapshot.values, snapshot.times);
        if (result == null) return null;
        return new float[]{result.hr, result.rmssdMs, result.sdnnMs, result.score,
            result.cleanCount, result.totalCount};
    }



    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void log(String s) {
        sb.append(s).append('\n');
        Log.i(TAG, s);
    }

    private void writeOut() {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            dir.mkdirs();
            File out = new File(dir, "hrv_probe.log");
            BufferedWriter w = new BufferedWriter(new FileWriter(out));
            w.write(sb.toString());
            w.flush();
            w.close();
            Log.i(TAG, "WROTE " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
        } catch (Throwable t) {
            Log.e(TAG, "write failed", t);
        }
    }
}
