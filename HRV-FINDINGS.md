# Amazfit Pace 25 Hz HRV Findings

## Scope

This document records the implemented HRV pipeline, the raw PPG evidence used to calibrate it, the failures found in earlier versions, and the current limits of the result.

The implementation is under `hrv-probe/src/com/hrv/probe/`. The captured regression fixture is `captures/raw_ppg.csv`.

## Sensor and Timing Facts

The Amazfit Pace exposes raw optical data through Android sensor type `65538` (`com.huami.watch.ppg`). The application starts continuous measurement with the sensor-hub KLVP request payload `D0 02 01` and stops it with `D0 02 00`.

The application receives approximately 25.2–25.8 PPG samples per second. Each `SensorEvent` has 16 values, but only `values[0]` is the changing PPG sample. The remaining values are sensor metadata or constant/zero fields; they are not additional time samples.

Sensor callbacks do not arrive uniformly. The hub commonly delivers approximately five historical samples in a burst every 200 ms. Callback arrival timestamps therefore cannot be used as individual pulse timestamps. Doing so previously inflated RMSSD from approximately 37–41 ms to 147–168 ms while leaving mean heart rate nearly unchanged.

The current analyzer fits one uniform sample clock by least squares over every callback time and its sample index:

```text
dt = slope(sample_index, callback_elapsed_time)
peak_time = fractional_peak_sample_index * dt
```

This preserves the measured mean sampling rate while averaging over burst position and scheduling jitter. Across all 305-sample fixture windows, first-to-last timing varied from 39.250 to 40.014 ms per sample (SD 0.330 ms); regression timing varied only from 39.598 to 39.666 ms (SD 0.018 ms).

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

### Lifecycle startup could outlive shutdown

The original background startup could acquire the wake lock, enable the LED, and register PPG after `onPause()` had already completed cleanup. Initialization failures and a blocked diagnostic response reader had similar lifetime leaks.

V20 makes the session worker own every resource inside `try/finally`. Startup and cleanup publish resources under one lock, cancellation is checked around every blocking boundary, empty/failed PPG registration is fatal, and launcher re-entry always creates a fresh activity. The unused response reader and all file logging were removed. Immediate-exit device testing shows `allDayHR=true` followed by `stopped: LED off` with no late PPG registration; normal re-entry registers PPG again and releases the wake lock on Back.

## Current Signal Processing

`HrvAnalyzer` performs the following steps:

1. Require at least 305 samples, approximately 12 seconds at the measured rate.
2. Fit the uniform sample period by linear regression over every sample index and callback time.
3. Reject windows outside the observed 24–27 Hz range.
4. Remove baseline drift with a centered, reflection-padded 1.6-second moving average.
5. Apply the zero-phase seven-point binomial smoother `[1, 6, 15, 20, 15, 6, 1] / 64`.
6. Estimate robust noise from the median absolute first difference multiplied by `1.4826`.
7. Detect local maxima whose bilateral prominence is at least `1.4 × robust_noise`.
8. Estimate sub-sample optical-apex positions with parabolic interpolation, limited to ±0.5 sample.
9. Apply a 380 ms refractory period and keep the stronger candidate when two candidates conflict.
10. Build a median normalized 13-sample pulse template and mark peaks below 0.85 morphology correlation.
11. Build IBIs from fractional peak positions on the regression-derived uniform sample clock.
12. Repair a split interval only when two short intervals sum to approximately one median IBI.
13. Reject intervals adjacent to bad morphology, outside 350–2,000 ms, outside 0.58–1.55 times the global median, or more than 28% from the local five-interval median.
14. Require at least 70% of expected intervals and 55% adjacent clean pairs; fragmented clean islands do not produce trusted metrics.
15. Compute HR, sample SDNN, and RMSSD only from valid NN intervals; RMSSD never crosses an invalid interval.
16. Compute coherence after 25 seconds on a 4 Hz NN series using real elapsed time and a four-times-oversampled frequency grid.

The 55-second sliding analysis window limits drift and prevents old transient data from dominating current metrics.

## Metrics on the Captured Data

The upgraded analyzer produces:

```text
Detected peaks:  82
Intervals:       81
Clean intervals: 81/81
IBI range:       approximately 597–862 ms
Heart rate:      83.28 bpm
RMSSD:           42.27 ms
SDNN:            71.35 ms
Score:           64.46%
```

The IBI sequence changes smoothly through the approximately 0.1 Hz paced-breathing cycle visible in the capture. The interval range corresponds to observed respiratory sinus arrhythmia rather than isolated timing jumps.

Earlier processing stages on the same data demonstrate why the complete detector matters:

```text
Stage                                      HR       RMSSD    SDNN     Score   Clean
Absolute-height detector                   81.24    25.34    61.16     2.56   51/56
Causal prominence detector                 83.21    40.02    70.37    40.31   81/81
Centered + morphology + regression clock   83.28    42.27    71.35    64.46   81/81
```

The first result discarded low-baseline pulses. The current result retains every visible pulse, removes preprocessing phase distortion, stabilizes the transport clock, and uses a frequency grid that is much less sensitive to sliding-window phase.

## Advanced Approach Comparison

Multiple plausible 25 Hz timing approaches were tested rather than selected by appearance:

