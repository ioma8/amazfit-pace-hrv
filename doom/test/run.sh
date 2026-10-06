#!/bin/sh
# Host checks for the doom app — no watch, no NDK needed for the first two.
#   1. WadFileTest  - the shipped WAD is bootable, bad /sdcard WADs are rejected
#   2. LayoutCheck  - controls fit the lit circle (rects read from DoomView.java)
#   3. host-smoke   - the engine + the shipped WAD render (same argv as the APK)
set -e

cd "$(dirname "$0")/../.."        # repo root
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT

javac -d "$out" \
    doom/src/com/doom/WadFile.java \
    doom/test/com/doom/WadFileTest.java \
    doom/test/com/doom/LayoutCheck.java
java -cp "$out" com.doom.WadFileTest doom/assets/freedoom1.wad
java -cp "$out" com.doom.LayoutCheck doom/src/com/doom/DoomView.java

make -C doom/jni host-smoke WAD=../assets/freedoom1.wad | tail -1
