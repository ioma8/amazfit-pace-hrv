# IMPROVEMENTS

Candidate improvements, ranked by value/effort. Not committed work — pick up
when convenient. Grounded in the shared-core refactor + rename (commit `e86a17d`)
and its code review.

## Quick wins

### 1. `make test` — unified host-test runner
Every app's host tests run via bespoke `javac` commands with fragile cwd
requirements (tuner tests must run from `tuner/`, hrv from the repo root —
the fixture `captures/raw_ppg.csv` is path-relative). Add a Makefile target
that compiles + runs all suites with the right cwd/classpath (incl. the
`org.json` jar caveat for weather). One command instead of a dozen ad-hoc
invocations.

### 2. Move `NotifBlocker` into `com.hrv.common`
Kills the last 63-line ×2 duplication. Previously rejected because the NLS
grant string embeds the component class name — but the grant is being
re-applied on the watch anyway (hrv's package changed to `com.hrv.hrv`), so
the migration now costs nothing extra. Shared class declared in both manifests
(`android:name="com.hrv.common.NotifBlocker"`), grant becomes
`com.hrv.mic/com.hrv.common.NotifBlocker:com.hrv.hrv/com.hrv.common.NotifBlocker`.
Update README grant line + TODO regrant note.

### 3. Vendor `org.json` for `ForecaParserTest`
The one host test that can't run today (android.jar's `org.json` is stubbed).
Vendor the jar in `weather/libs/` (like wifi-serve vendors zxing) and run the
test against it — closes the gap and enables `make test`.

## Medium effort

### 4. View-family consolidation onto the shared display core
`RoundView` exists but only TunerView uses it. Migrate:
- MicView/HrvView: 66 ms Handler loop, wave buffer (Waves math already shared)
- RenderView/EarthView/NebulaView: render-thread pacing, bitmap blit,
  `onDetachedFromWindow` (~90 shared lines) → a `BitmapRenderView` base
- FilesView/ImageViewer/TextViewer: round clip, swipe gesture, dark bg

~200 more lines deduped, one display core. Needs on-watch visual verification
(unit-scaling vs tuner's fixed 160,160 coords).

### 5. Incremental `make`
Currently FORCE-rebuilds all 17 apps every run (~20 s). Make each APK depend
on its sources (rebuild only what changed).

### 6. CI on push
`release.yml` builds only on tag push. Add a push/PR job running `make` +
`make test` to catch breakage early.

## High effort

### 7. Merge earth's Engine3d fork into common
~250 duplicated lines between common's flat-shaded Engine3d (from render3d)
and earth's textured/sun-shadowed fork (clip, cull, painter-sort, z-buffer
pipeline shared). Parameterize shading (flat vs texture vs sun hooks). Real
refactor of two working rasterizers — do it when a third 3D app is planned.

## Process

### 8. On-watch validation playbook
The smoke-test checklist lives in TODO.md; promote to a structured checklist
(install, NLS re-grant, notif-blocking, streaming recording, per-app launch)
so each on-watch session is repeatable.

### 9. versionCode discipline
`release.yml` drafts releases from tags. Bump versionCode per tag so
installs/updates are unambiguous.
