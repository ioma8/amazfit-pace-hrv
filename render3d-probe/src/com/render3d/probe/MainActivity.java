package com.render3d.probe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.WindowManager;

/** 3D Render demo: software-rendered rotating spider (3drend algorithm). */
public class MainActivity extends Activity {
    private RenderView view;
    private int savedBrightness = -1;
    private int savedMode = -1;
    private boolean brightnessOk = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        view = new RenderView(this);
        setContentView(view);
        saveAndMaxBrightness();
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

    @Override
    protected void onPause() {
        restoreBrightness();
        super.onPause();
        finish();
        Process.killProcess(Process.myPid());
    }
}
