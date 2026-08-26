package com.earth.probe;

import java.util.Arrays;

/**
 * Software rasterizer ported from the render3d-probe teapot demo (ioma8/3drend
 * engine3d.ts): near-plane clip, backface cull, painter sort, per-pixel 1/z
 * z-buffer — now with perspective-correct TEXTURE MAPPING and per-pixel
 * spherical shadowing:
 *
 *  - u/z and v/z are interpolated per pixel (perspective-correct u,v);
 *  - the surface normal is derived analytically from the texture coords
 *    (sin/cos lookup tables), rotated by the per-frame model matrix;
 *  - the single Blue Marble texture is copied unchanged on the day side and
 *    reused under a black shadow overlay on the night side — no diffuse
 *    lighting, night-lights texture, or rim-color modulation;
 *  - texture seam (u = 0/1) handled by shifting u across the wrap.
 *
 * The geocentric sun vector is updated each frame from UTC solar declination
 * and subsolar longitude (SkyMath); it is independent of drag orientation, so
 * the shadow stays attached to the geographic texture while the globe moves.
 */
final class Engine3d {
    static final float FOV = 70f * (float) Math.PI / 180f;
    static final float NEAR = 0.05f;
    static final float CAM_Y = 6f;
    static final float CAM_DIST = 170f; // camera at (0, CAM_Y, -CAM_DIST)
    static final float PITCH = -0.14f;
    static final float TILT = 23.44f * (float) Math.PI / 180f; // axial tilt
    static final float SPIN = 0.012f; // model yaw per frame (~8.7 s/rev)
    static final int BG = 0xFF02040A;

    static final int TW = 1024; // texture width
    static final int TH = 512;  // texture height

    final int w;
    final int h;
    final int[] pixels;
    final float[] zbuf;

    /** Camera-space unit direction toward the sun, set per frame. */
    float sunX, sunY, sunZ;

    /** Texture (RGB ints, TW*TH), set once at startup. */
    int[] tex;

    // per-triangle camera-space vertices and u/z, v/z (9 floats per tri)
    private final float[] cx;
    private final float[] cy;
    private final float[] cz;
    private final float[] cuz;
    private final float[] cvz;
    // near-clip scratch: up to 4 vertices
    private final float[] clipX = new float[4];
    private final float[] clipY = new float[4];
    private final float[] clipZ = new float[4];
    private final float[] clipU = new float[4];
    private final float[] clipV = new float[4];
    // drawn triangle storage (screen-space verts, depth, tex coords /z)
    private final float[] sx3;
    private final float[] sy3;
    private final float[] siz3;
    private final float[] suz;
    private final float[] svz;
    private final float[] depth;
    private final int[] order;
    private int drawn;

    private final float focal;
    private volatile float modelYaw = 0.4f; // Greenwich + 0.4 rad faces the camera
    private volatile float modelPitch = TILT;
    int statsTris;
    int statsPixels;

    // per-frame model rotation matrix (Rx(tilt) * Ry(yaw))
    private float m00, m01, m02, m10, m11, m12, m20, m21, m22;
    // normal tables indexed by texel: theta = u*2pi, phi = v*pi
    private final float[] sinTh, cosTh, sinPh, cosPh;

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
        cuz = new float[n9];
        cvz = new float[n9];
        int cap = maxTris * 2;
        sx3 = new float[cap * 3];
        sy3 = new float[cap * 3];
        siz3 = new float[cap * 3];
        suz = new float[cap * 3];
        svz = new float[cap * 3];
        depth = new float[cap];
        order = new int[cap];

