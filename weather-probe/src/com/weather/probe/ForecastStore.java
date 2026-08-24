package com.weather.probe;

import android.content.Context;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small atomic-ish line format so the forecast remains available offline. */
public final class ForecastStore {
    private static final String FILE = "ostrava-hourly.tsv";
    private static final String DAY = "DAY";
    private ForecastStore() { }

    public static void save(Context context, WeatherForecast today, WeatherForecast tomorrow) throws Exception {
        FileOutputStream out = context.openFileOutput(FILE, Context.MODE_PRIVATE);
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.println(today.fetchedAt);
        writeDay(writer, today);
        writer.println(DAY);
        writeDay(writer, tomorrow);
        writer.close();
    }

    private static void writeDay(PrintWriter writer, WeatherForecast day) {
        for (WeatherForecast.Entry e : day.entries) {
            writer.println(join(e.time, e.temperature, e.feelsLike, e.humidity,
                    e.precipitation, e.wind, e.condition, e.precipitationChance));
        }
    }

    /** @return [today, tomorrow] or null when nothing cached. */
    public static WeatherForecast[] load(Context context) {
        try {
            FileInputStream in = context.openFileInput(FILE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String first = reader.readLine();
            if (first == null) return null;
            long fetchedAt = Long.parseLong(first);
            List<WeatherForecast.Entry> rows = new ArrayList<WeatherForecast.Entry>();
            String line;
            while ((line = reader.readLine()) != null && !DAY.equals(line)) rows.add(parseLine(line));
            List<WeatherForecast.Entry> next = new ArrayList<WeatherForecast.Entry>();
            while ((line = reader.readLine()) != null && !DAY.equals(line)) next.add(parseLine(line));
            reader.close();
            if (rows.isEmpty() && next.isEmpty()) return null;
            return new WeatherForecast[]{
                    new WeatherForecast(fetchedAt, rows),
                    new WeatherForecast(fetchedAt, next)};
        } catch (Exception ignored) {
            return null;
        }
    }

    private static WeatherForecast.Entry parseLine(String line) {
        String[] p = line.split("\\t", -1);
        if (p.length != 8) return null;
        return new WeatherForecast.Entry(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]);
    }

    private static String join(String... values) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) result.append('\t');
            result.append(values[i].replace('\t', ' ').replace('\n', ' '));
        }
        return result.toString();
    }
}
