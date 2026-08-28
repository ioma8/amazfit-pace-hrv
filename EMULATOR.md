# Amazfit Pace emulator (AVD `pace`)

How to recreate the emulator used to validate the watch apps, run it, and
re-run the Pace Sync validation. Everything here was executed and verified on
this machine (macOS, Apple Silicon).

## Fidelity vs. the real watch

| | Watch A1612 | Emulator `pace` |
|---|---|---|
| Android | 5.1 (API 22) | 7.0 (API 24) |
| Screen | 320×300 round @ 238 dpi | 320×300 @ 238 dpi (square), fullscreen, no bars |
| RAM | 477 MB | 1536 MB |
| CPU | MIPS32r1 XBurst | arm64 (4 vCPU) |
| WiFi | real (client + AP) | **none at API 24** — AP mode untestable |
| Root | none (uid 2000) | adb root available (unused) |

Why API 24: the watch runs Android 5.1, but on Apple Silicon the emulator can
only run arm64 guests — arm64 system images start at API 24, and x86 images
are not supported on ARM hosts. API 24 is therefore the closest possible
match. App semantics still line up: `targetSdkVersion 22` keeps install-time
permission grants (no runtime prompts), and the hidden `WifiManager`
AP methods (`setWifiApEnabled`/`setWifiApConfiguration`) still exist at API
24 (they were removed at API 29).

## One-time setup

Prerequisites: JDK 17+, ~3.5 GB free disk, network access. `SDK` below is
`$HOME/Library/Android/sdk` (or `$ANDROID_HOME`).

### 1. cmdline-tools (if missing)

```bash
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
mkdir -p /tmp/ctl && cd /tmp/ctl
curl -fsSL -o ctl.zip https://dl.google.com/android/repository/commandlinetools-mac-11076708_latest.zip
unzip -q -o ctl.zip
mkdir -p "$SDK/cmdline-tools"
rm -rf "$SDK/cmdline-tools/latest" && mv cmdline-tools "$SDK/cmdline-tools/latest"
```

### 2. Accept licenses and install the system image

```bash
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses
"$SDK/cmdline-tools/latest/bin/sdkmanager" "system-images;android-24;default;arm64-v8a"
```

`package.xml` warnings about unexpected elements are harmless (they come from
newer ps16k images in the same SDK).

### 3. Create the AVD

```bash
echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n pace \
  -k "system-images;android-24;default;arm64-v8a" --force
```

### 4. Write the hardware profile

Replace `~/.android/avd/pace.avd/config.ini` with exactly this (the emulator
normalizes formatting and fills defaults on first boot; these are the values
that matter):

```ini
avd.ini.encoding = UTF-8
abi.type = arm64-v8a
hw.cpu.arch = arm64
hw.cpu.ncore = 4
hw.ramSize = 1536
hw.lcd.width = 320
hw.lcd.height = 300
hw.lcd.density = 238
hw.lcd.backlight = 100
hw.gpu.enabled = yes
hw.gpu.mode = swiftshader_indirect
hw.keyboard = yes
hw.mainKeys = no
hw.audioInput = no
hw.audioOutput = no
hw.camera.back = none
hw.camera.front = none
hw.sdCard = no
disk.dataPartition.size = 6442450944
skin.name = 320x300
skin.path = _no_skin
fastboot.forceColdBoot = yes
image.sysdir.1 = system-images/android-24/default/arm64-v8a/
```

**`image.sysdir.1` is mandatory.** If it is missing the emulator dies with
`FATAL: Broken AVD system path` — this happens if you overwrite the config
after `avdmanager` created it. `avdmanager delete avd -n pace` + recreate is
the clean redo path.

## Running

The emulator needs `ANDROID_SDK_ROOT` or it exits with
`FATAL: Cannot find AVD system path`:

```bash
export ANDROID_SDK_ROOT=${ANDROID_HOME:-$HOME/Library/Android/sdk}

# headed (watch window, click = tap):
"$ANDROID_SDK_ROOT/emulator/emulator" -avd pace -scale 2 \
  -prop qemu.hw.mainkeys=1 &

# headless (CI/screenshots only):
"$ANDROID_SDK_ROOT/emulator/emulator" -avd pace -no-window -no-audio \
  -no-boot-anim -no-snapshot -gpu swiftshader_indirect \
  -prop qemu.hw.mainkeys=1 \
  -netdelay none -netspeed full &
```

`-prop qemu.hw.mainkeys=1` makes the framework believe the device has
hardware keys, so the **nav bar never exists** (the watch has none; a plain
`hw.mainKeys = no` in the config does *not* translate to this prop on this
emulator). The status bar is hidden with a persisted setting (the watch has
none either):

```bash
"$ANDROID_SDK_ROOT/platform-tools/adb" shell settings put global policy_control immersive.full=*
```

