package com.wifi.provision;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads /sdcard/wifi.json ({"ssid":..,"password":..} entries) and provisions the watch Wi-Fi. */
public class MainActivity extends Activity {
    private static final String TAG = "WifiProvision";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView out = new TextView(this);
        out.setTextSize(12);
        out.setPadding(10, 10, 10, 10);
        setContentView(out);
        StringBuilder log = new StringBuilder();
        File file = new File(Environment.getExternalStorageDirectory(), "wifi.json");
        try {
            StringBuilder json = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) json.append(line);
            reader.close();
            List<String[]> networks = parse(json.toString());
            WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                log.append("no WifiManager\n");
            } else {
                if (!wifi.isWifiEnabled()) {
                    wifi.setWifiEnabled(true);
                    Thread.sleep(1500);
                }
                for (String[] network : networks) {
                    WifiConfiguration cfg = new WifiConfiguration();
                    cfg.SSID = "\"" + network[0] + "\"";
                    cfg.preSharedKey = "\"" + network[1] + "\"";
                    cfg.status = WifiConfiguration.Status.ENABLED;
                    cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                    int id = wifi.addNetwork(cfg);
                    String result = id >= 0 ? "added netId=" + id : "FAILED";
                    Log.i(TAG, network[0] + " -> " + result);
                    log.append(network[0]).append(" -> ").append(result).append('\n');
                    if (id >= 0) wifi.enableNetwork(id, false);
                }
                wifi.saveConfiguration();
                log.append("config saved\n");
            }
            if (file.delete()) log.append("wifi.json removed\n");
        } catch (Throwable t) {
            Log.e(TAG, "import failed", t);
            log.append("error: ").append(t).append('\n');
        }
        out.setText(log.toString());
    }

    private static final Pattern ENTRY = Pattern.compile(
            "\\{\\s*\"ssid\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"password\"\\s*:\\s*\"([^\"]*)\"\\s*\\}");

    private static List<String[]> parse(String json) {
        List<String[]> networks = new ArrayList<String[]>();
        Matcher m = ENTRY.matcher(json);
        while (m.find()) networks.add(new String[]{m.group(1), m.group(2)});
        return networks;
    }
}
