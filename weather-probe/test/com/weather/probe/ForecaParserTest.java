package com.weather.probe;

public final class ForecaParserTest {
    public static void main(String[] args) {
        String html = "<div class=\"hourContainer\">"
                + "<div class=\"hour\"><div class=\"row\"><div class=\"time\">"
                + "<span class=\"value time time_24h\">19</span></div>"
                + "<span class=\"value temp temp_c warm\">+19</span>"
                + "<div class=\"feelsLike\"><span class=\"value temp temp_c\">+18</span></div>"
                + "<span class=\"humidity\">45<span class=\"unit\">%</span></span>"
                + "<span class=\"value rain rain_mm\"><span>0 mm</span></span>"
                + "<span class=\"value wind wind_kmh\">7</span></div></div>"
                + "<div class=\"textRow\"><div class=\"symbolText\">Mostly clear</div></div>"
                + "<span class=\"precipChance\">Precip chance &lt; 10%</span></div></div></div></div>";
        WeatherForecast forecast = ForecaParser.parse(html, 123L);
        if (forecast.entries.size() != 1) throw new AssertionError("row count");
        WeatherForecast.Entry e = forecast.entries.get(0);
        check("19", e.time); check("+19", e.temperature); check("+18", e.feelsLike);
        check("45%", e.humidity); check("0 mm", e.precipitation); check("7 km/h", e.wind);
        check("Mostly clear", e.condition); check("Precip chance < 10%", e.precipitationChance);
        System.out.println("ForecaParser checks passed");
    }
    private static void check(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
}
