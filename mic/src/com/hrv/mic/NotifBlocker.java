package com.hrv.mic;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Cancels every notification the system posts while the probe is armed, so
 *  phone notifications (mirrored onto the watch via the iWDS framework as
 *  regular NotificationManager posts) can never pop up and steal focus from
 *  the running probe.
 *
 *  Requires notification access, which this ROM exposes only via adb:
 *      adb shell settings put secure enabled_notification_listeners com.hrv.mic/.NotifBlocker
 *  (persists across reboots). On this Android version a dead listener is not
 *  re-bound until the setting changes or reboot, so the probe process must
 *  stay alive across sessions — the app's status line shows whether the
 *  listener is actually connected. */
public class NotifBlocker extends NotificationListenerService {
    /** True while the probe activity is in the foreground. */
    static volatile boolean armed;
    /** True while the system has bound this listener (grant present + process alive). */
    static volatile boolean connected;

    private static NotifBlocker instance;

    @Override
    public void onListenerConnected() {
        connected = true;
        instance = this;
        if (armed) drainActive();
    }

    @Override
    public void onDestroy() {
        connected = false;
        if (instance == this) instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (armed) {
            try {
                cancelNotification(sbn.getKey());
            } catch (SecurityException ignored) {
                // access revoked mid-run; nothing we can do
            }
        }
    }

    /** Arm/disarm; when arming, clear any notification already showing. */
    static void setArmed(boolean value) {
        armed = value;
        if (value) {
            NotifBlocker self = instance;
            if (self != null) self.drainActive();
        }
    }

    private void drainActive() {
        try {
            for (StatusBarNotification sbn : getActiveNotifications()) {
                cancelNotification(sbn.getKey());
            }
        } catch (SecurityException ignored) {
        }
    }
}
