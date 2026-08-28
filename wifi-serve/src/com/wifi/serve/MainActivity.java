package com.wifi.serve;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.View;

import com.google.zxing.common.BitMatrix;

import com.hrv.common.ProbeActivity;
import com.hrv.common.WavWriter;

import java.io.File;
import java.io.IOException;

/**
 * Pace Sync — mic recording transfer via watch WiFi AP.
 *
 * Phase 1: watch starts an access point and shows a WIFI: QR; the phone scans
 * it and joins. The watch detects the join through /proc/net/arp (fallback:
 * 15 s timer, or tap to toggle) and switches to phase 2: a QR of the served
 * URL. Scanning that opens the browser page that lists, downloads, and clears
 * the mic recordings.
 *
 * Lifecycle follows the repo convention: screen stays on while visible, back /
 * pause tears everything down (HTTP server, AP) and hard-kills the process.
 */
public class MainActivity extends ProbeActivity {
    private static final String TAG = "PaceSync";
    private static final int PORT = 8080;
    private static final long TICK_MS = 500;
    private static final long ARP_FALLBACK_MS = 15000;
    private static final int QR_MODULES = 168;

    private MainView view;
    private HttpServer server;
    private File micDir;
    private final Handler handler = new Handler();
    private volatile boolean stopped = false;
    private long startTime;
    private boolean arpUsable = true;
    private boolean urlSet = false;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (stopped) {
                return;
            }
            if (view.getPhase() == MainView.PHASE_JOIN) {
                if (ApManager.isEnabled(MainActivity.this)) {
                    view.setStatus("AP on · waiting for phone");
                }
                String ip = ApManager.apIp();
                if (ip != null && !urlSet && view.getPhase() == MainView.PHASE_JOIN) {
                    urlSet = true;
                    setUrl("http://" + ip + ":" + PORT);
                }
                String subnet = subnetOf(ip);
                if (subnet != null) {
                    int clients = ApManager.apClients(subnet);
                    if (clients < 0) {
                        arpUsable = false;
                    } else if (clients > 0) {
                        switchToOpen();
                        return;
                    }
                }
                if (!arpUsable && System.currentTimeMillis() - startTime > ARP_FALLBACK_MS) {
                    switchToOpen();
                    return;
                }
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        hardKillOnPause();
        startTime = System.currentTimeMillis();

        view = new MainView(this);
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                view.togglePhase();
            }
        });
        setContentView(view);

        micDir = new File(Environment.getExternalStorageDirectory(), "mic");
        view.setFileCount(countRecordings());
        view.setStatus("AP starting");

        try {
            view.setJoinQr(Qr.toBitmap(Qr.encodeMatrix(
                    "WIFI:T:WPA;S:" + ApManager.SSID + ";P:" + ApManager.PASS + ";;", QR_MODULES), 12));
        } catch (Exception e) {
            Log.e(TAG, "join QR failed", e);
            view.setStatus("QR failed: " + e.getMessage());
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = ApManager.enable(MainActivity.this);
                if (ok && stopped) {
                    // user left while the AP was still coming up — undo it
                    ApManager.disable(MainActivity.this);
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            view.setStatus("AP failed (ROM gate?)");
                        }
                    }
                });
            }
        }, "ap-enable").start();

        server = new HttpServer(micDir, PORT);
        try {
            server.start();
        } catch (IOException e) {
            Log.e(TAG, "HTTP server failed", e);
            view.setStatus("HTTP server failed");
        }

        handler.postDelayed(tick, TICK_MS);
    }

    private void setUrl(final String url) {
        view.setUrl(url);
        try {
            view.setUrlQr(Qr.toBitmap(Qr.encodeMatrix(url, QR_MODULES), 12));
        } catch (Exception e) {
            Log.e(TAG, "url QR failed", e);
        }
    }

    private void switchToOpen() {
        view.setFileCount(countRecordings());
        view.setPhase(MainView.PHASE_OPEN);
    }

    private int countRecordings() {
        File[] all = micDir.listFiles();
        if (all == null) {
            return 0;
        }
        int n = 0;
        for (File f : all) {
            String name = f.getName();
            if (f.isFile() && name.startsWith(WavWriter.MIC_PREFIX) && name.endsWith(".wav")) {
                n++;
            }
        }
        return n;
    }

    private static String subnetOf(String ip) {
        if (ip == null) {
            return null;
        }
        int i = ip.lastIndexOf('.');
        return i > 0 ? ip.substring(0, i) : null;
    }

    @Override
    protected void onExitCleanup() {
        stopAll();
    }

    private void stopAll() {
        if (stopped) {
            return;
        }
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        if (server != null) {
            server.stop();
        }
        ApManager.disable(this);
    }
}
