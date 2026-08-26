#!/bin/bash
# Boot/use the "pace" emulator (Amazfit-Pace-like: 320x300 @ 238dpi, API 24).
# Usage:
#   ./wifi-serve/emulate.sh            # install + push samples + launch app
#   ./wifi-serve/emulate.sh --create   # one-time AVD creation (needs sdkmanager)
# Start the emulator watch-like (no nav bar) with:
#   $SDK/emulator/emulator -avd pace -scale 2 -prop qemu.hw.mainkeys=1
set -e
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB=$SDK/platform-tools/adb
EMU=$SDK/emulator/emulator

if [ "$1" = "--create" ]; then
    IMG=system-images;android-24;default;arm64-v8a
    yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
    "$SDK/cmdline-tools/latest/bin/sdkmanager" "$IMG" >/dev/null
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n pace -k "$IMG" --force >/dev/null
    cat >> ~/.android/avd/pace.avd/config.ini <<'CONF'
hw.lcd.width=320
hw.lcd.height=300
hw.lcd.density=238
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.ramSize=1536
image.sysdir.1=system-images/android-24/default/arm64-v8a/
CONF
    echo "AVD 'pace' created. Boot it: $EMU -avd pace -scale 2 &"
    exit 0
fi

if ! "$ADB" devices | grep -q '^emulator-'; then
    echo "No emulator running. Start it first:"
    echo "  $EMU -avd pace -scale 2 -prop qemu.hw.mainkeys=1"
    exit 1
fi

"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done

cd "$(dirname "$0")"
"$ADB" install -r wifi-serve/aligned.apk
"$ADB" shell mkdir -p /sdcard/mic-probe
"$ADB" shell settings put global policy_control immersive.full=*   # hide status bar
"$ADB" shell am start -n com.wifi.serve/.MainActivity
echo "Pace Sync running on the emulator."
