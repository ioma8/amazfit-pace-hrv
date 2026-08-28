package com.hrv.common;

import java.io.File;
import java.io.RandomAccessFile;

/** Host test for WavWriter: header layout, mid-recording header refresh,
 *  and PCM round-trip. Run: cd common && javac --release 8 -d /tmp/cw
 *  src/com/hrv/common/WavWriter.java test/com/hrv/common/WavWriterTest.java
 *  && java -cp /tmp/cw com.hrv.common.WavWriterTest */
public class WavWriterTest {
    public static void main(String[] args) throws Exception {
        File f = File.createTempFile("wavwriter", ".wav");
        f.deleteOnExit();
        short[] c1 = {0, 1, -1, 32767, -32768};
        short[] c2 = {100, -100, 200, -200, 300};
        short[] c3 = {32767, -32768, 0, 5, -5};

        WavWriter w = new WavWriter(f.getAbsolutePath(), 16000);
        w.write(c1, 5);
        w.refreshHeader();
        // mid-recording header must already be valid (interrupted-recording case)
        RandomAccessFile mid = new RandomAccessFile(f, "r");
        byte[] mh = new byte[44];
        mid.readFully(mh);
        check(leInt(mh, 40) == 10, "mid data size = 10");
        mid.close();
        w.write(c2, 5);
        w.write(c3, 5);
        w.close();

        RandomAccessFile raf = new RandomAccessFile(f, "r");
        byte[] hdr = new byte[44];
        raf.readFully(hdr);
        check(ascii(hdr, 0, 4).equals("RIFF"), "RIFF magic");
        check(leInt(hdr, 4) == 36 + 30, "RIFF size = 36 + data");
        check(ascii(hdr, 8, 4).equals("WAVE"), "WAVE magic");
        check(ascii(hdr, 12, 4).equals("fmt "), "fmt magic");
        check(leInt(hdr, 16) == 16, "fmt chunk size");
        check(leShort(hdr, 20) == 1, "PCM format");
        check(leShort(hdr, 22) == 1, "mono");
        check(leInt(hdr, 24) == 16000, "sample rate");
        check(leInt(hdr, 28) == 32000, "byte rate");
        check(leShort(hdr, 32) == 2, "block align");
        check(leShort(hdr, 34) == 16, "bits per sample");
        check(ascii(hdr, 36, 4).equals("data"), "data magic");
        check(leInt(hdr, 40) == 30, "data size = 15 samples * 2");

        byte[] pcm = new byte[30];
        raf.readFully(pcm);
        check(raf.length() == 44 + 30, "file length = header + data");
        short[] expect = {0, 1, -1, 32767, -32768, 100, -100, 200, -200, 300, 32767, -32768, 0, 5, -5};
        for (int i = 0; i < expect.length; i++) {
            int got = (pcm[i * 2] & 0xff) | (pcm[i * 2 + 1] << 8);
            check(got == expect[i], "sample " + i + " = " + expect[i]);
        }
        raf.close();
        System.out.println("WavWriterTest PASS");
    }

    private static String ascii(byte[] b, int off, int len) {
        return new String(b, off, len, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int leInt(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static int leShort(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAIL: " + what);
    }
}
