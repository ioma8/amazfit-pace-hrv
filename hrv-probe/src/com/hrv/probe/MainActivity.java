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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "HrvProbe";
    private static final int WIN = 1400; // ~55s window at 25.4 Hz
    private final StringBuilder sb = new StringBuilder();
    private HrvView view;
    private volatile boolean running = true;
    private boolean cleaned = false;
    private final Object lock = new Object();
    private final List<Float> series = new ArrayList<Float>();
    private final List<Long> times = new ArrayList<Long>();
    private SensorManager sm;
    private SensorEventListener listener;
    private HandlerThread ht;
    private PowerManager.WakeLock wl;

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
        log("=== V16 LIVE HRV ===");
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
                long t = System.nanoTime();
                synchronized (lock) {
                    series.add(v);
                    times.add(t);
                    int n = series.size();
                    if (n >= 32) {
                        double m = 0;
                        for (int i = n - 32; i < n; i++) m += series.get(i);
                        m /= 32;
                        view.pushWave(v - (float) m);
                    }
                }
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
            int sec = (int) ((System.nanoTime() - start) / 1e9);
            float[] metrics = analyze();
            if (metrics != null) {
                view.setMetrics(metrics[0], metrics[1], metrics[2], sec);
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
                    log("t+" + sec + "s collecting (n=" + series.size() + ")");
                }
            }
        }
        writeOut();
    }

    // Returns uniform-clock {hr, rmssdMs, sdnnMs, score, cleanCount, totalCount}.
    // SensorEvent callback time is deliberately not used: the hub delivers five-sample bursts.
    private float[] analyze() {
        int n;
        float[] vals;
        long[] wall;
        synchronized (lock) {
            n = series.size();
            if (n < 1000) return null; // >=39s: HRV needs a stationary window
            int from = Math.max(0, n - WIN);
            int len = n - from;
            vals = new float[len];
            wall = new long[len];
            for (int i = 0; i < len; i++) {
                vals[i] = series.get(from + i);
                wall[i] = times.get(from + i);
            }
        }
        int nn = vals.length;
        double dt = (wall[nn - 1] - wall[0]) / 1e9 / (nn - 1);
        double fs = 1.0 / dt;
        if (fs < 24 || fs > 27) return null;

        int w = (int) Math.round(1.2 * fs);
        double[] trend = new double[nn];
        double acc = 0;
        for (int i = 0; i < nn; i++) {
            acc += vals[i];
            if (i >= w) acc -= vals[i - w];
            trend[i] = acc / Math.min(i + 1, w);
        }
        double[] sm = new double[nn];
        for (int i = 0; i < nn; i++) sm[i] = vals[i] - trend[i];
        double[] s3 = new double[nn];
        for (int i = 1; i < nn - 1; i++) s3[i] = (sm[i - 1] + 2 * sm[i] + sm[i + 1]) / 4.0;
        s3[0] = sm[0];
        s3[nn - 1] = sm[nn - 1];

        double std = 0;
        for (double v : s3) std += v * v;
        std = Math.sqrt(std / nn);
        if (std < 100) return null;
        double thr = 0.5 * std;

        // One common peak set. pos is fractional sample index after parabolic interpolation.
        List<Double> peakPos = new ArrayList<Double>();
        for (int i = 1; i < nn - 1; i++) {
            if (s3[i] >= s3[i - 1] && s3[i] > s3[i + 1] && s3[i] > thr) {
                double y0 = s3[i - 1], y1 = s3[i], y2 = s3[i + 1];
                double denom = y0 - 2 * y1 + y2;
                double delta = denom != 0 ? 0.5 * (y0 - y2) / denom : 0;
                if (delta < -0.5) delta = -0.5;
                if (delta > 0.5) delta = 0.5;
                double pos = i + delta;
                if (peakPos.isEmpty() || (pos - peakPos.get(peakPos.size() - 1)) * dt >= 0.3) {
                    peakPos.add(pos);
                }
            }
        }
        if (peakPos.size() < 8) return null;
        List<Double> uniformIbi = new ArrayList<Double>();
        for (int i = 1; i < peakPos.size(); i++) {
            uniformIbi.add((peakPos.get(i) - peakPos.get(i - 1)) * dt);
        }
        double[] metrics = metricsFromIbi(uniformIbi);
        if (metrics == null) return null;
        return new float[]{(float) metrics[0], (float) (metrics[1] * 1000), (float) (metrics[2] * 1000),
            (float) metrics[3], (float) metrics[4], (float) metrics[5]};
    }

    // Same dicrotic merging + rejection for both timing hypotheses.
    private double[] metricsFromIbi(List<Double> input) {
        List<Double> ibi = new ArrayList<Double>();
        for (double d : input) if (d > 0.3 && d < 2.5) ibi.add(d);
        if (ibi.size() < 6) return null;
        List<Double> sorted = new ArrayList<Double>(ibi);
        Collections.sort(sorted);
        double med = sorted.get(sorted.size() / 2);

        List<Double> merged = new ArrayList<Double>();
        int i = 0;
        while (i < ibi.size()) {
            double d = ibi.get(i);
            if (d < 0.62 * med && i + 1 < ibi.size()) {
                double d2 = ibi.get(i + 1);
                double sum = d + d2;
                if (Math.abs(sum - 2 * med) < 0.35 * 2 * med) {
                    merged.add(sum);
                    i += 2;
                    continue;
                }
            }
            merged.add(d);
            i++;
        }
        if (merged.size() < 5) return null;
        List<Double> mergedSorted = new ArrayList<Double>(merged);
        Collections.sort(mergedSorted);
        double med2 = mergedSorted.get(mergedSorted.size() / 2);
        List<Double> clean = new ArrayList<Double>();
        for (double d : merged) if (d > 0.55 * med2 && d < 1.6 * med2) clean.add(d);
        if (clean.size() < 10) return null;

        double meanIbi = 0;
        for (double d : clean) meanIbi += d;
        meanIbi /= clean.size();
        double sdnn = 0;
        for (double d : clean) sdnn += (d - meanIbi) * (d - meanIbi);
        sdnn = Math.sqrt(sdnn / clean.size());
        double rmssd = 0;
        for (int j = 1; j < clean.size(); j++) {
            double d = clean.get(j) - clean.get(j - 1);
            rmssd += d * d;
        }
        rmssd = Math.sqrt(rmssd / (clean.size() - 1));
        double score = 100 * (1 - Math.exp(-rmssd / 0.035));
        return new double[]{60.0 / meanIbi, rmssd, sdnn, score, clean.size(), merged.size()};
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
