# Seismo nebula (`seismo-probe/`) — how it works

Live accelerometer-driven nebula for the Amazfit Pace: tilt steers the flow in
all directions, twisting the watch around the screen axis swirls it. No shake
reaction, no vibration. Screen stays on; the app hard-kills itself on pause
(back/home/charging-UI takeover), per the repo's app conventions.

Files: `seismo-probe/src/com/seismo/probe/NebulaView.java` (the effect),
`MainActivity.java` (sensor wiring).

## The effect: domain-warped fBm value noise

The classic Shadertoy "nebula" recipe, rendered on the CPU:

1. **Value noise** — a fixed, seeded 256-entry permutation table (no `sin`-hash,
   fast on MIPS). `noise(x, y)` bilinearly interpolates four lattice hashes with
   a smoothstep kernel.
2. **fBm** — 3 octaves: `v += a*noise(p); p *= 2.03; a *= 0.5`. Coordinate
   offsets keep the domain bounded so numbers stay small (no mediump-style
   precision collapse).
3. **Domain warp** — the two-layer distortion that makes it look like gas:
   `w = fbm(p + fbm(p))`, then `q = (fbm(p+w+…), fbm(p+w+…))`, then
   `c = fbm(p + 1.6*q)`.
4. **Swirl layer** — `s = fbm(rot(angle) * (uv-0.5) * 2.2)`, where `angle` comes
   from the z-axis tilt; rotates a second noise field for vortex motion.
5. **Palette** — blue/violet nebula mix of the `c`, `s`, `w` channels plus a
   `c*s` glow term, vignette darkening at the edges.

## Rendering strategy

- Computed at **64×60 px** (ARGB_8888), drawn to 320×300 with
  `Paint.FILTER_BITMAP_FLAG` — the bilinear upscale turns the coarse field into
  the smooth fluid look.
- MIPS-optimized inner loop: value noise uses an **integer floor**
  (`(int)(x+4096f)-4096`, no `Math.floor` JNI calls), coordinates kept in
  bounds so the trick always holds; single-warp `q` (1 fbm instead of 2);
  vignette computed on squared distance (no `sqrt`); ~10 `noise()` calls/pixel.
- Render-bound loop (`postDelayed(16)`), `invalidate()` per frame;
  a `fps=` log line every 30 frames is left in as a debug aid.

## Self-evolution

Even with the watch flat (flow ≈ 0) the nebula keeps moving — all drift terms
are bounded so coordinates never blow up over long runs:

- slow sway: `+1.6·sin(t·0.05)` / `+1.6·cos(t·0.043)` added to the field drift
- warp breathing: `0.35·sin(t·0.12)` offsets the warp anchors
- swirl-layer translation `0.8·sin(t·0.06)` and warp drift `0.6·sin(t·0.09)`
- palette pulse: brightness `1 + 0.08·sin(t·0.25)`

## Sensor mapping

```java
view.setFlow(ax / 9.81f, -ay / 9.81f, clamp(az / 9.81f));
```

`ax`/`ay` (screen right/up) become the flow direction; `az` (out of screen)
becomes the swirl amount. At rest the watch sits flat, flow ≈ 0, and the
nebula drifts on the time term alone.

## Why not a GPU shader (important finding)

First version was a real GLES2 fragment shader (fBm + warp + palette), because
the look is a perfect fit for the GPU. It compiled and ran cleanly:

```
nebula shader compiled
surface 320x300
frame drawn, glError=0 aPos=0 uRes=0
```

…yet the screen stayed **pure black (0,0,0)** — not even the `glClearColor`
(8,10,13) was visible. Screencap pixel analysis confirmed zero output. The GL
surface never presents on this ROM (Ingenic MIPS, Android 5.1); GLSurfaceView
renders into a context that SurfaceFlinger never composites. The CPU fallback
above is the reliable path on this device.

## Watch quirks encountered

- On the charger, the stock ChargingUI steals foreground a few seconds after
  launch → the app's kill-on-pause fires (expected). Off-wrist/wrist use is
  unaffected.
- `logcat -c` does not clear and the buffer is tiny; long-running debug output
  rotates out quickly.
