package com.radar.probe;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.util.Log;
import android.view.WindowManager;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CZ radar on a map (terrain + underlay + borders, same stack as the CHMU page).
 * Shows the latest frame; tap to loop the last 2 hours of frames (~400 ms/frame),
 * tap again for the latest still. Frames composited at 340x230 to fit ~24 bitmaps
 * in memory on the MIPS core.
 */
public class MainActivity extends Activity {
    private static final String TAG = "RadarProbe";
    private static final int FRAMES = 24;
    private static final int FRAME_MS = 100;
    private static final int W = 340;
    private static final int H = 230;
    private static final String LISTING_URL =
            "https://opendata.chmi.cz/meteorology/weather/radar/composite/maxz/png/";
    private static final Pattern PNG_NAME = Pattern.compile(
            "pacz2gmaps3\\.z_max3d\\.(\\d{8})\\.(\\d{4})\\.0\\.png");
    private static final String[] LAYERS = {
            "oro.jpg", "https://produkty.chmi.cz/radar/und/pacz2gmaps9.oro_col2sharp40.jpg", "4",
            "und.png", "https://produkty.chmi.cz/radar/und/pacz2gmaps9.und3.png", "4",
            "borders.png", "https://produkty.chmi.cz/radar/und/pacz2gmaps6.und.015.hranice2px_4b.png", "8",
    };
    private static final String CACHE = "radar.png";

