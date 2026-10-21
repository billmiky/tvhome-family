package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

public class StreamProxyTest {

    private static String encode(String link) {
        String backwards = new StringBuilder(link).reverse().toString();
        return Base64.getEncoder().encodeToString(backwards.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void fileUrlUndoesBase64AndReverse() {
        String link = "https://stream.example.com/s/Ab-c_D9";
        assertEquals(link, StreamProxy.fileUrl(encode(link)));
    }

    @Test
    public void fileUrlSkipsStrayCharacters() {
        String link = "https://stream.example.com/s/x";
        String e = encode(link);
        assertEquals(link, StreamProxy.fileUrl(e.substring(0, 8) + "\n" + e.substring(8)));
    }

    @Test
    public void configReadsStringFields() {
        String html = "<script>window.playerConfig = {\n streamType: \"api\",\n apiUrl: \"https://api.example.com\",\n"
                + " endpoint: \"x/1\", fallbackUrl: \"https://f/1.m3u8\", autoplay: true\n};</script>";
        Map<String, String> c = StreamProxy.config(html);
        assertEquals("api", c.get("streamType"));
        assertEquals("https://api.example.com", c.get("apiUrl"));
        assertEquals("x/1", c.get("endpoint"));
        assertEquals("https://f/1.m3u8", c.get("fallbackUrl"));
    }

    @Test
    public void configMissing() {
        assertThrows(IllegalArgumentException.class, () -> StreamProxy.config("<html></html>"));
    }
}
