package com.weather;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One cached current-day forecast and its hourly values. */
public final class WeatherForecast {
    public static final class Entry {
        public final String time;
        public final String temperature;
        public final String feelsLike;
        public final String humidity;
        public final String precipitation;
        public final String wind;
        public final String condition;
        public final String precipitationChance;

        public Entry(String time, String temperature, String feelsLike,
                     String humidity, String precipitation, String wind,
                     String condition, String precipitationChance) {
            this.time = time;
            this.temperature = temperature;
            this.feelsLike = feelsLike;
            this.humidity = humidity;
            this.precipitation = precipitation;
            this.wind = wind;
            this.condition = condition;
            this.precipitationChance = precipitationChance;
        }
    }

    public final long fetchedAt;
    public final List<Entry> entries;

    public WeatherForecast(long fetchedAt, List<Entry> entries) {
        this.fetchedAt = fetchedAt;
        this.entries = Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }
}
