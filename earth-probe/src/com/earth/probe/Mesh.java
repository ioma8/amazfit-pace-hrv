package com.earth.probe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * UV-sphere mesh for the Earth demo + landmask lookup for per-triangle base
 * colors (ocean/land/ice). The engine transforms and shades per frame; the
 * sphere is generated with poles along +Y and radius R, centered at origin.
 */
final class Mesh {
    static final float R = 85f;
    static final int LON = 32; // longitude segments
    static final int LAT = 16; // latitude rows
    static final int MW = 72;  // landmask width  (5 deg cells)
    static final int MH = 36;  // landmask height

    static final int OCEAN = 0xFF1E5E9E;
    static final int LAND = 0xFF3F7A3F;
    static final int ICE = 0xFFC7D2DA;

    final int triCount;
    final float[] vx, vy, vz; // 9 floats per triangle
    final int[] baseColor;    // per triangle ARGB

    private Mesh(int tris, float[] vx, float[] vy, float[] vz, int[] base) {
        this.triCount = tris;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.baseColor = base;
    }

    /** Parse the landmask resource: 36 rows of 72 chars, '#' = land. */
    static boolean[][] loadLand(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in));
        boolean[][] land = new boolean[MH][MW];
        int row = 0;
        String line;
        while ((line = r.readLine()) != null && row < MH) {
            for (int u = 0; u < MW && u < line.length(); u++) {
                land[row][u] = line.charAt(u) == '#';
            }
            row++;
        }
        r.close();
        return land;
    }

    /** Vertex at (latRow i, lonCol j): phi = i/LAT*pi from top pole. */
    private static float[] p(int i, int j) {
        double phi = i * Math.PI / LAT;
        double theta = j * 2.0 * Math.PI / LON;
        float y = (float) (Math.cos(phi) * R);
        float x = (float) (Math.sin(phi) * Math.sin(theta) * R);
        float z = (float) (Math.sin(phi) * Math.cos(theta) * R);
        return new float[]{x, y, z};
    }

    static Mesh sphere(boolean[][] land) {
        int tris = LAT * LON * 2;
        float[] vx = new float[tris * 9];
        float[] vy = new float[tris * 9];
        float[] vz = new float[tris * 9];
        int[] base = new int[tris];
        int t = 0;
        for (int i = 0; i < LAT; i++) {
            for (int j = 0; j < LON; j++) {
                float[] a = p(i, j);
                float[] b = p(i, j + 1);
                float[] c = p(i + 1, j + 1);
                float[] d = p(i + 1, j);
                // winding chosen so cross(b-a, c-a) points inward (engine
                // culls normal*centroid >= 0, matching the teapot convention)
                putTri(t, a, c, b, vx, vy, vz);
                base[t] = triColor(a, b, c, land);
                t++;
                putTri(t, a, d, c, vx, vy, vz);
                base[t] = triColor(a, c, d, land);
                t++;
            }
        }
        return new Mesh(tris, vx, vy, vz, base);
    }

    private static void putTri(int t, float[] a, float[] b, float[] c,
            float[] vx, float[] vy, float[] vz) {
        // engine convention: vertex k of triangle t lives at index 9t+3k in
        // each of vx/vy/vz (three vertices, stride 3)
        int o = t * 9;
        vx[o] = a[0];
        vy[o] = a[1];
        vz[o] = a[2];
        vx[o + 3] = b[0];
        vy[o + 3] = b[1];
        vz[o + 3] = b[2];
        vx[o + 6] = c[0];
        vy[o + 6] = c[1];
        vz[o + 6] = c[2];
    }

    private static int triColor(float[] a, float[] b, float[] c, boolean[][] land) {
        double phi = (Math.acos(a[1] / R) + Math.acos(b[1] / R) + Math.acos(c[1] / R)) / 3.0;
        double theta = (Math.atan2(a[0], a[2]) + Math.atan2(b[0], b[2])
                + Math.atan2(c[0], c[2])) / 3.0;
        double lat = 90 - phi * 180.0 / Math.PI;
        double lon = theta * 180.0 / Math.PI;
        int u = (int) ((lon + 180) / 360.0 * MW);
        int v = (int) ((90 - lat) / 180.0 * MH);
        if (u < 0) u = 0;
        if (u >= MW) u = MW - 1;
        if (v < 0) v = 0;
        if (v >= MH) v = MH - 1;
        // ice only where the landmask says land (Antarctica, Greenland) —
        // the Arctic Ocean stays water, not a white cap
        if (Math.abs(lat) > 68 && land[v][u]) {
            return ICE;
        }
        return land[v][u] ? LAND : OCEAN;
    }
}
