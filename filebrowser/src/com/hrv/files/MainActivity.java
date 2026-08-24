package com.hrv.files;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

import java.io.File;

public class MainActivity extends Activity {
    private FilesView files;
    private TextViewer reader;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        files = new FilesView(this, FilesView.pickRoot());
        files.setListener(new FilesView.Listener() {
            @Override public void onOpenFile(File f) { openReader(f); }
        });
        setContentView(files);
    }

    void openReader(File f) {
        reader = new TextViewer(this);
        reader.setListener(new TextViewer.Listener() {
            @Override public void onClose() { closeReader(); }
        });
        setContentView(reader);
        reader.load(f);
    }

    void closeReader() {
        reader = null;
        setContentView(files);
    }

    @Override public void onBackPressed() {
        if (reader != null) closeReader();
        else if (!files.up()) finish();
    }
}
