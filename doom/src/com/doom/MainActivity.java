package com.doom;

import android.os.Bundle;
import android.util.Log;

import com.hrv.common.ProbeActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Doom on the watch. Back exits; home exits via the probe base. */
public class MainActivity extends ProbeActivity {

    private static final String TAG = "doom";

    /** The WAD shipped in the APK: Freedoom Phase 1, four episodes. */
    private static final String BUNDLED_IWAD = "freedoom1.wad";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        hardKillOnPause();
        setContentView(new DoomView(this, wadPath()));
    }

    /**
     * A WAD directly on /sdcard wins if the engine can actually boot it (see
     * WadFile); otherwise the bundled Freedoom is unpacked once next to the app.
     * The check is what keeps a misnamed or half-copied WAD from reaching the
     * engine, whose I_Error path calls exit() and would kill the app silently.
     */
    private String wadPath() {
        File external = WadFile.findReadableIwad(new File("/sdcard"));
        if (external != null) {
            Log.i(TAG, "using IWAD from /sdcard: " + external);
            return external.getAbsolutePath();
        }
        Log.i(TAG, "no usable IWAD on /sdcard, falling back to " + BUNDLED_IWAD);
        File dst = new File(getFilesDir(), BUNDLED_IWAD);
        if (!dst.exists()) {
            unpack(BUNDLED_IWAD, dst);
        }
        return dst.getAbsolutePath();
    }

    /**
     * Copy the bundled WAD into the app dir — Doom needs a real file path and
     * chdir's to it for its config and savegames. Writes to a .part file and
     * renames, so a process kill mid-copy cannot leave a truncated IWAD behind
     * for the next launch to trip over.
     */
    private void unpack(String asset, File dst) {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".part");
        try (InputStream in = getAssets().open(asset);
             OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception e) {
            throw new RuntimeException("could not unpack " + asset, e);
        }
        if (!tmp.renameTo(dst)) {
            throw new RuntimeException("could not store " + dst);
        }
        Log.i(TAG, "unpacked " + asset + " to " + dst);
    }
}
