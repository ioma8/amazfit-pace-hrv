package com.hrv.common;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/** HTTP GET + Wi-Fi bring-up helpers shared by the network probes. Callers may
 *  pass their own User-Agent — the original per-app UAs are preserved. */
public final class Net {
    private static final String TAG = "Net";
    public static final int CONNECT_TIMEOUT_MS = 15000;
    public static final int READ_TIMEOUT_MS = 20000;
    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Android; Amazfit Pace) Probe/1.0";

    private Net() {
    }

    /** GET with probe timeouts, redirects and the probe UA. */
    public static HttpURLConnection open(String url) throws IOException {
        return open(url, DEFAULT_UA);
    }

    /** GET with probe timeouts, redirects and a caller-supplied UA. */
    public static HttpURLConnection open(String url, String userAgent)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", userAgent);
        return c;
    }

    /** GET + 2xx check + raw body bytes. */
    public static byte[] get(String url) throws IOException {
        return get(url, DEFAULT_UA);
    }

    public static byte[] get(String url, String userAgent) throws IOException {
        HttpURLConnection c = open(url, userAgent);
        try {
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** GET + 2xx check + body as UTF-8 text (gzip decoded transparently). */
    public static String getString(String url) throws IOException {
        return getString(url, DEFAULT_UA);
    }

    public static String getString(String url, String userAgent)
            throws IOException {
        HttpURLConnection c = open(url, userAgent);
        try {
            c.setRequestProperty("Accept-Encoding", "gzip");
            int status = c.getResponseCode();
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status);
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            r.close();
            Log.i(TAG, "GET " + url + " -> " + status + " len=" + sb.length());
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    /** Enable Wi-Fi if off and poll until connected (30 s deadline), logging
     *  state transitions for on-device debugging. */
    public static void ensureWifi(Context context) throws IOException {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        boolean wasEnabled = wifi != null && wifi.isWifiEnabled();
        Log.i(TAG, "ensureWifi: enabled=" + wasEnabled);
        if (wifi != null && !wasEnabled) {
            Log.i(TAG, "ensureWifi: enabling");
            wifi.setWifiEnabled(true);
        }
        ConnectivityManager manager = (ConnectivityManager) context
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        long deadline = System.currentTimeMillis() + 30000;
        long lastState = -1;
        while (System.currentTimeMillis() < deadline) {
            NetworkInfo info = manager == null ? null
                    : manager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            String ssid = wifi == null ? "?"
                    : String.valueOf(wifi.getConnectionInfo().getSSID());
            String state = info == null ? "null"
                    : info.getDetailedState() + "/connected=" + info.isConnected();
            if (state.hashCode() != lastState || !info.isConnected()) {
                Log.i(TAG, "ensureWifi poll: " + state + " ssid=" + ssid
                        + " supplicant=" + (wifi == null ? "?"
                        : wifi.getConnectionInfo().getSupplicantState()));
            }
            lastState = state.hashCode();
            if (info != null && info.isConnected()) return;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new IOException("interrupted while waiting for Wi-Fi", e);
            }
        }
        WifiInfo wifiInfo = wifi == null ? null : wifi.getConnectionInfo();
        String detail = wifiInfo == null ? "no WifiInfo"
                : "ssid=" + wifiInfo.getSSID() + " state=" + wifiInfo.getSupplicantState();
        throw new IOException("Wi-Fi is not connected (" + detail + ")");
    }
}
