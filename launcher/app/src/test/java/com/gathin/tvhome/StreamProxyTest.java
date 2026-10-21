package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
    public void vtvgoChannelFromPage() {
        assertEquals("1", StreamProxy.vtvgoChannel("https://vtvgo.vn/channel/1"));
        assertEquals("vietnamtoday", StreamProxy.vtvgoChannel("https://vtvgo.vn/channel/vietnamtoday/"));
        assertEquals("3", StreamProxy.vtvgoChannel("https://www.vtvgo.vn/channel/3?x=1"));
        assertNull(StreamProxy.vtvgoChannel("https://vtvgo.vn/watch/1"));
        assertNull(StreamProxy.vtvgoChannel("https://xlivetv.com/channel/1"));
        assertNull(StreamProxy.vtvgoChannel("https://vtvgo.vn.evil.com/channel/1"));
    }

    @Test
    public void vtvgoPicksFirstFreeSource() throws Exception {
        String json = "{\"data\":{\"sourceModes\":["
                + "{\"id\":\"1080p\",\"isVip\":1,\"multiSource\":[{\"drmInfo\":{\"drmType\":\"none\"},\"sources\":[{\"url\":\"https://vip\"}]}]},"
                + "{\"id\":\"default\",\"isVip\":0,\"multiSource\":["
                + "{\"drmInfo\":{\"drmType\":\"widevine\"},\"sources\":[{\"url\":\"https://drm\"}]},"
                + "{\"drmInfo\":{\"drmType\":\"none\"},\"sources\":[{\"url\":\"https://a/vtv1_free/playlist.m3u8\"}]},"
                + "{\"drmInfo\":{\"drmType\":\"none\"},\"sources\":[{\"url\":\"https://b\"}]}]}]}}";
        assertEquals("https://a/vtv1_free/playlist.m3u8", StreamProxy.vtvgoSource(json));
    }

    @Test
    public void vtvgoListsEveryFreeSourceInOrder() throws Exception {
        String json = "{\"data\":{\"sourceModes\":[{\"id\":\"default\",\"isVip\":0,\"multiSource\":["
                + "{\"drmInfo\":{\"drmType\":\"none\"},\"sources\":[{\"url\":\"https://a\"}]},"
                + "{\"drmInfo\":{\"drmType\":\"widevine\"},\"sources\":[{\"url\":\"https://drm\"}]},"
                + "{\"drmInfo\":{\"drmType\":\"none\"},\"sources\":[{\"url\":\"https://b\"},{\"url\":\"\"}]}]}]}}";
        assertEquals(java.util.Arrays.asList("https://a", "https://b"), StreamProxy.vtvgoSources(json));
    }

    @Test
    public void playlistLinksBecomeAbsolute() throws Exception {
        String master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8?t=1\n\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"audio/a.m3u8\"\nhttps://cdn2/x.m3u8\r\n/root/y.m3u8\n";
        String out = StreamProxy.absolute(master, "https://cdn/tok/123/vtv1/master.m3u8");
        assertEquals("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nhttps://cdn/tok/123/vtv1/low/index.m3u8?t=1\n\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,URI=\"https://cdn/tok/123/vtv1/audio/a.m3u8\"\nhttps://cdn2/x.m3u8\nhttps://cdn/root/y.m3u8\n", out);
    }

    @Test
    public void bestVariantOnly() {
        String master = "#EXTM3U\n#EXT-X-VERSION:3\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720\nhttps://c/720.m3u8\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=854x480\nhttps://c/480.m3u8\n"
                + "#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=9000000,BANDWIDTH=700000,RESOLUTION=640x360\n\nhttps://c/360.m3u8\n";
        assertEquals("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720\nhttps://c/720.m3u8\n",
                StreamProxy.bestVariant(master));
        String media = "#EXTM3U\n#EXTINF:6,\nhttps://c/s1.ts\n";
        assertEquals(media, StreamProxy.bestVariant(media));
    }

    @Test
    public void vtvVnPlayerAndStream() {
        String page = "<script>container.innerHTML = `<iframe src=\"https://ovp.sohatv.vn/embed?v=JnTu3cwriGGFMFAv\" width=\"880\">`;</script>";
        assertEquals("https://ovp.sohatv.vn/embed?v=JnTu3cwriGGFMFAv", StreamProxy.sohaEmbed(page));
        String embed = "{\"poster\":{\"src\":\"https://cdn.sohatv.vn/a.jpg\"},\"source\":{\"src\":\"https:\\/\\/cdn-live.vtv.vn\\/tok\\/1\\/live\\/vtv1\\/master.m3u8\"}}";
        assertEquals("https://cdn-live.vtv.vn/tok/1/live/vtv1/master.m3u8", StreamProxy.sohaSource(embed));
        assertThrows(IllegalArgumentException.class, () -> StreamProxy.sohaEmbed("<html>no player</html>"));
    }

    @Test
    public void filterXmltvKeepsWantedChannelsUnderPlaylistIds() throws Exception {
        String src = "<?xml version=\"1.0\"?>\n<tv>\n"
                + "  <channel id=\"HTV1.HD.vn\">\n    <display-name>HTV1 HD</display-name>\n  </channel>\n"
                + "  <channel id=\"Other.vn\">\n    <display-name>Other</display-name>\n  </channel>\n"
                + "  <channel id=\"BBC.Home.&amp;.Garden\"><display-name>B</display-name></channel>\n"
                + "  <programme start=\"20261021000000 +0000\" stop=\"20261021010000 +0000\" channel=\"HTV1.HD.vn\">\n"
                + "    <title>Old</title>\n  </programme>\n"
                + "  <programme start=\"20261021100000 +0700\" stop=\"20261021110000 +0700\" channel=\"HTV1.HD.vn\">\n"
                + "    <title>Now &amp; then</title>\n  </programme>\n"
                + "  <programme start=\"20261029100000 +0000\" stop=\"20261029110000 +0000\" channel=\"HTV1.HD.vn\">\n"
                + "    <title>Next week</title>\n  </programme>\n"
                + "  <programme start=\"20261021100000 +0700\" stop=\"20261021110000 +0700\" channel=\"Other.vn\">\n"
                + "    <title>Not wanted</title>\n  </programme>\n</tv>\n";
        java.util.Map<String, java.util.List<String>> want = new java.util.HashMap<>();
        want.put("HTV1.HD.vn", java.util.Arrays.asList("HTV1.vn@SD", "HTV1.vn@HD"));
        want.put("BBC.Home.&.Garden", java.util.Collections.singletonList("BBCHomeGarden.us@SD"));
        long now = StreamProxy.xmltvMillis("20261021033000 +0000");   // 10:30 +0700
        StringBuilder out = new StringBuilder();
        StreamProxy.filterXmltv(new java.io.BufferedReader(new java.io.StringReader(src)), want, now, out);
        String x = out.toString();
        assertTrue(x.contains("<channel id=\"HTV1.vn@SD\">\n<display-name>HTV1 HD</display-name>\n</channel>\n"));
        assertTrue(x.contains("<channel id=\"HTV1.vn@HD\">"));
        assertTrue(x.contains("<channel id=\"BBCHomeGarden.us@SD\"><display-name>B</display-name></channel>\n"));
        assertTrue(x.contains("channel=\"HTV1.vn@SD\">\n<title>Now &amp; then</title>\n</programme>\n"));
        assertEquals(-1, x.indexOf("Old"));
        assertEquals(-1, x.indexOf("Next week"));
        assertEquals(-1, x.indexOf("Other"));
    }

    @Test
    public void bestVariantDropsIFrameVariants() {
        String master = "#EXTM3U\n#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=848956,CODECS=\"avc1.4d4029\",URI=\"https://c/01-iframe.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=3902037,CODECS=\"avc1.4d4029,mp4a.40.2\"\nhttps://c/03.m3u8\n"
                + "#EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=3902037,CODECS=\"avc1.4d4029\",URI=\"https://c/03-iframe.m3u8\"\n";
        assertEquals("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3902037,CODECS=\"avc1.4d4029,mp4a.40.2\"\nhttps://c/03.m3u8\n",
                StreamProxy.bestVariant(master));
    }

    @Test
    public void vtvgoError() {
        assertThrows(IllegalArgumentException.class,
                () -> StreamProxy.vtvgoSource("{\"error\":{\"code\":\"BAD_REQUEST\"}}"));
        assertThrows(IllegalArgumentException.class,
                () -> StreamProxy.vtvgoSource("{\"data\":{\"sourceModes\":[{\"id\":\"1080p\",\"isVip\":1}]}}"));
    }

    @Test
    public void xmltvTimeFromIso() {
        assertEquals("20261020170000 +0000", StreamProxy.xmltvTime("2026-10-20T17:00:00Z"));
        assertEquals("20261020170000 +0000", StreamProxy.xmltvTime("2026-10-20T17:00:00.000Z"));
        assertNull(StreamProxy.xmltvTime("2026-10-20 17:00"));
        assertNull(StreamProxy.xmltvTime(""));
    }

    @Test
    public void xmltvFromCatalogAndGuide() throws Exception {
        org.json.JSONArray catalogs = new org.json.JSONArray("[{\"channels\":[{\"id\":\"1\",\"name\":\"VTV1\",\"logo\":\"https://l/1.webp?a=1&b=2\"},"
                + "{\"id\":\"vietnamtoday\",\"name\":\"VIETNAM TODAY\"}]},{\"channels\":[{\"id\":\"1\",\"name\":\"VTV1\"}]}]");
        assertEquals(java.util.Arrays.asList("1", "vietnamtoday"), StreamProxy.epgChannelIds(catalogs));
        String guide = "{\"data\":{\"vietnamtoday\":[{\"date\":\"2026-10-21\",\"programs\":[{\"title\":\"HERITAGE <JOURNEY>\","
                + "\"description\":\"Thai \\\"Xoe\\\" & dance\",\"startTime\":\"2026-10-20T17:30:00Z\",\"endTime\":\"2026-10-20T18:00:00Z\"}]}]}}";
        String x = StreamProxy.xmltv(catalogs, java.util.Collections.singletonList(guide));
        assertTrue(x.contains("<channel id=\"vtvgo-1\"><display-name>VTV1</display-name><icon src=\"https://l/1.webp?a=1&amp;b=2\"/></channel>"));
        assertEquals(1, x.split("<channel id=\"vtvgo-1\"", -1).length - 1);
        assertTrue(x.contains("<programme start=\"20261020173000 +0000\" stop=\"20261020180000 +0000\" channel=\"vtvgo-vietnamtoday\">"
                + "<title>HERITAGE &lt;JOURNEY&gt;</title><desc>Thai &quot;Xoe&quot; &amp; dance</desc></programme>"));
    }

    @Test
    public void configMissing() {
        assertThrows(IllegalArgumentException.class, () -> StreamProxy.config("<html></html>"));
    }

    @Test
    public void referersTryThePageThenTheStreamSiteThenNone() {
        assertEquals(java.util.Arrays.asList("https://xlivetv.com/", null),
                StreamProxy.referers("https://stream.xlivetv.com/s/abc", "https://xlivetv.com/ch/1"));
        assertEquals(java.util.Arrays.asList("https://adult-tv-channels.click/", "https://xlivetv.com/", null),
                StreamProxy.referers("https://stream.xlivetv.com/s/abc", "https://adult-tv-channels.click/x"));
    }
}
