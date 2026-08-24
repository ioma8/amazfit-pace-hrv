package com.hrv.files;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

public class MainActivity extends Activity {
    private FilesView view;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new FilesView(this, FilesView.pickRoot());
        setContentView(view);
    }

    @Override public void onBackPressed() {
        if (!view.up()) finish();
    }
}
