package com.weather.probe;

import java.io.BufferedReader;
import java.io.FileReader;

public final class ForecaParserTest {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            BufferedReader reader = new BufferedReader(new FileReader(args[0]));
            StringBuilder html = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) html.append(line);
            reader.close();
            WeatherForecast live = ForecaParser.parse(html.toString(), 0L);
            System.out.println("live rows: " + live.entries.size());
            for (WeatherForecast.Entry e : live.entries) {
                System.out.println(e.time + " " + e.temperature + " " + e.feelsLike + " "
                        + e.humidity + " " + e.precipitation + " " + e.wind + " "
                        + e.condition + " " + e.precipitationChance);
            }
            return;
        }
        String html = "renderHourly({\n  target: '#hourly-component',\n  language: 'en',\n  data: ["
                + "{\"time\":\"2026-08-24T19:00\",\"temp\":19,\"flike\":18,\"rhum\":45,\"rain\":0,"
                + "\"windskmh\":7,\"wx\":\"Mostly clear\",\"rainp\":2,\"h24\":\"19\"},"
                + "{\"time\":\"2026-08-24T20:00\",\"temp\":17,\"flike\":16,\"rhum\":51,\"rain\":0.3,"
                + "\"windskmh\":11,\"wx\":\"Mostly clear\",\"rainp\":10,\"h24\":\"20\"}],\n});";
        WeatherForecast forecast = ForecaParser.parse(html, 123L);
        if (forecast.entries.size() != 2) throw new AssertionError("row count");
        WeatherForecast.Entry e = forecast.entries.get(0);
        check("19", e.time); check("+19", e.temperature); check("+18", e.feelsLike);
        check("45%", e.humidity); check("0 mm", e.precipitation); check("7 km/h", e.wind);
        check("Mostly clear", e.condition); check("2%", e.precipitationChance);
        check("0.3 mm", forecast.entries.get(1).precipitation);
        check("+17", forecast.entries.get(1).temperature);
        System.out.println("ForecaParser checks passed");
    }

    private static void check(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
}
