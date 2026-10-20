package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.LruCache;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phone control: a small web page on the home network for the Gallery photos and a few switches, so
 * nobody needs adb for them. One thread, one connection at a time, fixed routes only, every request
 * limited in size and time. Only addresses on the local network get an answer, and every request needs
 * the six-digit code the TV shows (hold OK on the network pill); a visit with the code sets a cookie.
 * Settings go to the same SharedPreferences the Settings menu uses; while Home is in front it applies them
 * at once through {@link #remote}, otherwise on its next resume. Off with web_control false.
 */
final class WebControl {
    static final String TAG = "TvHomeWeb";

    static final String PREF_CODE = "web_code";
    private static final String COOKIE = "hk";
    private static final int READ_TIMEOUT_MS = 5000;
    static final int MAX_JSON = 4 * 1024;
    /** The occasions list: 50 entries with texts and YouTube links. */
    static final int MAX_OCCASIONS_JSON = 16 * 1024;
    static final long MAX_UPLOAD = 15L * 1024 * 1024;
    private static final int MAX_LISTED = 500;
    private static final int THUMB_PX = 240;
    private static final String FOLDER_PATH = Environment.DIRECTORY_PICTURES + "/" + Gallery.FOLDER + "/";

    /** What Home does with the page's requests while it is in front; null otherwise. */
    interface Remote {
        void settingsChanged();

        void step(boolean back);

        void photosChanged();
    }

    static volatile Remote remote;

    private static volatile Thread thread;
    private static volatile ServerSocket server;
    private static volatile int port;
    private static volatile boolean broken;
    private static final WebHttp.Limiter limiter = new WebHttp.Limiter();
    private static final LruCache<Long, byte[]> thumbs = new LruCache<Long, byte[]>(2 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long key, byte[] value) {
            return value.length;
        }
    };
    private static String page;

    private WebControl() {
    }

    /** Starts the server once per process, or stops it when switched off. Never on the main thread's time. */
    static synchronized void start(Context ctx, boolean on, int wantedPort) {
        if (!on || broken) {
            stop();
            return;
        }
        if (thread != null) return;
        final Context app = ctx.getApplicationContext();
        port = wantedPort;
        Thread t = new Thread(() -> serve(app, wantedPort), "web-control");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        thread = t;
        t.start();
    }

    static synchronized void stop() {
        ServerSocket s = server;
        server = null;
        thread = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Running and reachable on this port; 0 when off. */
    static int port() {
        return thread != null && !broken ? port : 0;
    }

    /** The access code, made the first time it is asked for. */
    static String code(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        String c = p.getString(PREF_CODE, null);
        if (c == null || c.length() != 6) {
            c = WebHttp.newCode(new SecureRandom());
            p.edit().putString(PREF_CODE, c).apply();
        }
        return c;
    }

    /** A new code: phones that used the old link or cookie have to open the new link. */
    static String newCode(Context ctx) {
        String c = WebHttp.newCode(new SecureRandom());
        ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit().putString(PREF_CODE, c).apply();
        return c;
    }

    private static void serve(Context app, int wantedPort) {
        try (ServerSocket s = new ServerSocket(wantedPort)) {
            server = s;
            while (server == s) {
                try (Socket client = s.accept()) {
                    handle(app, client);
                } catch (IOException e) {
                    if (server != s) break;   // stopped
                } catch (Exception | LinkageError e) {
                    // One bad request never takes the page down, let alone Home.
                    Log.w(TAG, "request failed: " + e);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "no server on port " + wantedPort + ": " + e);
        } catch (Exception | LinkageError e) {
            broken = true;
            Log.w(TAG, "phone control switched off for this session: " + e);
        } finally {
            synchronized (WebControl.class) {
                if (thread == Thread.currentThread()) thread = null;
            }
        }
    }

    private static void handle(Context ctx, Socket client) throws Exception {
        client.setSoTimeout(READ_TIMEOUT_MS);
        InetAddress from = client.getInetAddress();
        if (from == null || !WebHttp.local(from.getAddress())) return;   // not the home network: no answer
        String address = from.getHostAddress();
        InputStream in = new BufferedInputStream(client.getInputStream());
        OutputStream out = new BufferedOutputStream(client.getOutputStream());
        try {
            WebHttp.Request req;
            try {
                req = WebHttp.parseHead(WebHttp.readHead(in));
            } catch (WebHttp.BadRequest e) {
                send(out, e.status, "text/plain; charset=utf-8", e.getMessage().getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            long now = System.currentTimeMillis();
            if (limiter.blocked(address, now)) {
                send(out, 429, "text/plain; charset=utf-8", bytes("Too many wrong codes. Wait a minute."), null);
                return;
            }
            String code = code(ctx);
            String given = req.query.get("k");
            String cookie = WebHttp.cookie(req.header("cookie"), COOKIE);
            boolean byLink = WebHttp.sameCode(given, code);
            boolean byCookie = WebHttp.sameCode(cookie, code);
            if (!byLink && !byCookie) {
                if (given != null || cookie != null) limiter.failed(address, now);
                send(out, 403, "text/html; charset=utf-8", bytes(DENIED), null);
                return;
            }
            if (byLink && req.method.equals("GET") && req.path.equals("/")) {
                // Swap the code in the address bar for a cookie, so it isn't left in the phone's history.
                String headers = "Location: /\r\nSet-Cookie: " + COOKIE + "=" + code + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000\r\n";
                send(out, 303, "text/plain; charset=utf-8", bytes("See /"), headers);
                return;
            }
            route(ctx, req, in, out);
        } finally {
            out.flush();
        }
    }

    private static void route(Context ctx, WebHttp.Request req, InputStream in, OutputStream out) throws Exception {
        String key = req.method + " " + req.path;
        if (key.equals("GET /")) {
            send(out, 200, "text/html; charset=utf-8", bytes(page(ctx)), CSP);
            return;
        }
        if (key.equals("GET /thumb")) {
            thumb(ctx, req, out);
            return;
        }
        if (req.method.equals("POST") && !"tvhome".equals(req.header("x-requested-with"))) {
            // Only the page's own script sends this header; a form on another site can't.
            send(out, 403, "text/plain; charset=utf-8", bytes("Missing X-Requested-With"), null);
            return;
        }
        switch (key) {
            case "GET /api/state":
                sendJson(out, 200, state(ctx));
                break;
            case "GET /api/report":
                sendJson(out, 200, ScreenTime.json(ctx, ScreenTime.load(ctx, "7d".equals(req.query.get("range")))));
                break;
            case "POST /api/settings":
                settings(ctx, req, in, out);
                break;
            case "POST /api/occasions":
                occasions(ctx, req, in, out);
                break;
            case "POST /api/photo": {
                Remote r = remote;
                String dir = req.query.get("dir");
                if (!"prev".equals(dir) && !"next".equals(dir)) {
                    sendError(out, 400, "dir is prev or next");
                } else if (r == null) {
                    sendError(out, 409, "Home is not on screen");
                } else {
                    r.step("prev".equals(dir));
                    sendJson(out, 200, "{\"ok\":true}");
                }
                break;
            }
            case "POST /api/upload":
                upload(ctx, req, in, out);
                break;
            case "POST /api/delete":
                delete(ctx, req, out);
                break;
            default:
                sendError(out, 404, "Not found");
        }
    }

    // ---- settings ---------------------------------------------------------------------------------

    private static String state(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        Config cfg = Config.load(ctx);
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"gallery\":").append(p.getBoolean(MainActivity.PREF_GALLERY, cfg.gallery))
                .append(",\"btc\":").append(p.getBoolean(MainActivity.PREF_BTC, cfg.btcTicker))
                .append(",\"weather\":").append(p.getBoolean(MainActivity.PREF_WEATHER, cfg.weather))
                .append(",\"now_playing\":").append(p.getBoolean(MainActivity.PREF_NOW_PLAYING, cfg.nowPlaying))
                .append(",\"greeting\":").append(WebHttp.json(p.getString(MainActivity.PREF_GREETING, cfg.greetingName)))
                .append(",\"weather_location\":").append(WebHttp.json(p.getString(MainActivity.PREF_WEATHER_LOCATION, cfg.weatherLocation)))
                .append(",\"holidays\":").append(p.getBoolean(MainActivity.PREF_HOLIDAYS, cfg.holidays))
                .append(",\"occasions\":").append(Occasions.toJson(Occasions.parse(p.getString(MainActivity.PREF_OCCASIONS, cfg.occasions), null)))
                .append(",\"home\":").append(remote != null)
                .append(",\"photos\":[");
        if (Build.VERSION.SDK_INT >= 29) {
            String own = ctx.getPackageName();
            String[] cols = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.MediaColumns.OWNER_PACKAGE_NAME};
            int n = 0;
            try (Cursor c = ctx.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
                    MediaStore.Images.Media.RELATIVE_PATH + "=?", new String[] {FOLDER_PATH},
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                while (c != null && c.moveToNext() && n < MAX_LISTED) {
                    String name = c.getString(1);
                    if (!Gallery.isPhoto(name)) continue;
                    if (n++ > 0) sb.append(',');
                    sb.append("{\"id\":").append(c.getLong(0))
                            .append(",\"name\":").append(WebHttp.json(name == null ? "" : name))
                            .append(",\"own\":").append(own.equals(c.getString(2)))
                            .append('}');
                }
            }
        }
        return sb.append("]}").toString();
    }

    private static void settings(Context ctx, WebHttp.Request req, InputStream in, OutputStream out) throws Exception {
        long length = req.contentLength();
        if (length < 0 || length > MAX_JSON) {
            sendError(out, 413, "Settings are at most " + MAX_JSON + " bytes");
            return;
        }
        JSONObject o;
        try {
            o = new JSONObject(new String(readBody(in, (int) length), StandardCharsets.UTF_8));
        } catch (Exception e) {
            sendError(out, 400, "Not JSON");
            return;
        }
        SharedPreferences.Editor e = ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit();
        putBoolean(e, o, "gallery", MainActivity.PREF_GALLERY);
        putBoolean(e, o, "btc", MainActivity.PREF_BTC);
        putBoolean(e, o, "weather", MainActivity.PREF_WEATHER);
        putBoolean(e, o, "now_playing", MainActivity.PREF_NOW_PLAYING);
        putBoolean(e, o, "holidays", MainActivity.PREF_HOLIDAYS);
        if (o.has("greeting")) e.putString(MainActivity.PREF_GREETING, WebHttp.cleanText(o.optString("greeting"), 40));
        if (o.has("weather_location")) {
            e.putString(MainActivity.PREF_WEATHER_LOCATION, WebHttp.cleanText(o.optString("weather_location"), 80));
        }
        e.apply();
        Remote r = remote;
        if (r != null) r.settingsChanged();
        sendJson(out, 200, state(ctx));
    }

    /**
     * The whole list of the owner's occasions, {"occasions": [{"date": "MM-DD" or "YYYY-MM-DD", "text": "…",
     * "video": optional YouTube link or id}]},
     * replacing the previous one. Every entry must validate; a bad one rejects the request rather than
     * being dropped quietly.
     */
    private static void occasions(Context ctx, WebHttp.Request req, InputStream in, OutputStream out) throws Exception {
        long length = req.contentLength();
        if (length < 0 || length > MAX_OCCASIONS_JSON) {
            sendError(out, 413, "Occasions are at most " + MAX_OCCASIONS_JSON + " bytes");
            return;
        }
        JSONArray list;
        try {
            list = new JSONObject(new String(readBody(in, (int) length), StandardCharsets.UTF_8)).getJSONArray("occasions");
        } catch (Exception e) {
            sendError(out, 400, "Not JSON with an occasions list");
            return;
        }
        if (list.length() > Occasions.MAX_ENTRIES) {
            sendError(out, 400, "At most " + Occasions.MAX_ENTRIES + " occasions");
            return;
        }
        List<Occasions.Entry> entries = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            String video = o == null ? "" : o.optString("video", "").trim();
            Occasions.Entry e = o == null ? null : Occasions.entry(o.optString("date", ""), o.optString("text", ""), video);
            if (e == null) {
                sendError(out, 400, "Occasion " + (i + 1) + " needs a real date and a text");
                return;
            }
            if (!video.isEmpty() && e.video.isEmpty()) {
                sendError(out, 400, "Occasion " + (i + 1) + ": the video must be a YouTube link");
                return;
            }
            entries.add(e);
        }
        ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                .putString(MainActivity.PREF_OCCASIONS, Occasions.toJson(entries)).apply();
        Remote r = remote;
        if (r != null) r.settingsChanged();
        sendJson(out, 200, state(ctx));
    }

    private static void putBoolean(SharedPreferences.Editor e, JSONObject o, String name, String pref) {
        Object v = o.opt(name);
        if (v instanceof Boolean) e.putBoolean(pref, (Boolean) v);
    }

    // ---- photos -----------------------------------------------------------------------------------

    /**
     * One photo, sent as the raw request body (the page uploads files one by one), saved in
     * Pictures/Launcher through MediaStore: no storage permission needed, and the launcher owns the
     * file, so it can delete it later. The type comes from the first bytes, never from the phone's word.
     */
    private static void upload(Context ctx, WebHttp.Request req, InputStream in, OutputStream out) throws Exception {
        if (Build.VERSION.SDK_INT < 29) {
            sendError(out, 501, "Uploading needs Android 10 or newer");
            return;
        }
        long length = req.contentLength();
        if (length <= 12 || length > MAX_UPLOAD) {
            sendError(out, 413, "A photo is at most 15 MB");
            return;
        }
        byte[] headBytes = readBody(in, 12);
        String type = WebHttp.imageType(headBytes, 12);
        if (type == null) {
            sendError(out, 415, "Only JPEG, PNG and WebP photos");
            return;
        }
        String sent = req.header("x-filename");
        try {
            sent = sent == null ? null : URLDecoder.decode(sent, "UTF-8");
        } catch (IllegalArgumentException e) {
            sent = null;
        }
        ContentResolver cr = ctx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, WebHttp.fileName(sent, type, System.currentTimeMillis()));
        values.put(MediaStore.Images.Media.MIME_TYPE, type);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER_PATH);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            sendError(out, 500, "Could not create the photo");
            return;
        }
        boolean done = false;
        try {
            try (OutputStream file = cr.openOutputStream(uri)) {
                if (file == null) throw new IOException("no output stream");
                file.write(headBytes);
                long left = length - headBytes.length;
                byte[] buf = new byte[16 * 1024];
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new IOException("upload cut short");
                    file.write(buf, 0, n);
                    left -= n;
                }
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(uri, ready, null, null);
            done = true;
        } finally {
            if (!done) cr.delete(uri, null, null);
        }
        Remote r = remote;
        if (r != null) r.photosChanged();
        sendJson(out, 200, "{\"ok\":true,\"id\":" + ContentUris.parseId(uri) + "}");
    }

    private static void delete(Context ctx, WebHttp.Request req, OutputStream out) throws Exception {
        long id = id(req);
        String owner = id < 0 ? null : owner(ctx, id);
        if (owner == null) {
            sendError(out, 404, "No such photo in Pictures/Launcher");
            return;
        }
        if (!owner.equals(ctx.getPackageName())) {
            sendError(out, 403, "Added from a computer, can't delete it here");
            return;
        }
        int rows = ctx.getContentResolver().delete(
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), null, null);
        thumbs.remove(id);
        Remote r = remote;
        if (r != null) r.photosChanged();
        sendJson(out, rows > 0 ? 200 : 500, rows > 0 ? "{\"ok\":true}" : "{\"error\":\"Not deleted\"}");
    }

    private static void thumb(Context ctx, WebHttp.Request req, OutputStream out) throws Exception {
        long id = id(req);
        if (id < 0 || Build.VERSION.SDK_INT < 29 || owner(ctx, id) == null) {
            sendError(out, 404, "No such photo");
            return;
        }
        byte[] jpeg = thumbs.get(id);
        if (jpeg == null) {
            Bitmap bmp = ctx.getContentResolver().loadThumbnail(
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), new Size(THUMB_PX, THUMB_PX), null);
            ByteArrayOutputStream b = new ByteArrayOutputStream(16 * 1024);
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, b);
            bmp.recycle();
            jpeg = b.toByteArray();
            thumbs.put(id, jpeg);
        }
        send(out, 200, "image/jpeg", jpeg, "Cache-Control: private, max-age=3600\r\n");
    }

    private static long id(WebHttp.Request req) {
        String v = req.query.get("id");
        if (v == null || v.isEmpty() || v.length() > 18 || !v.chars().allMatch(Character::isDigit)) return -1;
        return Long.parseLong(v);
    }

    /** The owner package of a photo in Pictures/Launcher ("" when none is recorded), or null when it isn't there. */
    private static String owner(Context ctx, long id) {
        if (Build.VERSION.SDK_INT < 29) return null;
        String[] cols = {MediaStore.MediaColumns.OWNER_PACKAGE_NAME};
        try (Cursor c = ctx.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols,
                MediaStore.Images.Media._ID + "=? AND " + MediaStore.Images.Media.RELATIVE_PATH + "=?",
                new String[] {Long.toString(id), FOLDER_PATH}, null)) {
            if (c == null || !c.moveToFirst()) return null;
            String o = c.getString(0);
            return o == null ? "" : o;
        }
    }

    // ---- HTTP plumbing ----------------------------------------------------------------------------

    private static byte[] readBody(InputStream in, int length) throws IOException {
        byte[] b = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(b, off, length - off);
            if (n < 0) throw new IOException("body cut short");
            off += n;
        }
        return b;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void sendJson(OutputStream out, int status, String json) throws IOException {
        send(out, status, "application/json; charset=utf-8", bytes(json), null);
    }

    private static void sendError(OutputStream out, int status, String message) throws IOException {
        sendJson(out, status, "{\"error\":" + WebHttp.json(message) + "}");
    }

    private static final String CSP = "Content-Security-Policy: default-src 'none'; img-src 'self'; "
            + "style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'none'; frame-ancestors 'none'\r\n";

    private static void send(OutputStream out, int status, String type, byte[] body, String extra) throws IOException {
        StringBuilder h = new StringBuilder(256);
        h.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("Referrer-Policy: no-referrer\r\n")
                .append("X-Frame-Options: DENY\r\n");
        if (extra == null || !extra.contains("Cache-Control")) h.append("Cache-Control: no-store\r\n");
        if (extra != null) h.append(extra);
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
    }

    private static String reason(int status) {
        switch (status) {
            case 200:
                return "OK";
            case 303:
                return "See Other";
            case 400:
                return "Bad Request";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 409:
                return "Conflict";
            case 413:
                return "Payload Too Large";
            case 415:
                return "Unsupported Media Type";
            case 429:
                return "Too Many Requests";
            case 431:
                return "Request Header Fields Too Large";
            case 501:
                return "Not Implemented";
            default:
                return "Error";
        }
    }

    private static synchronized String page(Context ctx) throws IOException {
        if (page == null) {
            try (InputStream in = ctx.getResources().openRawResource(R.raw.web_control)) {
                ByteArrayOutputStream b = new ByteArrayOutputStream(16 * 1024);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                page = new String(b.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        return page;
    }

    private static final String DENIED = "<!doctype html><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>Home</title>"
            + "<body style='background:#000;color:#ddd;font:16px system-ui,sans-serif;padding:32px'>"
            + "<h2 style='font-weight:500'>Open the link shown on the TV</h2>"
            + "<p>On the TV, hold OK on the network button. The menu shows this page's address with its code.</p>";

    static String url(String ip, int port, String code) {
        return String.format(Locale.US, "http://%s:%d/?k=%s", ip, port, code);
    }
}
