# Amazfit Pace emulator (AVD `pace`)

How to recreate the emulator used to validate the watch apps, run it, and
re-run the Pace Sync validation. Everything here was executed and verified on
this machine (macOS, Apple Silicon).

## Fidelity vs. the real watch

| | Watch A1612 | Emulator `pace` |
|---|---|---|
| Android | 5.1 (API 22) | 7.0 (API 24) |
| Screen | 320×300 round @ 238 dpi | 320×300 @ 238 dpi (square), fullscreen, no bars |
| RAM | 477 MB | 1024 MB — the emulator raises anything lower (`Increasing RAM size to 1024MB`), so the watch's 477 MB cannot be reproduced |
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
hw.ramSize = 1024
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

## Validating Doom (playbook)

`doom` is the one app that is not pure Java: it ships `lib/mips/libdoom.so` for the
watch and `lib/arm64-v8a/libdoom.so` for this emulator, built from the same
sources by `doom/jni/Makefile` (`make -C doom/jni` builds both). The emulator has
**no MIPS backend** — its QEMU guests are only `aarch64`/`armel` — and the only
MIPS system images are API 16/17, below the app's `minSdkVersion 22`. So the
emulator validates everything *except* the MIPS instruction encoding itself,
which is covered by the ELF/linker evidence and by `host-smoke` instead.

```bash
export ANDROID_SDK_ROOT=${ANDROID_HOME:-$HOME/Library/Android/sdk}
"$ANDROID_SDK_ROOT/emulator/emulator" -avd pace -scale 2 \
  -gpu swiftshader_indirect -prop qemu.hw.mainkeys=1 &

ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
make doom
$ADB install -r apks/builds/doom.apk
$ADB shell am start -n com.doom/.MainActivity
$ADB logcat -s doom                  # unpacked ... -> doomgeneric starting
```

Observed on this AVD (2026-10-06):

| Check | Result |
|---|---|
| process alive, no app crash | pid present after 12 s; the crash buffer holds only the emulator's own boot-time `BatteryService` NPE |
| IWAD fallback + unpack | `no usable IWAD on /sdcard, falling back to freedoom1.wad` then `unpacked ... /data/user/0/com.doom/files/freedoom1.wad` |
| framebuffer geometry | black bars top (`y0-28`) and bottom; the game exactly `32,80`–`288,240`; controls exactly on their rects |
| engine live | 36% of the game rect changed per 4 s, and the change bbox was the game rect only |
| touch → native | `adb shell input tap 110 51` was dequeued by the engine as `key=27`; `tap 160 51` as `key=13` |
| menu → playable level | ESC then ENTER ×3 logged `G_DoLoadLevel map=1` (E1M1) — a real game starts |
| memory | ~27 MB PSS, ~20 MB of it native heap (the 16 MB Doom zone), so it fits the watch's 477 MB |
| frame pacing | 50th/90th percentile 5 ms, 1.7% janky — **emulator only**; a native arm64 build on an M-series host says nothing about the 1 GHz MIPS watch |

Drive it from the on-screen `ESC`/`ENT` buttons (the watch has no keys). While a
game runs the world keeps simulating behind the menu — Doom's ESC does not pause,
only the PAUSE key does — so "the picture is still moving" is not evidence that
input failed.

## MIPS probe (legacy emulator, proven)

The shipped `lib/mips/libdoom.so` had never executed on MIPS — no MIPS backend ships
with the current emulator and no MIPS system image exists above API 17. It *can*
be run, though, with Google's legacy emulator (25.2.5, still hosted) whose
`tools/qemu/darwin-x86_64/qemu-system-mipsel` is an x86_64 binary that runs under
Rosetta 2 on Apple Silicon. Caveat up front: the guest is **Android 4.2/API 17**,
so this proves the MIPS code executes, not that it behaves like the watch's 5.1.

