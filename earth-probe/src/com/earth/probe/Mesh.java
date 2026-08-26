package com.earth.probe;

/**
 * UV-sphere mesh with per-vertex texture coordinates for the textured Earth.
 * Poles along +Y, radius R; u = theta/2pi (0 = lon -180, 0.5 = Greenwich),
 * v = phi/pi (0 = north pole). 64x32 = 4,096 triangles; 64 divides the
 * 1024-wide texture so texel columns map exactly (no sampling cracks).
 * Winding is inward (engine cull convention: cross*centroid < 0 kept).
 */
final class Mesh {
    static final float R = 85f;
    static final int LON = 64;
    static final int LAT = 32;

    final int triCount;
    final float[] vx, vy, vz; // 9 floats per triangle (vertex stride 3)
    final float[] tu, tv;     // texture coords, same stride

    private Mesh(int tris, float[] vx, float[] vy, float[] vz,
            float[] tu, float[] tv) {
        this.triCount = tris;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.tu = tu;
        this.tv = tv;
    }

    private static float[] p(int i, int j) {
        double phi = i * Math.PI / LAT;
        double theta = j * 2.0 * Math.PI / LON;
        float y = (float) (Math.cos(phi) * R);
        float x = (float) (Math.sin(phi) * Math.sin(theta) * R);
        float z = (float) (Math.sin(phi) * Math.cos(theta) * R);
        return new float[]{x, y, z};
    }

    static Mesh sphere() {
        int tris = LAT * LON * 2;
        float[] vx = new float[tris * 9];
        float[] vy = new float[tris * 9];
        float[] vz = new float[tris * 9];
        float[] tu = new float[tris * 9];
        float[] tv = new float[tris * 9];
        int t = 0;
        for (int i = 0; i < LAT; i++) {
            for (int j = 0; j < LON; j++) {
                float[] a = p(i, j);
                float[] b = p(i, j + 1);
                float[] c = p(i + 1, j + 1);
                float[] d = p(i + 1, j);
                float ua = j / (float) LON;
                float ub = (j + 1) / (float) LON;
                float uc = (j + 1) / (float) LON;
                float ud = j / (float) LON;
                float va = i / (float) LAT;
                float vb = i / (float) LAT;
                float vc = (i + 1) / (float) LAT;
                float vd = (i + 1) / (float) LAT;
                putTri(t, a, c, b, ua, uc, ub, va, vc, vb, vx, vy, vz, tu, tv);
                t++;
                putTri(t, a, d, c, ua, ud, uc, va, vd, vc, vx, vy, vz, tu, tv);
                t++;
            }
        }
        return new Mesh(tris, vx, vy, vz, tu, tv);
    }

    private static void putTri(int t, float[] a, float[] b, float[] c,
            float u0, float u1, float u2, float v0, float v1, float v2,
            float[] vx, float[] vy, float[] vz, float[] tu, float[] tv) {
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
        tu[o] = u0;
        tu[o + 3] = u1;
        tu[o + 6] = u2;
        tv[o] = v0;
        tv[o + 3] = v1;
        tv[o + 6] = v2;
    }
}
