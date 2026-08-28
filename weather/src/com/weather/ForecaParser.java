package com.weather;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the JSON forecast array Foreca embeds in the server-rendered hourly page:
 * renderHourly({ ..., data: [{"time":...,"temp":..,"flike":..,"rhum":..,"rain":..,
 *                             "windskmh":..,"wx":..,"rainp":..,"h24":..}, ...] });
 */
public final class ForecaParser {
    private static final Pattern DATA = Pattern.compile(
            "data:\\s*(\\[[\\s\\S]*?\\])\\s*,", Pattern.CASE_INSENSITIVE);

    private ForecaParser() { }

    public static WeatherForecast parse(String html, long fetchedAt) {
        if (html == null || html.length() == 0) throw new IllegalArgumentException("empty response");
        Matcher m = DATA.matcher(html);
        if (!m.find()) throw new IllegalArgumentException("no hourly forecast data in page");
        try {
            JSONArray array = new JSONArray(m.group(1));
            List<WeatherForecast.Entry> rows = new ArrayList<WeatherForecast.Entry>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                rows.add(new WeatherForecast.Entry(
                        o.optString("h24", hourOf(o.optString("time", ""))),
                        signed(o.optDouble("temp", 0)),
                        signed(o.optDouble("flike", 0)),
                        (int) Math.round(o.optDouble("rhum", 0)) + "%",
                        formatRain(o.optDouble("rain", 0)),
                        (int) Math.round(o.optDouble("windskmh", 0)) + " km/h",
                        o.optString("wx", "--"),
                        (int) Math.round(o.optDouble("rainp", 0)) + "%"));
            }
            if (rows.isEmpty()) throw new IllegalArgumentException("no hourly forecast rows");
            return new WeatherForecast(fetchedAt, rows);
        } catch (JSONException e) {
            throw new IllegalArgumentException("bad forecast json: " + e.getMessage());
        }
    }

    private static String hourOf(String iso) {
        return iso.length() >= 13 ? iso.substring(11, 13) : "--";
    }

    private static String signed(double value) {
        int i = (int) Math.round(value);
        return i >= 0 ? "+" + i : String.valueOf(i);
    }

    private static String formatRain(double value) {
        return (value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value)) + " mm";
    }
}
