# Amazfit Pace HRV

Rootless raw-PPG capture, HRV analysis, and sensor-hub research for the Amazfit Pace A1612.

The watch exposes one usable PPG sample per Android sensor event at approximately 25.2 Hz. Events arrive in five-sample transport bursts, so this project fits a uniform sample clock, uses zero-phase filtering and adaptive local-prominence peaks, validates pulse morphology, and computes artifact-gated time-domain HRV plus an LF resonance score.

## Current result

The included 59.7-second on-wrist fixture contains 1,507 raw samples. The calibrated analyzer finds 82 pulse peaks and produces:

```text
HR       83.28 bpm
RMSSD    42.27 ms
SDNN     71.35 ms
Score    64.46%
Clean    81/81 intervals
```

A subsequent live watch run remained stable at 85–87 bpm, 31–36 ms RMSSD, and 56–62 ms SDNN.

## Repository map

- [`hrv-probe/`](hrv-probe/) — Android 5.1 watch application and installable APK
- [`weather-probe/`](weather-probe/) — Ostrava hourly-forecast watch app (Wi-Fi + offline cache)
- [`breathe-probe/`](breathe-probe/) — cyclic-sighing stress exercise (validated protocol)
- [`metronome-probe/`](metronome-probe/) — vibration metronome with BPM presets
- [`radar-probe/`](radar-probe/) — CZ radar on a map, with animation
- [`seismo-probe/`](seismo-probe/) — accelerometer nebula seismograph (CPU fBm, 60 fps)
- [`mic-probe/`](mic-probe/) — mic capture app with UI (record/stop, live waveform, speech DSP)
- [`sunface-probe/`](sunface-probe/) — moon phase + sun times watch face (NOAA ephemeris, GPS location)
- [`earth-probe/`](earth-probe/) — spinning Earth with the live day/night terminator (real sun position)
- [`tuner-probe/`](tuner-probe/) — guitar tuner: FFT pitch detection on the 16 kHz mic, note + cents gauge
- [`render3d-probe/`](render3d-probe/) — software 3D demo: rotating copper teapot, z-buffered rasterizer (3drend port)
- [`wifi-serve/`](wifi-serve/) — "Pace Sync": watch WiFi AP + QR, serves mic recordings over HTTP to the phone
- [`wifi-provision/`](wifi-provision/) — adds saved Wi-Fi networks from `/sdcard/wifi.json`
- [`filebrowser/`](filebrowser/) — simple sdcard file browser app (tap folders, swipe right = back, text reader + image viewer on file tap)
- [`pull-recordings.sh`](pull-recordings.sh) — downloads new watch recordings, clears the device
- [`captures/raw_ppg.csv`](captures/raw_ppg.csv) — captured regression fixture
- [`apks/system/`](apks/system/) — stock Amazfit Pace system APKs (odexed, RE source)
- [`HRV-FINDINGS.md`](HRV-FINDINGS.md) — algorithms, evidence, failures, and limits
- [`PACE-FINDINGS.md`](PACE-FINDINGS.md) — device and sensor-hub reverse engineering
- [`MIC-FINDINGS.md`](MIC-FINDINGS.md) — mic capture findings (only 16 kHz is usable)
- [`SEISMO-NEBULA.md`](SEISMO-NEBULA.md) — nebula seismograph design notes
- [`EMULATOR.md`](EMULATOR.md) — recreate the `pace` AVD, run it, re-run the validation playbook
- [`SUMMARY.md`](SUMMARY.md) — concise project findings
- [`HYPOTHESES.csv`](HYPOTHESES.csv) — tracked hypotheses log
- [`firmware/`](firmware/) and [`firmware-tools/`](firmware-tools/) — sensor-hub research artifacts

## Local checks

