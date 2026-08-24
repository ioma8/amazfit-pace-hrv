package com.weather.probe;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;
    private TableLayout table;
    private Button refresh;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        WeatherForecast cached = ForecastStore.load(this);
        if (cached != null) showForecast(cached, "Saved forecast");
        else status.setText("No saved forecast. Connecting to Wi-Fi…");
        download();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(10, 6, 10, 6);
        root.setBackgroundColor(Color.rgb(18, 22, 26));

        TextView title = text("Ostrava hourly", 19, Color.WHITE);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, new LinearLayout.LayoutParams(-1, 32));
        status = text("Loading…", 11, Color.LTGRAY);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(status, new LinearLayout.LayoutParams(-1, 28));

        refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setTextSize(11);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View ignored) { download(); }
        });
        root.addView(refresh, new LinearLayout.LayoutParams(-1, 38));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        table = new TableLayout(this);
        table.setStretchAllColumns(false);
        scroll.addView(table, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void download() {
        refresh.setEnabled(false);
        status.setText("Connecting to Wi-Fi…");
        executor.submit(new Runnable() {
            @Override public void run() {
                try {
                    final WeatherForecast forecast = ForecastDownloader.download(MainActivity.this);
                    ForecastStore.save(MainActivity.this, forecast);
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showForecast(forecast, "Updated"); }
                    });
                } catch (final Exception error) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            refresh.setEnabled(true);
                            status.setText("Offline — showing saved data (" + error.getMessage() + ")");
                        }
                    });
                }
            }
        });
    }

    private void showForecast(WeatherForecast forecast, String prefix) {
        table.removeAllViews();
        addRow(new String[]{"Time", "Temp", "Feels", "Hum", "Rain", "Wind", "Condition"}, true);
        for (WeatherForecast.Entry entry : forecast.entries) {
            addRow(new String[]{entry.time, entry.temperature, entry.feelsLike,
                    entry.humidity, entry.precipitation, entry.wind,
                    entry.condition + " (" + entry.precipitationChance + ")"}, false);
        }
        status.setText(prefix + " " + DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(new Date(forecast.fetchedAt)) + " · " + forecast.entries.size() + " hours");
        refresh.setEnabled(true);
    }

    private void addRow(String[] values, boolean header) {
        TableRow row = new TableRow(this);
        row.setPadding(0, 1, 0, 1);
        for (String value : values) {
            TextView cell = text(value, header ? 10 : 10, header ? Color.WHITE : Color.rgb(225, 230, 232));
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(4, 0, 4, 0);
            if (header) cell.setBackgroundColor(Color.rgb(55, 75, 82));
            int width = value.equals("Condition") || (!header && values.length == 7 && value.equals(values[6])) ? 122 : 43;
            row.addView(cell, new TableRow.LayoutParams(width, header ? 28 : 33));
        }
        table.addView(row);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
}
