package com.weather.probe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

public final class ForecastDownloader {
    private static final String TAG = "WeatherProbe";
    private static final String BASE =
            "https://www.foreca.com/103068799/Ostrava-Okres-Ostrava-m%C4%9Bsto-Czech-Republic/hourly?day=";

    private ForecastDownloader() { }

    /** @return [today, tomorrow] hourly forecasts. */
    public static WeatherForecast[] download(Context context) throws Exception {
        ensureWifi(context);
        WeatherForecast today = fetchDay(0);
        WeatherForecast tomorrow = fetchDay(1);
        return new WeatherForecast[]{today, tomorrow};
    }

    private static WeatherForecast fetchDay(int day) throws Exception {
        Log.i(TAG, "fetching day=" + day);
        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + day).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "gzip");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Amazfit Pace) OstravaWeather/1.0");
        try {
            int status = connection.getResponseCode();
            Log.i(TAG, "HTTP " + status + " enc=" + connection.getContentEncoding()
                    + " len=" + connection.getContentLength());
            if (status < 200 || status >= 300) throw new Exception("Foreca HTTP " + status);
            InputStream input = connection.getInputStream();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) input = new GZIPInputStream(input);
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            StringBuilder html = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) html.append(buffer, 0, count);
            reader.close();
            Log.i(TAG, "read " + html.length() + " chars");
            WeatherForecast forecast = ForecaParser.parse(html.toString(), System.currentTimeMillis());
            Log.i(TAG, "parsed " + forecast.entries.size() + " hours");
            return forecast;
        } finally {
            connection.disconnect();
        }
    }

    private static void ensureWifi(Context context) throws Exception {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        boolean wasEnabled = wifi != null && wifi.isWifiEnabled();
        Log.i(TAG, "ensureWifi: enabled=" + wasEnabled);
        if (wifi != null && !wasEnabled) {
            Log.i(TAG, "ensureWifi: enabling");
            wifi.setWifiEnabled(true);
        }
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        long deadline = System.currentTimeMillis() + 30000;
        long lastState = -1;
        while (System.currentTimeMillis() < deadline) {
            NetworkInfo info = manager == null ? null : manager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            String ssid = wifi == null ? "?" : String.valueOf(wifi.getConnectionInfo().getSSID());
            String state = info == null ? "null" : info.getDetailedState() + "/connected=" + info.isConnected();
            if (state.hashCode() != lastState || !info.isConnected()) {
                Log.i(TAG, "ensureWifi poll: " + state + " ssid=" + ssid
                        + " supplicant=" + (wifi == null ? "?" : wifi.getConnectionInfo().getSupplicantState()));
            }
            lastState = state.hashCode();
            if (info != null && info.isConnected()) return;
            Thread.sleep(500);
        }
        WifiInfo info = wifi == null ? null : wifi.getConnectionInfo();
        String detail = info == null ? "no WifiInfo" : "ssid=" + info.getSSID()
                + " state=" + info.getSupplicantState();
        throw new Exception("Wi-Fi is not connected (" + detail + ")");
    }
}
