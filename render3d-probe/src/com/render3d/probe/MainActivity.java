package com.render3d.probe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.view.WindowManager;

/** 3D Render demo: software-rendered rotating spider (3drend algorithm). */
public class MainActivity extends Activity {
    private RenderView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        view = new RenderView(this);
        setContentView(view);
    }

    @Override
    protected void onPause() {
        super.onPause();
        finish();
        Process.killProcess(Process.myPid());
    }
}
