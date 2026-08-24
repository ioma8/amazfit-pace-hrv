package com.weather.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the server-rendered hourly cards from Foreca's current-day page. */
public final class ForecaParser {
    private static final Pattern HOUR = Pattern.compile(
            "<div\\s+class=\\\"hour\\\"[^>]*>(.*?)</div>\\s*</div>\\s*</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private ForecaParser() { }

    public static WeatherForecast parse(String html, long fetchedAt) {
        if (html == null || html.length() == 0) throw new IllegalArgumentException("empty response");
        List<WeatherForecast.Entry> result = new ArrayList<WeatherForecast.Entry>();
        Matcher hours = HOUR.matcher(html);
        while (hours.find()) {
            String card = hours.group(1);
            result.add(new WeatherForecast.Entry(
                    value(card, "time_24h", "--"),
                    signed(value(card, "temp_c", "--")),
                    signed(feelsLike(card)),
                    value(card, "humidity", "--").replace("%", "").trim() + "%",
                    value(card, "rain_mm", "--"),
                    value(card, "wind_kmh", "--") + " km/h",
                    value(card, "symbolText", "--"),
                    value(card, "precipChance", "--")));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("no hourly forecast rows");
        return new WeatherForecast(fetchedAt, result);
    }

    private static String feelsLike(String html) {
        Matcher container = Pattern.compile(
                "class=\"[^\"]*feelsLike[^\"]*\"[^>]*>(.*?)</div>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        if (!container.find()) return "--";
        return value(container.group(1), "temp_c", "--");
    }

    private static String value(String html, String className, String fallback) {
        Pattern p = Pattern.compile("class=\\\"[^\\\"]*" + className
                + "[^\\\"]*\\\"[^>]*>(.*?)</(?:span|div)>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(html);
        if (!m.find()) return fallback;
        String text = m.group(1).replaceAll("<[^>]+>", " ");
        text = decode(text).replace('\u00a0', ' ').trim().replaceAll("\\s+", " ");
        return text.length() == 0 ? fallback : text;
    }

    private static String signed(String text) {
        return text.startsWith("+") || text.startsWith("-") || "--".equals(text) ? text : "+" + text;
    }

    private static String decode(String text) {
        return text.replace("&nbsp;", " ").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&")
                .replace("&#39;", "'").replace("&quot;", "\"");
    }
}
