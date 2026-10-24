package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Phone remote over the internet: one WebSocket from the TV to the relay (relay/ in this repo, a
 * Cloudflare Worker), which passes on what the paired phone sends. The phone gets the TV's id and a
 * phone key from the QR code in the network menu; the relay only ever sees the key's SHA-256, which
 * the TV sends on connecting. Commands: banner, banner_clear, screen_off, screen_on, standby, wake,
 * set_psk, set_pin, lock, unlock, lock_timer, lock_timer_cancel and ping; each is answered with an ack
 * the phone page shows, carrying the lock state (TvLock).
 *
 * One daemon thread for the socket, reconnecting with backoff; a ping every 45 s keeps it open through
 * NATs (the relay answers without waking up), and silence for 100 s or the screen coming on reconnects.
 * Off when remote_relay is empty.
 */
final class RemoteRelay {
    static final String TAG = "TvHomeRemote";

    private static final String PREF_ID = "remote_id";
    private static final String PREF_TV_KEY = "remote_tv_key";
    private static final String PREF_PHONE_KEY = "remote_phone_key";
    static final String PREF_PSK = "sony_psk";
    private static final int PING_MS = 45_000;
    private static final int READ_TIMEOUT_MS = 100_000;
    private static final long MAX_BACKOFF_MS = 120_000;
    private static final int MAX_FRAME = 64 * 1024;

    /** What Home does with a banner while the process runs; set by MainActivity. */
    interface Screen {
        /** On the main thread. Brings Home to the front if needed and shows the message. */
        void showBanner(String text);

        void clearBanner();
    }

    static volatile Screen screen;

