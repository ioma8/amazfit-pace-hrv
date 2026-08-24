#!/bin/bash
# Pull new mic recordings from the watch and clear the device folder.
# Each pulled file is prefixed with the current date+time (pull time).
# Usage: ./pull-recordings.sh   [ADB_SERIAL=serial ./pull-recordings.sh]
set -euo pipefail
cd "$(dirname "$0")"
OUT="captures/mic-probe"
DEV="/sdcard/mic-probe"
ADB=(adb)
[ -n "${ADB_SERIAL:-}" ] && ADB=(adb -s "$ADB_SERIAL")

mkdir -p "$OUT"
files=$("${ADB[@]}" shell "ls $DEV/*.wav 2>/dev/null" | tr -d '\r')
if [ -z "$files" ]; then
    echo "nothing to pull (no .wav in $DEV)"
    exit 0
fi

now=$(date +%Y%m%d_%H%M%S)
i=0
while IFS= read -r f; do
    [ -z "$f" ] && continue
    name=$(basename "$f")
    "${ADB[@]}" pull "$DEV/$name" "$OUT/${now}_${name}" >/dev/null
    echo "pulled: $name -> $OUT/${now}_${name}"
    i=$((i + 1))
done <<< "$files"

"${ADB[@]}" shell "rm -f $DEV/*.wav"
echo "cleared $i file(s) from device $DEV"
