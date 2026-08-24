# Amazfit Pace (A1612) — Recon Findings

Date: 2026-08-24. Source: live `adb` session against the device (USB).

## 1. Device fingerprint

| Property | Value |
|---|---|
| Model | Amazfit Pace |
| Huami model | `A1612` |
| Codename / product | `huangheUS` |
| Firmware | `huangheUS-2.8.5.0` |
| Android | 5.1 (API 22), `LMY47D` |
| Build type | `user`, `release-keys` |
| Build date | 2019-11-26 |
| Fingerprint | `Huami/huangheUS/watch:5.1/LMY47D/4:user/release-keys` |
| Board platform | `M200` |
| SoC | Ingenic XBurst, **MIPS32r1**, single core ~1.03 GHz (`BogoMIPS 1030.55`) |
| CPU ABI | `mips` (abilist32 = `mips,mips`; no arm, no 64-bit) |
| RAM | 477 MB total |
| Kernel | `3.10.14-gb7c467e`, gcc 4.9, PREEMPT, built 2019-11-26 |
| Screen | 320×300 physical, 238 dpi (round) |
| Storage | 4 GB eMMC (`mmcblk0`) |
| Host build box | `vm10-254-107-57.ksc.com` (user `ubuntu`) |

## 2. Partitions

`/dev/block/platform/jzmmc_v1.2.0/by-name` → `mmcblk0`:

| Name | Node | Blocks | Size | Mount | FS |
|---|---|---|---|---|---|
| boot | mmcblk0p1 | 9216 | 9 MB | — | — |
| recovery | mmcblk0p2 | 16384 | 16 MB | — | — |
| pretest | mmcblk0p3 | 16384 | 16 MB | — | — |
| reserved | mmcblk0p4 | 53248 | 52 MB | — | — |
| misc | mmcblk0p5 | 4096 | 4 MB | — | — |
| cache | mmcblk0p6 | 102400 | 100 MB | /cache | ext4 rw |
| system | mmcblk0p7 | 843776 | 824 MB | /system | ext4 **ro** |
| data | mmcblk0p8 | 2777088 | 2.6 GB | /data | ext4 rw |

Total device `mmcblk0` = 3,825,664 blocks (~3.9 GB).

## 3. Access level (current)

- adb shell runs as **`uid=2000(shell) gid=2000(shell)`** — NOT root.
- `adbd cannot run as root in production builds`.
- shell supplementary groups:
  `graphics(1003), input(1004), log(1007), adb(1011), sdcard_rw(1015), sdcard_r(1028), net_bt_admin(3001), net_bt(3002), inet(3003), net_bw_stats(3006)`
- No `su` binary anywhere reachable (`/system/bin/su`, `/system/xbin/su`, `/sbin/su`).
- `run-as` present at `/system/bin/run-as`, but **zero debuggable packages** found → no run-as targets.
- `busybox` present at `/system/bin/busybox` (1.5 MB, mode 0755, root:shell).

## 4. Security posture

| Property | Value | Impact |
|---|---|---|
| `ro.secure` | 1 | adb shell drops to non-root |
| `ro.debuggable` | 0 | no `adb root`, no run-as |
| `ro.adb.secure` | 1 | ADB RSA auth expected |
| **SELinux** | **Disabled** (`getenforce` empty) | any kernel/uid-1000 compromise = unrestricted |
| `ro.boot.fastboot_unlock` | 0 | bootloader lock flag |
| `sys.fastboot.enable` | `false` | fastboot not surfaced |
| `ro.enableAppScreenshot` | 0 | screenshots disabled |

## 5. Network attack surface

- **ADB over TCP is LISTENING:**
  ```
  tcp  0.0.0.0:5555  LISTEN  (adbd)
  service.adb.tcp.port = 5555
  ```
  `wlan0` exists but is DOWN (USB-only at capture). WiFi up ⇒ `adb connect <ip>:5555` works.
- Interfaces: `lo`, `sit0`, `wlan0`. No cellular.
- Kernel wifi driver threads: `cfg80211`, `dhd_*` (Broadcom `dhd`), `wl_event_handle`.
- No other listening TCP/UDP at capture time.

## 6. System-uid apps (uid 1000)

