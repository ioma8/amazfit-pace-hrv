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

## Important limits

This is experimental wearable research, not a medical device. The calculations match the captured PPG pulse timing, but no simultaneous ECG reference was recorded. At 25 Hz, interpolation improves smooth peak timing but cannot recover information absent from the sampled waveform.
