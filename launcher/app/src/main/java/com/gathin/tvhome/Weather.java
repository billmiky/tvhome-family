package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The weather next to the date: an icon and the temperature now, from Open-Meteo (no account, no key).
 * Off unless the owner turns it on (weather, or the Settings menu) and sets weather_location. A place
 * name is looked up once and the coordinates kept. Fixed HTTPS hosts through {@link Ticker#get}, on the
 * ticker thread, only while Home is in front, every 30 minutes. Parsing and the icon choice are plain Java
 * so they can be tested on the JVM.
 */
final class Weather {
    private static final String TAG = "TvHomeWeather";

    static final String CELSIUS = "celsius";
    static final String FAHRENHEIT = "fahrenheit";
    static final long REFRESH_MS = 30 * 60_000L;

    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code&timezone=auto";
    private static final String GEOCODE = "https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&name=";
    /** The last reading, kept in prefs too so the screensaver can show it after the process restarts. */
    private static final String PREF_LAST = "weather_last";
    private static final String PREF_GEO = "weather_geo";
    /** Place and unit the saved reading is for, so a changed setting never shows the old one. */
    private static final String PREF_FOR = "weather_for";

    static final AtomicBoolean busy = new AtomicBoolean(false);

    /** One reading: the temperature in the unit asked for, and the WMO weather code. */
    static final class Reading {
        final double temp;
        final int code;
        final long at;

        Reading(double temp, int code, long at) {
            this.temp = temp;
            this.code = code;
            this.at = at;
        }
    }

    /** The last reading in this process, and the place and unit it is for. */
    static volatile Reading last;
    private static volatile String lastFor;

    private Weather() {
    }

    // ---- plain Java ------------------------------------------------------------------------------

    /** "1.29,103.85" (spaces allowed) as {lat, lon}, or null when the text is not a coordinate pair. */
    static double[] parseLatLon(String s) {
        if (s == null) return null;
        String[] parts = s.trim().split("\\s*,\\s*");
        if (parts.length != 2) return null;
        try {
            double lat = Double.parseDouble(parts[0]), lon = Double.parseDouble(parts[1]);
            if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
            return new double[] {lat, lon};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The first geocoding result as {lat, lon}, or null when the place wasn't found. */
    static double[] parseGeocode(String body) throws Exception {
        JSONArray results = new JSONObject(body).optJSONArray("results");
        if (results == null || results.length() == 0) return null;
        JSONObject r = results.getJSONObject(0);
        return parseLatLon(r.getDouble("latitude") + "," + r.getDouble("longitude"));
    }

    /** {"current":{"temperature_2m":31.4,"weather_code":2,…}} */
    static Reading parseForecast(String body, long now) throws Exception {
        JSONObject cur = new JSONObject(body).getJSONObject("current");
        double t = cur.getDouble("temperature_2m");
        if (Double.isNaN(t) || t < -100 || t > 150) throw new IllegalArgumentException("not a temperature: " + t);
        return new Reading(t, cur.getInt("weather_code"), now);
    }

    /**
     * A symbol for a WMO weather code, from the old BMP symbol block so most TV fonts have it; the caller
     * drops it when the font hasn't. Night is not told apart: the sun stays the sun.
     */
    static String icon(int code) {
        if (code == 0) return "☀";                         // clear
        if (code == 1 || code == 2) return "⛅";            // mainly clear, partly cloudy
        if (code == 3) return "☁";                         // overcast
        if (code == 45 || code == 48) return "≡";          // fog
        if (code >= 95) return "⚡";                        // thunderstorm
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "❄";   // snow
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return "☂"; // drizzle, rain
        return "";
    }

    /** "31°" */
    static String formatTemp(double temp) {
        return Math.round(temp) + "°";
    }

    static String encodeLast(Reading r) {
        return String.format(Locale.US, "%.1f,%d,%d", r.temp, r.code, r.at);
    }

    static Reading decodeLast(String s) {
        if (s == null) return null;
        String[] p = s.split(",");
        if (p.length != 3) return null;
        try {
            return new Reading(Double.parseDouble(p[0]), Integer.parseInt(p[1]), Long.parseLong(p[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- Android side, on the ticker thread ------------------------------------------------------

    /** The cached reading for this place and unit, from memory or prefs; null when there is none. */
    static Reading cached(Context ctx, String location, String unit) {
        String key = key(location, unit);
        if (last != null && key.equals(lastFor)) return last;
        SharedPreferences prefs = prefs(ctx);
        if (!key.equals(prefs.getString(PREF_FOR, null))) return null;
        Reading r = decodeLast(prefs.getString(PREF_LAST, null));
        last = r;
        lastFor = key;
        return r;
    }

    private static String key(String location, String unit) {
        return (location == null ? "" : location.trim()) + "|" + unit;
    }

    /**
     * Looks the place up if needed and fetches the weather now. Returns the new reading, or the previous one
     * for the same place and unit when the request fails; null without a place or when there has never been one.
     */
    static Reading fetch(Context ctx, String location, String unit) {
        SharedPreferences prefs = prefs(ctx);
        try {
            double[] at = coordinates(prefs, location);
            if (at == null) return null;
            String url = FORECAST + String.format(Locale.US, "&latitude=%.4f&longitude=%.4f", at[0], at[1])
                    + (FAHRENHEIT.equals(unit) ? "&temperature_unit=fahrenheit" : "");
            Reading r = parseForecast(Ticker.get(url), System.currentTimeMillis());
            last = r;
            lastFor = key(location, unit);
            prefs.edit().putString(PREF_LAST, encodeLast(r)).putString(PREF_FOR, lastFor).apply();
            return r;
        } catch (Exception e) {
            Log.w(TAG, "weather: " + e);
            return cached(ctx, location, unit);
        }
    }

    /** Coordinates for weather_location: given directly, or a place name looked up once and remembered. */
    private static double[] coordinates(SharedPreferences prefs, String location) throws Exception {
        if (location == null || location.trim().isEmpty()) return null;
        double[] direct = parseLatLon(location);
        if (direct != null) return direct;
        String name = location.trim();
        if (name.length() > 80) return null;
        String saved = prefs.getString(PREF_GEO, null);
        if (saved != null && saved.startsWith(name + "|")) return parseLatLon(saved.substring(name.length() + 1));
        double[] found = parseGeocode(Ticker.get(GEOCODE + URLEncoder.encode(name, StandardCharsets.UTF_8.name())));
        if (found == null) {
            Log.w(TAG, "no place called " + name);
            return null;
        }
        prefs.edit().putString(PREF_GEO, name + "|" + found[0] + "," + found[1]).apply();
        return found;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }
}
