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

## Rendering strategy — Carmack-style fast math

Measured evolution on this MIPS core (soft/nominal FPU): float fBm 1.8 fps →
float-optimized 4 fps → all-integer 64×60 ~10-12 fps → 48×45 ~15-20 →
**24×22 grid pipeline 44-50 fps**.

- The fBm field is baked **once** into a **byte map** (256×256, 64 KB —
  L2-resident, 4× less memory traffic than ints; 8 texels/unit, 32-unit period,
  wrapped sampling).
- The four warp layers (`w/q/c/s`) are sampled on a **half-resolution grid**
  (12×11) and bilinearly upscaled in the final color pass — big-field reads
  drop ~4×; all intermediate grids are tiny and cache-hot.
- Final color pass at 24×22, upscaled to the screen by the GPU (measured
  0 ms — hardware-accelerated `drawBitmap`).
- All-integer math: no `Math.floor` (offset-cast), no `sqrt` (precomputed
  vignette table), ternaries instead of `Math.min`, `*410>>8` fixed-point warp
  scaling, integer `sin/cos` via 16.16 tables per frame, render-bound loop
  (`handler.post(this)`, no fixed delay).
- `fps=` log once per second as a debug aid.

## Scheduling (console-grade)

The Huami launcher burns 38-43 % CPU on this single core (per-second widget
redraws) and SurfaceFlinger another ~20 %. Two moves made the animation
immune:

- Rendering runs on a **dedicated thread at `THREAD_PRIORITY_URGENT_AUDIO`**
  (`render()` → `postInvalidate()`); the UI thread only blits the small bitmap
  to the hardware canvas (~0 ms). The render thread preempts the launcher's
  normal-priority threads during its 1-3 ms pass.
- The loop is **GC-free**: the per-frame `Rect` allocation in `onDraw` was
  causing a GC stall every ~1 s (the visible "freeze in blocks"); the Rect is
  now preallocated and logging is throttled to once per second.

Measured result: **sustained 60 fps** (the panel's vsync ceiling) even with the
launcher hammering the core.

## Tilt response

Sensor input is low-pass filtered (`sm += (raw - sm) * 0.15`) and the tilt gain
was reduced 10× (`flow * t * 0.012`, drift clamped to ±3) with the swirl
likewise calmer (`swirl * t * 0.05`) — the nebula now drifts smoothly instead
of lurching; the bounded self-evolution (sway, warp breathing, palette pulse)
still carries the motion when the watch is still.

## Self-evolution

Even with the watch flat (flow ≈ 0) the nebula keeps moving — all drift terms
are bounded so coordinates never blow up over long runs:

- sway (field drift): `+2.0·sin(t·0.28)` / `+2.0·cos(t·0.24)` — ~22 s cycle
- warp breathing: `0.5·sin(t·0.55)` — ~11 s
- swirl-layer translation `1.0·sin(t·0.34)` and warp drift `0.8·sin(t·0.42)`
- palette pulse: brightness `1 + 0.10·sin(t·0.9)` — ~7 s

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
