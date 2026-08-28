# Amazfit Pace HRV

Rootless watch apps and sensor research for the Amazfit Pace A1612 (Android 5.1, MIPS32, 320×300 round @238 dpi). No root, no dependencies; pure Java, one Makefile, host-testable logic.

## Apps

| App | What it is | Package |
|---|---|---|
| hrv | raw-PPG capture + HRV analysis (25.2 Hz, artifact-gated) | com.hrv.hrv |
| mic | streaming 16 kHz mono WAV recorder | com.hrv.mic |
| weather | Ostrava hourly forecast (Wi-Fi + offline cache) | com.weather |
| tuner | guitar tuner (FFT pitch, note + cents gauge) | com.tuner |
| sunface | sun times + moon phase watch face (GPS) | com.sunface |
| earth | textured Earth with real-sun terminator | com.earth |
| render3d | software 3D demo (teapot, z-buffered rasterizer) | com.render3d |
| seismo | accelerometer nebula seismograph | com.seismo |
| radar | CZ weather radar composite + animation | com.radar |
| breathe | cyclic-sighing stress exercise | com.breathe |
| metronome | vibration metronome, 30–240 BPM | com.metronome |
| mic-clock | 32 s raw mic calibration capture | com.micclock |
| multirate | PPG stream-rate diagnostic | com.hrv.multirate |
| sport | sensor-hub rate diagnostic | com.hrv.sport |
| wifi-serve | "Pace Sync": AP + QR, serves mic recordings over HTTP | com.wifi.serve |
| wifi-provision | adds Wi-Fi networks from `/sdcard/wifi.json` | com.wifi.provision |
| filebrowser | sdcard file browser (text/image viewer) | com.hrv.files |

## Build & install

Prereqs: JDK 8+, Android SDK platform 36 + Build Tools 37.0.0, `adb`, `zip` (`ANDROID_HOME` or `~/Library/Android/sdk`). Debug keystore at `~/.android/debug.keystore` (create once with `keytool -genkeypair -keystore ~/.android/debug.keystore -storepass android -alias androiddebugkey -keypass android -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -validity 10000`).

```bash
make                 # build all apps (incremental: only changed) -> signed APKs in apks/builds/
make hrv             # build one app (any app dir name works)
make clean

adb install -r apks/builds/hrv.apk
adb shell am start -n com.hrv.hrv/.MainActivity
```

`apksigner verify --verbose apks/builds/<app>.apk` checks the signature. An update needs the same signing key; otherwise `adb uninstall <package>` first.

Host tests (pure Java, no device): each app has `test/` — e.g. the HRV regression:

```bash
javac -d /tmp/t hrv/src/com/hrv/hrv/HrvAnalyzer.java hrv/src/com/hrv/hrv/HrvSamples.java \
  hrv/test/com/hrv/hrv/HrvAnalyzerTest.java hrv/test/com/hrv/hrv/HrvSamplesTest.java
java -cp /tmp/t com.hrv.hrv.HrvAnalyzerTest captures/raw_ppg.csv   # expects "checks passed"
```

## Mic recordings

The watch dmic clocks correctly only at **16000 Hz** (other rates are pitch-warped). `mic` records raw 16 kHz mono PCM streaming straight to `/sdcard/mic/`; back exits immediately, home after a 3 s grace.

```bash
make mic && adb install -r apks/builds/mic.apk
./pull-recordings.sh    # downloads new captures to captures/mic/, clears the device
```

## Pace Sync (`wifi-serve`)

1. Launch — the watch starts AP **`PaceSync`** (WPA2, `pace-sync`) and shows QR #1.
2. Scan with the phone → connect.
3. Watch detects the phone (ARP) and shows QR #2: `http://<ap-ip>:8080`.
4. Phone browser: file list, per-file download, **Download all (.zip)**, **Clear recordings**.

```bash
make wifi-serve && adb install -r apks/builds/wifi-serve.apk
```

iOS cameras don't parse `WIFI:` QRs (third-party QR app needed). Run on the cradle — AP + screen-on drains the battery.

## App conventions

- **Shared core** (`common/src/com/hrv/common/`, on every app's classpath): UI activities extend `ProbeActivity` (headless diagnostics multirate/sport stay raw Activity) (screen-on, wakelock, brightness force/restore, exit behaviour); round-screen views extend `RoundView` (tuner viewport: center 160,160, r=152, density-scaled text). Pure-Java pieces — `WavWriter`, `Fft`, `SkyMath`, `Net`, `Klvp`, `MicAudio`, `Engine3d`+`Mesh` — are host-testable.
- **Exit on pause**: `ProbeActivity.hardKillOnPause()` = cleanup → `finish()` + `Process.killProcess` (default probe behaviour); `useGraceExit()` (mic, hrv) = exit 3 s after pause unless resumed — the process stays alive so the notification listener stays bound. `onExitCleanup()` runs before finishing and must be idempotent. Neither mode = no exit-on-pause (filebrowser, mic-clock).
- **Notification blocking** (mic, hrv): a `NotificationListenerService` (`NotifBlocker`) cancels every notification while the app is foreground — this ROM has no notification-access UI, so grant once via adb (persists across reboots; re-apply after force-stop/reboot to re-bind):

  ```bash
  adb shell settings put secure enabled_notification_listeners \
    com.hrv.mic/.NotifBlocker:com.hrv.hrv/.NotifBlocker
  ```

  `NotifBlocker` stays a per-app copy: the grant embeds the class name.
- Screen stays on while visible (`FLAG_KEEP_SCREEN_ON`).
- Vibration throws a cosmetic `RuntimeException` on this ROM after vibrating — wrap in try/catch.
- Bitmaps: decode with `inSampleSize` to display resolution — the watch heap can't hold full-res stacks.

## Launcher gotchas

- Icons must be **PNG bitmaps** (launcher crashes on vector icons).
- No `android:screenOrientation` (trips the launcher's `contain 3 creator` bug).
- `adb shell svc wifi disable` fails on this device; the app itself can toggle Wi-Fi.
- `adb logcat -c` doesn't clear the log buffer.

## Research docs

- [`HRV-FINDINGS.md`](HRV-FINDINGS.md) — HRV algorithms, evidence, limits
- [`PACE-FINDINGS.md`](PACE-FINDINGS.md) — device + sensor-hub reverse engineering
- [`MIC-FINDINGS.md`](MIC-FINDINGS.md) — mic capture findings
- [`SUMMARY.md`](SUMMARY.md) / [`HYPOTHESES.csv`](HYPOTHESES.csv) — findings summary + tracked hypotheses
- [`EMULATOR.md`](EMULATOR.md) — recreate the `pace` AVD (API 24, 320×300 @238 dpi, software GPU)
- [`SEISMO-NEBULA.md`](SEISMO-NEBULA.md) — seismo design notes
- `captures/raw_ppg.csv` — HRV regression fixture; `apks/system/` — stock system APKs (RE source); `firmware/` + `firmware-tools/` — sensor-hub artifacts

## Important limits

Experimental wearable research, not a medical device. No simultaneous ECG reference was recorded; at 25 Hz, interpolation can't recover information absent from the sampled waveform.
