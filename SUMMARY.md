# Amazfit Pace A1612 — Findings Summary

Concise digest of the recon + reverse-engineering sessions. Detailed log: `PACE-FINDINGS.md`.

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

## HRV feasibility (pulse app)
- **SensorHub MCU** (`libsensorhub.so`) samples PPG and computes BPM; Android gets **BPM only** (sensor type 21).
- Stock app `MeasureHeartRateActivity` uses `getDefaultSensor(21)`; grep-verified it **never reads raw PPG**.
- Raw PPG (type **65538**) is exposed with **no permission gate** and is registerable from a third-party app.
- **Empirical (test APK, on-wrist): raw PPG NOT HRV-usable.** `values[]` = 16 packed values/event; `event.timestamp`
  non-monotonic/identical (FIFO bursts); values are DC baseline (no pulse) because the SensorHub measurement
  engine isn't running. Even accel is capped ~25 Hz.
- **Live BPM: PROVEN rootless.** `hm_sensor_hub_service` binder is open (no permissions, SELinux off). The stock
  `enableAllDayHeartMonitor` KLVP command (`sendKlvpRequest` target=4 cmd=1, SportConfig field 42 -> bytes
  `[D0 02 01]`) + `requestWearDetection(true)` starts continuous HR: type-21 streamed BPM 84-92 on wrist.
- **HRV verdict: LIVE HR + TREND HRV WORKS on the 25.8 Hz raw PPG.** Direct KLVP facade (klvp.watch.so) triggers
  the measurement; bandpass + parabolic peak detection on wall-clock + dicrotic merge + 55 s sliding window
  recover the pulse (resting HR 86-91 BPM, declining post-run curve). RMSSD inflated ~10-30% vs ECG (25 Hz
  ceiling) - trend HRV, not clinical. App: hrv-probe/ (v14, round-screen UI + breath pacer + auto LED-off).

## RE structure
- System APKs are **odexed**: no `classes.dex`; real code in `mips/*.odex` (carve dex → `jadx`).
- `libdataProcess.so` = sleep/HR analysis on BPM series; `libsensorhub.so` = SensorHub JNI bridge.
- Tooling: `apktool`, `jadx`, `kuna` (MIPS decompiler), `radare2` all work on this target.

## HRV precision fix
- Root cause of 98–100% scores: callback wall time represented five-sample transport bursts (~0/200 ms gaps),
  not the uniform ~25.4 Hz sample clock. Same-peak A/B: wall RMSSD 147–168 ms vs uniform RMSSD 37–41 ms;
  HR unchanged ~84 BPM.
- Final V16 uses fractional sample index x calibrated mean period, sorted medians, dicrotic merge, a 55 s window,
  and a soft RMSSD score curve. Expected resting score from the validated run: 65–69%.