Confirmed running as `system` (`uid 1000`) from `ps`:

`com.huami.watch.launcher`, `com.huami.watch.wearservices`, `com.huami.watch.hmwatchmanager`,
`com.huami.watch.health`, `com.huami.mobile.watchsettings`, `com.huami.watch.otawatch`,
`com.huami.ble.core`, `com.huami.watch.hmlab`.

- `com.huami.wififtp` (HmFTP): `sharedUser=android.uid.system`, versionName 1.0, targetSdk 21.
- **uid 1000 + SELinux disabled ⇒ can remount `/system` rw and install `su`.** System-uid code execution = permanent root.

## 7. Exposed components (IPC attack surface)

### `com.huami.watch.otawatch`
- Activities: `UpdateRomActivity`, `wifi.WifiListActivity`
- Service: `service.OtaService`
- Receivers: `notification.BootReceiver`, `NotificaitonDelayReceiver`, `IosNotificationUpdateReceiver`,
  `IosNotificationDelayReceiver`, `NotificaitonInstallReceiver` (+ `DelNotificationReceiver`)
- → flashing/update path.

### `com.huami.watch.wearservices`
- Services (binder names):
  - `WIFI_TRANS_SERVICE` → `wifitrans.control.server.WifiTransServer`, `wearubc.UbcService`
  - `DATA_SERVICE` → `transdata.DataService`, `transport.DataTransportService`
  - `com.huami.watchface.SlptClockService` → `watchface.SlptClockService`
  - `com.huami.watch.transport.DataTransportService`
  - `HOST_SERVICE` → `transport.httpsupport.transporter.server.DuplexDataExchangeService`
  - `com.huami.watch.action.LowPowerService` → `common.CommonService`
- Receivers: `BrightnessSettingSync2SLPT`, `ble.startReceiver` (×many), `transport.httpsupport.global.ConnectionReceiver`,
  `crashmonitor.DropboxListener`, `common.BootBroadcastReceiver`, `wearubc.BootBroadcastReceiver`.

### `com.huami.watch.sensorservice`
- Service: `com.huami.watch.sensormanagerservice` → `sensormanager.HmHealthDataManagerService`
- → health/step/HR/PPG data feed.

### `com.huami.watch.hmwatchmanager`
- Activity: `manager_client.MainActivity`
- Receivers: `assistant.receiver.ReminderReceiver`, `assistant.alarm.AlarmOperateReceiver`, `manager_client.startReceiver`
- Services: `BGService_file`, `com.ingenic.iwds.*` (IwdsService, smartsense.SensorService,
  remotedevice.RemoteDeviceService, uniconnect.ConnectionService, smartsense.RemoteSensorService)
- Content providers:
  - `com.huami.watch.companion.settings.SettingsProvider`
  - `com.huami.watch.companion.userinfo.UserInfoContentProvider`
- → companion profile / user info / settings data.

### `com.huami.wififtp`
- Service: `service.WifiApService` (uid 1000).

### `com.huami.watch.hmlab` (engineering app, running)
- Activity: `MainHmlabActivity`.

### `com.huami.watch.selftest`
- Activity: `MainActivity`.

## 8. Sensors (18 hardware + AOSP fusion)

| Handle | Name | Type | Rate |
|---|---|---|---|
| 0x01 | TAOS Light | 5 light | on-change |
| 0x02 | Accelerometer | 1 | 5–500 Hz, fifo 122000 |
| 0x03 | Magnetic field | 2 | 5–100 Hz |
| 0x04 | Gyroscope | 4 | 5–100 Hz |
| 0x05 | Pressure (barometer) | 6 | 5–100 Hz |
| 0x06 | Step Counter | 19 | on-change |
| 0x07 | Heart Rate (needs `BODY_SENSORS`) | 21 | on-change |
| 0x08 | Pick-out | 65537 | on-change |
| 0x09 | Wake gesture | 23 | one-shot |
| 0x0a–0x0d | PPG ×4 (auto/25/40/55 mA) | 65538 | 5–500 Hz |
| 0x0e | Infrared | 65539 | 5–500 Hz |
| 0x0f | Orientation | 3 | 5–500 Hz |
| 0x10 | Rotation vector | 11 | 5–500 Hz |
| 0x11 | Linear acceleration | 10 | 5–500 Hz |
| 0x12 | Gravity | 9 | 5–500 Hz |