        sinTh = new float[TW];
        cosTh = new float[TW];
        for (int i = 0; i < TW; i++) {
            double th = i * 2.0 * Math.PI / TW;
            sinTh[i] = (float) Math.sin(th);
            cosTh[i] = (float) Math.cos(th);
        }
        sinPh = new float[TH];
        cosPh = new float[TH];
        for (int i = 0; i < TH; i++) {
            double ph = i * Math.PI / TH;
            sinPh[i] = (float) Math.sin(ph);
            cosPh[i] = (float) Math.cos(ph);
        }
    }

    void setTexture(int[] rgb) {
        tex = rgb;
    }

    /** Sun vector in Earth coordinates. The displayed texture is mirrored in
     *  longitude, so EarthView supplies the matching geographic vector. This
     *  vector is deliberately independent of drag orientation. */
    void setSunWorld(float wx, float wy, float wz) {
        sunX = wx;
        sunY = wy;
        sunZ = wz;
    }

    /** Rotate the globe from a drag delta. No automatic motion. */
    void rotateBy(float dyaw, float dpitch) {
        modelYaw += dyaw;
        modelPitch += dpitch;
        if (modelPitch < -1.4f) {
            modelPitch = -1.4f;
        } else if (modelPitch > 1.4f) {
            modelPitch = 1.4f;
        }
    }

    void frame(Mesh m) {
        float cM = (float) Math.cos(modelYaw);
        float sM = (float) Math.sin(modelYaw);
        float cT = (float) Math.cos(modelPitch);
        float sT = (float) Math.sin(modelPitch);
        float cosP = (float) Math.cos(PITCH);
        float sinP = (float) Math.sin(PITCH);
        m00 = cM;
        m01 = 0f;
        m02 = sM;
        m10 = sM * sT;
        m11 = cT;
        m12 = -cM * sT;
        m20 = -sM * cT;
        m21 = sT;
        m22 = cM * cT;
        drawn = 0;
        statsTris = 0;
        statsPixels = 0;
        Arrays.fill(zbuf, 0f);
        Arrays.fill(pixels, BG);

        float[] vx = m.vx;
        float[] vy = m.vy;
        float[] vz = m.vz;
        float[] tu = m.tu;
        float[] tv = m.tv;
        int n = m.triCount;
        for (int t = 0; t < n; t++) {
            int b = t * 9;
            boolean allNear = true;
            boolean anyNear = false;
            for (int k = 0; k < 3; k++) {
                int i = b + k * 3;
                // model rotation: Ry(yaw) then Rx(axial tilt), then camera pitch
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
                cuz[i] = tu[i] / z2;
                cvz[i] = tv[i] / z2;
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

    /** Sutherland-Hodgman clip against z = NEAR; fans out 0..2 triangles.
     *  u/z and v/z interpolate linearly like the positions. */
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
                clipU[outN] = cuz[b + cur];
                clipV[outN] = cvz[b + cur];
                outN++;
            }
            if (curIn != nxtIn) {
                float tt = (NEAR - cz[b + cur]) / (cz[b + nxt] - cz[b + cur]);
                clipX[outN] = cx[b + cur] + (cx[b + nxt] - cx[b + cur]) * tt;
                clipY[outN] = cy[b + cur] + (cy[b + nxt] - cy[b + cur]) * tt;
                clipZ[outN] = NEAR;
                clipU[outN] = cuz[b + cur] + (cuz[b + nxt] - cuz[b + cur]) * tt;
                clipV[outN] = cvz[b + cur] + (cvz[b + nxt] - cvz[b + cur]) * tt;
                outN++;
            }
        }
        if (outN >= 3) {
            for (int i = 1; i < outN - 1; i++) {
                finish3(clipX[0], clipY[0], clipZ[0], clipU[0], clipV[0],
                        clipX[i], clipY[i], clipZ[i], clipU[i], clipV[i],
                        clipX[i + 1], clipY[i + 1], clipZ[i + 1],
                        clipU[i + 1], clipV[i + 1]);
            }
        }
    }

    private void finish(int b) {
        finish3(cx[b], cy[b], cz[b], cuz[b], cvz[b],
                cx[b + 3], cy[b + 3], cz[b + 3], cuz[b + 3], cvz[b + 3],
                cx[b + 6], cy[b + 6], cz[b + 6], cuz[b + 6], cvz[b + 6]);
    }

    /** Backface cull (normal vs centroid, camera space), perspective project,
     *  store screen verts + 1/z + u/z + v/z. */
    private void finish3(float ax, float ay, float az, float au, float av,
            float bx, float by, float bz, float bu, float bv,
            float cxx, float cyy, float czz, float cu, float cv) {
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
        // seam wrap: if the triangle spans u = 0/1, shift u < 0.5 up by 1
        float u0 = au / (1f / az);
        float u1 = bu / (1f / bz);
        float u2 = cu / (1f / czz);
        float uMin = Math.min(u0, Math.min(u1, u2));
        float uMax = Math.max(u0, Math.max(u1, u2));
        if (uMax - uMin > 0.5f) {
            float iz0 = 1f / az;
            float iz1 = 1f / bz;
            float iz2 = 1f / czz;
            if (u0 < 0.5f) au += iz0;
            if (u1 < 0.5f) bu += iz1;
            if (u2 < 0.5f) cu += iz2;
        }
        int o = drawn * 3;
        float hw = w / 2f;
        float hh = h / 2f;
        float iz0 = 1f / az;
        float iz1 = 1f / bz;
        float iz2 = 1f / czz;
        sx3[o] = hw + focal * (ax / az);
        sy3[o] = hh - focal * (ay / az);
        siz3[o] = iz0;
        suz[o] = au;
        svz[o] = av;
        sx3[o + 1] = hw + focal * (bx / bz);
        sy3[o + 1] = hh - focal * (by / bz);
        siz3[o + 1] = iz1;
        suz[o + 1] = bu;
        svz[o + 1] = bv;
        sx3[o + 2] = hw + focal * (cxx / czz);
        sy3[o + 2] = hh - focal * (cyy / czz);
        siz3[o + 2] = iz2;
        suz[o + 2] = cu;
        svz[o + 2] = cv;
        depth[drawn] = (az + bz + czz) / 3f;
        drawn++;
        statsTris++;
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

    /** Scanline rasterizer: perspective-correct texture mapping + per-pixel
     *  spherical normal lighting. */
    /** 1.25x contrast around mid-gray, then 1.5x saturation; clamped. */
    private static int boost(int r, int g, int b) {
        r = (((r - 128) * 5) >> 2) + 128;
        g = (((g - 128) * 5) >> 2) + 128;
        b = (((b - 128) * 5) >> 2) + 128;
        int mx = r > g ? (r > b ? r : b) : (g > b ? g : b);
        int mn = r < g ? (r < b ? r : b) : (g < b ? g : b);
        int mid = (mx + mn) >> 1;
        r += (r - mid) >> 1;
        g += (g - mid) >> 1;
        b += (b - mid) >> 1;
        if (r < 0) r = 0; else if (r > 255) r = 255;
        if (g < 0) g = 0; else if (g > 255) g = 255;
        if (b < 0) b = 0; else if (b > 255) b = 255;
        return (r << 16) | (g << 8) | b;
    }

    private void rasterAll() {
        int wl = w;
        int hl = h;
        int[] buf = pixels;
        float[] z = zbuf;
        int[] texture = tex;
        if (texture == null) {
            return;
        }
        for (int di = 0; di < drawn; di++) {
            int t = order[di];
            int o = t * 3;
            float v0x = sx3[o], v0y = sy3[o], v0z = siz3[o];
            float v0u = suz[o], v0v = svz[o];
            float v1x = sx3[o + 1], v1y = sy3[o + 1], v1z = siz3[o + 1];
            float v1u = suz[o + 1], v1v = svz[o + 1];
            float v2x = sx3[o + 2], v2y = sy3[o + 2], v2z = siz3[o + 2];
            float v2u = suz[o + 2], v2v = svz[o + 2];
            if (v1y < v0y) {
                float tx = v0x, ty = v0y, tz = v0z, tu = v0u, tv = v0v;
                v0x = v1x;
                v0y = v1y;
                v0z = v1z;
                v0u = v1u;
                v0v = v1v;
                v1x = tx;
                v1y = ty;
                v1z = tz;
                v1u = tu;
                v1v = tv;
            }
            if (v2y < v0y) {
                float tx = v0x, ty = v0y, tz = v0z, tu = v0u, tv = v0v;
                v0x = v2x;
                v0y = v2y;
                v0z = v2z;
                v0u = v2u;
                v0v = v2v;
                v2x = tx;
                v2y = ty;
                v2z = tz;
                v2u = tu;
                v2v = tv;
            }
            if (v2y < v1y) {
                float tx = v1x, ty = v1y, tz = v1z, tu = v1u, tv = v1v;
                v1x = v2x;
                v1y = v2y;
                v1z = v2z;
                v1u = v2u;
                v1v = v2v;
                v2x = tx;
                v2y = ty;
                v2z = tz;
                v2u = tu;
                v2v = tv;
            }
            int yTop = v0y > 0f ? (int) Math.ceil(v0y) : 0;
            int yBot = v2y < hl - 1 ? (int) Math.floor(v2y) : hl - 1;
            if (yBot < yTop) {
                continue;
            }
            float invDx = v1y != v0y ? 1f / (v1y - v0y) : 0f;
            float invDx2 = v2y != v0y ? 1f / (v2y - v0y) : 0f;
            float invDx3 = v2y != v1y ? 1f / (v2y - v1y) : 0f;
            for (int y = yTop; y <= yBot; y++) {
                float fy = y;
                float tL = (fy - v0y) * invDx2;
                float longX = v0x + (v2x - v0x) * tL;
                float longIz = v0z + (v2z - v0z) * tL;
                float longU = v0u + (v2u - v0u) * tL;
                float longV = v0v + (v2v - v0v) * tL;
                float xS;
                float sIz;
                float sU;
                float sV;
                if (fy < v1y) {
                    float tS = (fy - v0y) * invDx;
                    xS = v0x + (v1x - v0x) * tS;
                    sIz = v0z + (v1z - v0z) * tS;
                    sU = v0u + (v1u - v0u) * tS;
                    sV = v0v + (v1v - v0v) * tS;
                } else {
                    float tS = (fy - v1y) * invDx3;
                    xS = v1x + (v2x - v1x) * tS;
                    sIz = v1z + (v2z - v1z) * tS;
                    sU = v1u + (v2u - v1u) * tS;
                    sV = v1v + (v2v - v1v) * tS;
                }
                float xL, xR, lIz, rIz, lU, rU, lV, rV;
                if (longX <= xS) {
                    xL = longX;
                    lIz = longIz;
                    lU = longU;
                    lV = longV;
                    xR = xS;
                    rIz = sIz;
                    rU = sU;
                    rV = sV;
                } else {
                    xL = xS;
                    lIz = sIz;
                    lU = sU;
                    lV = sV;
                    xR = longX;
                    rIz = longIz;
                    rU = longU;
                    rV = longV;
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
                    float invIz = 1f / iz;
                    float u = (lU + (rU - lU) * tt) * invIz;
                    float v = (lV + (rV - lV) * tt) * invIz;
                    int tx = ((int) (u * TW)) & (TW - 1);
                    // Blue Marble is east/west mirrored relative to the
                    // sphere's longitude convention. Use the displayed
                    // longitude for both texture and shadow normal.
                    tx = TW - 1 - tx;
                    int ty = (int) (v * TH);
                    if (ty < 0) ty = 0;
                    if (ty >= TH) ty = TH - 1;
                    int c = texture[ty * TW + tx];
                    // Geographic normal from the displayed texture position.
                    // Its X sign matches the horizontal texture correction.
                    float nx = -sinPh[ty] * sinTh[tx];
                    float ny = cosPh[ty];
                    float nz = sinPh[ty] * cosTh[tx];
                    // Shadow is evaluated in Earth coordinates, not the
                    // dragged view coordinates: the terminator stays locked
                    // to the geographic texture while the globe is dragged.
                    float d = nx * sunX + ny * sunY + nz * sunZ;
                    float shadow = d <= -0.06f ? 0.98f
                            : d >= 0.06f ? 0f
                            : 0.98f * (0.06f - d) / 0.12f;
                    float visible = 1f - shadow;
                    int r = (int) (((c >> 16) & 255) * visible);
                    int g = (int) (((c >> 8) & 255) * visible);
                    int b = (int) ((c & 255) * visible);
                    // contrast + saturation boost so the day side pops
                    buf[zi] = 0xFF000000 | boost(r, g, b);
                    statsPixels++;
                }
            }
        }
    }
}
