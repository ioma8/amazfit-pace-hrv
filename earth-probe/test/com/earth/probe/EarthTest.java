package com.earth.probe;

/** Host-side validation of the Earth renderer. Renders at fixed sun
 *  positions and checks: sphere visible, day brighter than night, terminator
 *  orientation at sunrise, land pixels present. */
public class EarthTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        if (!ok) failures++;
        System.out.printf("%-46s %s%n", name, ok ? "ok" : "FAIL");
    }

    static double meanLuma(int[] px, int w, int h) {
        long sum = 0;
        int n = 0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            if (r + g + b < 30) continue; // skip background
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
                boolean isEast = x > w / 2;
                if (isEast != east) continue;
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
            if (g > r + 15 && g > b + 15 && r + g + b > 150) {
                n++; // lit land: green-dominant and bright
            }
        }
        return n;
    }

    public static void main(String[] a) throws Exception {
        boolean[][] land = Mesh.loadLand(
                new java.io.FileInputStream("res/raw/land.txt"));
        Mesh m = Mesh.sphere(land);
        Engine3d e = new Engine3d(160, 150, m.triCount);

        // sphere renders at all
        e.setSunWorld(0, 1, 0);
        e.frame(m);
        check("sphere fills screen", e.statsPixels > 8000);

        // afternoon sun from the south (elevation 20 deg): the front
        // hemisphere (Europe/Africa view) is day, green land visible
        double il = 1.0 / Math.sqrt(0.3 * 0.3 + 1.0);
        e.setSunWorld(0f, (float) (0.3 * il), (float) il);
        e.frame(m);
        double dayLuma = meanLuma(e.pixels, 160, 150);
        check("afternoon: green land visible", countGreen(e.pixels) > 100);

        // sun behind the globe: visible hemisphere is night
        e.setSunWorld(0, 0, -1);
        e.frame(m);
        double nightLuma = meanLuma(e.pixels, 160, 150);
        check("night: much darker than afternoon", nightLuma < dayLuma * 0.45);

        // sunrise: sun from the east -> east half of the disc brighter
        e.setSunWorld(1, 0, 0);
        e.frame(m);
        double east = sideLuma(e.pixels, 160, true);
        double west = sideLuma(e.pixels, 160, false);
        check("sunrise: east half brighter than west", east > west * 1.6);

        System.out.printf("  day=%.1f night=%.1f east=%.1f west=%.1f tris=%d%n",
                dayLuma, nightLuma, east, west, e.statsTris);
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }
}