Plus AOSP fusion: rotation vector, gravity, linear accel, orientation, corrected gyro, gyro-bias (debug).

Vendor string: `Huami Tech`, version 1. Custom types: `com.huami.watch.ppg`, `com.huami.watch.infrared`.

## 9. Hardware / radios

- **Radios:** WiFi (`wlan0`), Bluetooth + BLE, GPS (`gpsd` running as root). Microphone.
- **No camera, no NFC** (only software-level feature flags `android.software.camera` / `android.software.nfc`).
- Display 320×300, touch (multitouch, `fts_ts` = FocalTech). Round.
- Vibration motor. `consumer_ir` feature flag present.
- USB: `mtp,adb` (both functions active). `f_mtp` + `file-storage` kernel threads.
- Battery: Li-ion, voltage 4119 mV, temp 385 (38.5 °C), level 11 % at capture (USB powered).
- Kernel driver hints from `ps`: `sm5007` (PMIC), `usb-det`, `galcore` (GPU), `blue_sleep`, `SensorHub`,
  `jz-asoc-a` (Ingenic audio), `fts_ts` (touch), `jzmmc_v1.2.0` (eMMC).

## 10. Software inventory (`/system/app`, `/system/priv-app`)

System app dirs: AmazfitWeather, Bluetooth, CaptivePortalLogin, CertInstaller, ChargingUI, HmBleCore, HmFTP,
HmLab, HmLocation, HmMediaPlayer, HuamiIME, HuamiSelfTest, HuamiWatchFaces, KeyChain, MyWatch, NewWearSport,
PackageInstaller, SensorList, SetupWizard, TrainingPlan, WearBLE, WearCompass, WearHealth, WearLogger,
WearSensorService, WearServices, webview, BackupRestoreConfirmation, DefaultContainerService,
ExternalStorageProvider, FusedLocation, HmAlarmClock, InputDevices, ManagedProvisioning, MediaProvider, OtaWatch,
ProxyHandler, SettingsProvider, SharedStorageBackup, Shell, SystemUI, WearAirPlaneMode, WearLauncher, WearSettings,
WifiUploadData.

Notable vendor packages:

- `com.huami.watch.hmlab` (HmLab) — engineering/lab, running.
- `com.huami.watch.selftest` (HuamiSelfTest) — factory self-test.
- `com.huami.wififtp` (HmFTP) — WiFi FTP, **uid 1000**.
- `com.huami.watch.wearlogger` (WearLogger) — logging.
- `com.huami.watch.otawatch` (OtaWatch) — OTA flash/update.
- `watch.huami.com.mediaplayer` (HmMediaPlayer) — media player.
- `com.huami.watch.health` — health data.
- `com.huami.watch.sensorservice` (WearSensorService) — sensor manager.
- `com.huami.watch.train` (TrainingPlan/NewWearSport) — fitness.
- `com.huami.mobile.watchsettings` (WearSettings) — settings UI.
- `com.huami.watch.input` / `HuamiIME` — input method.

## 11. Root escalation paths

1. **System-uid code execution** (lowest effort): exploit an exported/buggy component in a uid-1000 app
   (otawatch, wearservices, wififtp, hmlab). With SELinux off, uid 1000 ⇒ remount `/system` rw ⇒ install `su`.
2. **Kernel exploit**: 3.10.14. Dirty COW `CVE-2016-5195` targets < 3.10.58 (any arch) — needs MIPS build;
   vendor may have backported the patch (build date 2019). Test, don't assume.
3. **Firmware repack**: dump `boot`/`recovery` (p1/p2), patch `default.prop` (`ro.secure=0`, `ro.debuggable=1`)
   or inject `su`, reflash via OTA path (`OtaService`/`UpdateRomActivity`). Needs a write path (fastboot not
   surfaced; `fastboot_unlock=0`).

## 12. Hard constraints

- **MIPS-only ABI.** APKs must be pure-Java or ship `libmips`. Modern ARM APKs will not run.
  Flutter has no MIPS backend; RN impractical.
