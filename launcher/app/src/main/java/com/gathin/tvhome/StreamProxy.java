package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
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
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Live stream links for IPTV players on this TV: http://127.0.0.1:8790/?url=&lt;channel page&gt; answers
 * with the page's current .m3u8, its links made absolute (players built on ExoPlayer do not follow a
 * redirect from this http address to an https one, so a 302 is only the fallback for a non-playlist), the way iptv/extract_stream.py finds it (the site's
 * player.js: read window.playerConfig, POST apiUrl/endpoint, fileUrl = reverse(base64decode(...))).
 * An .m3u can list the proxy link and stay valid while the stream tokens change. Loopback only, so
 * nothing outside the TV can use it; the stream itself goes straight from the site to the player.
 * VTVgo pages (vtvgo.vn/channel/&lt;id or name&gt;) ask the site's own playback API instead, vtv.vn live
 * pages (vtv.vn/truyen-hinh-truc-tuyen/&lt;channel&gt;.htm, up to 1080p) read the SohaTV player they embed, and
 * /epg.xml is VTVgo's programme guide for today and tomorrow as XMLTV, channel ids "vtvgo-&lt;id&gt;", plus the
 * channels listed in epg.json (pushed into the launcher's external files folder, see iptv/epg.json): public
 * XMLTV files fetched in the background every few hours, cut down to those channels and renamed to the
 * playlist's tvg-id.
 */
final class StreamProxy {
    static final String TAG = "TvHomeStream";
    static final int PORT = 8790;

    private static final String UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final Pattern CONFIG = Pattern.compile("window\\.playerConfig\\s*=\\s*\\{(.*?)\\};", Pattern.DOTALL);
    private static final Pattern FIELD = Pattern.compile("(\\w+)\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern VTVGO = Pattern.compile("^https?://(?:www\\.)?vtvgo\\.vn/channel/([A-Za-z0-9_-]+)/?(?:[?#].*)?$");
    private static final Pattern VTVVN = Pattern.compile("^https?://(?:www\\.)?vtv\\.vn/truyen-hinh-truc-tuyen/[a-z0-9-]+\\.htm(?:[?#].*)?$");
    private static final Pattern SOHA_EMBED = Pattern.compile("https://ovp\\.sohatv\\.vn/embed\\?v=[A-Za-z0-9_-]+");
    private static final Pattern SOHA_SRC = Pattern.compile("\"src\"\\s*:\\s*\"(https:[^\"]+?\\.m3u8[^\"]*)\"");
    private static final String VTVGO_API = "https://api.vtvdigital.org/live-channel/v21.0/playback/source";
    private static final Pattern BANDWIDTH = Pattern.compile("[:,]BANDWIDTH=(\\d+)");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]*)\"");
    private static final String VTVGO_CATALOG = "https://api.vtvdigital.org/display/v21.0/catalogs/with-channels";
    private static final String VTVGO_EPG = "https://api.vtvdigital.org/display/v21.0/epg";
    private static final long EPG_TTL_MS = 3_600_000L;
    private static final TimeZone VIETNAM = TimeZone.getTimeZone("Asia/Ho_Chi_Minh");

    private static byte[] epg;
    /** The epg.json channels as XMLTV elements (no &lt;tv&gt; wrapper), empty until the first build. */
    private static volatile byte[] extra = new byte[0];
    private static File dir;
    private static final Pattern ATTR_ID = Pattern.compile("\\bid=\"([^\"]*)\"");
    private static final Pattern ATTR_CHANNEL = Pattern.compile("\\bchannel=\"([^\"]*)\"");
    private static final Pattern ATTR_START = Pattern.compile("\\bstart=\"([^\"]*)\"");
    private static final Pattern ATTR_STOP = Pattern.compile("\\bstop=\"([^\"]*)\"");
    private static final long GUIDE_AHEAD_MS = 48 * 3_600_000L;
    private static long epgAt;

    private static Thread thread;

    private StreamProxy() {
    }

    /** Starts the server once per process. */
    static synchronized void start(Context ctx) {
        if (thread != null) return;
        dir = ctx.getApplicationContext().getExternalFilesDir(null);
        Thread g = new Thread(StreamProxy::guides, "stream-proxy-epg");
        g.setDaemon(true);
        g.setPriority(Thread.MIN_PRIORITY);
        g.start();
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
        WebHttp.Request req;
        try {
            req = WebHttp.parseHead(WebHttp.readHead(in));
        } catch (WebHttp.BadRequest e) {
            send(out, e.status, e.getMessage(), null);
            return;
        }
        if (req.path.equals("/epg.xml")) {
            try {
                send(out, 200, "application/xml; charset=utf-8", epg(), null);
            } catch (Exception e) {
                Log.w(TAG, "epg: " + e);
                send(out, 502, "error: " + e.getMessage(), null);
            }
            return;
        }
        String page = req.query.get("url");
        if (page == null || !(page.startsWith("http://") || page.startsWith("https://"))) {
            send(out, 400, "usage: /?url=<channel page or .m3u8 url>[&alt=<another one, tried when url fails>]", null);
            return;
        }
        String alt = req.query.get("alt");
        String firstSource = null;
        Exception failure = null;
        for (String p : alt != null && (alt.startsWith("http://") || alt.startsWith("https://"))
                ? Arrays.asList(page, alt) : Collections.singletonList(page)) {
            List<String> sources;
            try {
                sources = streams(p);
            } catch (Exception e) {
                Log.w(TAG, p + ": " + e);
                failure = e;
                continue;
            }
            if (firstSource == null) firstSource = sources.get(0);
            for (String src : sources) {
                try {
                    String[] got = fetchPlaylist(src);   // {final url, body}
                    if (got[1].startsWith("#EXTM3U")) {
                        String playlist = bestVariant(absolute(got[1], got[0]));
                        send(out, 200, "application/vnd.apple.mpegurl", playlist.getBytes(StandardCharsets.UTF_8), null);
                        return;
                    }
                } catch (IOException e) {
                    Log.w(TAG, src + ": " + e);   // try the next source
                }
            }
        }
        if (firstSource != null) send(out, 302, "", firstSource);
        else send(out, 502, "error: " + (failure != null ? failure.getMessage() : "no source"), null);
    }

    private static void send(OutputStream out, int status, String text, String location) throws IOException {
        send(out, status, "text/plain; charset=utf-8", (text + "\n").getBytes(StandardCharsets.UTF_8), location);
    }

    private static void send(OutputStream out, int status, String type, byte[] body, String location) throws IOException {
        String head = "HTTP/1.1 " + status + " " + (status == 302 ? "Found" : status == 200 ? "OK" : "Error") + "\r\n"
                + (location != null ? "Location: " + location + "\r\n" : "")
                + "Content-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /** The page's live stream links, best first (VTVgo lists several mirrors). */
    static List<String> streams(String page) throws Exception {
        String channel = vtvgoChannel(page);
        if (channel != null) return vtvgoSources(fetch(VTVGO_API, "https://vtvgo.vn", vtvgoBody(channel), "application/json"));
        if (new URL(page).getPath().endsWith(".m3u8")) return Collections.singletonList(page);   // a stream already
        if (VTVVN.matcher(page).matches()) {
            String embed = sohaEmbed(fetch(page, "https://vtv.vn", null, null));
            return Collections.singletonList(sohaSource(fetch(embed, "https://vtv.vn", null, null)));
        }
        return Collections.singletonList(stream(page));
    }

    /** The page's live stream link. */
    static String stream(String page) throws Exception {
        URL u = new URL(page);
        String origin = u.getProtocol() + "://" + u.getAuthority();
        Map<String, String> cfg = config(fetch(page, origin, null, null));
        if ("direct".equals(cfg.get("streamType"))) return required(cfg, "sourceUrl");
        try {
            String api = fetch(cfg.get("apiUrl") + "/" + cfg.get("endpoint"), origin, "", "application/x-www-form-urlencoded");
            return fileUrl(new JSONObject(api).getString("fileUrl"));
        } catch (Exception e) {
            Log.w(TAG, "api failed (" + e + "), using fallbackUrl");
            return required(cfg, "fallbackUrl");
        }
    }

    /** The SohaTV player a vtv.vn live page embeds. */
    static String sohaEmbed(String html) {
        Matcher m = SOHA_EMBED.matcher(html);
        if (!m.find()) throw new IllegalArgumentException("vtv.vn: no live player on page");
        return m.group();
    }

    /** The .m3u8 in a SohaTV embed page. */
    static String sohaSource(String html) {
        Matcher m = SOHA_SRC.matcher(html);
        if (!m.find()) throw new IllegalArgumentException("vtv.vn: no stream in player");
        return m.group(1).replace("\\/", "/");
    }

    /** The channel id or name of a vtvgo.vn/channel/... page, or null for any other page. */
    static String vtvgoChannel(String page) {
        Matcher m = VTVGO.matcher(page);
        return m.matches() ? m.group(1) : null;
    }

    static String vtvgoBody(String channel) throws Exception {
        return new JSONObject().put("channelId", channel).put("platform", "web").toString();
    }

    /** The first free (non-VIP, no DRM) source in a playback/source answer. */
    static String vtvgoSource(String json) throws Exception {
        return vtvgoSources(json).get(0);
    }

    /**
     * Every free (non-VIP, no DRM) source in a playback/source answer, Akamai mirrors first (they carry
     * 1080p, the AWS one stops at 720p; their hdnts token only gates the master, which hands out a 24 h
     * hdntl link), the rest in the API's order.
     */
    static List<String> vtvgoSources(String json) throws Exception {
        JSONObject r = new JSONObject(json);
        List<String> out = new ArrayList<>();
        List<String> akamai = new ArrayList<>();
        if (!r.has("data")) throw new IllegalArgumentException("vtvgo: " + r.optJSONObject("error"));
        JSONArray modes = r.getJSONObject("data").getJSONArray("sourceModes");
        for (int i = 0; i < modes.length(); i++) {
            JSONObject mode = modes.getJSONObject(i);
            if (mode.optInt("isVip") != 0) continue;
            JSONArray multi = mode.optJSONArray("multiSource");
            for (int j = 0; multi != null && j < multi.length(); j++) {
                JSONObject src = multi.getJSONObject(j);
                JSONObject drm = src.optJSONObject("drmInfo");
                if (drm != null && !"none".equals(drm.optString("drmType", "none"))) continue;
                JSONArray urls = src.optJSONArray("sources");
                for (int k = 0; urls != null && k < urls.length(); k++) {
                    String url = urls.getJSONObject(k).optString("url");
                    if (!url.isEmpty()) (url.contains(".akamaized.net/") ? akamai : out).add(url);
                }
            }
        }
        out.addAll(0, akamai);
        if (out.isEmpty()) throw new IllegalArgumentException("vtvgo: no free source");
        return out;
    }

    /** VTVgo's guide with the epg.json channels inside the same &lt;tv&gt;. */
    private static byte[] epg() {
        byte[] base;
        try {
            base = vtvgoEpg();
        } catch (Exception e) {
            Log.w(TAG, "vtvgo epg: " + e);
            base = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"tvhome\">\n</tv>\n".getBytes(StandardCharsets.UTF_8);
        }
        byte[] more = extra;
        if (more.length == 0) return base;
        String s = new String(base, StandardCharsets.UTF_8);
        int end = s.lastIndexOf("</tv>");
        return (s.substring(0, end) + new String(more, StandardCharsets.UTF_8) + s.substring(end)).getBytes(StandardCharsets.UTF_8);
    }

    /** Keeps the epg.json part fresh: loads the last build, then rebuilds when stale or when epg.json changed. */
    private static void guides() {
        File cache = new File(dir, "epg-extra.xml");
        File config = new File(dir, "epg.json");
        try {
            if (cache.isFile()) extra = readAll(cache);
        } catch (IOException e) {
            Log.w(TAG, "epg cache: " + e);
        }
        while (true) {
            try {
                if (config.isFile()) {
                    JSONObject cfg = new JSONObject(new String(readAll(config), StandardCharsets.UTF_8));
                    long every = cfg.optLong("refresh_hours", 12) * 3_600_000L;
                    if (!cache.isFile() || System.currentTimeMillis() - cache.lastModified() >= every
                            || config.lastModified() > cache.lastModified()) {
                        byte[] built = buildGuides(cfg).getBytes(StandardCharsets.UTF_8);
                        File tmp = new File(dir, "epg-extra.xml.tmp");
                        try (OutputStream o = new FileOutputStream(tmp)) {
                            o.write(built);
                        }
                        if (!tmp.renameTo(cache)) throw new IOException("cannot replace " + cache);
                        extra = built;
                        Log.i(TAG, "epg.json guide built, " + built.length + " bytes");
                    }
                }
            } catch (Exception | LinkageError e) {
                Log.w(TAG, "epg.json guide: " + e);
            }
            try {
                Thread.sleep(5 * 60_000L);   // cheap: only looks at two file times unless stale
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Fetches every source in cfg and keeps the channels cfg maps, under their playlist ids. */
    static String buildGuides(JSONObject cfg) throws Exception {
        JSONObject sources = cfg.getJSONObject("sources");
        JSONObject channels = cfg.getJSONObject("channels");
        Map<String, Map<String, List<String>>> want = new HashMap<>();   // source -> source channel id -> playlist ids
        for (Iterator<String> it = channels.keys(); it.hasNext(); ) {
            String id = it.next();
            JSONArray m = channels.getJSONArray(id);
            want.computeIfAbsent(m.getString(0), k -> new HashMap<>()).computeIfAbsent(m.getString(1), k -> new ArrayList<>()).add(id);
        }
        StringBuilder out = new StringBuilder();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Map<String, List<String>>> e : want.entrySet()) {
            String url = sources.optString(e.getKey());
            if (url.isEmpty()) continue;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            try {
                c.setConnectTimeout(TIMEOUT_MS);
                c.setReadTimeout(60_000);
                c.setRequestProperty("User-Agent", UA);
                if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode());
                InputStream in = c.getInputStream();
                if (url.endsWith(".gz")) in = new GZIPInputStream(in, 65536);
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 65536)) {
                    filterXmltv(r, e.getValue(), now, out);
                }
            } catch (IOException ex) {
                Log.w(TAG, "epg source " + e.getKey() + ": " + ex);   // the others still count
            } finally {
                c.disconnect();
            }
        }
        return out.toString();
    }

    /**
     * Copies the &lt;channel&gt; and &lt;programme&gt; elements of the wanted channels (one per line start, as
     * pretty-printed XMLTV has them) under each playlist id; programmes already over or more than two days
     * away are left out.
     */
    static void filterXmltv(BufferedReader in, Map<String, List<String>> want, long now, StringBuilder out) throws IOException {
        StringBuilder el = null;
        List<String> ids = null;
        String close = null, attr = null, attrName = null;
        for (String line; (line = in.readLine()) != null; ) {
            String t = line.trim();
            if (el == null) {
                boolean channel = t.startsWith("<channel "), programme = t.startsWith("<programme ");
                if (!channel && !programme) continue;
                Matcher m = (channel ? ATTR_ID : ATTR_CHANNEL).matcher(t);
                if (!m.find()) continue;
                ids = want.get(xmlUnescape(m.group(1)));
                if (ids == null || (programme && !inGuideWindow(t, now))) continue;
                close = channel ? "</channel>" : "</programme>";
                attr = m.group();
                attrName = channel ? "id" : "channel";
                el = new StringBuilder(t).append('\n');
                if (!t.endsWith("/>") && !t.contains(close)) continue;
            } else {
                el.append(t).append('\n');
                if (!t.contains(close)) continue;
            }
            String s = el.toString();
            for (String id : ids) out.append(s.replace(attr, attrName + "=\"" + xml(id) + "\""));
            el = null;
        }
    }

    /** Programme start tag still on air or due within two days; unreadable times count as yes. */
    static boolean inGuideWindow(String tag, long now) {
        Matcher a = ATTR_START.matcher(tag), b = ATTR_STOP.matcher(tag);
        try {
            if (b.find() && xmltvMillis(b.group(1)) < now) return false;
            return !a.find() || xmltvMillis(a.group(1)) < now + GUIDE_AHEAD_MS;
        } catch (java.text.ParseException e) {
            return true;
        }
    }

    static long xmltvMillis(String t) throws java.text.ParseException {
        t = t.trim();
        SimpleDateFormat f = new SimpleDateFormat(t.length() > 14 ? "yyyyMMddHHmmss Z" : "yyyyMMddHHmmss", Locale.US);
        if (t.length() <= 14) f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.parse(t).getTime();
    }

    private static String xmlUnescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            return buf.toByteArray();
        }
    }

    /** The VTVgo guide, rebuilt at most once an hour; a failed rebuild keeps serving the last one. */
    private static synchronized byte[] vtvgoEpg() throws Exception {
        long now = System.currentTimeMillis();
        if (epg != null && now - epgAt < EPG_TTL_MS) return epg;
        try {
            JSONArray catalogs = new JSONObject(fetch(VTVGO_CATALOG, "https://vtvgo.vn", null, null)).getJSONArray("data");
            SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            day.setTimeZone(VIETNAM);
            String[] days = {day.format(new Date(now)), day.format(new Date(now + 86_400_000L))};
            List<String> ids = epgChannelIds(catalogs);
            List<String> guides = new ArrayList<>();
            for (int i = 0; i < ids.size(); i += 20) {
                String chunk = String.join(",", ids.subList(i, Math.min(i + 20, ids.size())));
                for (String d : days) {
                    guides.add(fetch(VTVGO_EPG + "?channelIDs=" + URLEncoder.encode(chunk, "UTF-8") + "&from=" + d + "&to=" + d,
                            "https://vtvgo.vn", null, null));
                }
            }
            epg = xmltv(catalogs, guides).getBytes(StandardCharsets.UTF_8);
            epgAt = now;
        } catch (Exception e) {
            if (epg == null) throw e;
            Log.w(TAG, "epg refresh failed, keeping the last one: " + e);
        }
        return epg;
    }

    /** Every channel id in the catalog groups, once, in catalog order. */
    static List<String> epgChannelIds(JSONArray catalogs) throws Exception {
        LinkedHashMap<String, Boolean> ids = new LinkedHashMap<>();
        for (int i = 0; i < catalogs.length(); i++) {
            JSONArray channels = catalogs.getJSONObject(i).optJSONArray("channels");
            for (int j = 0; channels != null && j < channels.length(); j++) ids.put(channels.getJSONObject(j).getString("id"), true);
        }
        return new ArrayList<>(ids.keySet());
    }

    /** XMLTV from the catalog (names, logos) and the epg answers ({"data":{id:[{programs:[...]}]}}). */
    static String xmltv(JSONArray catalogs, List<String> guides) throws Exception {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"tvhome\">\n");
        LinkedHashMap<String, JSONObject> channels = new LinkedHashMap<>();
        for (int i = 0; i < catalogs.length(); i++) {
            JSONArray list = catalogs.getJSONObject(i).optJSONArray("channels");
            for (int j = 0; list != null && j < list.length(); j++) {
                JSONObject c = list.getJSONObject(j);
                if (!channels.containsKey(c.getString("id"))) channels.put(c.getString("id"), c);
            }
        }
        for (JSONObject c : channels.values()) {
            sb.append("<channel id=\"vtvgo-").append(xml(c.getString("id"))).append("\"><display-name>")
                    .append(xml(c.optString("name"))).append("</display-name>");
            if (!c.optString("logo").isEmpty()) sb.append("<icon src=\"").append(xml(c.getString("logo"))).append("\"/>");
            sb.append("</channel>\n");
        }
        for (String g : guides) {
            JSONObject data = new JSONObject(g).optJSONObject("data");
            if (data == null) continue;
            for (Iterator<String> it = data.keys(); it.hasNext(); ) {
                String id = it.next();
                JSONArray daysOf = data.optJSONArray(id);
                for (int i = 0; daysOf != null && i < daysOf.length(); i++) {
                    JSONArray programs = daysOf.getJSONObject(i).optJSONArray("programs");
                    for (int j = 0; programs != null && j < programs.length(); j++) {
                        JSONObject p = programs.getJSONObject(j);
                        String start = xmltvTime(p.optString("startTime")), stop = xmltvTime(p.optString("endTime"));
                        if (start == null || stop == null) continue;
                        sb.append("<programme start=\"").append(start).append("\" stop=\"").append(stop)
                                .append("\" channel=\"vtvgo-").append(xml(id)).append("\"><title>")
                                .append(xml(p.optString("title"))).append("</title>");
                        if (!p.optString("description").isEmpty()) sb.append("<desc>").append(xml(p.getString("description"))).append("</desc>");
                        sb.append("</programme>\n");
                    }
                }
            }
        }
        return sb.append("</tv>\n").toString();
    }

    /** 2026-10-20T17:00:00Z -> 20261020170000 +0000; null for anything else. */
    static String xmltvTime(String iso) {
        if (iso == null || !iso.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z")) return null;
        return iso.substring(0, 4) + iso.substring(5, 7) + iso.substring(8, 10)
                + iso.substring(11, 13) + iso.substring(14, 16) + iso.substring(17, 19) + " +0000";
    }

    private static String xml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '"') sb.append("&quot;");
            else if (c >= 0x20 || c == '\t' || c == '\n') sb.append(c);
        }
        return sb.toString();
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

    /** A playlist's lines with every URI (plain lines and URI="..." attributes) resolved against base. */
    static String absolute(String playlist, String base) throws IOException {
        URL b = new URL(base);
        StringBuilder sb = new StringBuilder(playlist.length() + 1024);
        for (String line : playlist.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty()) {
                sb.append('\n');
                continue;
            }
            if (t.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(t);
                StringBuffer out = new StringBuffer();
                while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement("URI=\"" + new URL(b, m.group(1)) + "\""));
                m.appendTail(out);
                sb.append(out).append('\n');
            } else {
                sb.append(new URL(b, t)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * A master playlist cut down to its highest-bandwidth variant, so players start (and stay) at the best
     * quality instead of climbing up from 360p; other tags stay, except I-frame (trick-play) variants: they declare
     * video-only codecs, and ExoPlayer preparing from the master then played some channels without sound. A media
     * playlist comes back unchanged.
     */
    static String bestVariant(String playlist) {
        String[] lines = playlist.split("\n", -1);
        int best = -1;
        long bestBw = -1;
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue;
            Matcher m = BANDWIDTH.matcher(lines[i]);
            long bw = m.find() ? Long.parseLong(m.group(1)) : 0;
            if (bw > bestBw) {
                bestBw = bw;
                best = i;
            }
        }
        if (best < 0) return playlist;
        StringBuilder sb = new StringBuilder(playlist.length());
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("#EXT-X-I-FRAME-STREAM-INF:")) continue;
            if (lines[i].startsWith("#EXT-X-STREAM-INF:") && i != best) {
                // skip the tag, any blank lines, and the URI line under it
                while (i + 1 < lines.length && lines[i + 1].isEmpty()) i++;
                if (i + 1 < lines.length && !lines[i + 1].startsWith("#")) i++;
                continue;
            }
            sb.append(lines[i]);
            if (i < lines.length - 1) sb.append('\n');
        }
        return sb.toString();
    }

    /** {final url after redirects, body}; only a 2xx answer counts. */
    private static String[] fetchPlaylist(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                for (int n; (n = in.read(b)) > 0; ) {
                    buf.write(b, 0, n);
                    if (buf.size() > MAX_BODY) throw new IOException("too large");
                }
                return new String[] {c.getURL().toString(), new String(buf.toByteArray(), StandardCharsets.UTF_8)};
            }
        } finally {
            c.disconnect();
        }
    }

    /** GET, or POST of body (as contentType) when body is not null. */
    private static String fetch(String url, String origin, String body, String contentType) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            // Cloudflare answers bare clients with a 403 page; a browser UA + Origin/Referer gets through
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Origin", origin);
            c.setRequestProperty("Referer", origin + "/");
            if (body != null) {
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setRequestProperty("Content-Type", contentType);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(b);
                }
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
