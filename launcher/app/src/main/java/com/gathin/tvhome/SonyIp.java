package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sony BRAVIA IP control (the TV's own REST API, /sony/system) for the phone remote's power buttons.
 * Needs Settings › Network › Home network › IP control › Authentication "Normal and Pre-Shared Key" and
 * the same key saved from the phone page. Blocking: call it off the main thread.
 */
final class SonyIp {
    private static final int TIMEOUT_MS = 3000;

    private SonyIp() {
    }

    /** Thrown with a message the phone page can show as it is. */
    static final class Failed extends Exception {
        Failed(String msg) {
            super(msg);
        }
    }

    /** Picture off keeps the TV running (and reachable); "off" turns the picture back on. */
    static void pictureOff(String psk, boolean off) throws Failed {
        call(psk, "setPowerSavingMode", "mode", off ? "pictureOff" : "off");
    }

    /** false: standby, like the remote's power button. true: wake. */
    static void power(String psk, boolean on) throws Failed {
        call(psk, "setPowerStatus", "status", on);
    }

    /** "active" or "standby"; a working call also proves the key. */
    static String powerStatus(String psk) throws Failed {
        JSONArray r = call(psk, "getPowerStatus", null, null);
        JSONObject first = r.optJSONObject(0);
        return first != null ? first.optString("status", "") : "";
    }

    private static JSONArray call(String psk, String method, String key, Object value) throws Failed {
        if (psk == null || psk.isEmpty()) throw new Failed("Set the Sony IP control key in Settings on the phone page first.");
        String body;
        try {
            JSONObject req = new JSONObject()
                    .put("method", method)
                    .put("id", 1)
                    .put("version", "1.0")
                    .put("params", key != null ? new JSONArray().put(new JSONObject().put(key, value)) : new JSONArray());
            body = req.toString();
        } catch (Exception e) {
            throw new Failed("Bad request.");
        }
        Failed last = new Failed("The TV's IP control did not answer. Is IP control switched on?");
        for (String host : hosts()) {
            try {
                return post(host, psk, body);
            } catch (Failed f) {
                throw f;   // the TV answered: no point asking it again on another address
            } catch (Exception e) {
                // Not listening on this address; try the next.
            }
        }
        throw last;
    }

    private static JSONArray post(String host, String psk, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + "/sony/system").openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setRequestProperty("X-Auth-PSK", psk);
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) {
                o.write(b);
            }
            int code = c.getResponseCode();
            if (code == 403 || code == 401) throw new Failed("The TV refused the IP control key. Check it in Settings.");
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (in == null) throw new Failed("The TV's IP control answered " + code + ".");
            JSONObject res = new JSONObject(read(in));
            JSONArray err = res.optJSONArray("error");
            if (err != null) {
                int ec = err.optInt(0);
                if (ec == 403 || ec == 401) throw new Failed("The TV refused the IP control key. Check it in Settings.");
                throw new Failed("The TV said: " + err.optString(1, "error " + ec));
            }
            JSONArray result = res.optJSONArray("result");
            return result != null ? result : new JSONArray();
        } finally {
            c.disconnect();
        }
    }

    /** The loopback first, then the TV's own IPv4 addresses (the API may listen only on the LAN side). */
    private static List<String> hosts() {
        List<String> out = new ArrayList<>();
        out.add("127.0.0.1");
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
            // Loopback only.
        }
        return out;
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n, total = 0;
            while ((n = i.read(buf)) > 0) {
                total += n;
                if (total > 64 * 1024) break;
                b.write(buf, 0, n);
            }
            return b.toString("UTF-8");
        }
    }
}