```bash
rm -rf /tmp/hrv-tests
javac -d /tmp/hrv-tests \
  hrv-probe/src/com/hrv/probe/HrvAnalyzer.java \
  hrv-probe/src/com/hrv/probe/HrvSamples.java \
  hrv-probe/test/com/hrv/probe/HrvAnalyzerTest.java \
  hrv-probe/test/com/hrv/probe/HrvSamplesTest.java
java -cp /tmp/hrv-tests com.hrv.probe.HrvAnalyzerTest captures/raw_ppg.csv
java -cp /tmp/hrv-tests com.hrv.probe.HrvSamplesTest
```

Expected output:

```text
HrvAnalyzer checks passed
HrvSamples checks passed
```

## Build, sign, and install the watch app

Prerequisites: JDK 8+ (`javac`, `jar`, `keytool`), Android SDK platform 35, Build Tools 36.0.0, `adb`, and `zip`. Set `ANDROID_SDK_ROOT` if the SDK is not under `~/Library/Android/sdk`.

From the repository root:

```bash
export ANDROID_SDK_ROOT=\"${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}\"
BT=\"$ANDROID_SDK_ROOT/build-tools/36.0.0\"
ANDROID_JAR=\"$ANDROID_SDK_ROOT/platforms/android-35/android.jar\"
OUT=/tmp/pace-hrv-build
KEYSTORE=\"$HOME/.android/debug.keystore\"

rm -rf \"$OUT\"
mkdir -p \"$OUT/classes\" \"$OUT/dex\"

find hrv-probe/src -name '*.java' -print0 |
  xargs -0 javac -source 8 -target 8 -classpath \"$ANDROID_JAR\" -d \"$OUT/classes\"
jar cf \"$OUT/classes.jar\" -C \"$OUT/classes\" .
\"$BT/d8\" --output \"$OUT/dex\" \"$OUT/classes.jar\"

\"$BT/aapt\" package -f \
  -M hrv-probe/AndroidManifest.xml \
  -S hrv-probe/res \
  -I \"$ANDROID_JAR\" \
  -F \"$OUT/unsigned.apk\"
zip -j \"$OUT/unsigned.apk\" \"$OUT/dex/classes.dex\"
\"$BT/zipalign\" -f 4 \"$OUT/unsigned.apk\" \"$OUT/aligned.apk\"
```

Create a local debug signing key once:

```bash
mkdir -p \"$(dirname \"$KEYSTORE\")\"
if [ ! -f \"$KEYSTORE\" ]; then
  keytool -genkeypair -v \
    -keystore \"$KEYSTORE\" -storepass android \
    -alias androiddebugkey -keypass android \
    -dname 'CN=Android Debug,O=Android,C=US' \
    -keyalg RSA -validity 10000
fi
```

Sign and verify:

```bash
\"$BT/apksigner\" sign \
  --ks \"$KEYSTORE\" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out hrv-probe/hrv-probe.apk \
  \"$OUT/aligned.apk\"
\"$BT/apksigner\" verify --verbose hrv-probe/hrv-probe.apk
\"$BT/aapt\" dump badging hrv-probe/hrv-probe.apk
```

Install and launch over ADB:

```bash
adb devices
adb install -r hrv-probe/hrv-probe.apk
adb shell am start -n com.hrv.probe/.MainActivity
```

Android requires updates to use the same signing key. If another key signed the installed copy, `adb install -r` returns `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; uninstall that copy first with `adb uninstall com.hrv.probe`, then install again.

## Ostrava weather app (`weather-probe/`)

Android 5.1 watch app: enables Wi-Fi, downloads Foreca's hourly forecast for Ostrava (today `?day=0` + tomorrow `?day=1`), parses the server-embedded `renderHourly({data:[...]})` JSON (the page is a JS shell — there are no server-rendered hour rows), caches it to app storage, and shows a vertically scrollable table: Time · Temp · Wind · Rain% · Condition, with a "Tomorrow" separator. Works offline (shows last saved forecast).

Battery behavior: Wi-Fi is on only while the app is open; `onPause()` (back, home, power) disables Wi-Fi, `finish()`es and hard-kills the process. Cache survives, so relaunch shows the last forecast while Wi-Fi reconnects.

Build, sign, and install (same toolchain as HRV, `ANDROID_SDK_ROOT` set):

```bash
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
BT="$ANDROID_SDK_ROOT/build-tools/36.0.0"
ANDROID_JAR="$ANDROID_SDK_ROOT/platforms/android-35/android.jar"
OUT=/tmp/weather-probe-build
KEYSTORE="$HOME/.android/debug.keystore"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"
find weather-probe/src -name '*.java' -print0 |
  xargs -0 javac -source 8 -target 8 -classpath "$ANDROID_JAR" -d "$OUT/classes"