    private final Handler handler = new Handler();
    private final List<Bitmap> frames = new ArrayList<Bitmap>();
    private ImageView image;
    private TextView status;
    private Button refresh;
    private Bitmap latest;
    private int frameIndex;
    private boolean animating;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!frames.isEmpty()) {
                image.setImageBitmap(frames.get(frameIndex));
                frameIndex = (frameIndex + 1) % frames.size();
            }
            handler.postDelayed(this, FRAME_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(4, 4, 4, 4);
        root.setBackgroundColor(Color.rgb(18, 22, 26));

        status = new TextView(this);
        status.setTextSize(11);
        status.setTextColor(Color.LTGRAY);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(status, new LinearLayout.LayoutParams(-1, 26));

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.BLACK);
        image.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
        root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));

        refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setTextSize(11);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { load(); }
        });
        root.addView(refresh, new LinearLayout.LayoutParams(-1, 36));
        setContentView(root);

        Bitmap cached = readCache();
        if (cached != null) {
            latest = cached;
            image.setImageBitmap(cached);
            status.setText("Cached radar · tap to animate");
        } else {
            status.setText("Connecting to Wi-Fi…");
        }
        load();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAnimation();
        finish();
        Process.killProcess(Process.myPid());
    }

    private void toggle() {
        if (frames.size() < 2) return;
        if (animating) stopAnimation();
        else startAnimation();
    }

    private void startAnimation() {
        animating = true;
        frameIndex = 0;
        status.setText("Animating · tap for latest");
        handler.post(tick);
    }

    private void stopAnimation() {
        animating = false;
        handler.removeCallbacks(tick);
        if (latest != null) image.setImageBitmap(latest);
        status.setText("Updated " + DateFormat.getTimeInstance(DateFormat.SHORT)
                .format(new Date(latestTime)) + " · " + frames.size() + " frames · tap to animate");
    }

    private long latestTime;

    private void load() {
        refresh.setEnabled(false);
        if (animating) stopAnimation();
        status.setText("Fetching radar…");
        final long started = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ensureWifi(MainActivity.this);
                    final Bitmap bg = background();
                    List<String> names = lastFrames();
                    Log.i(TAG, "frames=" + names.size());
                    frames.clear();
                    Bitmap last = null;
                    long lastStamp = -1;
                    for (String name : names) {
                        byte[] bytes = download(LISTING_URL + name);
                        Bitmap frame = frameBitmap(bg, bytes, labelOf(name));
                        if (frame != null) frames.add(frame);
                        long stamp = Long.parseLong(name.substring(20, 28))
                                * 10000L + Long.parseLong(name.substring(29, 33));
                        if (stamp > lastStamp) {
                            lastStamp = stamp;
                            last = frame;
                        }
                    }
                    if (frames.isEmpty()) throw new Exception("no frames decoded");
                    latest = last != null ? last : frames.get(frames.size() - 1);
                    latestTime = started;
                    writeCache(latest);
                    final int count = frames.size();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            image.setImageBitmap(latest);
                            status.setText("Updated " + DateFormat.getTimeInstance(DateFormat.SHORT)
                                    .format(new Date(started)) + " · " + count
                                    + " frames · tap to animate");
                            refresh.setEnabled(true);
                        }
                    });
                } catch (final Exception error) {
                    Log.e(TAG, "download failed", error);
                    final Bitmap cached = readCache();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (cached != null) image.setImageBitmap(cached);
                            status.setText("Offline — showing cached radar (" + error.getMessage() + ")");
                            refresh.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    private Bitmap background() throws Exception {
        Bitmap oro = layer(0);
        Bitmap und = layer(1);
        Bitmap borders = layer(2);
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (oro != null) canvas.drawBitmap(oro, 0, 0, paint);
        if (und != null) canvas.drawBitmap(und, 0, 0, paint);
        if (borders != null) canvas.drawBitmap(borders, 0, 0, paint);
        return out;
    }

    /** Frame UTC time (HHMM from filename) converted to local, e.g. "21:30". */
    private static String labelOf(String name) {
        String time = name.substring(29, 33);
        int utc = Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(2, 4));
        int offset = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        int local = (utc + offset + 1440) % 1440;
        return (local / 60 < 10 ? "0" : "") + (local / 60) + ":"
                + (local % 60 < 10 ? "0" : "") + (local % 60);
    }

    private Bitmap frameBitmap(Bitmap bg, byte[] radarBytes, String label) {
        Bitmap radar = decode(radarBytes, 2);
        if (radar == null) return null;
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(bg, 0, 0, paint);
        canvas.drawBitmap(radar, 0, 0, paint);
        // frame time, bottom-right, on a dark chip
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(22);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        android.graphics.Rect bounds = new android.graphics.Rect();
        textPaint.getTextBounds(label, 0, label.length(), bounds);
        int pad = 6;
        float baseline = H - 12f;
        canvas.drawRect(W - 12 - bounds.width() - 2 * pad, baseline + bounds.top - pad,
                W - 6, baseline + pad, paintDark());
        canvas.drawText(label, W - 12 - bounds.width() - pad, baseline, textPaint);
        return out;
    }

    private static Paint paintDark() {
        Paint p = new Paint();
        p.setColor(Color.argb(170, 0, 0, 0));
        return p;
    }

    /** Fetch a static map layer once, then serve from cache. */
    private Bitmap layer(int index) throws Exception {
        String file = LAYERS[index * 3];
        String url = LAYERS[index * 3 + 1];
        int sample = Integer.parseInt(LAYERS[index * 3 + 2]);
        byte[] bytes = readFile(file);
        if (bytes == null) {
            bytes = download(url);
            writeFile(file, bytes);
            Log.i(TAG, "layer " + file + " " + bytes.length + "B");
        }
        return decode(bytes, sample);
    }

    private Bitmap decode(byte[] bytes, int sample) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    /** @return the newest FRAMES filenames, oldest first. */
    private List<String> lastFrames() throws Exception {
        HttpURLConnection connection = open(LISTING_URL);
        try {
            int http = connection.getResponseCode();
            if (http < 200 || http >= 300) throw new Exception("listing HTTP " + http);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), "UTF-8"));
            List<String> all = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = PNG_NAME.matcher(line);
                while (m.find()) all.add(m.group(0));
            }
            reader.close();
            if (all.isEmpty()) throw new Exception("no radar frames in listing");
            int from = Math.max(0, all.size() - FRAMES);
            return all.subList(from, all.size());
        } finally {
            connection.disconnect();
        }
    }

    private byte[] download(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try {
            int http = connection.getResponseCode();
            if (http < 200 || http >= 300) throw new Exception("HTTP " + http);
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) != -1) buffer.write(chunk, 0, count);
            input.close();
            return buffer.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Amazfit Pace) Radar/1.0");
        return connection;
    }

    private void writeCache(Bitmap bitmap) throws Exception {
        FileOutputStream out = openFileOutput(CACHE, Context.MODE_PRIVATE);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
    }

    private Bitmap readCache() {
        try {
            FileInputStream in = openFileInput(CACHE);
            byte[] bytes = new byte[(int) new File(getFilesDir(), CACHE).length()];
            in.read(bytes);
            in.close();
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeFile(String name, byte[] bytes) throws Exception {
        FileOutputStream out = openFileOutput(name, Context.MODE_PRIVATE);
        out.write(bytes);
        out.close();
    }

    private byte[] readFile(String name) {
        try {
            FileInputStream in = openFileInput(name);
            byte[] bytes = new byte[(int) new File(getFilesDir(), name).length()];
            in.read(bytes);
            in.close();
            return bytes;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void ensureWifi(Context context) throws Exception {
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifi != null && !wifi.isWifiEnabled()) {
            Log.i(TAG, "enabling wifi");
            wifi.setWifiEnabled(true);
        }
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        long deadline = System.currentTimeMillis() + 30000;
        while (System.currentTimeMillis() < deadline) {
            NetworkInfo info = manager == null ? null : manager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            if (info != null && info.isConnected()) return;
            Thread.sleep(500);
        }
        throw new Exception("Wi-Fi is not connected");
    }
}