- 477 MB RAM, single 1 GHz core, 320×300 screen — tiny targets only.
- Small battery; continuous sensors/GPS drain fast.
- `targetSdk 21` (API 22) ceiling for native app compat.
- `ro.enableAppScreenshot=0` — no screencap.

## 13. Open questions / next probes

- `HmFTP` (wififtp) default credentials / auth model — dump APK, read `WifiApService`.
- `HmLab` + `HuamiSelfTest` capabilities — decompile (`jadx`) both APKs.
- Health data: authority paths for `UserInfoContentProvider` / `SettingsProvider`, and the
  `HmHealthDataManagerService` binder AIDL.
- OTA update format + signature check — determine if `UpdateRomActivity` accepts unsigned images.
- Verify Dirty COW or another MIPS-compatible kernel vuln against `3.10.14`.
- ADB auth behavior over TCP (does the watch auto-authorize, or require on-device confirm?).

## 14. Pulse / heart-rate pipeline (HRV feasibility)

Source: decompiled `WearHealth.odex` + `WearSensorService.odex` (jadx 1.5.6) and native `libdataProcess.so`.

### Architecture
- PPG (green/IR LED + photodiode) is sampled by the **SensorHub co-processor** (`/system/lib/libsensorhub.so`,
  kernel thread `SensorHub`), not the AP CPU.
- SensorHub runs the HR algorithm on the MCU and exposes **computed BPM** + events:
  `sensorhub.hearrate.reach.ratezone`, `sensorhub.heatrate.exceed_target_zone`,
  `sensorhub.heartrate.calorie_burn.maximum`, `sensorhub.algorithm.*`.
- Android talks to SensorHub via `IHmSensorHubService` binder + `HmSensorManager` JNI
  (`nativeConfigureSensorHubAlgorithm`, `nativeStartTransaction`, `readRawBytes`/`writeRawByte` = KLVP byte
  protocol) and protobuf (`SensorHubProtos`, `KlvpProtocol`).
- Health binder `IHmHealthDataManagerService` has **one method**: `getHealthData(type, span, since, cb)`
  → `HmHealthHistoryData` (aggregated daily/weekly: steps, HR, calory, mileage, sit). No raw stream.

### Native HR lib
`WearHealth/lib/mips/libdataProcess.so` (MIPS32 ELF, stripped, 520 functions) = sleep/HR **analysis**, not raw
PPG. Operates on BPM value series: `HeartRateClass::filter_hr`, `avg_window`, `std_window`,
`decisionTreeFiltering`, `featureNormalization`; `SleepClass::sleepAnalyze`; `ShoesClass`/`StepClass` for gait.
JNI entry: `com.huami.watch.health.sleepanalysis.DataAnalysis`.

### Stock pulse app never reads raw PPG
- `MeasureHeartRateActivity.init()`: `mSensorManager.getDefaultSensor(21)` — **type 21 = BPM
  (`android.sensor.heart_rate`, on-change)**, delay 0; displays `mU4HeartRate`.
- `StepLauncherView`: `getDefaultSensor(19)` (step counter).
- Full-stock-code grep: **zero** references to raw-PPG type `65538` (only
  `HmSensorHubConfigManager.TYPE_SPORT_SET_START = 65538`, unrelated).

### Raw PPG sensor (the HRV source)
From `dumpsys sensorservice` — present in the standard Android sensor list, **unused by stock apps**:

| Handle | Name | Type | Mode |
|---|---|---|---|
| 0x0a | PPG Sensor(auto) | 65538 | continuous 5–500 Hz |
| 0x0b | PPG Sensor(25 mA) | 65538 | continuous 5–500 Hz |
| 0x0c | PPG Sensor(40 mA) | 65538 | continuous 5–500 Hz |
| 0x0d | PPG Sensor(55 mA) | 65538 | continuous 5–500 Hz |

fifo 1220 events, `non-wakeUp`, **no `BODY_SENSORS` permission** (only the BPM sensor 0x07 carries it).
A third-party app can `SensorManager.getDefaultSensor(65538)` + `registerListener`.

### HRV verdict
- 500 Hz → 2 ms sample spacing; parabolic peak interpolation → ~1–2 ms beat-time precision ⇒ sufficient for
  time-domain HRV (RMSSD, SDNN, pNN50) and frequency-domain (LF/HF; needs ≥25 Hz, ideal 100–250 Hz).