```bash
# 1. legacy emulator (200 MB; extract somewhere durable, e.g. ~/android-legacy)
curl -o /tmp/tools.zip https://dl.google.com/android/repository/tools_r25.2.5-macosx.zip
mkdir -p ~/android-legacy && (cd ~/android-legacy && unzip -q /tmp/tools.zip 'tools/*')

# 2. the only MIPS image there is, plus an AVD with the watch's panel geometry
sdkmanager "system-images;android-17;default;mips"
echo no | avdmanager create avd -n mips17 -k "system-images;android-17;default;mips" --force
cat >> ~/.android/avd/mips17.avd/config.ini <<'INI'
hw.lcd.width = 320
hw.lcd.height = 300
hw.lcd.density = 238
skin.name = 320x300
skin.path = _no_skin
hw.ramSize = 477
INI

# 3. boot (Qt dylibs must be on DYLD_LIBRARY_PATH; ANDROID_SDK_ROOT must be exported)
ANDROID_SDK_ROOT=$HOME/Library/Android/sdk \
DYLD_LIBRARY_PATH=$HOME/android-legacy/tools/lib64/qt/lib \
  ~/android-legacy/tools/emulator64-mips -avd mips17 -no-window -no-audio \
  -no-snapshot -no-boot-anim -gpu off &
```

For this probe only, the app needs four throwaway edits (the shipped config is
`minSdkVersion 22` and API 21+): `minSdkVersion 22` → `17` in the manifest;
`Theme.Material.NoActionBar` → `Theme.Holo.NoActionBar` in `res/values/styles.xml`
(`Theme.Material` is API 21+); the MIPS sysroot in `doom/jni/Makefile` from
`platforms/android-21` to `android-17` and `D__ANDROID_API__=17`; and
`MainActivity.unpack`'s try-with-resources rewritten as try/finally, because
`AutoCloseable` is API 19. Then `make doom`, `adb install -r`, and
`adb shell am start -n com.doom/.MainActivity`. `git checkout -- <those files>`
afterwards. Note `/sdcard` is not mounted on this AVD, so the app takes its
"no usable IWAD on /sdcard" fallback — which incidentally exercises WadFile.

Result (2026-10-06), the answers we could not get any other way:

```
D/dalvikvm: Added shared lib /data/app-lib/com.doom-1/libdoom.so   <- MIPS .so loads
I/doom: no usable IWAD on /sdcard, falling back to freedoom1.wad
I/doom: unpacked freedoom1.wad to /data/data/com.doom/files/freedoom1.wad
I/doom: doomgeneric starting, iwad=/data/data/com.doom/files/freedoom1.wad
```

No `UnsatisfiedLinkError`, no crash, process alive at ~18% guest CPU; the screen
shows the game in its rect and 36% of it changed over 20 s (animating). The MIPS
build also links against the **android-17** stubs, so it needs nothing newer than
API 17 — the shipped android-21 build therefore has no symbol-version risk on the
API 22 watch. What this still does not measure is speed: a TCG guest inside
Rosetta is orders of magnitude slower than the watch's 1 GHz XBurst, so watch
framerate remains unknown pending hardware.

One caveat for reproducing it: this path is flaky under Rosetta — after roughly
fifteen minutes of emulation the process died with
`rosetta error: unexpectedly got a signal in sigtramp` (a Rosetta signal-trampoline
fault in that 2016 binary, not anything in the app). Take your evidence early; a
re-run just means booting it again, the `mips17` AVD survives.

## What cannot be validated on the emulator

- **AP mode** — no WiFi hardware at API 24. The app's status line will read
  "AP failed (ROM gate?)"; that failure path is itself validated, the success
  path is not.
- **Phone scan/join/browse flow** — needs a real phone + real AP.
- **Mic capture** — audio input is disabled (`hw.audioInput = no`).
- **MIPS code execution** — no MIPS QEMU backend on an arm64 host, and no MIPS
  image above API 17. `doom` therefore runs here as an arm64 build; the watch
  loads the MIPS one. See the Doom playbook above for what that leaves unproven.
- **Anything timing-sensitive** — the emulator runs the guest natively on a much
  faster CPU, so a comfortable result here says nothing about the 1 GHz watch
  (the seismo/nebula work needed a URGENT_AUDIO render thread precisely because
  the real core is slow).

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
