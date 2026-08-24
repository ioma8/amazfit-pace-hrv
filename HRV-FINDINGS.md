# Amazfit Pace 25 Hz HRV Findings

## Scope

This document records the implemented HRV pipeline, the raw PPG evidence used to calibrate it, the failures found in earlier versions, and the current limits of the result.

The implementation is under `hrv-probe/src/com/hrv/probe/`. The captured regression fixture is `captures/raw_ppg.csv`.

## Sensor and Timing Facts

The Amazfit Pace exposes raw optical data through Android sensor type `65538` (`com.huami.watch.ppg`). The application starts continuous measurement with the sensor-hub KLVP request payload `D0 02 01` and stops it with `D0 02 00`.

The application receives approximately 25.2–25.8 PPG samples per second. Each `SensorEvent` has 16 values, but only `values[0]` is the changing PPG sample. The remaining values are sensor metadata or constant/zero fields; they are not additional time samples.

Sensor callbacks do not arrive uniformly. The hub commonly delivers approximately five historical samples in a burst every 200 ms. Callback arrival timestamps therefore cannot be used as individual pulse timestamps. Doing so previously inflated RMSSD from approximately 37–41 ms to 147–168 ms while leaving mean heart rate nearly unchanged.

The current analyzer reconstructs one uniform sample clock from the first and last callback timestamps:

```text
dt = (last_callback_time - first_callback_time) / (sample_count - 1)
peak_time = fractional_peak_sample_index * dt
```

This preserves the measured average sampling rate while removing callback batching jitter.

## Captured PPG Fixture

`captures/raw_ppg.csv` contains the first 1,507 samples from a live on-wrist run:

```text
Duration:          59.7166 s
Measured rate:     25.2359 Hz
PPG value range:   33,852–35,446
PPG standard dev.: 317.16
Sensor values:     16 per event
```

CSV columns:

```text
index,time_ns,value,count,values
```

The complete 16-value `SensorEvent.values[]` array is retained in the final semicolon-separated column.

Callback timing in this capture confirms hub batching:

```text
Median callback delta:       0.318 ms
Mean callback delta:        39.652 ms
Deltas below 2 ms:           1,183
Deltas above 50 ms:            295
```

The mean rate across the full capture—not individual callback deltas—is the usable sample clock.

## Root Causes Found

### Bursty callback timestamps inflated HRV

Earlier implementations treated `System.nanoTime()` at callback arrival as each sample time. Five-sample callback bursts created almost-zero intervals inside a burst and approximately 200 ms gaps between bursts. Peak locations were correct, but beat timing was not.

The uniform reconstructed sample clock fixes this failure.

### Absolute peak height hid real beats

The earlier detector required the detrended waveform peak to exceed a global absolute threshold. Slow PPG baseline movement pushed valid pulse peaks below zero during part of each breathing cycle. Their local prominence remained clear, but the absolute-height condition rejected them.

On `captures/raw_ppg.csv`, this produced only 57 detected peaks and repeated false gaps of approximately 3.4–4.1 seconds.

The calibrated detector uses local prominence relative to robust noise and no absolute waveform level. It finds 82 visible pulse peaks and 81 intervals in the same capture with no implausible multi-second gaps.

### Removing intervals and joining their neighbors inflated RMSSD

A rejected interval was previously removed from the list. RMSSD was then computed over the shortened list, making the intervals on either side appear consecutive even though a missing or false beat separated them. This converted one artifact into a large successive difference.

The current calculation retains an interval-validity mask. RMSSD includes a pair only when both original adjacent intervals are valid. It never bridges an artifact.

### Dicrotic-peak merge used the wrong invariant

A false dicrotic peak divides one real beat interval into two short intervals. Their sum should therefore be approximately one median IBI. The older condition compared their sum with two median IBIs, which could create a double-length artifact.

The current merge accepts two short intervals only when:

```text
abs((short_1 + short_2) - median_ibi) <= 0.25 * median_ibi
```

### Spectral scoring closed artifact gaps

Removing invalid intervals before constructing the score timeline shortened elapsed time and shifted spectral power. The current implementation retains real cumulative time across rejected intervals and interpolates between valid NN measurements on that timeline.

### Shutdown could run one final analysis

If `onPause()` stopped measurement while the analysis thread was sleeping, the loop completed one more analysis and emitted a metric log after `stopped: LED off`.

The loop now checks `running` immediately after waking and exits before analysis. Sensor unregistration, HR LED shutdown, handler-thread shutdown, and wake-lock release remain idempotent.

## Current Signal Processing

`HrvAnalyzer` performs the following steps:

1. Require at least 305 samples, approximately 12 seconds at the measured rate.
2. Reconstruct the uniform sample period from the complete window.
3. Reject windows outside the observed 24–27 Hz range.
4. Remove baseline drift with a 1.6-second moving average.
5. Apply a three-point weighted smoother `[1, 2, 1] / 4`.
6. Estimate robust noise from the median absolute first difference multiplied by `1.4826`.
7. Detect local maxima whose bilateral prominence is at least `1.4 × robust_noise`.
8. Estimate sub-sample peak positions with parabolic interpolation, limited to ±0.5 sample.
9. Apply a 380 ms refractory period and keep the stronger candidate when two candidates conflict.
10. Build IBIs from fractional peak positions on the uniform sample clock.
11. Repair a split interval only when two short intervals sum to approximately one median IBI.
12. Reject intervals outside 350–2,000 ms, outside 0.58–1.55 times the global median, or more than 28% from the local five-interval median.
13. Compute HR, sample SDNN, and RMSSD only from valid NN intervals; RMSSD never crosses an invalid interval.
14. Compute the coherence score on a 4 Hz linearly interpolated NN series using its real elapsed timeline.

