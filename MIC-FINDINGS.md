# Amazfit Pace Microphone Capture Findings

## Bottom line

**Only the 16 kHz capture is usable.** The watch's digital mic (dmic) runs at a single
native sample rate, **16000 Hz**. Of the four rates the audio HAL declares
(8000 / 11025 / 16000 / 44100), only `16000` is captured at its true clock:

| Requested rate | True behavior | Heard as |
|---|---|---|
| 8000 | decimation from 16 kHz, clock correct | muffled / degraded vs native; voice barely intelligible |
| 11025 | **raw 16 kHz stream relabeled as 11025** | slowed down, pitch 0.69×, garbled |
| 16000 | native dmic rate, correct | **clean, intelligible** |
| 44100 | **raw 16 kHz stream relabeled as 44100** | sped up 2.76×, chipmunk, garbled |

For speech/audio capture use **`AudioRecord` at 16000 Hz, mono, PCM16**. The other
declared rates must not be trusted.

## Evidence

### 1. Tone stimulus (1 kHz + 2 kHz sine played from host speakers)

The probe recorded the tone at each rate. Full-spectrum peak search:

| Labeled rate | 1000 Hz tone landed at | 2000 Hz tone landed at | Clock inference |
|---|---|---|---|
| 8000 | 1000.0 Hz | 2000.0 Hz | true 8000 (decimated from 16k) |
| 11025 | **689.1 Hz** | **1378.1 Hz** | 16000, mislabeled (×11025/16000 = 0.689) |
| 16000 | 1000.0 Hz | 2000.0 Hz | true 16000 (native) |
| 44100 | **2756.2 Hz** | **5512.5 Hz** | 16000, mislabeled (×44100/16000 = 2.756) |

689.06 = 1000 × 11025/16000 and 2756.25 = 1000 × 44100/16000 exactly.
The HAL does not resample to 11025/44100 — it re-labels the 16 kHz stream.

### Precise native-clock calibration

`mic-clock-probe` recorded 32 seconds of untouched `MIC`/PCM16 while the host
played four independently generated 48 kHz tones. Three two-second windows per
tone produced:

| Source tone | Captured tone (16000 Hz label) | Inferred MIC rate |
|---:|---:|---:|
| 440 Hz | 439.996883 Hz | 16000.113 Hz |
| 997 Hz | 996.993518 Hz | 16000.104 Hz |
| 1753 Hz | 1752.988708 Hz | 16000.103 Hz |
| 3001 Hz | 3000.982317 Hz | 16000.094 Hz |

The common least-squares fit is **16000.097 Hz** (+6.1 ppm). Cross-tone
residuals are below 0.002 cents; the clock error is only **-0.0105 cents**.
The source WAV tones validate within 0.00001 cents. Clock drift therefore
cannot explain tuner errors on the order of 15-20 cents.

Probe output: `/sdcard/mic-clock-probe/capture.wav` (fixed 32-second raw
capture, overwritten on each launch).

### 2. Speech capture (user talking, same run)

Voice is present in all four files (60–96 % voiced frames) but intelligible only in
the 16 kHz capture. Voiced-pitch medians match the mislabeling prediction:

| File | Pitch median | Interpretation |
|---|---|---|
| mic_16000.wav | 127.0 Hz | normal speech pitch ✓ |
| mic_11025.wav | 85.5 Hz | 127 × 11025/16000 = 87.5 expected (slowed) |
| mic_44100.wav | 91.1 Hz | chipmunk; pitch detector out of range |
| mic_8000.wav | 83.3 Hz | 60 % voiced frames, lowest envelope modulation — degraded vs 16k |

### 3. Relabeling proof

Playing the mislabeled files back at their true 16 kHz clock restores normal speech
pitch:

```text
mic_11025.wav relabeled as 16000 Hz:  pitch 122.1 Hz  (≈ 127, normal)
mic_44100.wav relabeled as 16000 Hz:  pitch 112.7 Hz  (≈ 127, normal)
```

So the 11025/44100 files contain valid 16 kHz audio — they are simply tagged with the
wrong rate. Host-side resampling would be needed to "fix" them; capturing at 16000
avoids the problem entirely.

## 8000 Hz caveat

8000 Hz is clock-correct (tones land at 1000/2000 Hz), so it is a genuine decimation
of the 16 kHz stream — but measured speech quality is clearly below native (60 %
voiced frames, lowest envelope modulation, 0.162 vs 0.265). Treated as
degraded/unreliable for capture; prefer 16000.

## Analysis trap (recorded so nobody repeats it)