- **Proven:** raw PPG present at HAL level, 500 Hz, no permission gate, unused by stock software.
- **Unproven (needs a test APK):** whether SensorHub firmware streams raw PPG to the AP at 500 Hz on demand;
  `values[]` layout (ADC count? green vs IR channel?); real rate under load; motion-artifact quality.
- **Limiter is signal quality, not hardware:** wrist PPG + motion ⇒ artifact rejection via the 500 Hz
  accelerometer is mandatory for anything beyond resting HRV.

## 15. Empirical sensor probe (on-wrist, test APK `com.hrv.probe`)

Built a pure-Java MIPS APK (no NDK needed) that registers raw sensors and records `event.timestamp` + `values[]`,
then ran it on-wrist. Log recovered via logcat (file lands app-private under
`/storage/emulated/0/Android/data/com.hrv.probe/files/`, not shell-readable).

### Results
| Sensor (request) | register | events | vals/event | delivered rate | timestamps | ch0 values |
|---|---|---|---|---|---|---|
| PPG auto @0µs | true | 199 | 16 | 9900 Hz* | burst, non-monotonic | 2237–65535 (mean 10868) |
| PPG auto @2000µs | true | 187 | 16 | 9300 Hz* | burst | 21912–46760 |
| PPG 25mA @0µs | true | 200 | 16 | — | identical (0 ms gap) | 66–6416 |
| PPG 25mA @4000/20000µs | true | ~199 | 16 | — | identical | 224–240 (DC, const) |
| PPG 40mA (all rates) | true | ~200 | 16 | — | identical | ~357 (DC, const) |
| PPG 55mA (all rates) | true | ~200 | 16 | — | identical | ~468 (DC, const) |
| IR 65539 @0µs | true | **0** | — | — | — | none |
| HR BPM 21 @0µs | true | **0** | — | — | — | none (no active measure) |
| ACCEL 1 @2000µs | true | 200 | 3 | **25.4 Hz** | monotonic, 39 ms mean | 0.19–0.46 |

\* rate figures are the FIFO burst: all ~200 events arrive within ~20 ms (one flush), then silence for the
remaining session.

### Conclusions (resolves §14 "unproven" items)
1. **Raw PPG is reachable** (`getDefaultSensor(65538)` = true, 4 sensors, no permission) — but **not
   HRV-usable via the public API**:
   - `values[]` = **16 values/event** (packed, semantics unknown), not a 1–2-channel waveform.
   - `event.timestamp` is **not a per-sample clock**: most sessions return identical/non-monotonic timestamps
     (`rate=Infinity`, `meanGap=0`); delivery is FIFO-burst, not a stream.
   - Values are **DC baseline with no pulse oscillation** (40 mA ≈ 357 const, 55 mA ≈ 468 const) — the SensorHub
     measurement engine is idle, so the PPG frontend isn't AC-coupled/amplified for a waveform.
2. **HR BPM (type 21) returns nothing** without an active SensorHub measurement (stock app triggers it via the
   internal service; a bare listener does not).
3. **SensorHub downsamples everything**: accelerometer requested at 500 Hz delivered **25.4 Hz**.

### Verdict
Real-time HRV through the standard `SensorManager` API is **not feasible**. The SensorHub co-processor owns
acquisition and only leaks BPM (during measurement) or a raw-PPG FIFO without reliable timing. HRV would require
either (a) driving SensorHub measurement mode via Huami's `IHmSensorHubService` (uid-1000/system context), or
(b) reverse-engineering the 16-value packet + FIFO timing in `libsensorhub.so` / the HAL.


## 16. Sensor-hub measurement control (proven, rootless)

### Binder access
- `hm_sensor_hub_service` (`com.huami.watch.sensor.IHmSensorHubService`) is registered in the live
  servicemanager. Reachable from **shell** (`service call hm_sensor_hub_service <code>`) and from a
  **third-party app** (`ServiceManager.getService` via reflection) — no permission checks (SELinux disabled).
- Verified callable: `getSensorDataInfo` (live ver/steps/HR/quality), `getGpsState`, `getHeartHistoryData`
  (returns null), `configureSensorhub`, `configureSensorHubWakeupSource`, `requestWearDetection`,
  `sendKlvpRequest`.
