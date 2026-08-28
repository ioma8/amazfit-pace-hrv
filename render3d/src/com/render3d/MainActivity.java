package com.render3d;

import android.os.Bundle;

import com.hrv.common.ProbeActivity;

/** 3D Render demo: software-rendered rotating spider (3drend algorithm). */
public class MainActivity extends ProbeActivity {
    private RenderView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hardKillOnPause();
        setUrgentAudioPriority();
        view = new RenderView(this);
        setContentView(view);
        saveAndMaxBrightness();
    }
}
