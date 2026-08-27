package com.hrv.mic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/** 16 kHz mono PCM16 WAV writer for mic-probe captures. */
public class WavWriter {
    public static void writeWav(String path, short[] pcm, int rate) throws Exception {
        int data = pcm.length * 2;
        ByteArrayOutputStream hdr = new ByteArrayOutputStream(44);
        hdr.write("RIFF".getBytes("US-ASCII"));
        putInt(hdr, 36 + data);
        hdr.write("WAVEfmt ".getBytes("US-ASCII"));
        putInt(hdr, 16); putShort(hdr, 1); putShort(hdr, 1);
        putInt(hdr, rate); putInt(hdr, rate * 2); putShort(hdr, 2); putShort(hdr, 16);
        hdr.write("data".getBytes("US-ASCII"));
        putInt(hdr, data);
        FileOutputStream out = new FileOutputStream(new File(path));
        out.write(hdr.toByteArray());
        byte[] b = new byte[pcm.length * 2];
        for (int i = 0; i < pcm.length; i++) { b[i * 2] = (byte) pcm[i]; b[i * 2 + 1] = (byte) (pcm[i] >> 8); }
        out.write(b);
        out.close();
    }

    private static void putInt(ByteArrayOutputStream o, int v) {
        o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24);
    }

    private static void putShort(ByteArrayOutputStream o, int v) {
        o.write(v); o.write(v >> 8);
    }
}