- `configureSensorHubAlgorithm` requires a non-null `IConfigFinishDispatcher` binder (NPE with null).

### Measurement trigger (proved on wrist)
Exact stock sequence from `HmSensorHubConfigManager.enableAllDayHeartMonitor(true)`:
1. `sendKlvpRequest`: KlvpRequest{cmd=1, msgRemain=0, target=4 (TGT_SPORT_CONFIG),
   configValue = SportConfig protobuf with field 42 (`mIsAlldayAutoHeartRateEnabled`) = true -> bytes
   `[D0 02 01]`} — binder code 14, returns pairId.
2. `requestWearDetection(true, cb)` — binder code 17.

Result (watch worn, v5 probe):
- **type-21 delivered 29 events in 40 s, BPM 84–92** — live heart rate.
- Raw PPG (65538) stream active simultaneously: ch0 41219–44603, still ~25 Hz.
- Hub replies `responseCode=90, len=0` to the config — semantics undocumented in stock Java; measurement
  starts regardless.
- `getSensorDataInfo` HR field stays 0 (per-minute bucket — not the live channel); `getHeartHistoryData`
  stays null; sysfs attrs unchanged (`enable=0x1`, `delay_ms=0`).

### HRV final verdict
- **Live BPM: YES — proven, rootless, from a third-party app.**
- **Beat-to-beat / HRV: NO via any exposed API.** Type-21 BPM values are smoothed (constant across
  consecutive events, e.g. 86×7, 85×3; ~1–2 Hz updates), not inter-beat intervals. Raw PPG stays capped at
  ~25 Hz during measurement (40 ms spacing → RMSSD unusable). No IBI interface exists in the hub service.
- **Only remaining route**: reverse-engineer the KLVP protocol + hub firmware (in `sensors.sensorHub.so` /
  the proprietary hub blob) for an IBI or high-rate raw-PPG command. Uncertain payoff.

### Artifacts
- Probe app v5 (`hrv-probe/`): hub-service binder calls with hand-built parcels + KLVP response dispatcher.
- Ledger: `HYPOTHESES.csv` (5 hypotheses, 4 proved/disproved paths).


## 17. Live HRV app — final outcome

The 25.8 Hz raw PPG stream (the platform ceiling, see §14-16) IS sufficient for live HR + trend HRV
when the SensorHub measurement is active. Proven on-wrist (v11/v13):

- v11: IBI clusters 558-644 ms (93-107 BPM) + 787-814 ms (76 BPM) = declining post-run pulse; median 610 ms.
- v13: steady resting HR 86-91 BPM over 4 minutes.
- FFT corroborates (1.76-1.90 Hz peak).

### Working pipeline (all on-watch, MIPS, pure Java)
1. Trigger measurement: direct KLVP via JNI facade on `/system/lib/hw/klvp.watch.so`
   (`KlvpStream.sendRequestToSensorHub('a', 0, 0, cmd=1, 0, target=4, [D0 02 01])`) — no service, no root.
   Same for transactions: `libsensorhub.so` natives reimplemented as
   `com.huami.watch.sensor.HmSensorManager` (request/start/release transaction) - direct hub data fetch.
2. Collect PPG (type 65538) at ~25.4 Hz with `System.nanoTime()` wall clock (event.timestamp is broken).
3. Sliding 55 s window: 1.2 s moving-average trend removal + 3-pt smoothing.
4. Peak detection: local maxima above 0.5xstd, 300 ms min distance, parabolic interpolation on wall clock.
5. Dicrotic-notch merge: short+long IBI pairs summing to ~2x median are merged.
6. Outlier cleaning [0.55..1.6]x median; HR = 60/meanIBI; SDNN; RMSSD on successive diffs.
7. Score = 100 x (1 - exp(-RMSSD/35ms)) - realistic 0-100, no saturation.
8. UI (round 320x300): live waveform, HR, HRV ring gauge + score, RMSSD, 6 BPM resonant breath pacer
   (10 s cycle), FLAG_KEEP_SCREEN_ON, 15 fps animation.
9. Clean exit: onPause/onDestroy -> idempotent stopAll() sends allDayHR=false (LED off) + unregister +
   wakelock release.

