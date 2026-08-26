#!/bin/bash
# Build wifi-serve APK (pure Java + vendored zxing core). Produces aligned.apk (signed).
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/37.0.0
AJ=$SDK/platforms/android-36/android.jar
rm -rf obj dexout
mkdir -p obj dexout
javac --release 8 -classpath "$AJ:libs/core-3.5.3.jar" -d obj $(find src -name '*.java')
"$BT/d8" --lib "$AJ" --output dexout $(find obj -name '*.class') libs/core-3.5.3.jar
"$BT/aapt" package -f -M AndroidManifest.xml -S res -I "$AJ" -F unsigned.apk
(cd dexout && zip -q -0 ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 unsigned.apk aligned.apk
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
    --ks-key-alias androiddebugkey aligned.apk
echo "built: $PWD/aligned.apk"
