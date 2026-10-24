package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Remote buttons the owner set on the TV (Settings menu › Remote buttons): a button, pressed or held, runs one
 * action: open an app, Sony picture on/off, Wireless debugging on/off, mute, the Inputs list, Home, Back, Recent
 * apps, Settings, or a POST to a webhook URL. Recorded by the key helpers (InputKeyService, else HomeKeyService),
 * which see every key, also the ones Android hands out before any app. Unlike app_keys (RemoteKeys, config.xml or
 * a theme) these live in the launcher's own preferences. A button is the keycode plus its scancode, as Sony sends
 * YouTube, Prime Video and Disney+ as one keycode told apart by scancode; a key without a scancode (sent by the
 * phone remote) matches on the keycode alone. D-pad, OK, Back and Home are never recorded, so the menus always work.
 */
final class KeyRemap {

    static final String PREFS = "key_remap";
    private static final String PREF_MAP = "map";

    static final String APP = "app", PICTURE = "picture", ADB_WIFI = "adb_wifi", MUTE = "mute", INPUTS = "inputs",
            HOME = "home", BACK = "back", RECENTS = "recents", SETTINGS = "settings", WEBHOOK = "webhook";
    /** Every action, in the order the menu lists them. */
    static final String[] ACTIONS = {APP, PICTURE, ADB_WIFI, MUTE, INPUTS, HOME, BACK, RECENTS, SETTINGS, WEBHOOK};

    private KeyRemap() { }

    /** One button: keycode, scancode (0: any), held or pressed, and its action with the app or URL it needs. */
    static final class Entry {
        final int code, scan;
        final boolean hold;
        final String action, arg;

        Entry(int code, int scan, boolean hold, String action, String arg) {
            this.code = code;
            this.scan = Math.max(scan, 0);
            this.hold = hold;
            this.action = action;
            this.arg = arg == null ? "" : arg;
        }

        boolean sameButton(int code, int scan, boolean hold) {
            return this.code == code && this.scan == Math.max(scan, 0) && this.hold == hold;
        }
    }

    // ---- the list ------------------------------------------------------------------------------------

