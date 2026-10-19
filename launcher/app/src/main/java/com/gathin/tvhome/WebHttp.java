package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The plain-Java half of the phone page (WebControl): reading an HTTP/1.1 request head with hard limits,
 * query and cookie parsing, the access-code check with its rate limit, and recognising image files by
 * their first bytes. Nothing here touches Android, so it is tested on the JVM. Every byte that arrives is
 * treated as hostile: anything past a limit or out of shape is a {@link BadRequest}.
 */
final class WebHttp {

    static final int MAX_HEAD = 8 * 1024;
    static final int MAX_PARAMS = 32;
    static final int MAX_HEADERS = 64;

    private WebHttp() {
    }

    static final class BadRequest extends Exception {
        final int status;

        BadRequest(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    static final class Request {
        final String method;
        final String path;
        final Map<String, String> query;
        /** Header names in lower case. */
        final Map<String, String> headers;

        Request(String method, String path, Map<String, String> query, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        /** Content-Length, -1 when absent; a malformed or negative one is a bad request. */
        long contentLength() throws BadRequest {
            String v = header("content-length");
            if (v == null) return -1;
            if (v.isEmpty() || v.length() > 12 || !v.chars().allMatch(Character::isDigit)) {
                throw new BadRequest(400, "bad Content-Length");
            }
            return Long.parseLong(v);
        }
    }

    /** Reads up to the blank line that ends the head, never more than {@link #MAX_HEAD} bytes. */
    static String readHead(InputStream in) throws IOException, BadRequest {
        ByteArrayOutputStream out = new ByteArrayOutputStream(512);
        int state = 0;   // how much of \r\n\r\n has been seen
        while (true) {
            int b = in.read();
            if (b < 0) throw new BadRequest(400, "connection closed in the head");
            out.write(b);
            if (out.size() > MAX_HEAD) throw new BadRequest(431, "head too large");
            if (b == '\r' && (state == 0 || state == 2)) state++;
            else if (b == '\n' && (state == 1 || state == 3)) state++;
            else state = b == '\r' ? 1 : 0;
            if (state == 4) break;
        }
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    /** Parses a request head: GET or POST, an origin-form target, HTTP/1.0 or 1.1, simple headers. */
    static Request parseHead(String head) throws BadRequest {
        String[] lines = head.split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length != 3) throw new BadRequest(400, "bad request line");
        String method = first[0];
        if (!method.equals("GET") && !method.equals("POST")) throw new BadRequest(405, "method not allowed");
        if (!first[2].equals("HTTP/1.1") && !first[2].equals("HTTP/1.0")) throw new BadRequest(505, "version");
        String target = first[1];
        if (!target.startsWith("/") || target.length() > 2048) throw new BadRequest(400, "bad target");
        int q = target.indexOf('?');
        String path = q < 0 ? target : target.substring(0, q);
        Map<String, String> query = parseQuery(q < 0 ? "" : target.substring(q + 1));
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;
            int colon = line.indexOf(':');
            if (colon <= 0) throw new BadRequest(400, "bad header");
            if (headers.size() >= MAX_HEADERS) throw new BadRequest(431, "too many headers");
            String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
            headers.put(name, line.substring(colon + 1).trim());
        }
        if (headers.containsKey("transfer-encoding")) throw new BadRequest(501, "no chunked bodies");
        return new Request(method, path, query, headers);
    }

    /** a=1&b=two%20words; at most {@link #MAX_PARAMS} pairs, later duplicates ignored. */
    static Map<String, String> parseQuery(String s) throws BadRequest {
        Map<String, String> out = new LinkedHashMap<>();
        if (s.isEmpty()) return out;
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            if (out.size() >= MAX_PARAMS) throw new BadRequest(400, "too many parameters");
            int eq = pair.indexOf('=');
            String k = decode(eq < 0 ? pair : pair.substring(0, eq));
            String v = decode(eq < 0 ? "" : pair.substring(eq + 1));
            if (!out.containsKey(k)) out.put(k, v);
        }
        return out;
    }

    private static String decode(String s) throws BadRequest {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            throw new BadRequest(400, "bad escape");
        }
    }

