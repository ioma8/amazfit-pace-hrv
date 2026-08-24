# Amazfit Pace HRV

Rootless raw-PPG capture, HRV analysis, and sensor-hub research for the Amazfit Pace A1612.

The watch exposes one usable PPG sample per Android sensor event at approximately 25.4 Hz. Events arrive in five-sample transport bursts, so this project reconstructs a uniform sample clock, detects pulse peaks with adaptive local prominence, rejects artifacts without bridging them, and computes time-domain HRV plus an LF resonance score.

## Current result

The included 59.7-second on-wrist fixture contains 1,507 raw samples. The calibrated analyzer finds 82 pulse peaks and produces:

```text
HR       83.21 bpm
RMSSD    40.02 ms
SDNN     70.37 ms
Score    40.31%
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

## Important limits

This is experimental wearable research, not a medical device. The calculations match the captured PPG pulse timing, but no simultaneous ECG reference was recorded. At 25 Hz, interpolation improves smooth peak timing but cannot recover information absent from the sampled waveform.
