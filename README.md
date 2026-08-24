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
- [`wifi-provision/`](wifi-provision/) — one-shot Wi-Fi network provisioning helper
- [`captures/raw_ppg.csv`](captures/raw_ppg.csv) — captured regression fixture
- [`HRV-FINDINGS.md`](HRV-FINDINGS.md) — algorithms, evidence, failures, and limits
- [`PACE-FINDINGS.md`](PACE-FINDINGS.md) — device and sensor-hub reverse engineering
- [`SUMMARY.md`](SUMMARY.md) — concise project findings
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

## Launcher compatibility gotchas

- App icons must be **PNG bitmaps** — the stock launcher crashes (`VectorDrawable cannot be cast to BitmapDrawable`) on vector icons.
- Do **not** set `android:screenOrientation` — it forces a config change that relaunches the launcher and trips its `contain 3 creator` bug.
- `adb shell svc wifi disable` fails on this device (shell lacks `CHANGE_WIFI_STATE`); the app itself can toggle Wi-Fi.
- `adb logcat -c` does not clear the log buffer on this watch.

## Important limits

This is experimental wearable research, not a medical device. The calculations match the captured PPG pulse timing, but no simultaneous ECG reference was recorded. At 25 Hz, interpolation improves smooth peak timing but cannot recover information absent from the sampled waveform.
