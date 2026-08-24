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
- Raw PPG (type **65538**, up to **500 Hz**, no `BODY_SENSORS` gate) is exposed in the standard sensor list → a custom app can `getDefaultSensor(65538)` + `registerListener`.
- 500 Hz = 2 ms spacing ⇒ enough for **RMSSD/SDNN/pNN50** and **LF/HF**. Limiter is wrist motion artifacts, not hardware.
- Unproven: real delivered rate + `values[]` layout (needs a test APK).

## RE structure
- System APKs are **odexed**: no `classes.dex`; real code in `mips/*.odex` (carve dex → `jadx`).
- `libdataProcess.so` = sleep/HR analysis on BPM series; `libsensorhub.so` = SensorHub JNI bridge.
- Tooling: `apktool`, `jadx`, `kuna` (MIPS decompiler), `radare2` all work on this target.
