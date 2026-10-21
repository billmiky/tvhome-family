package com.gathin.tvhome;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live stream links for IPTV players on this TV: http://127.0.0.1:8790/?url=&lt;channel page&gt; answers
 * with a redirect to the page's current .m3u8, the way iptv/extract_stream.py finds it (the site's
 * player.js: read window.playerConfig, POST apiUrl/endpoint, fileUrl = reverse(base64decode(...))).
 * An .m3u can list the proxy link and stay valid while the stream tokens change. Loopback only, so
 * nothing outside the TV can use it; the stream itself goes straight from the site to the player.
 */
final class StreamProxy {
    static final String TAG = "TvHomeStream";
    static final int PORT = 8790;

    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36";
    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final Pattern CONFIG = Pattern.compile("window\\.playerConfig\\s*=\\s*\\{(.*?)\\};", Pattern.DOTALL);
    private static final Pattern FIELD = Pattern.compile("(\\w+)\\s*:\\s*\"([^\"]*)\"");

    private static Thread thread;

    private StreamProxy() {
    }

    /** Starts the server once per process. */
    static synchronized void start() {
        if (thread != null) return;
        Thread t = new Thread(StreamProxy::serve, "stream-proxy");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    private static void serve() {
        // A player may open several channels' links at once; each lookup waits on the network.
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread w = new Thread(r, "stream-proxy-req");
            w.setDaemon(true);
            return w;
        });
        // 127.0.0.1 as the links say: getLoopbackAddress() is ::1 on Android, which IPv4 clients never reach.
        try (ServerSocket s = new ServerSocket(PORT, 16, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            while (true) {
                Socket client = s.accept();
                pool.execute(() -> {
                    try (Socket c = client) {
                        handle(c);
                    } catch (Exception | LinkageError e) {
                        Log.w(TAG, "request failed: " + e);
                    }
                });
            }
        } catch (IOException e) {
            Log.w(TAG, "no stream proxy on port " + PORT + ": " + e);
        } finally {
            pool.shutdown();
            synchronized (StreamProxy.class) {
                thread = null;
            }
        }
    }

    private static void handle(Socket client) throws IOException {
        client.setSoTimeout(5000);
        InputStream in = new BufferedInputStream(client.getInputStream());
        OutputStream out = client.getOutputStream();
        String page;
        try {
            page = WebHttp.parseHead(WebHttp.readHead(in)).query.get("url");
        } catch (WebHttp.BadRequest e) {
            send(out, e.status, e.getMessage(), null);
            return;
        }
        if (page == null || !(page.startsWith("http://") || page.startsWith("https://"))) {
            send(out, 400, "usage: /?url=<channel page url>", null);
            return;
        }
        try {
            send(out, 302, "", stream(page));
        } catch (Exception e) {
            Log.w(TAG, page + ": " + e);
            send(out, 502, "error: " + e.getMessage(), null);
        }
    }

    private static void send(OutputStream out, int status, String text, String location) throws IOException {
        byte[] body = (text + "\n").getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + (status == 302 ? "Found" : "Error") + "\r\n"
                + (location != null ? "Location: " + location + "\r\n" : "")
                + "Content-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /** The page's live stream link. */
    static String stream(String page) throws Exception {
        URL u = new URL(page);
        String origin = u.getProtocol() + "://" + u.getAuthority();
        Map<String, String> cfg = config(fetch(page, origin, false));
        if ("direct".equals(cfg.get("streamType"))) return required(cfg, "sourceUrl");
        try {
            String api = fetch(cfg.get("apiUrl") + "/" + cfg.get("endpoint"), origin, true);
            return fileUrl(new JSONObject(api).getString("fileUrl"));
        } catch (Exception e) {
            Log.w(TAG, "api failed (" + e + "), using fallbackUrl");
            return required(cfg, "fallbackUrl");
        }
    }

    /** The string fields of window.playerConfig in a channel page. */
    static Map<String, String> config(String html) {
        Matcher m = CONFIG.matcher(html);
        if (!m.find()) throw new IllegalArgumentException("no playerConfig on page");
        Map<String, String> out = new HashMap<>();
        Matcher f = FIELD.matcher(m.group(1));
        while (f.find()) out.put(f.group(1), f.group(2));
        return out;
    }

    /** The API's fileUrl: base64 of the link written backwards. */
    static String fileUrl(String encoded) {
        // The MIME decoder skips characters outside the alphabet, like Python's b64decode.
        String s = new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
        return new StringBuilder(s).reverse().toString();
    }

    private static String required(Map<String, String> cfg, String key) {
        String v = cfg.get(key);
        if (v == null || v.isEmpty()) throw new IllegalArgumentException("no " + key + " in playerConfig");
        return v;
    }

    private static String fetch(String url, String origin, boolean post) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            // Cloudflare answers bare clients with a 403 page; a browser UA + Origin/Referer gets through
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Origin", origin);
            c.setRequestProperty("Referer", origin + "/");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            if (post) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
                c.getOutputStream().close();
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException(url + " -> HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) {
                    buf.write(b, 0, n);
                    if (buf.size() > MAX_BODY) throw new IOException(url + " -> too large");
                }
                return new String(buf.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