### Limits (honest)
- 25.8 Hz sampling: RMSSD is inflated vs ECG (~10-30%) and sensitive to motion; keep still, breathe with
  the pacer for best readings. Trend HRV, not clinical.
- Contact gate: std < 100 counts -> "no signal" (avoids fake 100% on charger).
- The hub still owns the high-rate data; only BPM (type-21) and 25 Hz PPG leave it.

## 18. HRV precision root cause and final fix

On-wrist A/B test used the **same peak set and cleaning**, changing only the time base:

| Timing | RMSSD | SDNN | HR |
|---|---:|---:|---:|
| callback arrival time | 147–168 ms | 97–101 ms | 83.8–84.4 BPM |
| reconstructed uniform sample clock | **36.9–40.7 ms** | **42–44 ms** | 83.8–84.4 BPM |

### Root cause
+ SensorHub emits one PPG sample per event at ~25.4 Hz, but transports them as five-event bursts every ~200 ms.
+ `SensorEvent.timestamp` is zero/broken; `System.nanoTime()` in the callback measures burst delivery, producing
+  near-zero gaps within a burst and ~200 ms gaps between bursts.
+ Using callback times as peak times created artificial successive-IBI differences and inflated RMSSD roughly 4x.

### Final algorithm
+ Calibrate `dt = (lastCallback-firstCallback)/(sampleCount-1)` over the 55 s window (valid only at 24–27 Hz).
+ Peak time = `(fractionalSampleIndex * dt)`; fractional index comes from 3-point parabolic interpolation.
+ Never use callback arrival time as individual sample time.
+ Sliding 55 s window; minimum 39 s before publishing HRV.
+ Sort the merged IBI list before selecting its median (the previous chronological-middle value was also wrong).
+ Dicrotic-notch merge + `[0.55..1.6] x median` rejection.
+ Score `100 x (1 - exp(-RMSSD/35ms))`: 20 ms -> 44%, 35 ms -> 63%, 60 ms -> 82%.

V16 is installed. Verified final expected resting output: HR ~84 BPM, RMSSD 37–41 ms, HRV score 65–69%.


## 19. ADPD174 100 Hz firmware patch (prepared, not flashed)

Hardware evidence: official ADPD174 register definitions use:

- `REG_SAMPLING_FREQ = 0x12`; value `8000 / sample_rate`.
- `REG_DEC_MODE = 0x15`; internal-average factor bits: Slot A bits 4–6, Slot B bits 8–10.
- Firmware normal configuration sets `REG_SAMPLING_FREQ = 0x0050` (100 Hz).
- Firmware configuration table at file offset `0x3EFE9` contains `15 02 20`: `REG_DEC_MODE=0x0220`,
  factor 2 = 4-sample averaging on both slots. 100 Hz / 4 = the observed 25 Hz output.

### Exact patch

Input: `/system/etc/firmware/sensorhub.bin` (259,180 bytes).

```text
file offset 0x003EFEA: 02 20  ->  00 00
firmware address:       0x0803EFEA
meaning:                REG_DEC_MODE 0x0220 -> 0x0000
expected output:        100 Hz (subject to firmware/transport limits)
```

The patch disables sample averaging only; it does not change LED current, pulse timing, AFE gain, or the
100 Hz optical sample configuration.

Artifacts:

| File | SHA-256 | Status |
|---|---|---|
| `firmware/sensorhub-25hz-original.bin` | `fb562b8cb854141e220ec1971d7f328b4fdf43f17c3cc259dfebccba70679f7f` | exact pull/rollback |
| `firmware/sensorhub-100hz.bin` | `ed76f3601ad504054b436cb1cbf2f8e32ddfe69e825bb7ab5e1860402e5dd8db` | two-byte candidate |

### Current status

- **Not flashed.** Device firmware hash was rechecked after the unsigned OTA test and matched the original.
- Stock recovery rejected the unsigned ZIP; Huami OTA certificate is device-specific.
- Fastboot was unavailable even after `sys.fastboot.enable=true`.
- Dirty COW was tested safely against a temporary read-only file and failed; do not use it against firmware.
- User cannot disassemble the watch, so SPI/SWD/JTAG programming is excluded.
- Remaining software delivery options: discover a signed/privileged OTA path, exploit a root/system-uid
  path, or find a SensorHub command that changes ADPD174 `REG_DEC_MODE` live.
