package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class WebHttpTest {

    private static WebHttp.Request parse(String head) throws Exception {
        return WebHttp.parseHead(WebHttp.readHead(new ByteArrayInputStream(head.getBytes(StandardCharsets.ISO_8859_1))));
    }

    private static void refused(String head, int status) {
        try {
            parse(head);
            fail(head);
        } catch (WebHttp.BadRequest e) {
            assertEquals(head, status, e.status);
        } catch (Exception e) {
            fail(head + ": " + e);
        }
    }

    @Test
    public void parsesARequest() throws Exception {
        WebHttp.Request r = parse("GET /api/state?k=012345&x=a%20b HTTP/1.1\r\nHost: tv\r\nCookie: a=1; hk=999999\r\n\r\nignored");
        assertEquals("GET", r.method);
        assertEquals("/api/state", r.path);
        assertEquals("012345", r.query.get("k"));
        assertEquals("a b", r.query.get("x"));
        assertEquals("tv", r.header("Host"));
        assertEquals("999999", WebHttp.cookie(r.header("cookie"), "hk"));
        assertNull(WebHttp.cookie(r.header("cookie"), "k"));
        assertEquals(-1, r.contentLength());
    }

    @Test
    public void refusesBadRequests() {
        refused("DELETE / HTTP/1.1\r\n\r\n", 405);
        refused("GET / HTTP/2\r\n\r\n", 505);
        refused("GET http://evil/ HTTP/1.1\r\n\r\n", 400);
        refused("GET /?a=%zz HTTP/1.1\r\n\r\n", 400);
        refused("GET / HTTP/1.1\r\nno colon\r\n\r\n", 400);
        refused("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n", 501);
        refused("GET / HTTP/1.1\r\n", 400);   // cut short
        StringBuilder big = new StringBuilder("GET / HTTP/1.1\r\nX: ");
        for (int i = 0; i < WebHttp.MAX_HEAD; i++) big.append('a');
        refused(big.append("\r\n\r\n").toString(), 431);
        StringBuilder many = new StringBuilder("GET /?");
        for (int i = 0; i < WebHttp.MAX_PARAMS + 1; i++) many.append("p").append(i).append("=1&");
        refused(many.append(" HTTP/1.1\r\n\r\n").toString().replace("& ", " "), 400);
    }

    @Test
    public void contentLengthIsStrict() throws Exception {
        assertEquals(42, parse("POST /x HTTP/1.1\r\nContent-Length: 42\r\n\r\n").contentLength());
        for (String v : new String[] {"-1", "4 2", "", "99999999999999"}) {
            try {
                parse("POST /x HTTP/1.1\r\nContent-Length: " + v + "\r\n\r\n").contentLength();
                fail(v);
            } catch (WebHttp.BadRequest expected) {
                // refused
            }
        }
    }

    @Test
    public void codes() {
        assertTrue(WebHttp.sameCode("012345", "012345"));
        assertFalse(WebHttp.sameCode("012346", "012345"));
        assertFalse(WebHttp.sameCode("01234", "012345"));
        assertFalse(WebHttp.sameCode("0123456", "012345"));
        assertFalse(WebHttp.sameCode(null, "012345"));
        String c = WebHttp.newCode(new Random(7));
        assertEquals(6, c.length());
        assertTrue(c.chars().allMatch(Character::isDigit));
    }

    @Test
    public void limiterShutsOutAfterTenFailures() {
        WebHttp.Limiter l = new WebHttp.Limiter();
        for (int i = 0; i < 9; i++) l.failed("10.0.0.2", 1000 + i);
        assertFalse(l.blocked("10.0.0.2", 1100));
        l.failed("10.0.0.2", 1200);
        assertTrue(l.blocked("10.0.0.2", 1300));
        assertFalse(l.blocked("10.0.0.3", 1300));
        assertFalse(l.blocked("10.0.0.2", 1200 + WebHttp.Limiter.WINDOW_MS));
        // Failures spread over more than a minute never add up to a block.
        WebHttp.Limiter slow = new WebHttp.Limiter();
        for (int i = 0; i < 30; i++) slow.failed("10.0.0.4", i * 10_000L);
        assertFalse(slow.blocked("10.0.0.4", 300_000L));
    }

    @Test
    public void imageTypesByMagic() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        byte[] webp = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'};
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0};
        assertEquals(WebHttp.JPEG, WebHttp.imageType(jpeg, 12));
        assertEquals(WebHttp.PNG, WebHttp.imageType(png, 12));
        assertEquals(WebHttp.WEBP, WebHttp.imageType(webp, 12));
        assertNull(WebHttp.imageType(gif, 12));
        assertNull(WebHttp.imageType(png, 4));
    }

    @Test
    public void fileNamesAreSafe() {
        assertEquals("IMG_1234.jpg", WebHttp.fileName("IMG_1234.HEIC", WebHttp.JPEG, 1));
        assertEquals("passwd.png", WebHttp.fileName("../../etc/passwd", WebHttp.PNG, 1));
        assertEquals("photo-5.webp", WebHttp.fileName("<>:\"|?*", WebHttp.WEBP, 5));
        assertEquals("photo-5.jpg", WebHttp.fileName(null, WebHttp.JPEG, 5));
    }

    @Test
    public void textAndJson() {
        assertEquals("\"a\\\"b\\\\c\\u003cd\\u000a\"", WebHttp.json("a\"b\\c<d\n"));
        assertEquals("Mr. Trịnh", WebHttp.cleanText("  Mr.\u0007 Trịnh \n", 40));
        assertEquals("abc", WebHttp.cleanText("abcdef", 3));
    }

    @Test
    public void onlyLocalAddresses() {
        assertTrue(WebHttp.local(new byte[] {(byte) 192, (byte) 168, 1, 20}));
        assertTrue(WebHttp.local(new byte[] {10, 0, 0, 1}));
        assertTrue(WebHttp.local(new byte[] {(byte) 172, 20, 0, 1}));
        assertTrue(WebHttp.local(new byte[] {127, 0, 0, 1}));
        assertFalse(WebHttp.local(new byte[] {(byte) 172, 32, 0, 1}));
        assertFalse(WebHttp.local(new byte[] {8, 8, 8, 8}));
        assertFalse(WebHttp.local(new byte[] {100, 64, 0, 1}));   // carrier-grade NAT / Tailscale
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        mapped[12] = (byte) 192;
        mapped[13] = (byte) 168;
        assertTrue(WebHttp.local(mapped));
        byte[] linkLocal = new byte[16];
        linkLocal[0] = (byte) 0xFE;
        linkLocal[1] = (byte) 0x80;
        assertTrue(WebHttp.local(linkLocal));
        assertFalse(WebHttp.local(new byte[16]));   // ::
        assertFalse(WebHttp.local(null));
    }
}