    /** The saved list; entries with an unknown action, no keycode or a missing app or URL are skipped. */
    static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry(o.optInt("code"), o.optInt("scan"), o.optBoolean("hold"), o.optString("action"), o.optString("arg"));
                if (valid(e)) out.add(e);
            }
        } catch (Exception ignored) {
            // A broken list is an empty one.
        }
        return out;
    }

    static String serialize(List<Entry> entries) {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : entries) {
                JSONObject o = new JSONObject().put("code", e.code).put("action", e.action);
                if (e.scan > 0) o.put("scan", e.scan);
                if (e.hold) o.put("hold", true);
                if (!e.arg.isEmpty()) o.put("arg", e.arg);
                a.put(o);
            }
        } catch (Exception ignored) {
            // put() only throws for non-finite numbers.
        }
        return a.toString();
    }

    static boolean valid(Entry e) {
        if (e.code <= 0) return false;
        boolean known = false;
        for (String a : ACTIONS) known |= a.equals(e.action);
        if (!known) return false;
        if (APP.equals(e.action)) return !e.arg.isEmpty();
        if (WEBHOOK.equals(e.action)) return webhookUrl(e.arg);
        return true;
    }

    /** http:// or https:// with a host; nothing else is sent anywhere. */
    static boolean webhookUrl(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase(java.util.Locale.ROOT);
        String rest = u.startsWith("https://") ? u.substring(8) : u.startsWith("http://") ? u.substring(7) : null;
        return rest != null && !rest.isEmpty() && rest.charAt(0) != '/' && rest.charAt(0) != ':' && !u.contains(" ");
    }

    /**
     * The entry for a key: one with this keycode and scancode, else, for a key without a scancode (the phone
     * remote's), one with this keycode alone. null: the button isn't set this way.
     */
    static Entry find(List<Entry> entries, int code, int scan, boolean hold) {
        Entry loose = null;
        for (Entry e : entries) {
            if (e.code != code || e.hold != hold) continue;
            if (e.scan == Math.max(scan, 0)) return e;
            if (scan <= 0 && loose == null) loose = e;
        }
        return loose;
    }

    /** The list with this button set to e (replacing what it did), or cleared when e is null. */
    static List<Entry> with(List<Entry> entries, int code, int scan, boolean hold, Entry e) {
        List<Entry> out = new ArrayList<>();
        for (Entry x : entries) if (!x.sameButton(code, scan, hold)) out.add(x);
        if (e != null) out.add(e);
        return out;
    }

    /** Buttons that move around the menus: never recorded or remapped. */
    static boolean reserved(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_UNKNOWN:
                return true;
            default:
                return false;
        }
    }

    // ---- saved list, shared by the key helpers and the menu (one process) ---------------------------

    private static volatile List<Entry> cached;

    static List<Entry> entries(Context ctx) {
        List<Entry> c = cached;
        if (c == null) {
            c = Collections.unmodifiableList(parse(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_MAP, "")));
            cached = c;
        }
        return c;
    }

    static void save(Context ctx, List<Entry> entries) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_MAP, serialize(entries)).apply();
        cached = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    // ---- keys ----------------------------------------------------------------------------------------

    /** Told the button pressed while the Add a button dialog is open: keycode, scancode, held. */
    interface Recorder {
        void recorded(int code, int scan, boolean hold);
    }

    /** Set while the menu waits for a button; the key helpers hand it the next one instead of running it. */
    static volatile Recorder recorder;
    /** The press whose hold action already ran (its downTime), so its release does nothing. */
    private static long heldDown = -1;
    /** Made on first use, so the plain-Java half loads in JVM tests. */
    private static Handler main;

    private static synchronized Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }

    /** True when the key helper has the launcher's Accessibility switch on, so buttons can be set. */
    static boolean helperOn() {
        return InputKeyService.running != null || HomeKeyService.running != null;
    }

    /**
     * From a key helper's onKeyEvent: true consumes the key. A pressed button acts when it is let go; a held one
     * as soon as it has been held for RemoteKeys.HOLD_MS, so holding feels immediate. A set button never
     * reaches the app in front, also when only its hold is set.
     */
    static boolean onKey(AccessibilityService service, KeyEvent event) {
        int code = event.getKeyCode(), scan = event.getScanCode();
        if (reserved(code)) return false;
        Recorder r = recorder;
        if (r != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                final boolean held = event.getEventTime() - event.getDownTime() >= RemoteKeys.HOLD_MS;
                main().post(() -> {
                    if (recorder == r) r.recorded(code, scan, held);
                });
            }
            return true;
        }
        List<Entry> list = entries(service);
        if (list.isEmpty()) return false;
        Entry tap = find(list, code, scan, false), hold = find(list, code, scan, true);
        if (tap == null && hold == null) return false;
        long down = event.getDownTime();
        boolean longEnough = event.getEventTime() - down >= RemoteKeys.HOLD_MS;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (hold != null && longEnough && heldDown != down) {
                heldDown = down;
                run(service, hold);
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled() && heldDown != down) {
            // A hold that ended between repeats (some remotes don't repeat) still counts as a hold.
            Entry e = longEnough && hold != null ? hold : tap;
            if (e != null) run(service, e);
        }
        return true;
    }

    // ---- actions -------------------------------------------------------------------------------------

    /** Webhooks and the TV's IP control, one at a time, off the key thread. */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    static void run(AccessibilityService service, Entry e) {
        final Context app = service.getApplicationContext();
        switch (e.action) {
            case APP:
                RemoteKeys.launch(service, e.arg);
                break;
            case PICTURE:
                EXECUTOR.execute(() -> togglePicture(app));
                break;
            case ADB_WIFI:
                toggleAdbWifi(app);
                break;
            case MUTE: {
                AudioManager am = app.getSystemService(AudioManager.class);
                if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI);
                break;
            }
            case INPUTS:
                start(service, new Intent("com.android.tv.action.VIEW_INPUTS"));
                break;
            case HOME:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
                break;
            case BACK:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
                break;
            case RECENTS:
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                break;
            case SETTINGS:
                start(service, new Intent(Settings.ACTION_SETTINGS));
                break;
            case WEBHOOK: {
                final String body = webhookBody(e, Build.MODEL);
                EXECUTOR.execute(() -> {
                    try {
                        post(e.arg.trim(), body);
                    } catch (Exception x) {
                        toast(app, app.getString(R.string.remap_webhook_failed, x.getMessage() != null ? x.getMessage() : x.toString()));
                    }
                });
                break;
            }
            default:
                break;
        }
    }

    private static void start(Context ctx, Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    /** Sony picture off and back, through IP control (the key saved on the phone page). */
    private static void togglePicture(Context app) {
        String psk = app.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString(RemoteRelay.PREF_PSK, "");
        try {
            JSONObject now = SonyIp.call(psk, "system", "getPowerSavingMode", new JSONArray(), "1.0").optJSONObject(0);
            boolean off = now == null || !"pictureOff".equals(now.optString("mode"));
            SonyIp.pictureOff(psk, off);
        } catch (SonyIp.Failed f) {
            toast(app, f.getMessage());
        }
    }

    /** Wireless debugging on or off (WRITE_SECURE_SETTINGS, granted by the install script), with a toast. */
    private static void toggleAdbWifi(Context app) {
        android.content.ContentResolver cr = app.getContentResolver();
        boolean on = Settings.Global.getInt(cr, "adb_wifi_enabled", 0) != 1;
        try {
            if (on && Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 0) Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
            Settings.Global.putInt(cr, "adb_wifi_enabled", on ? 1 : 0);
            toast(app, app.getString(R.string.net_menu_wireless) + ": "
                    + app.getString(on ? R.string.settings_menu_on : R.string.settings_menu_off));
        } catch (SecurityException e) {
            toast(app, app.getString(R.string.remap_no_permission));
        }
    }

    /** {"keycode", "scancode", "hold", "tv"}: enough for the receiver to tell buttons and TVs apart. */
    static String webhookBody(Entry e, String tv) {
        try {
            return new JSONObject().put("keycode", e.code).put("scancode", e.scan).put("hold", e.hold).put("tv", tv).toString();
        } catch (Exception x) {
            return "{}";
        }
    }

    /** POSTs the JSON body; http goes over a plain socket (LocalHttp), as cleartext is off for HttpURLConnection. */
    private static void post(String url, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (url.regionMatches(true, 0, "http://", 0, 7)) {
            URL u = new URL(url);
            Map<String, String> h = new HashMap<>();
            h.put("Content-Type", "application/json");
            String path = u.getFile().isEmpty() ? "/" : u.getFile();
            LocalHttp.Response r = LocalHttp.request(u.getHost(), u.getPort() > 0 ? u.getPort() : 80, "POST", path, h, bytes, 5000, 16 * 1024);
            if (r.status >= 400) throw new Exception("HTTP " + r.status);
            return;
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                try (InputStream s = in) {
                    byte[] buf = new byte[4096];
                    ByteArrayOutputStream sink = new ByteArrayOutputStream();
                    int n;
                    while ((n = s.read(buf)) > 0 && sink.size() < 16 * 1024) sink.write(buf, 0, n);
                }
            }
            if (status >= 400) throw new Exception("HTTP " + status);
        } finally {
            c.disconnect();
        }
    }

    private static void toast(Context app, String text) {
        main().post(() -> Toast.makeText(app, text, Toast.LENGTH_SHORT).show());
    }
}