The 55-second sliding analysis window limits drift and prevents old transient data from dominating current metrics.

## Metrics on the Captured Data

The calibrated analyzer produces:

```text
Detected peaks: 82
Intervals:      81
Clean intervals:81/81
IBI range:      596–857 ms
Heart rate:     83.21 bpm
RMSSD:          40.02 ms
SDNN:           70.37 ms
Score:          40.31%
```

The IBI sequence changes smoothly through the approximately 0.1 Hz paced-breathing cycle visible in the capture. The 596–857 ms range corresponds to the observed respiratory sinus arrhythmia rather than isolated timing jumps.

The previous detector on the same data returned approximately:

```text
Heart rate:      81.24 bpm
RMSSD:           25.34 ms
SDNN:            61.16 ms
Score:            2.56%
Clean intervals: 51/56
```

That result was biased because the absolute-height threshold discarded low-baseline pulses. The calibrated result uses every visible pulse and therefore captures substantially more of the real beat-to-beat variation.

## Live Watch Verification

A subsequent on-wrist V18 run produced:

```text
t+16s HR=84.9 RMSSD=35.8ms SDNN=56.5ms score=0%  clean=16/17
t+32s HR=86.8 RMSSD=31.3ms SDNN=57.1ms score=12% clean=40/41
t+49s HR=85.2 RMSSD=35.1ms SDNN=60.3ms score=37% clean=63/64
t+64s HR=86.0 RMSSD=34.5ms SDNN=61.9ms score=36% clean=75/76
```

No `AndroidRuntime` crash occurred. The KLVP request returned sensor-hub response code `90`, PPG registration succeeded, and cleanup turned the LED off.

A zero score during the first short window means the frequency-domain score does not yet have its required clean time span; it should not be interpreted as a physiologically measured zero-coherence state.

## HRV Score Algorithm

The score was aligned with `/Users/jakubkolcar/projects/customs/emwave-utils/src/metrics.rs`. It is a resonance/coherence score, not a wellness score derived directly from RMSSD.

The algorithm:

1. Represent valid NN intervals against their physiological timestamps.
2. Resample the NN series to 4 Hz with linear interpolation.
3. Remove a linear trend and apply a Hann window.
4. Compute a direct power spectrum.
5. Sum LF power from 0.04 to 0.15 Hz and HF power from 0.15 to 0.40 Hz.
6. Compute normalized LF power:

```text
LF_nu = LF / (LF + HF) * 100
```

7. Find the strongest LF bin and compare it with the median LF-bin power.
8. Convert peak prominence to a bounded concentration:

```text
concentration = clamp(log10(max(1, peak_power / median_lf_power)) / 2, 0, 1)
```

9. Calculate and explicitly bound the score:

```text
score = clamp(LF_nu * concentration, 0, 100)
```

The prior exponential `RMSSD → percent` mapping was removed. High RMSSD alone does not imply coherent paced breathing, and that mapping saturated near 100% for ordinary RMSSD values.

## UI and Source Organization

The current source responsibilities are separated as follows:

- `MainActivity.java`: Android lifecycle, sensor-hub control, and orchestration.
- `HrvSamples.java`: synchronized raw PPG storage and window snapshots.
- `HrvAnalyzer.java`: pure beat extraction, artifact handling, metrics, and score.
- `PpgWaveform.java`: causal display-only filtering; it does not alter analysis data.
- `HrvView.java`: rendering, score ring, waveform, and breathing pacer.

The breathing pacer is a horizontal bar: green for inhale and blue for exhale. The live waveform uses a separate baseline-removal/smoothing path and RMS-based display scaling so isolated samples do not flatten visible pulses.

## Regression Verification

The permanent local check is:

```text
hrv-probe/test/com/hrv/probe/HrvAnalyzerTest.java
```

Run it with:

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

The checks protect:

- the exact captured peak/interval count;
- captured HR, RMSSD, SDNN, and score ranges;
- distinction between steady and genuinely variable synthetic pulse timing;
- baseline drift and isolated optical spikes;
- the approximately 12-second initial metric window;
- the score's `0..100` bound.

## Current Limits

- The calibration is grounded in one complete raw on-wrist capture plus subsequent live metric logs. More captures across users, heart rates, motion levels, skin contact, and LED modes are required before broad population claims.
- There is no simultaneous ECG reference. The metrics match the pulse timing present in the PPG data, but absolute clinical accuracy has not been established.
- At approximately 25 Hz, one raw sample is about 39.6 ms apart. Parabolic interpolation improves peak timing when pulse morphology is smooth, but it cannot create information absent from the sampled waveform.
- Motion artifacts can still remove usable intervals. The analyzer rejects questionable NN pairs rather than inventing replacements.
- Frequency-domain coherence needs a longer window than basic HR/RMSSD. Early score zero is currently also used as the unavailable value in the UI.
- The score measures LF resonance concentration. It is not a diagnosis, stress measurement, or universal health percentage.

## Artifacts

- Production APK: `hrv-probe/hrv-probe.apk`
- Raw captured fixture: `captures/raw_ppg.csv`
- Analyzer: `hrv-probe/src/com/hrv/probe/HrvAnalyzer.java`
- Regression check: `hrv-probe/test/com/hrv/probe/HrvAnalyzerTest.java`
- Debugging audit trail: `HYPOTHESES.csv`
- Broader watch reverse-engineering findings: `PACE-FINDINGS.md`