| Approach | Result |
|---|---|
| Causal moving-average detrending | Rejected. Under gain, offset, and slow-baseline perturbations it detected 74–82 peaks; centered processing always detected 82. |
| Centered seven-point binomial processing | Selected. It preserves all captured peaks and materially reduces additive-noise and baseline sensitivity. |
| Maximum rising-slope pulse foot | Rejected. Foot-to-apex offset SD was 20–38 ms and RMSSD varied from 52 to 98 ms with the search window. |
| Median-template phase alignment | Rejected for timing. It reduced noisy trial spread but shifted clean RMSSD by morphology-dependent phase offsets without ECG evidence. |
| Median-template morphology correlation | Selected for quality only. Every clean captured pulse exceeds 0.95 correlation; a conservative 0.85 threshold identifies distorted beats. |
| Three-, five-, and seven-point apex fits | The three-point parabola after seven-point smoothing stayed within roughly 1 ms RMS IBI difference of higher-order fits, without their edge sensitivity. |

Stress testing over 200 gain/offset/baseline trials kept the centered detector at 82 peaks with RMSSD SD 0.03 ms; the causal detector ranged down to 74 peaks with RMSSD SD 0.45 ms. With severe added noise (`σ=20` ADC units), centered processing reduced RMSSD median/p95 from approximately 59.5/72.3 ms to 52.3/59.1 ms. Morphology marking reduced four-spike trial RMSSD from approximately 54/74 ms median/p95 to 43/51 ms.

The coherence grid was also tested over four overlapping 1,400-sample windows. Ordinary DFT-bin scoring ranged from 51.69% to 68.85%; four-times frequency oversampling narrowed this to 57.06%–63.53% while preserving the same LF/HF definition.

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
4. Evaluate the direct spectrum on a grid spaced at `1 / (window_span × 4)` Hz.
5. Sum grid power from 0.04 to 0.15 Hz as LF and from 0.15 to 0.40 Hz as HF.
6. Compute normalized LF power:

```text
LF_nu = LF / (LF + HF) * 100
```

7. Find the strongest oversampled LF frequency and compare it with median LF-grid power.
8. Convert peak prominence to a bounded concentration:

```text
concentration = clamp(log10(max(1, peak_power / median_lf_power)) / 2, 0, 1)
```

9. Calculate and explicitly bound the score:

```text
score = clamp(LF_nu * concentration, 0, 100)
```

The prior exponential `RMSSD → percent` mapping was removed. High RMSSD alone does not imply coherent paced breathing, and that mapping saturated near 100% for ordinary RMSSD values.

The score is withheld until at least 25 seconds of valid physiological span is available. This follows the `BUILDING` boundary in `emwave-utils` and avoids presenting a poorly resolved short-window spectrum as a physiological zero.

## UI and Source Organization

The current source responsibilities are separated as follows:

- `MainActivity.java`: lifecycle-safe session ownership, sensor-hub control, and orchestration.
- `HrvSamples.java`: synchronized fixed-size primitive ring buffers and chronological window snapshots.
- `HrvAnalyzer.java`: pure beat extraction, artifact handling, metrics, score availability, and scoring.
- `PpgWaveform.java`: causal display-only filtering; it does not alter analysis data.
- `HrvView.java`: rendering, score ring, waveform, score-building state, and breathing pacer.

The breathing pacer is a round-screen-safe horizontal bar running at five breaths per minute: green for the six-second inhale and blue for the six-second exhale. The live waveform uses a separate baseline-removal/smoothing path and RMS-based display scaling so isolated samples do not flatten visible pulses.

The detector accepts real 160–170 bpm pulse spacing but rejects unsupported 180 bpm data instead of aliasing it to half-rate. Coherence availability is distinct from a valid `0%`, so RMSSD remains visible while the score is building or legitimately zero. The application retains only the latest 1,400 raw samples in memory and writes neither raw PPG nor diagnostic files.

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

- the exact captured peak/interval count and tightened metric ranges;
- invariance to optical gain, offset, and slow baseline movement;
- morphology-aware handling of isolated optical spikes;
- rejection of a 24-second flat signal dropout;
- distinction between steady and genuinely variable synthetic pulse timing;
- regression-clock immunity to five-sample callback bursts;
- the approximately 12-second initial HR/RMSSD window;
- coherence stability across overlapping 55-second windows;
- the score's `0..100` bound and 25-second minimum span.

## Current Limits

- The calibration is grounded in one complete raw on-wrist capture plus subsequent live metric logs. More captures across users, heart rates, motion levels, skin contact, and LED modes are required before broad population claims.
- There is no simultaneous ECG reference. The metrics match the pulse timing present in the PPG data, but absolute clinical accuracy has not been established.
- At approximately 25 Hz, one raw sample is about 39.6 ms apart. Parabolic interpolation improves peak timing when pulse morphology is smooth, but it cannot create information absent from the sampled waveform.
- Motion artifacts can still remove usable intervals. Pulse morphology, expected-beat coverage, and adjacent-pair gates reject questionable windows rather than inventing replacements.
- Frequency-domain coherence needs at least 25 seconds, substantially longer than basic HR/RMSSD. Early score zero is the UI's unavailable value, not a measured zero-coherence state.
- The score measures LF resonance concentration. It is not a diagnosis, stress measurement, or universal health percentage.

## Artifacts

- Production APK: `hrv-probe/hrv-probe.apk`
- Raw captured fixture: `captures/raw_ppg.csv`
- Analyzer: `hrv-probe/src/com/hrv/probe/HrvAnalyzer.java`
- Regression checks: `hrv-probe/test/com/hrv/probe/HrvAnalyzerTest.java` and `hrv-probe/test/com/hrv/probe/HrvSamplesTest.java`
- Debugging audit trail: `HYPOTHESES.csv`
- Broader watch reverse-engineering findings: `PACE-FINDINGS.md`
