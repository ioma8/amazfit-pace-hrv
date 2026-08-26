package com.render3d.probe;

import java.util.Arrays;

/**
 * Port of the ioma8/3drend software renderer (engine3d.ts) to GC-free Java:
 * near-plane clip, backface cull, perspective projection, painter sort (far
 * first), scanline rasterization with a per-pixel 1/z z-buffer. No textures —
 * flat shaded color.
 *
 * Turntable framing: the MODEL rotates around its own Y axis each frame while
 * the camera stays fixed (the stock engine rotates the camera view instead,
 * which would swing the model out of frame). Touch drag stops the auto-spin
 * and rotates the model freely around both Y and X (rotateBy); releasing
 * resumes the auto-spin from the dragged orientation. Flat shading follows
 * shadeMesh() but is recomputed per frame from the cull cross product (which
 * is already computed) dotted with a fixed camera-space light direction, so
 * the lighting stays world-fixed while the model spins.
 *
 * All per-frame buffers are preallocated; the hot loop allocates nothing.
 * Rendering runs at a fixed internal resolution (RW x RH) and is upscaled by
 * the view, seismo-probe style.
 */
final class Engine3d {
    static final float FOV = 70f * (float) Math.PI / 180f;
    static final float NEAR = 0.05f;
    static final float CAM_Y = 6f;
    static final float CAM_DIST = 170f; // camera at (0, CAM_Y, -CAM_DIST)
    static final float PITCH = -0.14f;
    static final float SPIN = 0.022f; // model yaw per frame (rad)
    static final int BG = 0xFF0B0F14;
    // light direction in camera space: world (0.4082, 0.8165, 0.4082) rotated
    // by the fixed camera pitch (yaw is 0 in the turntable framing)
    private static final float LIGHT_X = 0.4082f;
    private static final float LIGHT_Y = 0.8654f;
    private static final float LIGHT_Z = 0.2903f;

    final int w;
    final int h;
    final int[] pixels;
    final float[] zbuf;

    // per-triangle camera-space vertices (9 floats per tri, indexed like the mesh)
    private final float[] cx;
    private final float[] cy;
    private final float[] cz;
    // near-clip scratch: up to 4 vertices
    private final float[] clipX = new float[4];
    private final float[] clipY = new float[4];
    private final float[] clipZ = new float[4];
    // drawn triangle storage (screen-space verts, depth, color)
    private final float[] sx3;
    private final float[] sy3;
    private final float[] siz3;
    private final float[] depth;
    private final int[] color;
    private final int[] order;
    private int drawn;

    private final float focal;
    private volatile float modelYaw = 0.6f;
    private volatile float modelPitch = 0f;
    private volatile boolean autoSpin = true;
    private volatile long resumeAtMs;
    int statsTris;
    int statsPixels;

    /** After a drag release, hold the model still for this long, then
     *  resume the auto-spin (inspection pause). */
    static final long RESUME_DELAY_MS = 2000;

    /** If the release event is lost (e.g. mouse released outside the emulator
     *  window), the spin still resumes after this cap — never stuck forever. */
    private static final long STUCK_CAP_MS = 8000;

    /** Called on touch down: stop the spin; the cap bounds the stopped state
     *  if no release ever arrives. */
    void touchDown() {
        autoSpin = false;
        resumeAtMs = System.currentTimeMillis() + STUCK_CAP_MS;
    }

    /** Called on touch release: schedule the spin resume RESUME_DELAY_MS later. */
    void releaseTouch() {
        resumeAtMs = System.currentTimeMillis() + RESUME_DELAY_MS;
    }

    /** Touch drag deltas (radians); safe to call from the UI thread. */
    void rotateBy(float dyaw, float dpitch) {
        modelYaw += dyaw;
        modelPitch += dpitch;
    }

    Engine3d(int w, int h, int maxTris) {
        this.w = w;
        this.h = h;
        focal = (h / 2f) / (float) Math.tan(FOV / 2f);
        pixels = new int[w * h];
        zbuf = new float[w * h];
        int n9 = maxTris * 9;
        cx = new float[n9];
        cy = new float[n9];
        cz = new float[n9];
        int cap = maxTris * 2;
        sx3 = new float[cap * 3];
        sy3 = new float[cap * 3];
        siz3 = new float[cap * 3];
        depth = new float[cap];
        color = new int[cap];
        order = new int[cap];
    }

