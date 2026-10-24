package com.gathin.tvhome;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Plain HTTP/1.0 to services inside the TV itself (the phone page on 127.0.0.1, Sony IP control on the
 * TV's own address). Android refuses cleartext through HttpURLConnection for an app targeting API 28+,
 * and these addresses can't be listed in a network security config, so this speaks HTTP over a socket:
 * one request, Connection: close, the body read to the end (or Content-Length), size and time limited.
 * Never for anything off the TV. Blocking: off the main thread.
 */
final class LocalHttp {
    static final class Response {
        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private LocalHttp() {
    }

    static Response request(String host, int port, String method, String path, Map<String, String> headers,
                            byte[] body, int timeoutMs, int maxBytes) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            StringBuilder head = new StringBuilder()
                    .append(method).append(' ').append(path).append(" HTTP/1.0\r\n")
                    .append("Host: ").append(host).append(port == 80 ? "" : ":" + port).append("\r\n")
                    .append("Connection: close\r\n");
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
                }
            }
            if (body != null) head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("\r\n");
            OutputStream out = s.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            if (body != null) out.write(body);
            out.flush();

            InputStream in = new BufferedInputStream(s.getInputStream());
            String status = line(in);
            String[] parts = status.split(" ", 3);
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("not HTTP: " + status);
            int code = Integer.parseInt(parts[1]);
            long length = -1;
            for (String h; !(h = line(in)).isEmpty(); ) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().toLowerCase(Locale.ROOT).equals("content-length")) {
                    length = Long.parseLong(h.substring(c + 1).trim());
                }
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (length < 0 || b.size() < length) {
                int n = in.read(buf);
                if (n < 0) break;
                b.write(buf, 0, n);
                if (b.size() > maxBytes) throw new IOException("answer too large");
            }
            return new Response(code, b.toString("UTF-8"));
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) {
                if (sb.length() == 0) throw new IOException("no answer");
                break;
            }
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("header too long");
        }
        return sb.toString();
    }
}
