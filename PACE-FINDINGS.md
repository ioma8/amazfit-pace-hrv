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
