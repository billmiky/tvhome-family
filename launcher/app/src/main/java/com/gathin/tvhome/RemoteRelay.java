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
 * One daemon thread for the socket, reconnecting with backoff; a ping every 20 s keeps it open through
 * NATs and a VPN's userspace TCP stack (Proxy Switchboard drops a flow idle for 60 s), and the relay answers
 * it without waking up. No pong within 10 s, silence for 60 s, a new default network or the screen coming
 * on reconnects. Off when remote_relay is empty.
 */
final class RemoteRelay {
    static final String TAG = "TvHomeRemote";

    private static final String PREF_ID = "remote_id";
    private static final String PREF_TV_KEY = "remote_tv_key";
    private static final String PREF_PHONE_KEY = "remote_phone_key";
    static final String PREF_PSK = "sony_psk";
    private static final int PING_MS = 20_000;
    /** A ping not answered this soon: the path is dead one way (a half-open socket), drop it. */
    private static final int PONG_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    /** TLS handshake and the 101: a VPN accepts the TCP connection at once, so a dead path stalls here. */
    private static final int HANDSHAKE_MS = 15_000;
    /** A Wi-Fi blip fails a few attempts in a row (no route, no DNS): keep the wait short so it comes back soon. */
    private static final long MAX_BACKOFF_MS = 30_000;
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
    private static volatile long startedAt;
    /** When the relay last sent anything (elapsedRealtime): a pong, a command, a frame. */
    private static volatile long lastHeard;
    /** The default network the last connect went out on: a different one means the socket is stale. */
    private static volatile android.net.Network network;
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
        startedAt = android.os.SystemClock.elapsedRealtime();
        Thread t = new Thread(RemoteRelay::loop, "remote-relay");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        thread = t;
        t.start();
        // Back from standby the old socket is usually dead: reconnect at once instead of waiting it out
        // (the new hello says the TV is on). Going off, tell the relay first: the socket may not survive.
        try {
            IntentFilter screenOnOff = new IntentFilter(Intent.ACTION_SCREEN_ON);
            screenOnOff.addAction(Intent.ACTION_SCREEN_OFF);
            // The network coming back (after a Wi-Fi blip or a change of network): connect now rather than at
            // the end of the backoff.
            android.net.ConnectivityManager cm = app.getSystemService(android.net.ConnectivityManager.class);
            if (cm != null) {
                cm.registerDefaultNetworkCallback(new android.net.ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(android.net.Network n) {
                        // Android calls this once at registration too, while the first connect is under way.
                        android.net.Network was = network;
                        network = n;
                        if (android.os.SystemClock.elapsedRealtime() - startedAt <= 5000) return;
                        // A VPN coming up or going away is a new default network: the open socket went out on
                        // the old one and may be dead without knowing it, so reconnect even when online.
                        if (!online || (was != null && !was.equals(n))) kick();
                    }
                });
            }
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) event("off");
                    else kick();
                }
            }, screenOnOff);
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

    /**
     * Drops the current socket (or ends the wait between attempts) so the loop connects again now.
     * Off the caller's thread: closing a TLS socket writes to the network, which the main thread may not.
     */
    static void kick() {
        Thread k = new Thread(() -> {
            closeQuietly();
            Thread t = thread;
            if (t != null) t.interrupt();
        }, "remote-kick");
        k.setDaemon(true);
        k.start();
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
            raw.setSoTimeout(HANDSHAKE_MS);
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
        s.setSoTimeout(HANDSHAKE_MS);
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
        s.setSoTimeout(READ_TIMEOUT_MS);

        online = true;
        lastHeard = android.os.SystemClock.elapsedRealtime();
        hello();
        final Socket mine = s;
        Thread pinger = new Thread(() -> {
            try {
                while (socket == mine) {
                    Thread.sleep(PING_MS);
                    if (socket != mine) break;
                    long sent = android.os.SystemClock.elapsedRealtime();
                    send("ping");
                    Thread.sleep(PONG_MS);
                    // Writes still succeed into a path that drops what comes back (a VPN's TCP stack ACKs them
                    // itself): only the missing pong shows it. Closing makes the read loop end and reconnect.
                    if (socket == mine && lastHeard < sent) {
                        Log.i(TAG, "no pong in " + PONG_MS + " ms: reconnecting");
                        closeQuietly();
                    }
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
            lastHeard = android.os.SystemClock.elapsedRealtime();
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
                    .put("phones", new JSONArray().put(sha256(p.getString(PREF_PHONE_KEY, ""))))
                    .put("power", interactive() ? "on" : "off")
                    .put("state", TvLock.state(app));
            send(m.toString());
        } catch (Exception e) {
            Log.w(TAG, "hello failed: " + e);
        }
    }

    /** Power ("on"/"off", or null to leave it) and the lock state, for the phone pages and notifications. */
    static void event(String power) {
        if (app == null || base == null) return;
        work.execute(() -> {
            try {
                JSONObject m = new JSONObject().put("t", "event").put("state", TvLock.state(app));
                if (power != null) m.put("power", power);
                send(m.toString());
            } catch (Exception e) {
                // Offline: the next hello carries the state.
            }
        });
    }

    // ---- live view -----------------------------------------------------------------------------------

    private static final java.util.concurrent.atomic.AtomicBoolean frameInFlight = new java.util.concurrent.atomic.AtomicBoolean();

    /** Connected and the last frame is out: a slow link drops frames instead of queueing them. */
    static boolean canSendFrame() {
        return online && !frameInFlight.get();
    }

    /** One JPEG of the screen, as a binary message the relay hands to the watching phones. */
    static void sendFrame(byte[] jpeg) {
        if (!frameInFlight.compareAndSet(false, true)) return;
        try {
            frame(0x2, jpeg);
        } catch (IOException e) {
            Log.i(TAG, "frame not sent: " + e);
        } finally {
            frameInFlight.set(false);
        }
    }

    static void liveError(String why) {
        if (app == null) return;
        work.execute(() -> {
            try {
                send(new JSONObject().put("t", "live_error").put("msg", why).toString());
            } catch (Exception ignored) {
                // Offline: nobody is watching.
            }
        });
    }

    private static boolean interactive() {
        android.os.PowerManager pm = app.getSystemService(android.os.PowerManager.class);
        return pm == null || pm.isInteractive();
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
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write((int) ((long) data.length >>> (8 * i)) & 0xff);
        }
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
        if ("live".equals(m.optString("t"))) {
            // A phone opened or closed the live view (the relay counts the viewers).
            final boolean on = m.optBoolean("on");
            final int fps = m.optInt("fps", 2);
            main.post(() -> LiveService.setWanted(app, on, fps));
            return;
        }
        if (!"cmd".equals(m.optString("t"))) return;
        final String n = m.optString("n");
        final String cmd = m.optString("cmd");
        final String arg = m.optString("text", "");
        final JSONObject args = m.optJSONObject("args") != null ? m.optJSONObject("args") : new JSONObject();
        work.execute(() -> {
            boolean ok = true;
            String msg;
            JSONObject out = new JSONObject();
            try {
                msg = run(cmd, arg, args, out);
            } catch (SonyIp.Failed f) {
                ok = false;
                msg = f.getMessage();
            } catch (Exception e) {
                ok = false;
                msg = "The TV could not do that: " + e.getMessage();
            }
            try {
                JSONObject ack = new JSONObject().put("t", "ack").put("n", n).put("ok", ok).put("msg", msg)
                        .put("state", TvLock.state(app));
                if (out.has("data")) ack.put("data", out.get("data"));
                String json = ack.toString();
                if (json.length() > MAX_FRAME - 64) {
                    // Too large for one frame: say so instead of dropping the answer.
                    ack.remove("data");
                    ack.put("ok", false).put("msg", "The answer is too large.");
                    json = ack.toString();
                }
                send(json);
            } catch (Exception e) {
                Log.w(TAG, "ack failed: " + e);
            }
        });
    }

    /** Every command the TV knows, for the phone page to check before it offers a feature. */
    static final String[] COMMANDS = {
            "ping", "caps", "info", "banner", "banner_clear", "lock", "unlock", "lock_timer", "lock_timer_cancel",
            "set_pin", "screen_off", "screen_on", "standby", "wake", "set_psk",
            "sony", "button", "buttons", "intent", "launch", "apps", "global", "local", "type", "adb", "longpress",
    };

    /**
     * Runs one command on the work thread; returns the message for the phone and may put "data" in out.
     * Besides the fixed features there are building blocks, so a new feature on the phone page needs no
     * new launcher: sony (any IP control method), button (any remote button), intent and launch (open an
     * app or a link), apps, global (Back, Home…), local (the home-network phone page's API) and info.
     */
    private static String run(String cmd, String arg, JSONObject args, JSONObject out) throws Exception {
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
            case "standby": {
                SonyIp.Failed sonyFailed = null;
                if (!psk.isEmpty()) {
                    try {
                        SonyIp.power(psk, false);
                        return "Turning off.";
                    } catch (SonyIp.Failed f) {
                        sonyFailed = f;
                    }
                }
                // No (working) IP control: the accessibility services can put the TV to sleep (Android 9+).
                AccessibilityService a = InputKeyService.running != null ? InputKeyService.running : HomeKeyService.running;
                if (a != null && Build.VERSION.SDK_INT >= 28) {
                    main.post(() -> a.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN));
                    return "Turning off.";
                }
                throw sonyFailed != null ? sonyFailed : new SonyIp.Failed("Set the Sony IP control key in Settings on the phone page first.");
            }
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
                if (arg.isEmpty()) {
                    prefs(app).edit().remove(PREF_PSK).apply();
                    return "Key removed.";
                }
                // Kept only once the TV accepts it, so "IP control set up" means it works. getPowerStatus
                // answers without a key, so the check is a method that needs one.
                try {
                    SonyIp.call(arg, "system", "getSystemInformation", new JSONArray(), "1.0");
                } catch (SonyIp.Failed f) {
                    throw f.getMessage().contains("refused") ? new SonyIp.Failed("Incorrect key.") : f;
                }
                prefs(app).edit().putString(PREF_PSK, arg).apply();
                // Remote start (Wake-on-LAN): keeps the network up in standby, so the TV can be woken.
                String wol;
                try {
                    SonyIp.call(arg, "system", "setWolMode", new JSONArray().put(new JSONObject().put("enabled", true)), "1.0");
                    wol = " Remote start is on.";
                } catch (SonyIp.Failed f) {
                    wol = " (Remote start: " + f.getMessage() + ")";
                }
                return "Saved. IP control works." + wol;
            }
            case "caps":
                out.put("data", new JSONArray(java.util.Arrays.asList(COMMANDS)));
                return "";
            case "info":
                out.put("data", info());
                return "";
            case "sony": {
                JSONArray params = args.optJSONArray("params");
                out.put("data", SonyIp.call(psk, args.optString("service"), args.optString("method"),
                        params != null ? params : new JSONArray(), args.optString("version", "1.0")));
                return "";
            }
            case "button": {
                JSONArray names = args.optJSONArray("names");
                if (names == null) names = new JSONArray().put(args.optString("name", arg));
                if (names.length() > 20) throw new SonyIp.Failed("At most 20 buttons at once.");
                for (int i = 0; i < names.length(); i++) {
                    if (i > 0) Thread.sleep(Math.max(50, Math.min(2000, args.optInt("gapMs", 250))));
                    // "ActionMenu|Options": the first name this TV has.
                    String[] choices = names.optString(i).split("[|]");
                    SonyIp.Failed last = null;
                    for (String c : choices) {
                        try {
                            SonyIp.button(psk, c.trim());
                            last = null;
                            break;
                        } catch (SonyIp.Failed f) {
                            last = f;
                            if (!f.getMessage().startsWith("The TV has no button")) break;
                        }
                    }
                    if (last != null) throw last;
                }
                return "";
            }
            case "longpress":
                return longPress();
            case "adb":
                out.put("data", adb(args.optString("action", arg.isEmpty() ? "status" : arg)));
                return "";
            case "type":
                return type(psk, args.optString("text", arg), args.optBoolean("enter"));
            case "buttons":
                out.put("data", new JSONArray(new java.util.TreeSet<>(SonyIp.buttonCodes(psk).keySet())));
                return "";
            case "intent":
            case "launch": {
                if (TvLock.locked(app)) throw new SonyIp.Failed("The TV is locked.");
                final Intent i = "launch".equals(cmd) ? launchIntent(args.optString("package", arg)) : intent(args);
                if (i == null) throw new SonyIp.Failed("Nothing on the TV opens that.");
                final Exception[] failed = new Exception[1];
                final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                main.post(() -> {
                    try {
                        app.startActivity(i);
                    } catch (Exception e) {
                        failed[0] = e;
                    }
                    done.countDown();
                });
                done.await(3, java.util.concurrent.TimeUnit.SECONDS);
                if (failed[0] != null) throw new SonyIp.Failed("Could not open it: " + failed[0].getMessage());
                return "Opened.";
            }
            case "apps":
                out.put("data", apps());
                return "";
            case "global":
                return global(args.optString("action", arg));
            case "local":
                out.put("data", local(args.optString("method", "GET"), args.optString("path"), args.opt("body")));
                return "";
            default:
                throw new SonyIp.Failed("This TV does not know \"" + cmd + "\" yet. Update the launcher.");
        }
    }

    // ---- building blocks ---------------------------------------------------------------------------

    private static JSONObject info() throws Exception {
        android.os.PowerManager pm = app.getSystemService(android.os.PowerManager.class);
        // "Set up" means the saved key works: a method that needs it (getPowerStatus answers anyone).
        String psk = prefs(app).getString(PREF_PSK, "");
        boolean ipWorks = false;
        if (!psk.isEmpty()) {
            try {
                SonyIp.call(psk, "system", "getSystemInformation", new JSONArray(), "1.0");
                ipWorks = true;
            } catch (SonyIp.Failed ignored) {
                // Wrong key, or IP control off: the page asks for it again.
            }
        }
        return new JSONObject()
                .put("model", Build.MODEL)
                .put("android", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("launcher", BuildConfig.VERSION_NAME)
                .put("interactive", pm != null && pm.isInteractive())
                .put("ipKeySaved", !psk.isEmpty())
                .put("ipControl", ipWorks)
                .put("overlay", Settings.canDrawOverlays(app))
                .put("accessibility", InputKeyService.running != null || HomeKeyService.running != null)
                .put("caps", new JSONArray(java.util.Arrays.asList(COMMANDS)));
    }

    /** The TV's apps (the ones with a TV entry), label and package, by label. */
    private static JSONArray apps() throws Exception {
        android.content.pm.PackageManager pm = app.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        java.util.TreeMap<String, String> byLabel = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(q, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(app.getPackageName())) continue;
            byLabel.put(String.valueOf(r.loadLabel(pm)) + "\u0000" + pkg, pkg);
        }
        JSONArray a = new JSONArray();
        for (java.util.Map.Entry<String, String> e : byLabel.entrySet()) {
            a.put(new JSONObject().put("label", e.getKey().substring(0, e.getKey().indexOf('\u0000'))).put("package", e.getValue()));
        }
        return a;
    }

    private static Intent launchIntent(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        android.content.pm.PackageManager pm = app.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** {action, data, type, package, component "pkg/.Activity", categories [], extras {k: string|number|bool}}. */
    private static Intent intent(JSONObject a) throws Exception {
        Intent i = new Intent(a.optString("action", Intent.ACTION_VIEW));
        String data = a.optString("data", ""), type = a.optString("type", "");
        if (!data.isEmpty() && !type.isEmpty()) i.setDataAndType(android.net.Uri.parse(data), type);
        else if (!data.isEmpty()) i.setData(android.net.Uri.parse(data));
        else if (!type.isEmpty()) i.setType(type);
        if (!a.optString("package").isEmpty()) i.setPackage(a.optString("package"));
        if (!a.optString("component").isEmpty()) {
            android.content.ComponentName c = android.content.ComponentName.unflattenFromString(a.optString("component"));
            if (c == null) throw new SonyIp.Failed("component is package/.Activity");
            i.setComponent(c);
        }
        JSONArray cats = a.optJSONArray("categories");
        if (cats != null) for (int k = 0; k < cats.length(); k++) i.addCategory(cats.optString(k));
        JSONObject extras = a.optJSONObject("extras");
        if (extras != null) {
            for (java.util.Iterator<String> it = extras.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = extras.get(k);
                if (v instanceof Boolean) i.putExtra(k, (Boolean) v);
                else if (v instanceof Integer) i.putExtra(k, (Integer) v);
                else if (v instanceof Long) i.putExtra(k, (Long) v);
                else if (v instanceof Number) i.putExtra(k, ((Number) v).doubleValue());
                else i.putExtra(k, String.valueOf(v));
            }
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return app.getPackageManager().resolveActivity(i, 0) != null ? i : null;
    }

    /**
     * Text from the phone into the TV's focused text field: replaces what is there (the phone sends the
     * whole text as it is typed). Through the accessibility service when it can see the field, else
     * Sony's appControl.setTextForm (the on-screen keyboard must be open). enter: submit afterwards.
     */
    private static String type(String psk, String text, boolean enter) throws Exception {
        InputKeyService a = InputKeyService.running;
        android.view.accessibility.AccessibilityNodeInfo field = null;
        String where = "";
        if (a != null) {
            try {
                // Every window, not only the active one: with the keyboard up the field's window may not be.
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = a.getWindows();
                for (android.view.accessibility.AccessibilityWindowInfo w : windows) {
                    android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                    if (root == null) continue;
                    if (w.isActive() && root.getPackageName() != null) where = String.valueOf(root.getPackageName());
                    android.view.accessibility.AccessibilityNodeInfo f = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
                    if (f == null || !f.isEditable()) f = focusedEditable(root, 0);
                    if (f != null && f.isEditable()) {
                        field = f;
                        break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "no text field: " + e);
            }
        }
        if (field != null) {
            android.os.Bundle b = new android.os.Bundle();
            b.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean ok = field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, b);
            if (ok && enter && Build.VERSION.SDK_INT >= 30) {
                field.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            }
            if (ok) return enter ? "Sent." : "";
        }
        try {
            SonyIp.call(psk, "appControl", "setTextForm", new JSONArray().put(new JSONObject().put("text", text)), "1.1");
        } catch (SonyIp.Failed f) {
            String hint = a == null ? " The launcher's accessibility service is off."
                    : " No text field found" + (where.isEmpty() ? "" : " in " + where)
                    + ": this app may draw its own keyboard, which can't be typed into from outside.";
            if (f.getMessage().startsWith("Set the Sony")) throw new SonyIp.Failed("Select a text field on the TV first." + hint);
            try {
                SonyIp.call(psk, "appControl", "setTextForm", new JSONArray().put(text), "1.0");
            } catch (SonyIp.Failed f2) {
                throw new SonyIp.Failed("Select a text field on the TV first." + hint);
            }
        }
        if (enter) SonyIp.button(psk, "Confirm");
        return enter ? "Sent." : "";
    }

    /**
     * Holding OK on the phone: IRCC has no key hold, so the accessibility service long-clicks the node that has
     * the focus (Home's menus and tiles, or another app's), the same as holding OK on the remote.
     */
    private static String longPress() throws Exception {
        InputKeyService a = InputKeyService.running;
        if (a == null) throw new SonyIp.Failed("The launcher's accessibility service is off.");
        android.view.accessibility.AccessibilityNodeInfo target = null;
        for (android.view.accessibility.AccessibilityWindowInfo w : a.getWindows()) {
            android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            android.view.accessibility.AccessibilityNodeInfo f = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
            if (f == null) f = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (f != null && w.isActive()) {
                target = f;
                break;
            }
            if (f != null && target == null) target = f;
        }
        // The focused view itself may not take a long click; its nearest ancestor that does.
        for (int i = 0; target != null && i < 6 && !target.isLongClickable(); i++) target = target.getParent();
        if (target == null) throw new SonyIp.Failed("Nothing on the TV is focused to hold.");
        if (!target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            throw new SonyIp.Failed("That item on the TV has nothing on hold.");
        }
        return "";
    }

    /** A focused editable node under n, depth-first, a bounded walk. */
    private static android.view.accessibility.AccessibilityNodeInfo focusedEditable(android.view.accessibility.AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 40) return null;
        if (n.isEditable() && (n.isFocused() || n.isAccessibilityFocused())) return n;
        for (int i = 0; i < Math.min(n.getChildCount(), 200); i++) {
            android.view.accessibility.AccessibilityNodeInfo r = focusedEditable(n.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /**
     * ADB over the network: status, on or off (Wireless debugging, which the launcher may switch with
     * WRITE_SECURE_SETTINGS). Answers {wifi, adb, ip, port, tcp5555}; the Wireless debugging port is found
     * the way `adb mdns services` finds it.
     */
    private static JSONObject adb(String action) throws Exception {
        android.content.ContentResolver cr = app.getContentResolver();
        if ("on".equals(action) || "off".equals(action)) {
            boolean on = "on".equals(action);
            try {
                if (on && Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 0) Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
                Settings.Global.putInt(cr, "adb_wifi_enabled", on ? 1 : 0);
            } catch (SecurityException e) {
                throw new SonyIp.Failed("The launcher may not change this (WRITE_SECURE_SETTINGS, granted by the install script).");
            }
            if (on) Thread.sleep(1500);   // adbd needs a moment before it advertises the new port
        } else if (!"status".equals(action)) {
            throw new SonyIp.Failed("action is status, on or off");
        }
        boolean wifi = Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1;
        String ip = null;
        for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                if (a instanceof java.net.Inet4Address && a.isSiteLocalAddress()) ip = a.getHostAddress();
            }
        }
        boolean tcp = false;
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress("127.0.0.1", 5555), 400);
            tcp = true;
        } catch (IOException ignored) {
            // Classic ADB over TCP is off.
        }
        int port = wifi && ip != null ? adbPort(ip) : 0;
        JSONObject o = new JSONObject()
                .put("wifi", wifi)
                .put("adb", Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1)
                .put("tcp5555", tcp);
        if (ip != null) o.put("ip", ip);
        if (port > 0) o.put("port", port);
        return o;
    }

    /** The Wireless debugging port this TV advertises (_adb-tls-connect._tcp), 0 if none within 4 s. */
    private static int adbPort(String ip) {
        final android.net.nsd.NsdManager nsd = app.getSystemService(android.net.nsd.NsdManager.class);
        if (nsd == null) return 0;
        final int[] port = {0};
        final java.util.concurrent.CountDownLatch found = new java.util.concurrent.CountDownLatch(1);
        android.net.nsd.NsdManager.DiscoveryListener l = new android.net.nsd.NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(android.net.nsd.NsdServiceInfo info) {
                nsd.resolveService(info, new android.net.nsd.NsdManager.ResolveListener() {
                    @Override
                    public void onServiceResolved(android.net.nsd.NsdServiceInfo r) {
                        if (r.getHost() != null && ip.equals(r.getHost().getHostAddress())) {
                            port[0] = r.getPort();
                            found.countDown();
                        }
                    }

                    @Override
                    public void onResolveFailed(android.net.nsd.NsdServiceInfo r, int error) {
                    }
                });
            }

            @Override
            public void onServiceLost(android.net.nsd.NsdServiceInfo info) {
            }

            @Override
            public void onDiscoveryStarted(String type) {
            }

            @Override
            public void onDiscoveryStopped(String type) {
            }

            @Override
            public void onStartDiscoveryFailed(String type, int error) {
                found.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String type, int error) {
            }
        };
        try {
            nsd.discoverServices("_adb-tls-connect._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, l);
            found.await(4, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // No port.
        } finally {
            try {
                nsd.stopServiceDiscovery(l);
            } catch (Exception ignored) {
                // Never started.
            }
        }
        return port[0];
    }

    /** back, home, recents, notifications, quick_settings, lock_screen through the accessibility service. */
    private static String global(String action) throws Exception {
        final AccessibilityService a = InputKeyService.running != null ? InputKeyService.running : HomeKeyService.running;
        if (a == null) throw new SonyIp.Failed("The launcher's accessibility service is off.");
        final int id;
        switch (action) {
            case "back": id = AccessibilityService.GLOBAL_ACTION_BACK; break;
            case "home": id = AccessibilityService.GLOBAL_ACTION_HOME; break;
            case "recents": id = AccessibilityService.GLOBAL_ACTION_RECENTS; break;
            case "notifications": id = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": id = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock_screen": id = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN; break;
            default: throw new SonyIp.Failed("Unknown action " + action + ".");
        }
        main.post(() -> a.performGlobalAction(id));
        return "";
    }

    /** The home-network phone page's API (WebControl) from inside the TV: GET /api/state, POST /api/settings… */
    private static Object local(String method, String path, Object body) throws Exception {
        int port = WebControl.port();
        if (port <= 0) throw new SonyIp.Failed("The phone page (web_control) is off on this TV.");
        if (path == null || !path.startsWith("/api/")) throw new SonyIp.Failed("path starts with /api/");
        if (!"GET".equals(method) && !"POST".equals(method)) throw new SonyIp.Failed("method is GET or POST");
        String full = path + (path.contains("?") ? "&" : "?") + "k=" + WebControl.code(app);
        java.util.Map<String, String> h = new java.util.HashMap<>();
        h.put("X-Requested-With", "tvhome");
        byte[] b = null;
        if ("POST".equals(method)) {
            h.put("Content-Type", "application/json");
            b = (body == null ? "" : body.toString()).getBytes(StandardCharsets.UTF_8);
        }
        LocalHttp.Response r = LocalHttp.request("127.0.0.1", port, method, full, h, b, 8000, MAX_FRAME);
        if (r.status >= 400) throw new SonyIp.Failed("The phone page answered " + r.status + ": " + r.body);
        try {
            return r.body.trim().startsWith("[") ? new JSONArray(r.body) : new JSONObject(r.body);
        } catch (Exception e) {
            return r.body;
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