    void frame(Mesh m) {
        if (autoSpin) {
            modelYaw += SPIN;
        } else if (System.currentTimeMillis() >= resumeAtMs) {
            autoSpin = true;
        }
        float cM = (float) Math.cos(modelYaw);
        float sM = (float) Math.sin(modelYaw);
        float cT = (float) Math.cos(modelPitch);
        float sT = (float) Math.sin(modelPitch);
        float cosP = (float) Math.cos(PITCH);
        float sinP = (float) Math.sin(PITCH);
        drawn = 0;
        statsTris = 0;
        statsPixels = 0;
        Arrays.fill(zbuf, 0f);
        Arrays.fill(pixels, BG);

        float[] vx = m.vx;
        float[] vy = m.vy;
        float[] vz = m.vz;
        int n = m.triCount;
        for (int t = 0; t < n; t++) {
            int b = t * 9;
            boolean allNear = true;
            boolean anyNear = false;
            for (int k = 0; k < 3; k++) {
                int i = b + k * 3;
                // model rotation: Ry(yaw) then Rx(pitch), then fixed camera
                float xr = vx[i] * cM + vz[i] * sM;
                float zr = vz[i] * cM - vx[i] * sM;
                float yr = vy[i] * cT - zr * sT;
                float zrr = vy[i] * sT + zr * cT;
                float ry = yr - CAM_Y;
                float rz = zrr + CAM_DIST;
                float y2 = ry * cosP - rz * sinP;
                float z2 = ry * sinP + rz * cosP;
                cx[i] = xr;
                cy[i] = y2;
                cz[i] = z2;
                if (z2 <= NEAR) {
                    anyNear = true;
                } else {
                    allNear = false;
                }
            }
            if (allNear) {
                continue;
            }
            if (anyNear) {
                clipAndFinish(b);
            } else {
                finish(b);
            }
        }

        // painter order: far triangles first
        heapSort();
        rasterAll();
    }

    /** Sutherland-Hodgman clip against z = NEAR; fans out 0..2 triangles. */
    private void clipAndFinish(int b) {
        int outN = 0;
        for (int k = 0; k < 3; k++) {
            int cur = k * 3;
            int nxt = ((k + 1) % 3) * 3;
            boolean curIn = cz[b + cur] > NEAR;
            boolean nxtIn = cz[b + nxt] > NEAR;
            if (curIn) {
                clipX[outN] = cx[b + cur];
                clipY[outN] = cy[b + cur];
                clipZ[outN] = cz[b + cur];
                outN++;
            }
            if (curIn != nxtIn) {
                float tt = (NEAR - cz[b + cur]) / (cz[b + nxt] - cz[b + cur]);
                clipX[outN] = cx[b + cur] + (cx[b + nxt] - cx[b + cur]) * tt;
                clipY[outN] = cy[b + cur] + (cy[b + nxt] - cy[b + cur]) * tt;
                clipZ[outN] = NEAR;
                outN++;
            }
        }
        if (outN >= 3) {
            for (int i = 1; i < outN - 1; i++) {
                finish3(clipX[0], clipY[0], clipZ[0],
                        clipX[i], clipY[i], clipZ[i],
                        clipX[i + 1], clipY[i + 1], clipZ[i + 1]);
            }
        }
    }

    private void finish(int b) {
        finish3(cx[b], cy[b], cz[b],
                cx[b + 3], cy[b + 3], cz[b + 3],
                cx[b + 6], cy[b + 6], cz[b + 6]);
    }

    /**
     * Backface cull (normal vs centroid, camera space), flat shade from the
     * same cross product, perspective project.
     */
    private void finish3(float ax, float ay, float az,
            float bx, float by, float bz,
            float cxx, float cyy, float czz) {
        float abx = bx - ax, aby = by - ay, abz = bz - az;
        float acx = cxx - ax, acy = cyy - ay, acz = czz - az;
        float nx = aby * acz - abz * acy;
        float ny = abz * acx - abx * acz;
        float nz = abx * acy - aby * acx;
        float cxm = (ax + bx + cxx) / 3f;
        float cym = (ay + by + cyy) / 3f;
        float czm = (az + bz + czz) / 3f;
        if (nx * cxm + ny * cym + nz * czm >= 0f) {
            return; // backface
        }
        if (az <= NEAR || bz <= NEAR || czz <= NEAR) {
            return; // degenerate after clip
        }
        int o = drawn * 3;
        float hw = w / 2f;
        float hh = h / 2f;
        sx3[o] = hw + focal * (ax / az);
        sy3[o] = hh - focal * (ay / az);
        siz3[o] = 1f / az;
        sx3[o + 1] = hw + focal * (bx / bz);
        sy3[o + 1] = hh - focal * (by / bz);
        siz3[o + 1] = 1f / bz;
        sx3[o + 2] = hw + focal * (cxx / czz);
        sy3[o + 2] = hh - focal * (cyy / czz);
        siz3[o + 2] = 1f / czz;
        depth[drawn] = (az + bz + czz) / 3f;
        color[drawn] = shadedColor(nx, ny, nz);
        drawn++;
        statsTris++;
    }

