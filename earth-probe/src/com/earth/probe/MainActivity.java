package com.earth.probe;

import android.app.Activity;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.provider.Settings;
import android.view.WindowManager;

/** Spinning Earth with a live day/night terminator driven by the real sun
 *  position at the current location (GPS fix, fallback: Ostrava). */
public class MainActivity extends Activity {
    static final double DEFAULT_LAT = 49.82;
    static final double DEFAULT_LON = 18.26;

    private EarthView view;
    private LocationManager lm;
    private Handler h = new Handler();
    private int savedBrightness = -1;
    private int savedMode = -1;
    private boolean brightnessOk = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        view = new EarthView(this);
        setContentView(view);
        saveAndMaxBrightness();
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location last = null;
        try {
            last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (SecurityException ignored) {
        }
        if (last != null) {
            apply(last);
        } else {
            view.setLocation(DEFAULT_LAT, DEFAULT_LON, false);
            requestFix();
        }
    }

    /** Remember the user's brightness setting, then force maximum. */
    private void saveAndMaxBrightness() {
        try {
            savedBrightness = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);
            savedMode = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 255);
            brightnessOk = true;
        } catch (SecurityException e) {
            brightnessOk = false;
        }
    }

    /** Put the user's brightness back (these apps kill themselves on pause). */
    private void restoreBrightness() {
        if (!brightnessOk) {
            return;
        }
        try {
            if (savedMode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    && savedBrightness >= 0) {
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, savedBrightness);
            }
            Settings.System.putInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, savedMode);
        } catch (SecurityException ignored) {
        }
    }

    private void requestFix() {
        final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                try {
                    lm.removeUpdates(listener);
                } catch (SecurityException ignored) {
                }
            }
        };
        try {
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener,
                    getMainLooper());
            h.postDelayed(timeout, 25000);
        } catch (SecurityException e) {
            view.setLocation(DEFAULT_LAT, DEFAULT_LON, false);
        }
    }

    private final LocationListener listener = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            h.removeCallbacksAndMessages(null);
            apply(l);
        }

        @Override
        public void onStatusChanged(String p, int s, android.os.Bundle b) {
        }

        @Override
        public void onProviderEnabled(String p) {
        }

        @Override
        public void onProviderDisabled(String p) {
        }
    };

    private void apply(Location l) {
        view.setLocation(l.getLatitude(), l.getLongitude(), true);
    }

    @Override
    protected void onPause() {
        restoreBrightness();
        super.onPause();
        finish();
        Process.killProcess(Process.myPid());
    }
}
