package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

public class ScreenTimeTest {

    private static final long M = 60_000L;
    private static final long[] TWO_HOURS = {0, 60 * M, 120 * M};
    private static final String[] LABELS = {"0", "1"};

    private static ScreenTime.Event ev(long min, int type, String pkg) {
        return new ScreenTime.Event(min * M, type, pkg);
    }

    @Test
    public void countsTheAppInFrontAcrossBuckets() {
        List<ScreenTime.Event> events = Arrays.asList(
                ev(30, ScreenTime.RESUMED, "yt"),
                ev(90, ScreenTime.PAUSED, "yt"),
                ev(90, ScreenTime.RESUMED, "home"),
                ev(100, ScreenTime.PAUSED, "home"));
        ScreenTime.Report r = ScreenTime.count(events, TWO_HOURS, LABELS, 120 * M, "home");
        assertEquals(30 * M, r.buckets[0]);
        assertEquals(40 * M, r.buckets[1]);
        assertEquals(60 * M, (long) r.apps.get("yt"));
        assertEquals(30 * M, r.appBuckets.get("yt")[0]);   // its own chart
        assertEquals(30 * M, r.appBuckets.get("yt")[1]);
        assertNull(r.apps.get("home"));
        assertNull(r.appBuckets.get("home"));        // Home is not an app in the list
        assertEquals(70 * M, r.total);         // but the TV was on
    }

    @Test
    public void screenOffStopsTheClockAndScreenOnRestartsIt() {
        List<ScreenTime.Event> events = Arrays.asList(
                ev(0, ScreenTime.RESUMED, "yt"),
                ev(10, ScreenTime.SCREEN_OFF, null),
                ev(50, ScreenTime.SCREEN_ON, null),
                ev(60, ScreenTime.PAUSED, "yt"));
        ScreenTime.Report r = ScreenTime.count(events, TWO_HOURS, LABELS, 120 * M, "home");
        assertEquals(20 * M, (long) r.apps.get("yt"));
    }

    @Test
    public void startsFromWhatWasInFrontBeforeTheRangeAndRunsToNow() {
        long[] bounds = {60 * M, 120 * M};
        List<ScreenTime.Event> events = Arrays.asList(ev(0, ScreenTime.RESUMED, "nf"));
        ScreenTime.Report r = ScreenTime.count(events, bounds, new String[] {"1"}, 100 * M, "home");
        assertEquals(40 * M, (long) r.apps.get("nf"));
        assertEquals(40 * M, r.total);
    }

    @Test
    public void settingsCountInTheTotalButAreNotListed() {
        List<ScreenTime.Event> events = Arrays.asList(
                ev(0, ScreenTime.RESUMED, "com.android.tv.settings"),
                ev(10, ScreenTime.PAUSED, "com.android.tv.settings"));
        ScreenTime.Report r = ScreenTime.count(events, TWO_HOURS, LABELS, 120 * M, "home");
        assertNull(r.apps.get("com.android.tv.settings"));
        assertEquals(10 * M, r.total);
    }

    @Test
    public void shutdownEndsTheApp() {
        List<ScreenTime.Event> events = Arrays.asList(
                ev(0, ScreenTime.RESUMED, "yt"),
                ev(5, ScreenTime.SHUTDOWN, null),
                ev(30, ScreenTime.SCREEN_ON, null));
        ScreenTime.Report r = ScreenTime.count(events, TWO_HOURS, LABELS, 120 * M, "home");
        assertEquals(5 * M, (long) r.apps.get("yt"));
    }

    @Test
    public void boundsAndLabels() {
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 20, 10, 55, 0, 0, ZoneId.of("Asia/Singapore"));
        long[] h = ScreenTime.hourBounds(now);
        assertEquals(25, h.length);
        assertEquals("11", ScreenTime.hourLabels(h, now.getZone())[0]);      // 11:00 yesterday
        assertEquals("10", ScreenTime.hourLabels(h, now.getZone())[23]);     // this hour
        long[] d = ScreenTime.dayBounds(now);
        assertEquals(8, d.length);
        assertEquals("Wed", ScreenTime.dayLabels(d, now.getZone())[0]);
        assertEquals("Tue", ScreenTime.dayLabels(d, now.getZone())[6]);
    }

    @Test
    public void formats() {
        assertEquals("0m", ScreenTime.format(0));
        assertEquals("42m", ScreenTime.format(42 * M));
        assertEquals("3h 05m", ScreenTime.format(185 * M));
        assertEquals("1h24m", ScreenTime.formatShort(84 * M));
        assertEquals("1h", ScreenTime.formatShort(60 * M));
        assertEquals("45m", ScreenTime.formatShort(45 * M));
        assertEquals("", ScreenTime.formatShort(30_000L));
    }
}
