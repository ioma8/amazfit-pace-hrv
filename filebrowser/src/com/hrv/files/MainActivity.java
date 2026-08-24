package com.hrv.files;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

import java.io.File;

public class MainActivity extends Activity {
    private FilesView files;
    private TextViewer reader;
    private ImageViewer image;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        files = new FilesView(this, FilesView.pickRoot());
        files.setListener(new FilesView.Listener() {
            @Override public void onOpenFile(File f) {
                if (FilesView.isImageFile(f)) openImage(f);
                else openReader(f);
            }
        });
        setContentView(files);
    }

    void openReader(File f) {
        reader = new TextViewer(this);
        reader.setListener(new TextViewer.Listener() {
            @Override public void onClose() { closeOverlay(); }
        });
        setContentView(reader);
        reader.load(f);
    }

    void openImage(File f) {
        image = new ImageViewer(this);
        image.setListener(new ImageViewer.Listener() {
            @Override public void onClose() { closeOverlay(); }
        });
        setContentView(image);
        image.load(f);
    }

    void closeOverlay() {
        reader = null;
        image = null;
        setContentView(files);
    }

    @Override public void onBackPressed() {
        if (reader != null || image != null) closeOverlay();
        else if (!files.up()) finish();
    }
}
