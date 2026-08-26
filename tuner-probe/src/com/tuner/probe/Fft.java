package com.tuner.probe;

/**
 * Iterative radix-2 complex FFT, in-place, pure java.*. Size must be a power
 * of two. Input: re[], im[] (im may be null -> zeros).
 */
final class Fft {
    private Fft() {
    }

    static void fft(float[] re, float[] im) {
        int n = re.length;
        if (im == null) {
            im = new float[n];
        }
        // bit-reversal permutation
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                float tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                tr = im[i];
                im[i] = im[j];
                im[j] = tr;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang);
            float wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cwr = 1f;
                float cwi = 0f;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    float xr = re[b] * cwr - im[b] * cwi;
                    float xi = re[b] * cwi + im[b] * cwr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    float nwr = cwr * wr - cwi * wi;
                    cwi = cwr * wi + cwi * wr;
                    cwr = nwr;
                }
            }
        }
    }
}
