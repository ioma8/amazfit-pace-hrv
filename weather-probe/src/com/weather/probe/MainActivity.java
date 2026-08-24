package com.weather.probe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        WeatherForecast[] cached = ForecastStore.load(this);
        if (cached != null) showForecast(cached[0], cached[1], "Saved forecast");
        else status.setText("No saved forecast. Connecting to Wi-Fi…");
        download();
    }

    @Override
    protected void onPause() {
        super.onPause();
        WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (wifi != null && wifi.isWifiEnabled()) {
            android.util.Log.i("WeatherProbe", "paused: disabling wifi");
            wifi.setWifiEnabled(false);
        }
        finish();
        Process.killProcess(Process.myPid());
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
                    final WeatherForecast[] forecast = ForecastDownloader.download(MainActivity.this);
                    ForecastStore.save(MainActivity.this, forecast[0], forecast[1]);
                    android.util.Log.i("WeatherProbe", "download ok: " + forecast[0].entries.size()
                            + "+" + forecast[1].entries.size() + " hours");
                    runOnUiThread(new Runnable() {
                        @Override public void run() { showForecast(forecast[0], forecast[1], "Updated"); }
                    });
                } catch (final Exception error) {
                    android.util.Log.e("WeatherProbe", "download failed", error);
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

    private void showForecast(WeatherForecast today, WeatherForecast tomorrow, String prefix) {
        table.removeAllViews();
        addRow(new String[]{"Time", "Temp", "Wind", "Rain%", "Condition"}, true);
        for (WeatherForecast.Entry e : today.entries) addRow(row(e), false);
        addSeparator("Tomorrow");
        for (WeatherForecast.Entry e : tomorrow.entries) addRow(row(e), false);
        status.setText(prefix + " " + DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(new Date(today.fetchedAt)) + " · " + today.entries.size()
                + "+" + tomorrow.entries.size() + " h");
        refresh.setEnabled(true);
    }

    private String[] row(WeatherForecast.Entry e) {
        return new String[]{e.time, e.temperature, e.wind, e.precipitationChance, e.condition};
    }

    private void addSeparator(String label) {
        TextView sep = text(label, 10, Color.WHITE);
        sep.setGravity(Gravity.CENTER_HORIZONTAL);
        sep.setBackgroundColor(Color.rgb(55, 75, 82));
        table.addView(sep, new TableLayout.LayoutParams(-1, 24));
    }

    private void addRow(String[] values, boolean header) {
        TableRow row = new TableRow(this);
        row.setPadding(0, 1, 0, 1);
        int[] widths = new int[]{40, 40, 50, 50, 120};
        for (int i = 0; i < values.length; i++) {
            TextView cell = text(values[i], 10, header ? Color.WHITE : Color.rgb(225, 230, 232));
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(4, 0, 4, 0);
            if (header) cell.setBackgroundColor(Color.rgb(55, 75, 82));
            row.addView(cell, new TableRow.LayoutParams(widths[i], header ? 28 : 33));
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
