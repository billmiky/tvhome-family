package com.gathin.tvhome;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Process;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Screen time for the last 24 hours (by hour) or 7 days (by day), from Android's usage events: which app
 * was in front while the screen was on. Needs the usage access the install script grants over adb
 * (appops GET_USAGE_STATS); Android keeps about a week of events, which is all the 7 day view needs.
 */
final class ScreenTime {

    /** Reads the event log off the main thread, one report at a time. */
    static final java.util.concurrent.ExecutorService EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor();

    // UsageEvents.Event types, spelled out so the counting below is plain Java and testable.
    static final int RESUMED = 1, PAUSED = 2, SCREEN_ON = 15, SCREEN_OFF = 16, SHUTDOWN = 26;

    /** One usage event: when, what kind, which app. */
    static final class Event {
        final long time;
        final int type;
        final String pkg;

        Event(long time, int type, String pkg) {
            this.time = time;
            this.type = type;
            this.pkg = pkg;
        }
    }

    /** Totals over [bounds[0], bounds[n]): per bucket, per app, and all together. */
    static final class Report {
        final long[] bounds;
        final long[] buckets;
        final String[] labels;
        final Map<String, Long> apps = new HashMap<>();
        long total;

        Report(long[] bounds, String[] labels) {
            this.bounds = bounds;
            this.labels = labels;
            this.buckets = new long[bounds.length - 1];
        }

        /** Packages by time, longest first. */
        List<Map.Entry<String, Long>> ranked() {
            List<Map.Entry<String, Long>> list = new ArrayList<>(apps.entrySet());
            Collections.sort(list, (a, b) -> Long.compare(b.getValue(), a.getValue()));
            return list;
        }
    }

    private ScreenTime() {
    }

    /**
     * Adds up the time each app was in front with the screen on. Events must be in time order and may
     * start before bounds[0] (to know what was in front then); an app still in front counts up to now.
     * The launcher itself is left out of the apps but counted in the total: the TV was on.
     */
    static Report count(List<Event> events, long[] bounds, String[] labels, long now, String self) {
        Report r = new Report(bounds, labels);
        String front = null;
        long since = 0;
        boolean screenOn = true;
        for (Event e : events) {
            switch (e.type) {
                case RESUMED:
                    if (front != null && screenOn) add(r, front, since, e.time, self);
                    front = e.pkg;
                    since = e.time;
                    screenOn = true;   // something came to the front, so the screen is on
                    break;
                case PAUSED:
                    if (e.pkg != null && e.pkg.equals(front)) {
                        if (screenOn) add(r, front, since, e.time, self);
                        front = null;
                    }
                    break;
                case SCREEN_OFF:
                case SHUTDOWN:
                    if (front != null && screenOn) add(r, front, since, e.time, self);
                    screenOn = false;
                    if (e.type == SHUTDOWN) front = null;
                    break;
                case SCREEN_ON:
                    screenOn = true;
                    since = e.time;   // whatever was in front comes back with the screen
                    break;
                default:
                    break;
            }
        }
        if (front != null && screenOn) add(r, front, since, now, self);
        return r;
    }

    private static void add(Report r, String pkg, long from, long to, String self) {
        long[] b = r.bounds;
        from = Math.max(from, b[0]);
        to = Math.min(to, b[b.length - 1]);
        if (to <= from) return;
        for (int i = 0; i < r.buckets.length; i++) {
            long s = Math.max(from, b[i]), e = Math.min(to, b[i + 1]);
            if (e > s) r.buckets[i] += e - s;
        }
        r.total += to - from;
        if (!pkg.equals(self)) {
            Long had = r.apps.get(pkg);
            r.apps.put(pkg, (had == null ? 0 : had) + (to - from));
        }
    }

    /** 24 hourly buckets ending with the current hour, labelled by hour ("14"). */
    static long[] hourBounds(ZonedDateTime now) {
        ZonedDateTime top = now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
        long[] b = new long[25];
        for (int i = 0; i <= 24; i++) b[i] = top.minusHours(24 - i).toInstant().toEpochMilli();
        return b;
    }

