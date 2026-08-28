# TODO

## Previous run finish (2026-08-28)

Committed and pushed:

- `4facb81` mic/hrv probes: block notifications while running via NLS + pause grace — 7 files, +259/−9 (both `NotifBlocker` classes, both manifests, both MainActivities, README).
- `master` → `origin/master` (`64b368a..4facb81`), tree clean.

Still pending when the watch is back online:

- Install both APKs: `adb install -r apks/builds/mic-probe.apk` + `apks/builds/hrv-probe.apk`.
- One-time notification-access grant (persists across reboots; re-apply after force-stop/reboot to re-bind the listener):

  ```bash
  adb shell settings put secure enabled_notification_listeners \
    com.hrv.mic/.NotifBlocker:com.hrv.probe/.NotifBlocker
  ```

- Smoke-test: launch each probe, send a phone notification while running (should not pop; mic status line shows `Notif block ON`), then quit and confirm notifications behave normally again.
