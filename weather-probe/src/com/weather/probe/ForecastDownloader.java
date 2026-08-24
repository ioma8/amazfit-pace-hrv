package com.weather.probe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

public final class ForecastDownloader {
    public static final String URL_STRING = "https://www.foreca.com/103068799/Ostrava-Okres-Ostrava-m%C4%9Bsto-Czech-Republic/hourly?day=0";

    private ForecastDownloader() { }

    public static WeatherForecast download(Context context) throws Exception {
        ensureWifi(context);
        HttpURLConnection connection = (HttpURLConnection) new URL(URL_STRING).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept-Encoding", "gzip");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Amazfit Pace) OstravaWeather/1.0");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new Exception("Foreca HTTP " + status);
            InputStream input = connection.getInputStream();
            if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) input = new GZIPInputStream(input);
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            StringBuilder html = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) != -1) html.append(buffer, 0, count);
            reader.close();
            return ForecaParser.parse(html.toString(), System.currentTimeMillis());
        } finally {
            connection.disconnect();
        }
    }

    private static void ensureWifi(Context context) throws Exception {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null && !wifi.isWifiEnabled()) wifi.setWifiEnabled(true);
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        long deadline = System.currentTimeMillis() + 12000;
        while (System.currentTimeMillis() < deadline) {
            NetworkInfo info = manager == null ? null : manager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (info != null && info.isConnected()) return;
            Thread.sleep(500);
        }
        throw new Exception("Wi-Fi is not connected");
    }
}