That setting lives in the AVD's data partition, so it survives reboots — apply
it once per AVD. Result: apps render fullscreen 320×300, exactly like the
watch panel.

Wait for boot and verify the panel metrics:

```bash
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
$ADB wait-for-device
until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 5; done
$ADB shell wm size      # expect: Physical size: 320x300
$ADB shell wm density   # expect: Physical density: 238
$ADB shell getprop qemu.hw.mainkeys   # expect: 1 (no nav bar)
```

Observed boots: ~20–60 s cold, faster on rerun (no snapshots used).
Stop the emulator with `$ADB emu kill`.

## Validating Pace Sync (playbook)

All commands from the repository root. Expected outputs are shown.

```bash
# 0. (once per AVD) watch-like fullscreen — no status bar, no nav bar
"$ANDROID_SDK_ROOT/platform-tools/adb" shell settings put global policy_control immersive.full=*

# 1. build + install
make wifi-serve
"$ANDROID_SDK_ROOT/platform-tools/adb" install -r apks/builds/wifi-serve.apk

# 2. sample recordings (16 kHz mono WAVs named like the watch produces them)
"$ANDROID_SDK_ROOT/platform-tools/adb" shell mkdir -p /sdcard/mic
"$ANDROID_SDK_ROOT/platform-tools/adb" push /tmp/mic_16000_*.wav /sdcard/mic/

# 3. launch
"$ANDROID_SDK_ROOT/platform-tools/adb" shell am start -n com.wifi.serve/.MainActivity
```

### QR rendering (proves the on-screen QR is scannable)

```bash
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
$ADB exec-out screencap -p > /tmp/phase.png
# compile the decode tool once:
javac -cp wifi-serve/libs/core-3.5.3.jar -d /tmp \
  wifi-serve/tools/QrScreenDecode.java
java -cp /tmp:wifi-serve/libs/core-3.5.3.jar QrScreenDecode /tmp/phase.png
```

Expected: `DECODED: WIFI:T:WPA;S:PaceSync;P:pace-sync;;` (phase 1) or
`DECODED: http://10.0.2.15:8080` (phase 2). Toggle phases with
`$ADB shell input tap 160 150` — the app also auto-advances to phase 2 when a
client appears in the AP subnet's ARP table (on the emulator the slirp gateway
10.0.2.2 triggers it; on the watch a phone MAC does).

### HTTP surface (through the emulator's NAT)

```bash
$ADB forward tcp:18080 tcp:8080
curl -s http://localhost:18080/                     # HTML: 3 recordings listed
curl -s http://localhost:18080/file?n=mic_16000_20260826_120000.wav | wc -c  # 64044
curl -s -o /tmp/all.zip http://localhost:18080/all.zip && unzip -l /tmp/all.zip  # 3 entries
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:18080/generate_204    # 302
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:18080/clear   # 302, dir emptied
```

### Lifecycle

```bash
$ADB shell pidof com.wifi.serve                      # some pid
$ADB shell input keyevent KEYCODE_BACK
$ADB shell pidof com.wifi.serve                      # empty — process killed
$ADB logcat -d | grep -E 'FATAL.*wifi|AndroidRuntime'  # nothing from the app
```

A `BatteryService` FATAL in logcat dated at boot time is a known API 24 arm64
quirk of the emulator itself — ignore it.

## What cannot be validated on the emulator

- **AP mode** — no WiFi hardware at API 24. The app's status line will read
  "AP failed (ROM gate?)"; that failure path is itself validated, the success
  path is not.
- **Phone scan/join/browse flow** — needs a real phone + real AP.
- **Mic capture** — audio input is disabled (`hw.audioInput = no`).

These remain on-device tests for the watch.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `FATAL: Cannot find AVD system path` | `ANDROID_SDK_ROOT` unset — export it (see Running) |
| `FATAL: Broken AVD system path` | `image.sysdir.1` missing from `config.ini` — add it or recreate the AVD |
| Boot > 5 min / stuck | First cold boot is slow; give it 2 min more. `rm -f ~/.android/avd/pace.avd/*.lock` and relaunch if wedged |
| Window too small | `-scale 2` (640×600) or `-scale 3` |
| Rendering glitches | `-gpu swiftshader_indirect` (already the config default) |
| Status bar / nav bar visible | Missed `-prop qemu.hw.mainkeys=1` or the `policy_control` setting — see Running |
| `sdkmanager` package.xml warnings | Harmless — newer image metadata in the same SDK |
| AVD won't start, port in use | Another emulator running (`adb devices`); kill it with `adb emu kill` |

## Helper files

- `wifi-serve/emulate.sh` — `--create` (steps 1–4 above) or boot-check +
  install + launch against a running emulator.
- `wifi-serve/tools/QrScreenDecode.java` — decodes a QR from a screenshot
  (needs `libs/core-3.5.3.jar`; host JDK only).
