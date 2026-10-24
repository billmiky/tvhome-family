package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sony BRAVIA IP control (the TV's own REST API under /sony/ and the IRCC remote-button service) for the
 * phone remote. Needs Settings › Network › Home network › IP control › Authentication "Normal and
 * Pre-Shared Key" and the same key saved from the phone page. Blocking: call it off the main thread.
 *
 * Besides the power helpers, {@link #call} reaches any service and method (audio, avContent, appControl…)
 * and {@link #button} presses any remote button, so the phone page can add features without a new launcher.
 */
final class SonyIp {
    private static final int TIMEOUT_MS = 3000;
    private static final int MAX_RESPONSE = 256 * 1024;
    /** Remote button names (lower case) to IRCC codes, from system.getRemoteControllerInfo; asked once. */
    private static volatile Map<String, String> buttons;

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
        call(psk, "system", "setPowerSavingMode", params("mode", off ? "pictureOff" : "off"), "1.0");
    }

    /** false: standby, like the remote's power button. true: wake. */
    static void power(String psk, boolean on) throws Failed {
        call(psk, "system", "setPowerStatus", params("status", on), "1.0");
    }

    /** "active" or "standby"; a working call also proves the key. */
    static String powerStatus(String psk) throws Failed {
        JSONArray r = call(psk, "system", "getPowerStatus", new JSONArray(), "1.0");
        JSONObject first = r.optJSONObject(0);
        return first != null ? first.optString("status", "") : "";
    }

    /** Any method of any service: /sony/{service}, {"method", "params", "version"}. Returns "result". */
    static JSONArray call(String psk, String service, String method, JSONArray params, String version) throws Failed {
        if (!name(service) || method == null || method.isEmpty()) throw new Failed("Bad service or method.");
        String body;
        try {
            body = new JSONObject()
                    .put("method", method)
                    .put("id", 1)
                    .put("version", version == null || version.isEmpty() ? "1.0" : version)
                    .put("params", params != null ? params : new JSONArray())
                    .toString();
        } catch (Exception e) {
            throw new Failed("Bad request.");
        }
        String text = post(psk, "/sony/" + service, body, "application/json; charset=UTF-8", null);
        try {
            JSONObject res = new JSONObject(text);
            JSONArray err = res.optJSONArray("error");
            if (err != null) {
                int ec = err.optInt(0);
                if (ec == 403 || ec == 401) throw new Failed("The TV refused the IP control key. Check it in Settings.");
                throw new Failed("The TV said: " + err.optString(1, "error " + ec));
            }
            JSONArray result = res.optJSONArray("result");
            if (result == null) result = res.optJSONArray("results");
            return result != null ? result : new JSONArray();
        } catch (Failed f) {
            throw f;
        } catch (Exception e) {
            throw new Failed("The TV's answer was not JSON.");
        }
    }

    /** Presses a remote button: an IRCC code (base64) or a name such as VolumeUp, Netflix, Num1, Home. */
    static void button(String psk, String nameOrCode) throws Failed {
        if (nameOrCode == null || nameOrCode.isEmpty()) throw new Failed("Which button?");
        String code = nameOrCode.endsWith("=") || nameOrCode.startsWith("AAAA") ? nameOrCode : buttonCodes(psk).get(nameOrCode.toLowerCase(Locale.ROOT));
        if (code == null) throw new Failed("The TV has no button called " + nameOrCode + ".");
        String soap = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:X_SendIRCC xmlns:u=\"urn:schemas-sony-com:service:IRCC:1\"><IRCCCode>" + xml(code) + "</IRCCCode>"
                + "</u:X_SendIRCC></s:Body></s:Envelope>";
        post(psk, "/sony/IRCC", soap, "text/xml; charset=UTF-8", "\"urn:schemas-sony-com:service:IRCC:1#X_SendIRCC\"");
    }

    /** The TV's button names and codes. */
    static Map<String, String> buttonCodes(String psk) throws Failed {
        Map<String, String> b = buttons;
        if (b != null) return b;
        JSONArray r = call(psk, "system", "getRemoteControllerInfo", new JSONArray(), "1.0");
        JSONArray list = r.optJSONArray(1);
        Map<String, String> m = new HashMap<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o != null) m.put(o.optString("name").toLowerCase(Locale.ROOT), o.optString("value"));
            }
        }
        if (!m.isEmpty()) buttons = m;
        return m;
    }

    private static JSONArray params(String key, Object value) throws Failed {
        try {
            return new JSONArray().put(new JSONObject().put(key, value));
        } catch (Exception e) {
            throw new Failed("Bad request.");
        }
    }

    private static String post(String psk, String path, String body, String type, String soapAction) throws Failed {
        if (psk == null || psk.isEmpty()) throw new Failed("Set the Sony IP control key in Settings on the phone page first.");
        for (String host : hosts()) {
            try {
                return post(host, psk, path, body, type, soapAction);
            } catch (Failed f) {
                throw f;   // the TV answered: no point asking it again on another address
            } catch (Exception e) {
                // Not listening on this address; try the next.
            }
        }
        throw new Failed("The TV's IP control did not answer. Is IP control switched on?");
    }

    private static String post(String host, String psk, String path, String body, String type, String soapAction) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", type);
        h.put("X-Auth-PSK", psk);
        if (soapAction != null) h.put("SOAPACTION", soapAction);
        LocalHttp.Response r = LocalHttp.request(host, 80, "POST", path, h, body.getBytes(StandardCharsets.UTF_8), TIMEOUT_MS, MAX_RESPONSE);
        if (r.status == 403 || r.status == 401) throw new Failed("The TV refused the IP control key. Check it in Settings.");
        if (r.status >= 400 && !r.body.startsWith("{")) throw new Failed("The TV's IP control answered " + r.status + ".");
        return r.body;
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

    /** Letters, digits and dots only: a service name can't walk to another path. */
    private static boolean name(String s) {
        if (s == null || s.isEmpty() || s.length() > 40 || !Character.isLetter(s.charAt(0)) || s.contains("..")) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!(Character.isLetterOrDigit(ch) || ch == '.')) return false;
        }
        return true;
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
