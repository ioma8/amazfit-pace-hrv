package com.hrv.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * OBJ loader + mesh normalization for the spider model (assimp test suite,
 * via the ioma8/3drend repo). Faces may be quads/n-gons (fan-triangulated),
 * indices may carry vt/vn refs or be negative (relative).
 *
 * The mesh is centered and scaled to a target radius so the camera framing
 * is stable. Shading is computed per frame by the engine.
 */
public class Mesh {
    static final float TARGET_RADIUS = 95f; // teapot fills ~80% of the frame

    public final int triCount;
    final float[] vx, vy, vz; // 9 floats per triangle

    private Mesh(int tris, float[] vx, float[] vy, float[] vz) {
        this.triCount = tris;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
    }

    public static Mesh load(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in), 1 << 16);
        float[] pos = new float[4096];
        int pc = 0;
        int[] faces = new int[4096];
        int[] faceLen = new int[256];
        int fc = 0;      // index stream cursor
        int fn = 0;      // face count
        String line;
        while ((line = r.readLine()) != null) {
            if (line.length() < 2) {
                continue;
            }
            char c = line.charAt(0);
            if (c == 'v' && line.charAt(1) == ' ') {
                String[] t = line.substring(2).trim().split("\\s+");
                if (t.length >= 3) {
                    if (pc + 3 > pos.length) {
                        pos = grow(pos, pc + 3);
                    }
                    pos[pc++] = Float.parseFloat(t[0]);
                    pos[pc++] = Float.parseFloat(t[1]);
                    pos[pc++] = Float.parseFloat(t[2]);
                }
            } else if (c == 'f' && line.charAt(1) == ' ') {
                String[] t = line.substring(2).trim().split("\\s+");
                int n = t.length;
                if (n < 3) {
                    continue;
                }
                if (fn >= faceLen.length) {
                    faceLen = grow(faceLen, fn + 1);
                }
                faceLen[fn++] = n;
                if (fc + n > faces.length) {
                    faces = grow(faces, fc + n);
                }
                for (int i = 0; i < n; i++) {
                    String tok = t[i];
                    int slash = tok.indexOf('/');
                    if (slash >= 0) {
                        tok = tok.substring(0, slash);
                    }
                    int idx = Integer.parseInt(tok);
                    if (idx < 0) {
                        idx = pc / 3 + idx + 1; // relative to current vertex count
                    }
                    faces[fc++] = idx - 1;
                }
            }
        }
        r.close();

        int vCount = pc / 3;
        // bounds + center
        float minX = pos[0], maxX = pos[0], minY = pos[1], maxY = pos[1], minZ = pos[2], maxZ = pos[2];
        for (int i = 3; i < pc; i += 3) {
            float x = pos[i], y = pos[i + 1], z = pos[i + 2];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        float cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f, cz = (minZ + maxZ) / 2f;
        float radius = 0f;
        for (int i = 0; i < pc; i += 3) {
            float dx = pos[i] - cx, dy = pos[i + 1] - cy, dz = pos[i + 2] - cz;
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > radius) {
                radius = d;
            }
        }
        float scale = TARGET_RADIUS / radius;

        // fan-triangulate into per-triangle arrays
        int triCount = 0;
        for (int f = 0; f < fn; f++) {
            triCount += faceLen[f] - 2;
        }
        float[] vx = new float[triCount * 9];
        float[] vy = new float[triCount * 9];
        float[] vz = new float[triCount * 9];

        int cursor = 0;
        int tri = 0;
        for (int f = 0; f < fn; f++) {
            int n = faceLen[f];
            // fan around faces[cursor]
            for (int i = 1; i < n - 1; i++) {
                int a = faces[cursor], b = faces[cursor + i], c = faces[cursor + i + 1];
                int o = tri * 9;
                put(o, a, b, c, pos, vCount, scale, cx, cy, cz, vx, vy, vz);
                tri++;
            }
            cursor += n;
        }
        return new Mesh(triCount, vx, vy, vz);
    }

    private static void put(int o, int a, int b, int c, float[] pos, int vCount,
            float scale, float cx, float cy, float cz,
            float[] vx, float[] vy, float[] vz) {
        vx[o] = (pos[a * 3] - cx) * scale;
        vy[o] = (pos[a * 3 + 1] - cy) * scale;
        vz[o] = (pos[a * 3 + 2] - cz) * scale;
        vx[o + 3] = (pos[b * 3] - cx) * scale;
        vy[o + 3] = (pos[b * 3 + 1] - cy) * scale;
        vz[o + 3] = (pos[b * 3 + 2] - cz) * scale;
        vx[o + 6] = (pos[c * 3] - cx) * scale;
        vy[o + 6] = (pos[c * 3 + 1] - cy) * scale;
        vz[o + 6] = (pos[c * 3 + 2] - cz) * scale;
    }

    private static float[] grow(float[] a, int min) {
        int n = a.length;
        while (n < min) {
            n <<= 1;
        }
        float[] b = new float[n];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    private static int[] grow(int[] a, int min) {
        int n = a.length;
        while (n < min) {
            n <<= 1;
        }
        int[] b = new int[n];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }
}
