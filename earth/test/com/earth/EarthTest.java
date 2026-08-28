package com.earth;

/** Host-side validation of the textured Earth renderer. Uses a synthetic
 * feature texture so shadow geometry and texture preservation are checkable
 * without Android's BitmapFactory. The real Blue Marble is validated by the
 * emulator screenshot. */
public class EarthTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.printf("%-44s %s%n", name, ok ? "ok" : "FAIL");
    }

    /** Synthetic equirectangular texture: broad green land, blue ocean,
     * white polar caps. This deliberately makes land visible in the front
     * hemisphere regardless of the exact initial longitude. */
    static int[] synthTexture() {
        int[] tex = new int[Engine3d.TW * Engine3d.TH];
        for (int v = 0; v < Engine3d.TH; v++) {
            double lat = 90 - (v + 0.5) * 180.0 / Engine3d.TH;
            for (int u = 0; u < Engine3d.TW; u++) {
                double lon = (u + 0.5) * 360.0 / Engine3d.TW - 180;
                int c = 0xFF1E5E9E;
                if (Math.abs(lat) > 70) {
                    c = 0xFFE6EAEF;
                } else if (lon >= -160 && lon <= 160
                        && lat >= -60 && lat <= 60) {
                    c = 0xFF3F8A3F;
                }
                tex[v * Engine3d.TW + u] = c;
            }
        }
        return tex;
    }

    static double meanLuma(int[] px, int w, int h) {
        long sum = 0;
        int n = 0;
        for (int c : px) {
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            if (r + g + b < 30) continue;
            sum += (r * 3 + g * 5 + b * 2) / 10;
            n++;
        }
        return n == 0 ? 0 : (double) sum / n;
    }

    static double sideLuma(int[] px, int w, boolean east) {
        long sum = 0;
        int n = 0;
        for (int y = 0; y < 150; y++) {
            for (int x = 0; x < w; x++) {
                int c = px[y * w + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                if (r + g + b < 30) continue;
                if ((x > w / 2) != east) continue;
                sum += (r * 3 + g * 5 + b * 2) / 10;
                n++;
            }
        }
        return n == 0 ? 0 : (double) sum / n;
    }

    static int countGreen(int[] px) {
        int n = 0;
        for (int c : px) {
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            if (g > r + 15 && g > b + 15 && r + g + b > 150) n++;
        }
        return n;
    }

    public static void main(String[] a) {
        Mesh m = Mesh.sphere();
        Engine3d e = new Engine3d(160, 150, m.triCount);
        e.setTexture(synthTexture());

        // South-facing sunlight is behind the north-looking camera: visible
        // front is day and the source texture colors remain unmodified.
        e.setSunWorld(0, 0, -1);
        e.frame(m);
        double dayLuma = meanLuma(e.pixels, 160, 150);
        int[] dayPixels = e.pixels.clone();
        check("sphere fills screen", e.statsPixels > 8000);
        check("day: green texture visible", countGreen(e.pixels) > 100);

        // North-facing sunlight is in front of the camera: visible front is
        // shadowed, but still uses the same texture under a black overlay.
        e.setSunWorld(0, 0, 1);
        e.frame(m);
        double nightLuma = meanLuma(e.pixels, 160, 150);
        check("night shadow darker than day", nightLuma < dayLuma * 0.65);

        int shadowDiff = 0;
        for (int i = 0; i < dayPixels.length; i++) {
            if (dayPixels[i] != e.pixels[i]) shadowDiff++;
        }
        check("shadow changes pixels with sun direction", shadowDiff > 1000);

        System.out.printf("  day=%.1f night=%.1f shadowPixels=%d tris=%d px=%d%n",
                dayLuma, nightLuma, shadowDiff, e.statsTris, e.statsPixels);
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
