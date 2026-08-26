package com.wifi.serve;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the watch into a WiFi access point via the hidden WifiManager APIs
 * (setWifiApConfiguration / setWifiApEnabled are @hide on API 22) and reads
 * the resulting AP address. Also watches /proc/net/arp so the UI can detect
 * when a phone has joined the AP.
 */
public final class ApManager {
    private static final String TAG = "PaceSync";
    private static final int WIFI_AP_STATE_ENABLED = 13;

    public static final String SSID = "PaceSync";
    public static final String PASS = "pace-sync";

    private static final Pattern ARP_LINE = Pattern.compile(
            "^\\s*([0-9.]+)\\s+\\S+\\s+\\S+\\s+([0-9a-fA-F:]{17})\\s+\\S+\\s+\\S+$");

    private ApManager() {
    }

    /** Configure + enable the AP. Returns false if the ROM rejects a non-system app. */
    public static boolean enable(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            WifiConfiguration cfg = new WifiConfiguration();
            cfg.SSID = SSID;
            cfg.preSharedKey = PASS;
            cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            cfg.priority = 1;
            Method setCfg = WifiManager.class.getMethod("setWifiApConfiguration", WifiConfiguration.class);
            Method setAp = WifiManager.class.getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            // Disable first so a stale live AP is torn down and the new
            // config (SSID/pass) is actually applied on re-enable.
            setAp.invoke(wm, cfg, Boolean.FALSE);
            setCfg.invoke(wm, cfg);
            setAp.invoke(wm, cfg, Boolean.TRUE);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "AP enable failed", t);
            return false;
        }
    }

    public static boolean isEnabled(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            Method m = WifiManager.class.getMethod("getWifiApState");
            return ((Integer) m.invoke(wm)).intValue() == WIFI_AP_STATE_ENABLED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void disable(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            Method m = WifiManager.class.getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            m.invoke(wm, new Object[]{null, Boolean.FALSE});
            // Turn the radio off too: the watch normally leaves WiFi off,
            // and the AP hand-off would otherwise let it reconnect/scan.
            wm.setWifiEnabled(false);
        } catch (Throwable t) {
            Log.e(TAG, "AP disable failed", t);
        }
    }

    /**
     * IPv4 address of the AP interface (wlan0, else the first private IPv4),
     * or null while the AP is still coming up.
     */
    public static String apIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp()) {
                    continue;
                }
                String iface = ni.getName();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if ("wlan0".equals(iface) || ip.startsWith("192.168.")
                                || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "apIp failed", t);
        }
        return null;
    }

    /**
     * Number of real (non-zero) MAC addresses currently in the ARP table on
     * the given subnet prefix ("192.168.43"). Returns -1 if /proc/net/arp is
     * not readable (caller falls back to a timer).
     */
    public static int apClients(String subnetPrefix) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/net/arp"));
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                Matcher m = ARP_LINE.matcher(line);
                if (!m.matches()) {
                    continue;
                }
                String ip = m.group(1);
                String mac = m.group(2);
                if (!"00:00:00:00:00:00".equals(mac) && ip.startsWith(subnetPrefix)) {
                    n++;
                }
            }
            return n;
        } catch (Throwable t) {
            return -1;
        } finally {
            try {
                if (r != null) {
                    r.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
