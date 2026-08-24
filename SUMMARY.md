# Amazfit Pace A1612 — Findings Summary

Concise digest of the recon + reverse-engineering sessions. Detailed logs: [`PACE-FINDINGS.md`](PACE-FINDINGS.md) and [`HRV-FINDINGS.md`](HRV-FINDINGS.md).

## Device
- Amazfit Pace **A1612** (`huangheUS`), Android **5.1** (API 22), kernel **3.10.14** (MIPS32r1, 2019-11-26).
- Ingenic **XBurst** single-core ~1 GHz, **477 MB** RAM, **320×300** round LCD @238 dpi.
- 4 GB eMMC: `system` 824 MB (ro) / `data` 2.6 GB / `cache` 100 MB; plus `boot`, `recovery`, `pretest`, `reserved`, `misc`.

## Access & security
- adb shell = **uid 2000** (not root); `adbd` cannot root (production build); no `su`; `busybox` present.
- **SELinux disabled** · `ro.secure=1` · `ro.debuggable=0` · fastboot locked.
- **ADB over TCP already listening on `:5555`** (WiFi down at capture).

## Root paths (ranked)
1. **System-uid code exec** — vendor apps run as `uid 1000` (launcher, wearservices, hmwatchmanager, health, wififtp, hmlab). SELinux off ⇒ remount `/system` rw + install `su`.
2. **Kernel exploit** — 3.10.14 (Dirty COW `CVE-2016-5195` candidate, needs MIPS build; may be backported).
3. **Firmware repack** — via OTA path (`OtaService`/`UpdateRomActivity`).

## Exposed surface
- Health binder `IHmHealthDataManagerService.getHealthData(...)` (aggregates only).
- `UserInfoContentProvider` + `SettingsProvider` (hmwatchmanager).
- Engineering apps `HmLab` (running), `HuamiSelfTest`, `SensorList`, `WearLogger`.
- `HmFTP` (wififtp) service, **uid 1000**.

## Sensors / radios
- Radios: **WiFi, BT + BLE, GPS, mic**. No camera, no NFC.
- 18 HW sensors: accel **500 Hz**, gyro, mag, **barometer**, light, step, **HR (BPM)**, **raw PPG ×4 (type 65538, 5–500 Hz)**, infrared, gesture, + AOSP fusions.

## HRV feasibility and calibrated app
- Raw PPG type **65538** is exposed without a permission gate. The proven rootless KLVP command `[D0 02 01]`
  starts continuous optical measurement; `[D0 02 00]` stops it and turns the LED off.
- The hub delivers one changing PPG value per event at approximately **25.2–25.8 Hz**. The 16-value event array
  is not 16 time samples. Callbacks arrive as approximately five-sample bursts every 200 ms.
- Callback arrival timestamps are unusable for beat timing. A least-squares sample-index clock reduces 305-sample
  period-estimate SD from **0.330 ms** to **0.018 ms** and avoids five-sample burst boundary bias.
- A captured 59.7-second, 1,507-sample fixture proved that a global absolute-height threshold discarded real
  pulses during baseline drift. Zero-phase adaptive local-prominence detection finds **82 peaks / 81 valid intervals**.
- Calibrated fixture result: **83.28 bpm**, **RMSSD 42.27 ms**, **SDNN 71.35 ms**, coherence **64.46%**.
- A subsequent watch run was stable at **85–87 bpm**, **RMSSD 31–36 ms**, **SDNN 56–62 ms**, with up to
  **75/76** clean intervals. No Android runtime crash occurred.
- RMSSD never bridges rejected intervals. Median-template morphology, expected-beat coverage, and adjacent-pair
  gates reject distorted or fragmented windows. Dicrotic repair preserves one real IBI.
- The score follows `emwave-utils/src/metrics.rs`, with its LF frequency scan oversampled four times for stable
  sliding windows. It is bounded to **0–100** and withheld until 25 seconds; the saturating RMSSD curve is gone.
- Current app: `hrv-probe/`; evidence and limitations: [`HRV-FINDINGS.md`](HRV-FINDINGS.md); regression fixture:
  `captures/raw_ppg.csv`.

## RE structure
- System APKs are **odexed**: no `classes.dex`; real code in `mips/*.odex` (carve dex → `jadx`).
- `libdataProcess.so` = sleep/HR analysis on BPM series; `libsensorhub.so` = SensorHub JNI bridge.
- Tooling: `apktool`, `jadx`, `kuna` (MIPS decompiler), `radare2` all work on this target.


## Root audit status
- Towelroot/futex prerequisite: **patched** (`FUTEX_CMP_REQUEUE_PI` same-address returns `EINVAL`).
- Dirty COW race: **failed** on this 3.10.14 build; no file modification.
- Unsigned OTA rejected; fastboot unavailable; HmFTP only exposes anonymous `/sdcard/`.
- iovyroot/CVE-2015-1805 remains the only plausible kernel route, but its public offsets are ARM/ARM64-only;
  this MIPS build needs exact kernel symbols and a custom port. No root method is currently proven.
