package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class KeyRemapTest {
    private static KeyRemap.Entry e(int code, int scan, boolean hold, String action, String arg) {
        return new KeyRemap.Entry(code, scan, hold, action, arg);
    }

    @Test public void roundTrips() {
        List<KeyRemap.Entry> list = new ArrayList<>();
        list.add(e(8, 0, false, KeyRemap.APP, "com.google.android.youtube.tv"));
        list.add(e(196, 490, true, KeyRemap.WEBHOOK, "http://192.168.1.10:8123/api/webhook/tv"));
        list.add(e(9, 2, false, KeyRemap.PICTURE, ""));
        List<KeyRemap.Entry> back = KeyRemap.parse(KeyRemap.serialize(list));
        assertEquals(3, back.size());
        assertEquals("com.google.android.youtube.tv", back.get(0).arg);
        assertEquals(490, back.get(1).scan);
        assertTrue(back.get(1).hold);
        assertEquals(KeyRemap.PICTURE, back.get(2).action);
    }

    @Test public void skipsBrokenEntries() {
        String json = "[{\"code\":8,\"action\":\"app\"},{\"code\":0,\"action\":\"mute\"},{\"code\":9,\"action\":\"reboot\"},"
                + "{\"code\":10,\"action\":\"webhook\",\"arg\":\"ftp://x\"},{\"code\":11,\"action\":\"mute\"}]";
        List<KeyRemap.Entry> list = KeyRemap.parse(json);
        assertEquals(1, list.size());
        assertEquals(11, list.get(0).code);
        assertTrue(KeyRemap.parse("not json").isEmpty());
        assertTrue(KeyRemap.parse(null).isEmpty());
    }

    @Test public void scancodeTellsSonyAppButtonsApart() {
        List<KeyRemap.Entry> list = new ArrayList<>();
        list.add(e(196, 485, false, KeyRemap.APP, "a"));
        list.add(e(196, 490, false, KeyRemap.APP, "b"));
        assertEquals("b", KeyRemap.find(list, 196, 490, false).arg);
        assertNull(KeyRemap.find(list, 196, 491, false));   // a third button with the same keycode isn't set
        assertNull(KeyRemap.find(list, 196, 490, true));
        // The phone remote sends no scancode: the keycode alone, first entry.
        assertEquals("a", KeyRemap.find(list, 196, 0, false).arg);
    }

    @Test public void withReplacesAndClears() {
        List<KeyRemap.Entry> list = new ArrayList<>();
        list.add(e(8, 0, false, KeyRemap.MUTE, ""));
        list.add(e(8, 0, true, KeyRemap.HOME, ""));
        list = KeyRemap.with(list, 8, 0, false, e(8, 0, false, KeyRemap.INPUTS, ""));
        assertEquals(2, list.size());
        assertEquals(KeyRemap.INPUTS, KeyRemap.find(list, 8, 0, false).action);
        assertEquals(KeyRemap.HOME, KeyRemap.find(list, 8, 0, true).action);
        list = KeyRemap.with(list, 8, 0, true, null);
        assertEquals(1, list.size());
        assertNull(KeyRemap.find(list, 8, 0, true));
    }

    @Test public void webhookUrls() {
        assertTrue(KeyRemap.webhookUrl("http://192.168.1.10:8123/api/webhook/tv"));
        assertTrue(KeyRemap.webhookUrl("https://example.com/hook"));
        assertFalse(KeyRemap.webhookUrl("example.com/hook"));
        assertFalse(KeyRemap.webhookUrl("http://"));
        assertFalse(KeyRemap.webhookUrl("http:///path"));
        assertFalse(KeyRemap.webhookUrl("http://a b"));
        assertFalse(KeyRemap.webhookUrl(null));
    }

    @Test public void webhookBodyNamesTheButton() throws Exception {
        JSONObject o = new JSONObject(KeyRemap.webhookBody(e(196, 490, true, KeyRemap.WEBHOOK, "https://x"), "BRAVIA 4K AE2"));
        assertEquals(196, o.getInt("keycode"));
        assertEquals(490, o.getInt("scancode"));
        assertTrue(o.getBoolean("hold"));
        assertEquals("BRAVIA 4K AE2", o.getString("tv"));
    }

    @Test public void navigationIsNeverRemapped() {
        assertTrue(KeyRemap.reserved(23));   // DPAD_CENTER
        assertTrue(KeyRemap.reserved(4));    // BACK
        assertFalse(KeyRemap.reserved(8));   // 1
    }
}
