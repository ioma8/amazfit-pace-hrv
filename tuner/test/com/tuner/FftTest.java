package com.tuner;
import com.hrv.common.Fft;

import java.util.Random;

/**
 * Correctness validation of Fft.fft against independent references:
 *  - bit-exact-ish match with a naive O(n^2) DFT on small sizes,
 *  - Parseval energy conservation at the full 4096 size,
 *  - impulse -> flat unit spectrum,
 *  - exact-bin sine -> single peak at the right bin.
 */
public class FftTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.printf("%-48s %s%n", name, ok ? "ok" : "FAIL");
    }

    /** Naive DFT, double precision, forward convention e^-j2pi kn/N. */
    static void dft(float[] reIn, float[] imIn, double[] reOut, double[] imOut) {
        int n = reIn.length;
        for (int k = 0; k < n; k++) {
            double sr = 0, si = 0;
            for (int j = 0; j < n; j++) {
                double a = -2 * Math.PI * k * j / n;
                double c = Math.cos(a);
                double s = Math.sin(a);
                sr += reIn[j] * c - imIn[j] * s;
                si += reIn[j] * s + imIn[j] * c;
            }
            reOut[k] = sr;
            imOut[k] = si;
        }
    }

    public static void main(String[] a) {
        // 1. vs naive DFT on small sizes (the strongest correctness check)
        for (int n : new int[]{8, 16, 32, 64, 128}) {
            Random r = new Random(n);
            float[] re = new float[n];
            float[] im = new float[n];
            for (int i = 0; i < n; i++) {
                re[i] = r.nextFloat() * 2 - 1;
                im[i] = r.nextFloat() * 2 - 1;
            }
            double[] dRe = new double[n];
            double[] dIm = new double[n];
            dft(re, im, dRe, dIm);
            Fft.fft(re, im);
            double maxErr = 0;
            for (int i = 0; i < n; i++) {
                maxErr = Math.max(maxErr, Math.abs(re[i] - dRe[i]));
                maxErr = Math.max(maxErr, Math.abs(im[i] - dIm[i]));
            }
            check("DFT match N=" + n + " (max err " + String.format("%.5f", maxErr) + ")",
                    maxErr < 1e-3);
        }

        // 2. Parseval: energy is conserved up to the 1/N DFT scaling
        int N = 4096;
        Random r = new Random(7);
        float[] re = new float[N];
        float[] im = new float[N];
        double energyT = 0;
        for (int i = 0; i < N; i++) {
            re[i] = r.nextFloat() * 2 - 1;
            im[i] = r.nextFloat() * 2 - 1;
            energyT += re[i] * re[i] + im[i] * im[i];
        }
        Fft.fft(re, im);
        double energyF = 0;
        for (int i = 0; i < N; i++) {
            energyF += re[i] * re[i] + im[i] * im[i];
        }
        double ratio = energyF / N / energyT;
        check("Parseval energyF/N == energyT (ratio "
                + String.format("%.5f", ratio) + ")", Math.abs(ratio - 1) < 1e-3);

        // 3. impulse -> flat unit spectrum (every bin has magnitude 1)
        re = new float[N];
        im = new float[N];
        re[0] = 1;
        Fft.fft(re, im);
        boolean flat = true;
        for (int i = 0; i < N; i++) {
            if (Math.abs(re[i] - 1) > 1e-3 || Math.abs(im[i]) > 1e-3) {
                flat = false;
                break;
            }
        }
        check("impulse -> flat unit spectrum", flat);

        // 4. exact-bin sine -> single peak at the right bin
        int k0 = 57;
        re = new float[N];
        im = new float[N];
        for (int i = 0; i < N; i++) {
            re[i] = (float) Math.sin(2 * Math.PI * k0 * i / N);
        }
        Fft.fft(re, im);
        double peak = 0;
        int peakBin = -1;
        for (int i = 1; i < N / 2; i++) {
            double m = re[i] * re[i] + im[i] * im[i];
            if (m > peak) {
                peak = m;
                peakBin = i;
            }
        }
        check("exact-bin sine peaks at bin " + k0 + " (got " + peakBin + ")",
                peakBin == k0);

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