An earlier "rate verification" of the tone captures searched for power only in a
narrow window around the expected 1000/2000 Hz bins and reported ±0.03 % accuracy at
every rate. That was wrong: the search found noise-floor peaks in the expected band,
while the real tone sat elsewhere (e.g. 2756 Hz in the "44100" file). **Always search
the full spectrum for dominant peaks when validating sample-rate clocks.**

## Capture app with UI (`com.hrv.mic`)

Replaces the headless probe. Round-screen UI (320×300): live waveform, mm:ss
duration, REC/STOP buttons, save status. Records at 16000 Hz (max 60 s), writes
two files per capture to `/sdcard/mic-probe/`:

```text
mic_16000_<yyyyMMdd_HHmmss_SSS>.wav       processed (loud, denoised)
mic_16000_<yyyyMMdd_HHmmss_SSS>_raw.wav   untouched capture
```

The timestamp is taken at **recording start**; millisecond precision keeps rapid
successive recordings in separate files (each REC/STOP cycle creates a new pair).

Source split:

- `MainActivity.java` — lifecycle, AudioRecord loop, save orchestration
- `MicView.java` — round-screen rendering + touch (unit-scaled like HrvView)
- `SpeechProc.java` — pure-Java DSP, no Android deps, offline self-test `main()`

Lifecycle: **back button or any pause (home, screen off) hard-kills the process**
(`finish()` + `Process.killProcess`) — the probe never lingers as a paused
zombie. While running, the screen is held awake (`FLAG_KEEP_SCREEN_ON` +
`SCREEN_DIM_WAKE_LOCK`); a partial wakelock covers the capture itself.

Build/install: `make mic-probe`, `adb install -r apks/builds/mic-probe.apk`.

## Speech DSP chain (validated on the 16 kHz speech capture)

| Stage | Parameters | Why |
|---|---|---|
| HPF | 4th-order biquad cascade @ 120 Hz | the 0–300 Hz rumble was 20 dB above the speech band |
| LP | 4th-order biquad cascade @ 5500 Hz | kills hiss; keeps sibilants (4k cut was cleaner but duller) |
| AGC | target −18 dBFS, max +34 dB, 15 ms attack / 400 ms release | speech was at −41 dBFS RMS (peak-normalization alone left it quiet — the one loud spike set the gain) |
| Noise gate | close < −32 dBFS to −26 dB mute, 20 ms close / 200 ms open | the AGC pumped pause noise to −40 dBFS; gate makes pauses −65 dBFS |
| Limiter | tanh at 0.92 | zero clipping, peak −1 dBFS |

Measured on the talking capture: RMS −41.4 → −21.8 dBFS, pauses −40 → −65 dBFS,
SNR(300–3400 vs 5–8 kHz) 6.5 → 8.9 dB, 0 clipped samples, pitch intact.

**Rejected approaches** (all measured): peak normalization (spike-set gain leaves
speech quiet), RMS normalization (same), minimum-statistics spectral subtraction
(no measurable effect on this file — the noise is gated, not subtractable),
HPF below 120 Hz (rumble not attenuated), LP at 4000 (dull sibilants).

**Port verification**: `SpeechProc.java` reproduces the Python reference
**bit-identical** (max sample diff = 0):

```bash
javac --release 8 -d /tmp/sp src/com/hrv/mic/SpeechProc.java
java -cp /tmp/sp com.hrv.mic.SpeechProc in.wav out.wav
```

## Pull workflow

`pull-recordings.sh` (repo root) downloads every WAV from the device to
`captures/mic-probe/` keeping the device file names (the app already stamps each
recording with its start time), then clears the device folder:

```bash
./pull-recordings.sh            # ADB_SERIAL=xxx for multiple devices
```

Result: `captures/mic-probe/mic_16000_20260824_200859.wav`

## Artifacts

- Capture app: `mic-probe/` (build script, APK output, source)
- Pull/download: `pull-recordings.sh`
- Processed demo: `captures/mic_16000_processed_final.wav` (validated chain)
- Captured fixtures: `captures/mic-probe/` (tone runs), `captures/mic-probe-noise/` (speech run)
- Audio HAL policy: `/system/etc/audio_policy.conf` declares `8000|16000|11025|44100`
  for the builtin mic — declaration ≠ reality; the hardware is 16000 only.

## Practical guidance

- Record at **16000 Hz**. 11025/44100 are pitch-warped, 8000 is lossy.
- The dmic chain (`audio.primary.watch.so`, digital mic) is otherwise healthy:
  no clipping, no dead samples, near-zero DC, clean open/close in the HAL logs.
- No usable speaker output path (`AudioTrack` cannot initialize) — loopback testing
  is not possible on-device.
