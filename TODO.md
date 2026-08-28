
## App rename — watch-side follow-up (2026-08-28)

All `*-probe` apps renamed to final names (folders, packages, labels, docs;
uncommitted — `git add -A` + commit + push when ready). The watch still has
the OLD packages installed and the OLD NLS grant, so when it is back online:

- Install the renamed APKs (old installs must be replaced/removed):
  `adb install -r apks/builds/mic.apk`, `apks/builds/hrv.apk`, and any other
  renamed app you still use.
- **Re-grant notification access** — the persisted grant still names
  `com.hrv.probe/.NotifBlocker`, which no longer exists after the package
  rename; the listener stays unbound until the grant is re-applied:

  ```bash
  adb shell settings put secure enabled_notification_listeners \
    com.hrv.mic/.NotifBlocker:com.hrv.hrv/.NotifBlocker
  ```

- Smoke-test notif blocking (mic status line `Notif block ON`; quit → normal
  notifications again).
- On-device recording dir moved `/sdcard/mic-probe` → `/sdcard/mic` (wifi-serve
  now serves the new dir). Old recordings remain orphaned in
  `/sdcard/mic-probe` — pull them first if wanted.

## Previous run finish (2026-08-28)

Committed and pushed:

- `4facb81` mic/hrv probes: block notifications while running via NLS + pause grace — 7 files, +259/−9 (both `NotifBlocker` classes, both manifests, both MainActivities, README).
- `master` → `origin/master` (`64b368a..4facb81`), tree clean.

Still pending when the watch is back online:

- Install both APKs: `adb install -r apks/builds/mic.apk` + `apks/builds/hrv.apk`.
- One-time notification-access grant (persists across reboots; re-apply after force-stop/reboot to re-bind the listener):

  ```bash
  adb shell settings put secure enabled_notification_listeners \
    com.hrv.mic/.NotifBlocker:com.hrv.hrv/.NotifBlocker
  ```

- Smoke-test: launch each probe, send a phone notification while running (should not pop; mic status line shows `Notif block ON`), then quit and confirm notifications behave normally again.