- **Do not write the patch without a verified rollback/flash path.** A bad SensorHub image may disable HR,
  SensorHub communication, or firmware update recovery.


## 20. Firmware RE status: live DEC_MODE command not found

### Confirmed firmware configuration
- Firmware is ARM Cortex-M Thumb, flash base `0x08000000`; `sensorhub.bin` is 259,180 bytes.
- Normal ADPD174 setup calls `fcn.080255f8`, whose 7-register standby table is in MCU SRAM at `0x2000022A`.
- Firmware config table at file address `0x0803EFE9` contains `15 02 20`: register `0x15` (`REG_DEC_MODE`), value
  `0x0220` (4× Slot-A/Slot-B internal averaging).
- Normal setup contains `12 00 50`: register `0x12` (`REG_SAMPLING_FREQ`), value `0x0050` (100 Hz).
- ADI driver formula: `REG_DEC_MODE` factors 0/1/2 = 1×/2×/4×; output therefore becomes 25 Hz.
- Factory-test paths use a separate table containing `12 00 04` (2 kHz) and `15 00 00` (no averaging), but
  Android factory PPG modes still exported 25 Hz; the high-rate path is not exposed by those modes.

### Live-command investigation
- Traced all firmware callers of the ADPD register-write primitive. `REG_DEC_MODE` writes occur in initialization,
  calibration, and mode setup; no exposed KLVP command handler was found that accepts arbitrary ADPD register writes.
- `sensorhub-channel-log` is diagnostic text, not a raw PPG stream. `sensorhub-health` and `sensorhub-algo` return
  no direct stream data.
- `mcu_sdram` expects `/dev/sensorhub-log`, while this kernel exposes `sensorhub-channel-log`; a read-only request
  through the renamed node did not return an SRAM dump. No memory or firmware write was performed.
- H11 remains **need more info**: a hidden firmware/debug command is not ruled out, but none is statically identified.

### Delivery constraint
The two-byte patch is prepared in `firmware/`, but installing it requires one of: Huami-signed OTA, root/system write
access, a working bootloader/recovery path, or physical SPI/SWD access. None is currently available without risking the
watch. Do not write `force_upgrade` or alter the image until a verified rollback path exists.


## 21. Root audit status

### Tested/eliminated
- `adb root`: production build rejects it; shell remains uid 2000.
- Dirty COW (`CVE-2016-5195`): static MIPS32 race against a harmless read-only file failed after 20 million
  iterations (~137 s); no file change. Do not run it against firmware.
- Towelroot/futex (`CVE-2014-3153`): corrected `FUTEX_CMP_REQUEUE_PI` same-address probe returned `-1/EINVAL`;
  requeue validation is patched.
- Stock recovery: OTA trigger works, but unsigned ZIP is rejected. The only observed side effect was Dalvik-cache
  rebuild/"Optimizing apps"; firmware hash stayed unchanged.
- Fastboot: `adb reboot bootloader`, `reboot fastboot`, and `sys.fastboot.enable=true` did not expose fastboot.
- HmFTP exported system-uid service: default anonymous FTP home is `/sdcard/`, not a root/system path; no root
  primitive found.
- Public `iovyroot`/CVE-2015-1805 source has only ARM/ARM64 device-specific offsets; no MIPS offsets for this
  kernel/build. Porting requires kernel addresses and a MIPS payload.

### Remaining plausible root paths
1. Port CVE-2015-1805/iovyroot to this exact MIPS kernel after obtaining kernel symbols/boot image. High crash risk;
   no blind run performed.
2. Find a uid-1000 exported-component vulnerability in HmLab/OtaWatch/WearServices/HmFTP. Static inventory found
   exported components but no proven code-execution primitive.
3. Recover a Huami-signed OTA package/key or exploit recovery verification. No bypass found.
4. Physical SPI/SWD programming is the most reliable firmware route but unavailable by constraint.

Current conclusion: **no root method is proven**. The safest achieved capability is rootless direct SensorHub/KLVP
control and the V16 HRV app.
