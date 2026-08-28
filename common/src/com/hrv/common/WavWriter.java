package com.hrv.common;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Streaming 16-bit mono PCM WAV writer. Writes a placeholder header up front,
 *  appends PCM chunks in real time, and re-patches the RIFF/data sizes on
 *  refreshHeader()/close() so the file stays readable even if the recording is
 *  cut short. Pure java.io — host-testable. */
public class WavWriter {
    /** Filename prefix shared with wifi-serve's recording filter. */
    public static final String MIC_PREFIX = "mic_16000_";

    private final RandomAccessFile raf;
    private final int sampleRate;
    private int frames;

    public WavWriter(String path, int sampleRate) throws IOException {
        this.sampleRate = sampleRate;
        raf = new RandomAccessFile(new File(path), "rw");
        writeHeader(0);
    }

    /** Append one chunk of PCM (little-endian). */
    public void write(short[] chunk, int len) throws IOException {
        byte[] b = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            b[i * 2] = (byte) chunk[i];
            b[i * 2 + 1] = (byte) (chunk[i] >> 8);
        }
        raf.write(b);
        frames += len;
    }

    /** Re-patch sizes (call ~1/s so an interrupted recording has a valid header). */
    public void refreshHeader() throws IOException {
        writeHeader(frames);
    }

    /** Finalize: patch the sizes and fsync, so a just-quit recording is durable
     *  and playable by the time close() returns. */
    public void close() throws IOException {
        writeHeader(frames);
        raf.getFD().sync();
        raf.close();
    }

    /** One-shot: write all samples and finalize. */
    public static void writeAll(String path, int sampleRate, short[] pcm, int n)
            throws IOException {
        WavWriter w = new WavWriter(path, sampleRate);
        try {
            w.write(pcm, n);
        } finally {
            w.close();
        }
    }

    private void writeHeader(int frameCount) throws IOException {
        int data = frameCount * 2;
        raf.seek(0);
        raf.write("RIFF".getBytes("US-ASCII"));
        putInt(36 + data);
        raf.write("WAVEfmt ".getBytes("US-ASCII"));
        putInt(16);
        putShort(1); putShort(1);
        putInt(sampleRate); putInt(sampleRate * 2);
        putShort(2); putShort(16);
        raf.write("data".getBytes("US-ASCII"));
        putInt(data);
        raf.seek(44 + data); // back to the end for the next append
    }

    private void putInt(int v) throws IOException {
        raf.write(v); raf.write(v >> 8); raf.write(v >> 16); raf.write(v >> 24);
    }

    private void putShort(int v) throws IOException {
        raf.write(v); raf.write(v >> 8);
    }
}
