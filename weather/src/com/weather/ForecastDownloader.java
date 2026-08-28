package com.weather;

import android.content.Context;
import android.util.Log;

import com.hrv.common.Net;

public final class ForecastDownloader {
    private static final String TAG = "Weather";
    private static final String BASE =
            "https://www.foreca.com/103068799/Ostrava-Okres-Ostrava-m%C4%9Bsto-Czech-Republic/hourly?day=";
    private static final String UA = "Mozilla/5.0 (Android; Amazfit Pace) OstravaWeather/1.0";

    private ForecastDownloader() { }

    /** @return [today, tomorrow] hourly forecasts. */
    public static WeatherForecast[] download(Context context) throws Exception {
        Net.ensureWifi(context);
        WeatherForecast today = fetchDay(0);
        WeatherForecast tomorrow = fetchDay(1);
        return new WeatherForecast[]{today, tomorrow};
    }

    private static WeatherForecast fetchDay(int day) throws Exception {
        Log.i(TAG, "fetching day=" + day);
        String html = Net.getString(BASE + day, UA);
        Log.i(TAG, "read " + html.length() + " chars");
        WeatherForecast forecast = ForecaParser.parse(html, System.currentTimeMillis());
        Log.i(TAG, "parsed " + forecast.entries.size() + " hours");
        return forecast;
    }

}