    /** The value of one cookie in a Cookie header, or null. */
    static String cookie(String header, String name) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name)) return part.substring(eq + 1).trim();
        }
        return null;
    }

    /** Same answer in the same time whatever the input, so the code can't be guessed digit by digit. */
    static boolean sameCode(String given, String code) {
        if (given == null || code == null) return false;
        byte[] a = given.getBytes(StandardCharsets.UTF_8);
        byte[] b = code.getBytes(StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        for (int i = 0; i < b.length; i++) diff |= (i < a.length ? a[i] : 0) ^ b[i];
        return diff == 0;
    }

    /** A random six-digit code, from 6 random digits (leading zeros allowed). */
    static String newCode(java.util.Random random) {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append((char) ('0' + random.nextInt(10)));
        return sb.toString();
    }

    /** 10 wrong codes from one address within a minute shut that address out for a minute. */
    static final class Limiter {
        static final int MAX_FAILURES = 10;
        static final long WINDOW_MS = 60_000L;
        static final int MAX_ADDRESSES = 256;

        private static final class Entry {
            long windowStart;
            int failures;
            long blockedUntil;
        }

        private final Map<String, Entry> entries = new HashMap<>();

        synchronized boolean blocked(String address, long now) {
            Entry e = entries.get(address);
            return e != null && now < e.blockedUntil;
        }

        synchronized void failed(String address, long now) {
            Entry e = entries.get(address);
            if (e == null) {
                if (entries.size() >= MAX_ADDRESSES) prune(now);
                e = new Entry();
                e.windowStart = now;
                entries.put(address, e);
            }
            if (now - e.windowStart >= WINDOW_MS) {
                e.windowStart = now;
                e.failures = 0;
            }
            if (++e.failures >= MAX_FAILURES) {
                e.blockedUntil = now + WINDOW_MS;
                e.failures = 0;
                e.windowStart = now;
            }
        }

        private void prune(long now) {
            Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator();
            while (it.hasNext()) {
                Entry e = it.next().getValue();
                if (now >= e.blockedUntil && now - e.windowStart >= WINDOW_MS) it.remove();
            }
            if (entries.size() >= MAX_ADDRESSES) entries.clear();
        }
    }

    static final String JPEG = "image/jpeg";
    static final String PNG = "image/png";
    static final String WEBP = "image/webp";

    /** The image type from the file's first bytes (at least 12), or null when it is none of the three. */
    static String imageType(byte[] b, int n) {
        if (n >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return JPEG;
        if (n >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return PNG;
        if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return WEBP;
        return null;
    }

    static String extension(String type) {
        return JPEG.equals(type) ? ".jpg" : PNG.equals(type) ? ".png" : ".webp";
    }

    /**
     * A safe display name for an uploaded photo: the base name the phone sent, letters, digits and
     * - _ . space only, at most 60 characters, with the extension that matches its real type.
     */
    static String fileName(String sent, String type, long now) {
        String base = sent == null ? "" : sent;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        base = base.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length() && sb.length() < 60; i++) {
            char c = base.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == ' ') {
                sb.append(c);
            }
        }
        String clean = sb.toString().trim();
        if (clean.isEmpty()) clean = "photo-" + now;
        return clean + extension(type);
    }

    /** Text for a JSON string literal. */
    static String json(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                default:
                    if (c < 0x20 || c == '<' || c == '>' || c == '&' || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /** A setting typed on the phone: control characters removed, trimmed, at most {@code max} characters. */
    static String cleanText(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char c = s.charAt(i);
            if (!Character.isISOControl(c) && c != 0x2028 && c != 0x2029) sb.append(c);
        }
        return sb.toString().trim();
    }

    /** Whether a remote address may use the page: loopback, link-local and the private IPv4 ranges. */
    static boolean local(byte[] addr) {
        if (addr == null) return false;
        if (addr.length == 4) {
            int a = addr[0] & 0xFF, b = addr[1] & 0xFF;
            return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                    || (a == 169 && b == 254);
        }
        if (addr.length == 16) {
            boolean loopback = true;
            for (int i = 0; i < 15; i++) loopback &= addr[i] == 0;
            if (loopback && addr[15] == 1) return true;
            int first = ((addr[0] & 0xFF) << 8) | (addr[1] & 0xFF);
            if ((first & 0xFFC0) == 0xFE80) return true;          // link-local
            if ((first & 0xFE00) == 0xFC00) return true;          // unique local
            boolean mapped = true;                                 // ::ffff:a.b.c.d
            for (int i = 0; i < 10; i++) mapped &= addr[i] == 0;
            if (mapped && (addr[10] & 0xFF) == 0xFF && (addr[11] & 0xFF) == 0xFF) {
                return local(new byte[] {addr[12], addr[13], addr[14], addr[15]});
            }
        }
        return false;
    }
}