jar cf "$OUT/classes.jar" -C "$OUT/classes" .
"$BT/d8" --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/classes.jar"
"$BT/aapt" package -f -M weather-probe/AndroidManifest.xml -S weather-probe/res \
  -I "$ANDROID_JAR" -F "$OUT/unsigned.apk"
zip -j "$OUT/unsigned.apk" "$OUT/dex/classes.dex"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out weather-probe/weather-probe.apk "$OUT/aligned.apk"
adb install -r weather-probe/weather-probe.apk
adb shell am start -n com.weather.probe/.MainActivity
```

Parser regression test (needs a real `org.json` jar, e.g. from Maven Central, because `android.jar`'s is stubbed):

```bash
javac -cp /tmp/json.jar -d /tmp/wpt weather-probe/src/com/weather/probe/*.java \
  weather-probe/test/com/weather/probe/ForecaParserTest.java
java -cp /tmp/json.jar:/tmp/wpt com.weather.probe.ForecaParserTest            # fixture
java -cp /tmp/json.jar:/tmp/wpt com.weather.probe.ForecaParserTest page.html   # live page
```

## Wi-Fi provisioning (`wifi-provision/`)

No root exists on the watch (uid 2000), so `wpa_supplicant.conf` is not writable — networks are added through `WifiManager.addNetwork` from a tiny app that reads `/sdcard/wifi.json`:

```json
[{"ssid":"Vodafone-8614","password":"..."},{"ssid":"RNT","password":"..."}]
```

```bash
adb push wifi.json /sdcard/wifi.json
adb shell am start -n com.wifi.provision/.MainActivity   # adds networks, deletes the json
```

Currently saved on the device: `Vodafone-8614` (netId 0), `RNT` (netId 1).

Credentials extraction from macOS: AirPort passwords live in the System keychain; grant the CLI once with `sudo security set-key-partition-list -S apple-tool:,apple: -s -k "" /Library/Keychains/System.keychain`, then `security find-generic-password -w -a "<ssid>" -s AirPort` (or `/tmp/dump-wifi.sh`).

## 3D render demo (`render3d-probe/`)

Software 3D engine ported from [`ioma8/3drend`](https://github.com/ioma8/3drend)
(engine3d.ts): near-plane clip, backface cull, painter sort, scanline
rasterization with a per-pixel 1/z z-buffer. No GPU, no textures — flat
shading from a fixed light (`shadeMesh()` formula, recomputed per frame so the
light stays world-fixed while the model spins).

The model is the classic Utah teapot (`res/raw/teapot.obj`, 2,256 tris, via
the McNopper/OpenGL examples collection), centered and scaled at load, flat
shaded in copper. Turntable framing: the model rotates around its own Y axis
(0.022 rad/frame) in front of a fixed camera. Touch and drag stops the
auto-spin and rotates the model freely — horizontal drag spins it around Y,
vertical around X; releasing holds the model still for 2 seconds (inspection
pause), then the auto-spin resumes from the dragged orientation (if the
release event is ever lost — e.g. mouse released outside the emulator window
— the spin self-heals after 8 seconds). Rendering runs on a dedicated
`THREAD_PRIORITY_URGENT_AUDIO` thread into a half-resolution bitmap (160×150)
upscaled with bilinear filtering — the seismo-probe pattern. Screen shows
fps · tris · pixels; FPS is also logged every 60 frames (`logcat -s Render3D`).

```bash
render3d-probe/build.sh
adb install -r render3d-probe/aligned.apk
adb shell am start -n com.render3d.probe/.MainActivity
```

 Emulator (pace AVD): 60 fps at ~830 drawn tris. The real MIPS watch is
 slower; the render resolution (RenderView `RW`/`RH`) and framing
 (Mesh `TARGET_RADIUS`, Engine3d `CAM_DIST`) are the tuning knobs.

## Sun face (`sunface-probe/`)

Watch face: current time/date, a 24 h dial with the daylight arc
(sunrise→sunset) and a live sun dot, moon phase with the exact per-pixel
terminator (rendered into a small bitmap, not an ellipse approximation), and
the day's event line. Location comes from the last GPS fix (`geo fix` on the
emulator); fallback is Ostrava 49.82N 18.26E, badge shows the source.

Ephemeris math is `SkyMath.java` — NOAA-style declination/equation of time,
sunrise/sunset/solar noon, elevation/azimuth, and a synodic moon model
(epoch: new moon 2000-01-06 18:14 UTC). Validated against known eclipses
(2024-04-08 new, 2024-03-25 full) and real Ostrava sun times; the mean-motion
moon model drifts up to ~7 h over decades (lunar eccentricity) — invisible on
the face. Redraws every 30 s, no render thread.

```bash
sunface-probe/build.sh
adb install -r sunface-probe/aligned.apk
adb shell am start -n com.sunface.probe/.MainActivity
adb emu geo fix 18.26 49.82    # feed GPS on the emulator
```

## Spinning Earth (`earth-probe/`)

The render3d engine repurposed: a UV-sphere Earth (32×16, 1,024 tris,
radius 85) colored per triangle from a 72×36 landmask rasterized from
Natural Earth 110 m land polygons (`tools/gen_landmask.py`, `res/raw/land.txt`):
ocean, land, and ice only where the mask says land (Antarctica/Greenland —
the Arctic stays water). The globe spins (0.012 rad/frame) on its fixed
23.44° tilted axis while the day/night terminator follows the real sun —
azimuth/elevation from `SkyMath` at the current location, rotated into camera
space every frame, so the lit side and terminator orientation match your
actual sky at the moment you look at it. Soft terminator (0.12 dot width),
bluish rim glow at the silhouette, static star field outside the globe.

Emulator (pace AVD): 59 fps at ~210 drawn tris with GPS lock. The host test
(`EarthTest.java`) renders at fixed sun directions and asserts: sphere fills
the screen, day ≈ 8× brighter than night, east/west asymmetry at sunrise,
green land visible in an afternoon sun.

```bash
earth-probe/build.sh
adb install -r earth-probe/aligned.apk
adb shell am start -n com.earth.probe/.MainActivity
adb emu geo fix 18.26 49.82
```

## Guitar tuner (`tuner-probe/`)

Pitch detector on the 16 kHz mic (the only rate the dmic clocks correctly):
4096-sample Hann window, radix-2 FFT (3.906 Hz bins), peak search
59–1098 Hz with parabolic interpolation on log-magnitude (~0.4 Hz accuracy,
±1 cent on synthetic tones), noise-gated (peak must stand 4× above the band
mean). Maps to the nearest semitone — round UI: big note letter, a −50…+50
cent gauge with a needle, green in-tune zone, frequency readout, and a
vibration tick when within ±3 cents. Covers all six guitar strings
(E2 82.41 Hz … E4 329.63 Hz).

Host test (`TunerTest.java`) synthesizes the six strings, concert A4,
detuned tones (+20/−25/+4 cents), a strong-2nd-harmonic case, and pure
noise — all pass; noise is rejected. The emulator has no audio input
(`hw.audioInput=no`), so the mic path needs the real watch.

```bash
tuner-probe/build.sh
adb install -r tuner-probe/aligned.apk
adb shell am start -n com.tuner.probe/.MainActivity
```

## Wrist tools (`breathe-probe/`, `metronome-probe/`, `radar-probe/`)

## Wrist tools (`breathe-probe/`, `metronome-probe/`, `radar-probe/`)


Three minimalist utilities, same build/install flow as `weather-probe`.

### Breathe — cyclic sighing
The best-validated breathing pattern for acute stress reduction (Balban et al. 2023,
*Cell Reports Medicine*, PMID 36630953): two nasal inhales + long slow exhale,
~1:2 ratio. Pacer: 2s in → 2s top-up → 8s sigh out, 25 sighs (~5 min, as studied).
No vibration. Tap to pause/resume/restart; screen stays on.

### Metronome
Vibration metronome, 30–240 BPM, preset rows (60–200), first beat of each 4/4 bar
accented (90 ms vs 30 ms tick). `Start` toggles to `Stop`. Screen stays on.

### Radar
CZ radar composite, same layer stack as the CHMÚ page:
`opendata.chmi.cz/.../maxz/png/` frames over `produkty.chmi.cz` terrain
(`oro`), underlay (`und3`) and border (`hranice`) layers. Latest 24 frames
(~2 h) are composited at 340×230 (keeps ~24 bitmaps ≈ 8 MB on the MIPS heap),
static layers cached. Latest still by default; tap to loop frames at 100 ms
(bourky.cz style), tap again for the still. Each frame shows its local time
(UTC + device offset) bottom-right. Offline: last composite cached.

## App conventions (all watch apps)

- Kill on pause: `onPause()` runs cleanup, then `finish()` +
  `Process.killProcess(Process.myPid())` — no zombie processes (weather-probe
  additionally disables Wi-Fi).
- Screen awake while visible: `FLAG_KEEP_SCREEN_ON` on the window (no permission).
- Vibration: this ROM throws a cosmetic `RuntimeException("Vibrator")` on a
  background thread *after* vibrating — wrap in try/catch, keep going.
- Bitmaps: always decode with `inSampleSize` to display resolution; this watch's
  heap can't hold full-res layer stacks.

## Launcher compatibility gotchas

- App icons must be **PNG bitmaps** — the stock launcher crashes (`VectorDrawable cannot be cast to BitmapDrawable`) on vector icons.
- Do **not** set `android:screenOrientation` — it forces a config change that relaunches the launcher and trips its `contain 3 creator` bug.
- `adb shell svc wifi disable` fails on this device (shell lacks `CHANGE_WIFI_STATE`); the app itself can toggle Wi-Fi.
- `adb logcat -c` does not clear the log buffer on this watch.

## Mic capture (see [`MIC-FINDINGS.md`](MIC-FINDINGS.md))

The watch's digital mic runs at one native rate, **16000 Hz** — the other declared
rates (8000/11025/44100) are decimated or mislabeled (pitch-warped) and unusable.
The mic app records at 16 kHz and applies a validated speech chain
(HPF 120 Hz → LP 5500 Hz → AGC → noise gate → tanh limiter) so speech is loud and
pauses are silent. Build with `mic-probe/build.sh`, install the APK, tap REC/STOP,
then pull the recordings:

```bash
mic-probe/build.sh
adb install -r mic-probe/aligned.apk
adb shell am start -n com.hrv.mic/.MainActivity   # tap REC, speak, tap STOP
./pull-recordings.sh                                # downloads + clears device
```

Back/home exits the app completely (hard kill), and the screen stays awake while
it runs.

Each pull creates `captures/mic-probe/mic_16000_<rec-time>.wav`
(processed) and `..._raw.wav` (unprocessed), keeping the on-device names.
The DSP is pure Java (`SpeechProc.java`), verified bit-identical to the Python
prototype.

## Pace Sync — mic recordings to phone (`wifi-serve/`)

The watch turns itself into a **WiFi access point** and serves `/sdcard/mic-probe/`
over HTTP, so the phone can pull recordings with nothing but its browser and
camera. Transfer flow (scan-twice, no typing, no phone-side install):

1. Launch Pace Sync. The watch starts AP **`PaceSync`** (WPA2, password
   `pace-sync`) and shows QR #1: `WIFI:T:WPA;S:PaceSync;P:pace-sync;;`.
2. Scan QR #1 with the phone camera → connect to the network.
3. The watch detects the phone via `/proc/net/arp` (fallback: 15 s timer, or
   tap the screen to toggle) and shows QR #2: `http://<ap-ip>:8080`.
4. Scan QR #2 → the phone browser opens the page: file list with sizes,
   per-file download links, **Download all (.zip)**, and **Clear recordings**
   (POST, scoped to `mic_16000_*.wav`, JS confirm).

Details: QR encoding is vendored `zxing core 3.5.3` (`libs/`); the HTTP server
is pure `java.net` and redirects every unknown path (captive-portal probes
like `/generate_204` included) to `/` as a hedge. The phone has no internet
while on the watch AP — the page is fully self-contained. On exit the app
tears the AP down and hard-kills (repo convention).

```bash
wifi-serve/build.sh
adb install -r wifi-serve/aligned.apk
adb shell am start -n com.wifi.serve/.MainActivity
```

Device notes: AP mode is enabled via the hidden `WifiManager` APIs
(`setWifiApEnabled`, gated on `CHANGE_WIFI_STATE` in AOSP) — whether the Huami
ROM allows a non-system uid to create an AP is the one open device question;
the app surfaces failure on screen. iOS cameras do not parse `WIFI:` QR
payloads (third-party QR app needed). Run on the cradle — AP + screen-on
drains the battery.

Host regression tests (pure Java, no device needed):

```bash
cd wifi-serve
javac -cp "$ANDROID_SDK_ROOT/platforms/android-36/android.jar:libs/core-3.5.3.jar" -d /tmp/ws-test \
  src/com/wifi/serve/HttpServer.java src/com/wifi/serve/Qr.java \
  test/com/wifi/serve/HttpServerTest.java test/com/wifi/serve/QrTest.java
java -cp /tmp/ws-test:libs/core-3.5.3.jar com.wifi.serve.HttpServerTest
java -cp /tmp/ws-test:libs/core-3.5.3.jar com.wifi.serve.QrTest   # expects "checks passed"
```

## Emulator validation (`pace` AVD)

Full how-to (recreate from scratch, run, re-run the validation playbook):
[`EMULATOR.md`](EMULATOR.md).

An Android emulator approximating the watch for on-device testing of the watch
apps: **API 24** (Android 7.0 — closest available to the watch's 5.1; arm64
images start at API 24, and x86 images can't run on Apple Silicon), **320×300
@ 238 dpi** (exact watch panel metrics), software GPU.

```bash
wifi-serve/emulate.sh --create          # one-time: install image + create AVD
$HOME/Library/Android/sdk/emulator/emulator -avd pace -scale 2 &
wifi-serve/emulate.sh                   # wait boot, install app, push samples, launch
```

Validated on the emulator: both QR phases render and **decode from a
screenshot** (connect QR → `WIFI:T:WPA;S:PaceSync;P:pace-sync;;`; URL QR →
`http://<guest-ip>:8080`); the ARP-based auto-switch to phase 2 fires when a
client appears on the AP subnet (the emulator's slirp gateway triggers it);
all HTTP routes work end-to-end through `adb forward` (list, exact file bytes,
`all.zip`, `/generate_204` → 302, POST `/clear` deletes only recordings);
BACK kills the process; AP-enable failure is surfaced on screen ("AP failed
(ROM gate?)").

Not validatable on the emulator: **real AP mode** (the emulator has no WiFi
hardware at API 24) and the phone scan/join flow — those stay on-device tests.
The emulator is set up watch-like: status bar and nav bar hidden (see
`EMULATOR.md`), so apps render fullscreen 320×300.

## Important limits

This is experimental wearable research, not a medical device. The calculations match the captured PPG pulse timing, but no simultaneous ECG reference was recorded. At 25 Hz, interpolation improves smooth peak timing but cannot recover information absent from the sampled waveform.