    /** 7 daily buckets ending with today, local midnight to midnight. */
    static long[] dayBounds(ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        long[] b = new long[8];
        for (int i = 0; i <= 7; i++) {
            b[i] = today.minusDays(6 - i).atStartOfDay(now.getZone()).toInstant().toEpochMilli();
        }
        return b;
    }

    static String[] hourLabels(long[] bounds, ZoneId zone) {
        String[] l = new String[bounds.length - 1];
        for (int i = 0; i < l.length; i++) {
            l[i] = String.valueOf(Instant.ofEpochMilli(bounds[i]).atZone(zone).getHour());
        }
        return l;
    }

    static String[] dayLabels(long[] bounds, ZoneId zone) {
        String[] l = new String[bounds.length - 1];
        for (int i = 0; i < l.length; i++) {
            l[i] = Instant.ofEpochMilli(bounds[i]).atZone(zone).getDayOfWeek()
                    .getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        }
        return l;
    }

    /** "3h 05m", "42m", "0m". */
    static String format(long ms) {
        long min = ms / 60_000L;
        if (min < 60) return min + "m";
        return (min / 60) + "h " + String.format(Locale.ROOT, "%02dm", min % 60);
    }

    // ---- Android -----------------------------------------------------------------------------------

    static boolean allowed(Context ctx) {
        AppOpsManager ops = ctx.getSystemService(AppOpsManager.class);
        return ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), ctx.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    /** The report for "24h" or "7d"; null without usage access. Reads the event log: off the main thread. */
    static Report load(Context ctx, boolean week) {
        if (!allowed(ctx)) return null;
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        if (usm == null) return null;
        ZonedDateTime now = ZonedDateTime.now();
        long[] bounds = week ? dayBounds(now) : hourBounds(now);
        String[] labels = week ? dayLabels(bounds, now.getZone()) : hourLabels(bounds, now.getZone());
        // Start a day early to learn what was already in front when the range begins.
        UsageEvents ue = usm.queryEvents(bounds[0] - 86_400_000L, now.toInstant().toEpochMilli());
        List<Event> events = new ArrayList<>();
        UsageEvents.Event e = new UsageEvents.Event();
        while (ue != null && ue.hasNextEvent()) {
            ue.getNextEvent(e);
            int t = e.getEventType();
            if (t == RESUMED || t == PAUSED || t == SCREEN_ON || t == SCREEN_OFF || t == SHUTDOWN) {
                events.add(new Event(e.getTimeStamp(), t, e.getPackageName()));
            }
        }
        return count(events, bounds, labels, now.toInstant().toEpochMilli(), ctx.getPackageName());
    }

    /** The phone page's report: totals, buckets with their labels, and the top apps by name. */
    static String json(Context ctx, Report r) {
        if (r == null) return "{\"allowed\":false}";
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"allowed\":true,\"total\":").append(r.total).append(",\"labels\":[");
        for (int i = 0; i < r.labels.length; i++) sb.append(i > 0 ? "," : "").append(WebHttp.json(r.labels[i]));
        sb.append("],\"buckets\":[");
        for (int i = 0; i < r.buckets.length; i++) sb.append(i > 0 ? "," : "").append(r.buckets[i]);
        sb.append("],\"apps\":[");
        List<Map.Entry<String, Long>> ranked = r.ranked();
        for (int i = 0; i < Math.min(TOP, ranked.size()); i++) {
            Map.Entry<String, Long> e = ranked.get(i);
            if (e.getValue() < 60_000L) break;
            sb.append(i > 0 ? "," : "").append("{\"name\":").append(WebHttp.json(label(ctx, e.getKey())))
                    .append(",\"ms\":").append(e.getValue()).append('}');
        }
        return sb.append("]}").toString();
    }

    /** Apps listed in a report. */
    static final int TOP = 8;

    /** An app's name for the report, or its package when it is gone. */
    static String label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            // Hidden from us (a stock launcher switched off, say): name the well-known ones.
            if (pkg.startsWith("com.google.android.apps.tv.launcherx") || pkg.equals("com.google.android.tvlauncher")) {
                return "Google TV Home";
            }
            return pkg;
        }
    }
}