    /** engine3d.ts shadeMesh() on the camera-space normal; returns 0..255. */
    private int shadedColor(float nx, float ny, float nz) {
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        int s;
        if (nl == 0f) {
            s = 255;
        } else {
            float d = (nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z) / nl;
            float v = 0.45f + 0.55f * d;
            s = v < 0.35f ? 90 : v > 1f ? 255 : (int) (v * 255f);
        }
        int r = (232 * s) >> 8;   // copper base 0xE8A86B
        int g = (168 * s) >> 8;
        int b = (107 * s) >> 8;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Max-heap sort of drawn indices by depth, descending (far first). */
    private void heapSort() {
        int n = drawn;
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        for (int i = n / 2 - 1; i >= 0; i--) {
            sift(i, n);
        }
        for (int i = n - 1; i > 0; i--) {
            int t = order[0];
            order[0] = order[i];
            order[i] = t;
            sift(0, i);
        }
    }

    private void sift(int root, int n) {
        while (true) {
            int l = root * 2 + 1;
            if (l >= n) {
                return;
            }
            int r = l + 1;
            int g = l;
            if (r < n && depth[order[r]] > depth[order[l]]) {
                g = r;
            }
            if (depth[order[g]] <= depth[order[root]]) {
                return;
            }
            int t = order[root];
            order[root] = order[g];
            order[g] = t;
            root = g;
        }
    }

    /** Scanline rasterizer with per-pixel 1/z z-buffer (engine3d.ts raster()). */
    private void rasterAll() {
        int wl = w;
        int hl = h;
        int[] buf = pixels;
        float[] z = zbuf;
        for (int di = 0; di < drawn; di++) {
            int t = order[di];
            int o = t * 3;
            float v0x = sx3[o], v0y = sy3[o], v0z = siz3[o];
            float v1x = sx3[o + 1], v1y = sy3[o + 1], v1z = siz3[o + 1];
            float v2x = sx3[o + 2], v2y = sy3[o + 2], v2z = siz3[o + 2];
            if (v1y < v0y) {
                float tx = v0x, ty = v0y, tz = v0z;
                v0x = v1x; v0y = v1y; v0z = v1z;
                v1x = tx; v1y = ty; v1z = tz;
            }
            if (v2y < v0y) {
                float tx = v0x, ty = v0y, tz = v0z;
                v0x = v2x; v0y = v2y; v0z = v2z;
                v2x = tx; v2y = ty; v2z = tz;
            }
            if (v2y < v1y) {
                float tx = v1x, ty = v1y, tz = v1z;
                v1x = v2x; v1y = v2y; v1z = v2z;
                v2x = tx; v2y = ty; v2z = tz;
            }
            int yTop = v0y > 0f ? (int) Math.ceil(v0y) : 0;
            int yBot = v2y < hl - 1 ? (int) Math.floor(v2y) : hl - 1;
            if (yBot < yTop) {
                continue;
            }
            float invDx = v1y != v0y ? 1f / (v1y - v0y) : 0f;
            float invDx2 = v2y != v0y ? 1f / (v2y - v0y) : 0f;
            float invDx3 = v2y != v1y ? 1f / (v2y - v1y) : 0f;
            int col = color[t];
            for (int y = yTop; y <= yBot; y++) {
                float fy = y;
                float tL = (fy - v0y) * invDx2;
                float longX = v0x + (v2x - v0x) * tL;
                float longIz = v0z + (v2z - v0z) * tL;
                float xS;
                float sIz;
                if (fy < v1y) {
                    float tS = (fy - v0y) * invDx;
                    xS = v0x + (v1x - v0x) * tS;
                    sIz = v0z + (v1z - v0z) * tS;
                } else {
                    float tS = (fy - v1y) * invDx3;
                    xS = v1x + (v2x - v1x) * tS;
                    sIz = v1z + (v2z - v1z) * tS;
                }
                float xL, xR, lIz, rIz;
                if (longX <= xS) {
                    xL = longX;
                    lIz = longIz;
                    xR = xS;
                    rIz = sIz;
                } else {
                    xL = xS;
                    lIz = sIz;
                    xR = longX;
                    rIz = longIz;
                }
                int x0 = xL > 0f ? (int) Math.ceil(xL) : 0;
                int x1 = xR < wl - 1 ? (int) Math.floor(xR) : wl - 1;
                if (x1 < x0) {
                    continue;
                }
                int rowOff = y * wl;
                float span = xR - xL;
                float invSpan = span != 0f ? 1f / span : 0f;
                for (int x = x0; x <= x1; x++) {
                    float tt = (x - xL) * invSpan;
                    float iz = lIz + (rIz - lIz) * tt;
                    int zi = rowOff + x;
                    if (iz <= z[zi]) {
                        continue;
                    }
                    z[zi] = iz;
                    buf[zi] = col;
                    statsPixels++;
                }
            }
        }
    }
}