    private static volatile String base;
    private static volatile Thread thread;
    private static volatile Socket socket;
    private static volatile boolean online;
    private static Context app;
    private static final Object writeLock = new Object();
    private static final ExecutorService work = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "remote-cmd");
        t.setDaemon(true);
        return t;
    });
    private static final Handler main = new Handler(Looper.getMainLooper());

    private RemoteRelay() {
    }

    /** Starts the connection once per process; nothing when the relay address is empty. */
    static synchronized void start(Context ctx, String relay) {
        if (relay == null || relay.trim().isEmpty() || thread != null) return;
        app = ctx.getApplicationContext();
        base = relay.trim().replaceAll("/+$", "");
        keys(app);
        TvLock.start(app);
        Thread t = new Thread(RemoteRelay::loop, "remote-relay");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        thread = t;
        t.start();
        // Back from standby the old socket is usually dead: reconnect at once instead of waiting it out.
        try {
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    kick();
                }
            }, new IntentFilter(Intent.ACTION_SCREEN_ON));
        } catch (Exception e) {
            Log.w(TAG, "no screen-on receiver: " + e);
        }
    }

    static boolean enabled() {
        return base != null;
    }

    static boolean online() {
        return online;
    }

    /** The link in the QR code: the phone page with #<TV id>.<phone key>. Null when off. */
    static String phoneUrl(Context ctx) {
        if (base == null) return null;
        SharedPreferences p = keys(ctx);
        return base + "/#" + p.getString(PREF_ID, "") + "." + p.getString(PREF_PHONE_KEY, "");
    }

    /** A new phone key: phones paired with the old one stop working. */
    static void newPhoneKey(Context ctx) {
        prefs(ctx).edit().putString(PREF_PHONE_KEY, random(16)).apply();
        work.execute(RemoteRelay::hello);
    }

    // ---- connection --------------------------------------------------------------------------------

    private static void loop() {
        long backoff = 2000;
        while (true) {
            try {
                connect();
            } catch (Exception e) {
                Log.i(TAG, "relay connection ended: " + e);
            } finally {
                // A connection that worked starts the backoff over.
                if (online) backoff = 2000;
                online = false;
                closeQuietly();
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException ignored) {
                // kick(): try again now
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
    }

    /** Drops the current socket (or ends the wait between attempts) so the loop connects again now. */
    static void kick() {
        closeQuietly();
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    private static void connect() throws Exception {
        SharedPreferences p = keys(app);
        URI uri = URI.create(base);
        String host = uri.getHost();
        boolean tls = !"http".equals(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : tls ? 443 : 80;
        Socket s;
        if (tls) {
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), 10_000);
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
            // A layered socket checks the chain but not the name: ask for the HTTPS host check.
            SSLParameters params = ssl.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            s = ssl;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), 10_000);
        }
        socket = s;
        s.setSoTimeout(READ_TIMEOUT_MS);
        s.setKeepAlive(true);
        OutputStream out = s.getOutputStream();
        InputStream in = new BufferedInputStream(s.getInputStream());

        String wsKey = Base64.encodeToString(bytes(16), Base64.NO_WRAP);
        String req = "GET /tv?id=" + p.getString(PREF_ID, "") + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Authorization: Bearer " + p.getString(PREF_TV_KEY, "") + "\r\n"
                + "User-Agent: TvHome/" + BuildConfig.VERSION_NAME + "\r\n\r\n";
        synchronized (writeLock) {
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }
        String status = line(in);
        if (!status.startsWith("HTTP/1.1 101")) throw new IOException("relay answered " + status);
        String accept = null;
        for (String h; !(h = line(in)).isEmpty(); ) {
            int c = h.indexOf(':');
            if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("sec-websocket-accept")) accept = h.substring(c + 1).trim();
        }
        String want = Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
        if (!want.equals(accept)) throw new IOException("bad handshake");

        online = true;
        hello();
        final Socket mine = s;
        Thread pinger = new Thread(() -> {
            try {
                while (socket == mine) {
                    Thread.sleep(PING_MS);
                    if (socket == mine) send("ping");
                }
            } catch (Exception ignored) {
                // The read loop notices the socket is gone.
            }
        }, "remote-ping");
        pinger.setDaemon(true);
        pinger.start();

        ByteArrayOutputStream message = new ByteArrayOutputStream();
        while (true) {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) throw new EOFException();
            int op = b0 & 0x0f;
            long len = b1 & 0x7f;
            if (len == 126) len = (read(in) << 8) | read(in);
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | read(in);
            }
            if (len > MAX_FRAME || message.size() + len > MAX_FRAME) throw new IOException("frame too large");
            byte[] mask = (b1 & 0x80) != 0 ? new byte[]{(byte) read(in), (byte) read(in), (byte) read(in), (byte) read(in)} : null;
            byte[] data = new byte[(int) len];
            for (int off = 0; off < len; ) {
                int n = in.read(data, off, (int) len - off);
                if (n < 0) throw new EOFException();
                off += n;
            }
            if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
            if (op == 0x8) throw new EOFException("closed by relay");
            if (op == 0x9) {
                frame(0xA, data);
                continue;
            }
            if (op == 0xA) continue;
            if (op == 0x1 || op == 0x0) {
                message.write(data);
                if ((b0 & 0x80) != 0) {
                    String text = message.toString("UTF-8");
                    message.reset();
                    if (!"pong".equals(text)) received(text);
                }
            }
        }
    }

    private static void hello() {
        try {
            SharedPreferences p = keys(app);
            String name = null;
            try {
                name = Settings.Global.getString(app.getContentResolver(), "device_name");
            } catch (Exception ignored) {
                // Model name below.
            }
            if (name == null || name.isEmpty()) name = Build.MODEL;
            JSONObject m = new JSONObject()
                    .put("t", "hello")
                    .put("name", name)
                    .put("v", BuildConfig.VERSION_NAME)
                    .put("phones", new JSONArray().put(sha256(p.getString(PREF_PHONE_KEY, ""))));
            send(m.toString());
        } catch (Exception e) {
            Log.w(TAG, "hello failed: " + e);
        }
    }

    private static void send(String text) throws IOException {
        frame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    /** One masked frame, as a client must send. */
    private static void frame(int op, byte[] data) throws IOException {
        Socket s = socket;
        if (s == null) throw new IOException("not connected");
        ByteArrayOutputStream f = new ByteArrayOutputStream(data.length + 14);
        f.write(0x80 | op);
        if (data.length < 126) f.write(0x80 | data.length);
        else if (data.length < 65536) {
            f.write(0x80 | 126);
            f.write(data.length >> 8);
            f.write(data.length & 0xff);
        } else throw new IOException("message too large");
        byte[] mask = bytes(4);
        f.write(mask, 0, 4);
        for (int i = 0; i < data.length; i++) f.write(data[i] ^ mask[i & 3]);
        synchronized (writeLock) {
            OutputStream o = s.getOutputStream();
            o.write(f.toByteArray());
            o.flush();
        }
    }

    // ---- commands ----------------------------------------------------------------------------------

    private static void received(String text) {
        final JSONObject m;
        try {
            m = new JSONObject(text);
        } catch (Exception e) {
            return;
        }
        if (!"cmd".equals(m.optString("t"))) return;
        final String n = m.optString("n");
        final String cmd = m.optString("cmd");
        final String arg = m.optString("text", "");
        work.execute(() -> {
            boolean ok = true;
            String msg;
            try {
                msg = run(cmd, arg);
            } catch (SonyIp.Failed f) {
                ok = false;
                msg = f.getMessage();
            } catch (Exception e) {
                ok = false;
                msg = "The TV could not do that: " + e.getMessage();
            }
            try {
                send(new JSONObject().put("t", "ack").put("n", n).put("ok", ok).put("msg", msg)
                        .put("state", TvLock.state(app)).toString());
            } catch (Exception e) {
                Log.w(TAG, "ack failed: " + e);
            }
        });
    }

    /** Runs one command on the work thread; returns the message for the phone. */
    private static String run(String cmd, String arg) throws Exception {
        String psk = prefs(app).getString(PREF_PSK, "");
        switch (cmd) {
            case "ping":
                return "";
            case "banner":
                main.post(() -> showBanner(arg));
                return "Shown on the TV.";
            case "banner_clear":
                main.post(() -> {
                    Screen sc = screen;
                    if (sc != null) sc.clearBanner();
                });
                return "Cleared.";
            case "screen_off":
                SonyIp.pictureOff(psk, true);
                return "Screen off. The TV keeps running.";
            case "screen_on":
                SonyIp.pictureOff(psk, false);
                return "Screen on.";
            case "standby":
                if (psk.isEmpty()) {
                    // No IP control: the accessibility services can put the TV to sleep (Android 9+).
                    AccessibilityService a = InputKeyService.running != null ? InputKeyService.running : HomeKeyService.running;
                    if (a != null && Build.VERSION.SDK_INT >= 28) {
                        main.post(() -> a.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN));
                        return "Turning off.";
                    }
                }
                SonyIp.power(psk, false);
                return "Turning off.";
            case "wake":
                SonyIp.power(psk, true);
                try {
                    SonyIp.pictureOff(psk, false);
                } catch (SonyIp.Failed ignored) {
                    // Already on.
                }
                return "The TV is on.";
            case "set_pin":
                if (!AppLock.valid(arg)) throw new SonyIp.Failed("The PIN is 4 digits.");
                AppLock.setPin(prefs(app), arg);
                return "PIN saved. It is also the App lock PIN.";
            case "lock":
                if (!TvLock.lock(app)) throw new SonyIp.Failed("Set a PIN first.");
                return "The TV is locked.";
            case "unlock":
                TvLock.unlock(app);
                return "The TV is unlocked.";
            case "lock_timer": {
                int min;
                try {
                    min = Integer.parseInt(arg.trim());
                } catch (NumberFormatException e) {
                    min = 0;
                }
                if (min < 1 || min > TvLock.MAX_TIMER_MIN) throw new SonyIp.Failed("The timer is 1 to 720 minutes.");
                if (!TvLock.startTimer(app, min)) throw new SonyIp.Failed("Set a PIN first.");
                return "The TV locks in " + min + " min.";
            }
            case "lock_timer_cancel":
                TvLock.cancelTimer(app);
                return "Timer cancelled.";
            case "set_psk": {
                prefs(app).edit().putString(PREF_PSK, arg).apply();
                if (arg.isEmpty()) return "Key removed.";
                String state = SonyIp.powerStatus(arg);
                return "Saved. IP control works (" + (state.isEmpty() ? "ok" : state) + ").";
            }
            default:
                throw new Exception("unknown command " + cmd);
        }
    }

    /** Home shows it; when Home isn't running yet, start it with the text and it shows it on create. */
    private static void showBanner(String text) {
        Screen sc = screen;
        if (sc != null) {
            sc.showBanner(text);
            return;
        }
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(app.getPackageName())
                .putExtra(MainActivity.EXTRA_BANNER, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(home);
        } catch (Exception e) {
            Log.w(TAG, "could not start Home: " + e);
        }
    }

    // ---- keys --------------------------------------------------------------------------------------

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    /** The TV id and both keys, made on first use. */
    private static synchronized SharedPreferences keys(Context ctx) {
        SharedPreferences p = prefs(ctx);
        SharedPreferences.Editor e = null;
        if (p.getString(PREF_ID, "").length() < 12) (e = p.edit()).putString(PREF_ID, random(12));
        if (p.getString(PREF_TV_KEY, "").length() < 22) (e = e != null ? e : p.edit()).putString(PREF_TV_KEY, random(24));
        if (p.getString(PREF_PHONE_KEY, "").length() < 22) (e = e != null ? e : p.edit()).putString(PREF_PHONE_KEY, random(16));
        if (e != null) e.commit();
        return p;
    }

    private static final SecureRandom RNG = new SecureRandom();

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    /** n random bytes as base64url without padding. */
    private static String random(int n) {
        return Base64.encodeToString(bytes(n), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    static String sha256(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(64);
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static int read(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException();
        return b;
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int b; (b = in.read()) != '\n'; ) {
            if (b < 0) throw new EOFException();
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 4096) throw new IOException("header too long");
        }
        return sb.toString();
    }

    private static void closeQuietly() {
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
