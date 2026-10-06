package com.doom;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Host checks for WadFile: the WAD we ship is bootable, and the cases that used
 * to reach the engine and kill the process are rejected before it sees them.
 *
 *   javac -d /tmp/x doom/src/com/doom/WadFile.java doom/test/com/doom/WadFileTest.java
 *   java -cp /tmp/x com.doom.WadFileTest doom/assets/freedoom1.wad
 */
public final class WadFileTest {

    private static int checks = 0;
    private static File root;

    public static void main(String[] args) throws IOException {
        File shipped = new File(args.length > 0 ? args[0] : "doom/assets/freedoom1.wad");

        // The WAD the APK ships must pass the same check the app applies to an
        // /sdcard override — this is the end-to-end fact, on the real file.
        check("shipped freedoom1.wad is bootable as Doom 1",
                WadFile.isBootable(shipped, "E1M1"));
        check("shipped freedoom1.wad has no MAP01 lump",
                !WadFile.isBootable(shipped, "MAP01"));

        // Name/content mismatch — the /sdcard/doom2.wad trap. The engine picks
        // the mission from the name, then aborts because MAP01 is missing.
        File a = dir("mismatch");
        writeWad(new File(a, "doom2.wad"), new String[]{"E1M1", "THINGS"});
        check("Doom 1 lumps named doom2.wad are rejected",
                WadFile.findReadableIwad(a) == null);

        // The same lumps under a name that matches them are accepted.
        File b = dir("accept");
        File good = new File(b, "doom2.wad");
        writeWad(good, new String[]{"MAP01", "THINGS"});
        check("Doom 2 lumps named doom2.wad are accepted",
                good.equals(WadFile.findReadableIwad(b)));

        // Case-insensitive: the engine compares with strcasecmp, and the file
        // may come off case-insensitive removable storage.
        File c = dir("case");
        File upper = new File(c, "DOOM1.WAD");
        writeWad(upper, new String[]{"E1M1"});
        check("DOOM1.WAD is found despite the case",
                upper.equals(WadFile.findReadableIwad(c)));

        // Junk must be rejected without crashing the search.
        File d = dir("junk");
        writeWad(new File(d, "doom.wad"), new String[]{"THINGS"});
        writeBytes(new File(d, "freedoom1.wad"), new byte[]{'I', 'W', 'A', 'D'});
        check("a WAD without any map lump is rejected",
                WadFile.findReadableIwad(d) == null);

        File e = dir("garbage");
        writeBytes(new File(e, "doom1.wad"), "not a wad at all".getBytes("US-ASCII"));
        check("a non-IWAD signature is rejected",
                !WadFile.isBootable(new File(e, "doom1.wad"), "E1M1"));
        check("garbage in the directory yields null",
                WadFile.findReadableIwad(e) == null);

        // A header claiming more lumps than the file can hold must not be trusted.
        File f = dir("lying");
        byte[] lying = wadBytes(new String[]{"E1M1"});
        lying[4] = (byte) 0xff; // numlumps = 255 in a 28 byte file
        writeBytes(new File(f, "doom1.wad"), lying);
        check("an oversized lump count is rejected",
                !WadFile.isBootable(new File(f, "doom1.wad"), "E1M1"));

        check("a missing directory yields null",
                WadFile.findReadableIwad(new File(root, "nope")) == null);

        deleteTree(root);
        System.out.println("WadFileTest checks passed (" + checks + ")");
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            throw new AssertionError("FAILED: " + what);
        }
    }

    private static File dir(String name) throws IOException {
        if (root == null) {
            root = File.createTempFile("wadtest", "");
            if (!root.delete() || !root.mkdirs()) {
                throw new IOException("could not create " + root);
            }
        }
        File d = new File(root, name);
        if (!d.mkdirs()) {
            throw new IOException("could not create " + d);
        }
        return d;
    }

    private static void writeWad(File f, String[] lumps) throws IOException {
        writeBytes(f, wadBytes(lumps));
    }

    /** Minimal IWAD: 12 byte header + `lumps` by 16 byte directory entries. */
    private static byte[] wadBytes(String[] lumps) throws IOException {
        byte[] out = new byte[12 + lumps.length * 16];
        out[0] = 'I';
        out[1] = 'W';
        out[2] = 'A';
        out[3] = 'D';
        put32(out, 4, lumps.length);
        put32(out, 8, 12);
        for (int i = 0; i < lumps.length; i++) {
            byte[] name = lumps[i].getBytes("US-ASCII");
            System.arraycopy(name, 0, out, 12 + i * 16 + 8, Math.min(8, name.length));
        }
        return out;
    }

    private static void put32(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
        b[at + 2] = (byte) (v >> 16);
        b[at + 3] = (byte) (v >> 24);
    }

    private static void writeBytes(File f, byte[] b) throws IOException {
        OutputStream out = new FileOutputStream(f);
        try {
            out.write(b);
        } finally {
            out.close();
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File kid : kids) {
                deleteTree(kid);
            }
        }
        f.delete();
    }
}
