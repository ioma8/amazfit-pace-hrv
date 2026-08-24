package com.seismo.probe;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.WindowManager;

/** CPU fBm nebula that flows with the watch tilt; no shake reaction. */
public class MainActivity extends Activity implements SensorEventListener {
    private static final String TAG = "SeismoProbe";

    private SensorManager sensors;
    private NebulaView view;
    private boolean loggedFirst;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        view = new NebulaView(this);
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Sensor accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel != null) {
            sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
            Log.i(TAG, "accel registered");
        } else {
            Log.e(TAG, "no accelerometer");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        view.setFlow(event.values[0] / 9.81f, -event.values[1] / 9.81f,
                clamp(event.values[2] / 9.81f));
        if (!loggedFirst) {
            loggedFirst = true;
            float g = (float) Math.sqrt(event.values[0] * event.values[0]
                    + event.values[1] * event.values[1]
                    + event.values[2] * event.values[2]) / 9.81f;
            Log.i(TAG, "first sample g=" + g);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private static float clamp(float v) {
        return v < -1f ? -1f : v > 1f ? 1f : v;
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensors.unregisterListener(this);
        finish();
        Process.killProcess(Process.myPid());
    }
}
