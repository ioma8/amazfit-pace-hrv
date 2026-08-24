package com.hrv.mic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Speech loudness/denoise chain, exact port of the validated Python prototype:
 *  HPF 120 Hz + LP 5500 Hz (biquad cascade) -> AGC (target -18 dBFS) ->
 *  noise gate (-32 dBFS) -> tanh soft limiter (0.92).
 *  Pure Java, no Android deps; main() doubles as an offline self-check. */
public class SpeechProc {

    public static short[] process(short[] in, int fs) {
        double[] x = new double[in.length];
        for (int i = 0; i < in.length; i++) x[i] = in[i];
        x = zp(x, fs, 120.0, true);
        x = zp(x, fs, 5500.0, false);
        x = agc(x, fs, -18.0, 34.0, 0.015, 0.4);
        x = gate(x, fs, -32.0, -26.0, 0.02, 0.2);
        short[] out = new short[x.length];
        for (int i = 0; i < x.length; i++) {
            double v = Math.tanh(x[i] / 32767.0 / 0.92) * 0.92 * 32767.0;
            out[i] = (short) Math.max(-32767, Math.min(32767, Math.round(v)));
        }
        return out;
    }

    /** biquad cascade, mirroring the Python zero-phase prototype exactly:
     *  2 forward, 1 on reversed, 2 forward, 1 on reversed (same effective
     *  filter the validated WAV was produced with). */
    static double[] zp(double[] x, int fs, double fc, boolean highpass) {
        for (int i = 0; i < 2; i++) x = fwd(x, fs, fc, highpass);
        x = fwd(rev(x), fs, fc, highpass); x = rev(x);
        for (int i = 0; i < 2; i++) x = fwd(x, fs, fc, highpass);
        x = fwd(rev(x), fs, fc, highpass); x = rev(x);
        return x;
    }

    static double[] fwd(double[] x, int fs, double fc, boolean hp) {
        double w0 = 2 * Math.PI * fc / fs;
        double alpha = Math.sin(w0) / (2 * 0.707), c = Math.cos(w0);
        double b0, b1, b2;
        if (hp) { b0 = (1 + c) / 2; b1 = -(1 + c); b2 = (1 + c) / 2; }
        else    { b0 = (1 - c) / 2; b1 = (1 - c);  b2 = (1 - c) / 2; }
        double a0 = 1 + alpha, a1 = -2 * c, a2 = 1 - alpha;
        b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0;
        double[] y = new double[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double v = x[i];
            y[i] = b0 * v + x1 * b1 + x2 * b2 - y1 * a1 - y2 * a2;
            x2 = x1; x1 = v; y2 = y1; y1 = y[i];
        }
        return y;
    }

    static double[] rev(double[] x) {
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[x.length - 1 - i];
        return y;
    }

    /** sliding-window RMS envelope, same windowing as the prototype */
    static double[] envOf(double[] x, int fs, double winSec) {
        int w = (int) (fs * winSec), pad = w / 2, n = x.length;
        double[] pref = new double[n + 2 * pad + 1];
        for (int i = 0; i < n; i++) pref[pad + i + 1] = pref[pad + i] + x[i] * x[i];
        for (int i = 0; i < pad; i++) pref[i + 1] = pref[i];
        int last = n + pad;
        for (int i = last; i < pref.length - 1; i++) pref[i + 1] = pref[i];
        double[] env = new double[n];
        for (int i = 0; i < n; i++) {
            int hi = Math.min(i + pad + w, n + 2 * pad);
            env[i] = Math.sqrt(Math.max(0, (pref[hi] - pref[i + pad]) / w));
        }
        return env;
    }

    static double[] smoothGain(double[] g, int fs, double fastSec, double slowSec) {
        double af = 1 - Math.exp(-1 / (fastSec * fs));
        double as = 1 - Math.exp(-1 / (slowSec * fs));
        double[] gs = new double[g.length];
        gs[0] = g[0];
        for (int i = 1; i < g.length; i++) {
            double a = g[i] < gs[i - 1] ? af : as;
            gs[i] = gs[i - 1] + a * (g[i] - gs[i - 1]);
        }
        return gs;
    }

    static double[] agc(double[] x, int fs, double targetDb, double maxDb, double attack, double release) {
        double target = Math.pow(10, targetDb / 20) * 32767;
        double maxG = Math.pow(10, maxDb / 20);
        double[] env = envOf(x, fs, 0.05);
        double[] g = new double[x.length];
        for (int i = 0; i < x.length; i++)
            g[i] = Math.min(maxG, Math.max(1, target / Math.max(env[i], 1)));
        double[] gs = smoothGain(g, fs, attack, release);
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i] * gs[i];
        return y;
    }

    static double[] gate(double[] x, int fs, double thrDb, double muteDb, double attack, double release) {
        double thr = Math.pow(10, thrDb / 20) * 32767;
        double mute = Math.pow(10, muteDb / 20);
        double[] env = envOf(x, fs, 0.05);
        double[] g = new double[x.length];
        for (int i = 0; i < x.length; i++) g[i] = env[i] > thr ? 1.0 : mute;
        double[] gs = smoothGain(g, fs, attack, release);
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i] * gs[i];
        return y;
    }

    // ---- offline self-check: java SpeechProc in.wav out.wav ----
    public static void main(String[] args) throws Exception {
        String inPath = args.length > 0 ? args[0] : "in.wav";
        String outPath = args.length > 1 ? args[1] : "out.wav";
        short[] s = readWav(inPath);
        short[] p = process(s, 16000);
        writeWav(outPath, p, 16000);
        long sum = 0; int peak = 0, clip = 0;
        for (int i = 0; i < p.length; i++) {
            int v = p[i];
            sum += (long) v * v;
            int a = Math.abs(v);
            if (a > peak) peak = a;
            if (a >= 32767) clip++;
        }
        double rms = Math.sqrt(sum / (double) p.length);
        System.out.printf("frames=%d peak=%.1fdBFS rms=%.1fdBFS clip=%d%n",
            p.length, 20 * Math.log10(peak / 32767.0), 20 * Math.log10(rms / 32767.0), clip);
    }

    static short[] readWav(String path) throws Exception {
        FileInputStream in = new FileInputStream(path);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int r;
        while ((r = in.read(b)) > 0) buf.write(b, 0, r);
        in.close();
        byte[] all = buf.toByteArray();
        int dataLen = (all[40] & 0xff) | ((all[41] & 0xff) << 8) | ((all[42] & 0xff) << 16) | ((all[43] & 0xff) << 24);
        int n = dataLen / 2;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            int o = 44 + i * 2;
            s[i] = (short) ((all[o] & 0xff) | (all[o + 1] << 8));
        }
        return s;
    }

    static void writeWav(String path, short[] pcm, int rate) throws Exception {
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

    static void putInt(ByteArrayOutputStream o, int v) {
        o.write(v); o.write(v >> 8); o.write(v >> 16); o.write(v >> 24);
    }

    static void putShort(ByteArrayOutputStream o, int v) {
        o.write(v); o.write(v >> 8);
    }
}
