package com.sunface.probe;

import android.app.Activity;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.view.WindowManager;

/** Sun times + moon phase watch face. Uses the last GPS fix for location
 *  (fallback: Ostrava 49.82N 18.26E); redraws every 30 s. */
public class MainActivity extends Activity {
    static final double DEFAULT_LAT = 49.82;
    static final double DEFAULT_LON = 18.26;

    private SunFaceView view;
    private LocationManager lm;
    private Handler h = new Handler();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new SunFaceView(this);
        setContentView(view);
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
        super.onPause();
        finish();
        Process.killProcess(Process.myPid());
    }
}
