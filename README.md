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
- [`mic-probe/`](mic-probe/) — mic capture app with UI (record/stop, live waveform, speech DSP)
- [`pull-recordings.sh`](pull-recordings.sh) — downloads new watch recordings, clears the device
- [`captures/raw_ppg.csv`](captures/raw_ppg.csv) — captured regression fixture
- [`HRV-FINDINGS.md`](HRV-FINDINGS.md) — algorithms, evidence, failures, and limits
- [`PACE-FINDINGS.md`](PACE-FINDINGS.md) — device and sensor-hub reverse engineering
- [`MIC-FINDINGS.md`](MIC-FINDINGS.md) — mic capture findings (only 16 kHz is usable)
- [`SUMMARY.md`](SUMMARY.md) — concise project findings
- [`firmware/`](firmware/) and [`firmware-tools/`](firmware-tools/) — sensor-hub research artifacts

## Local analyzer check

```bash
rm -rf /tmp/hrv-tests
javac -d /tmp/hrv-tests \
  hrv-probe/src/com/hrv/probe/HrvAnalyzer.java \
  hrv-probe/test/com/hrv/probe/HrvAnalyzerTest.java
java -cp /tmp/hrv-tests \
  com.hrv.probe.HrvAnalyzerTest captures/raw_ppg.csv
```

Expected output:

```text
HrvAnalyzer checks passed
```

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

## Important limits

This is experimental wearable research, not a medical device. The calculations match the captured PPG pulse timing, but no simultaneous ECG reference was recorded. At 25 Hz, interpolation improves smooth peak timing but cannot recover information absent from the sampled waveform.
