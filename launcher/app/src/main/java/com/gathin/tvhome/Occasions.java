package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Days the greeting line says something else: the owner's own (birthdays, anniversaries) from
 * `occasions`, then the built-in holidays (`holidays`): Christmas (24–25 December), New Year (31 December
 * and 1 January) and Tết (three days before mùng 1 to five days after). Plain Java, so the date rules are
 * tested on the JVM.
 */
final class Occasions {

    static final int MAX_ENTRIES = 50;
    static final int MAX_TEXT = 48;

    static final String TET = "HAPPY NEW YEAR 🧧 🎉";
    static final String NEW_YEAR = "HAPPY NEW YEAR 🍾 🎉";
    static final String CHRISTMAS = "MERRY CHRISTMAS 🎄 🎅";
    /** The Tết greeting runs from this many days before mùng 1 … */
    static final int TET_BEFORE = 3;
    /** … to this many days after it, both inclusive: nine days. */
    static final int TET_AFTER = 5;

    /**
     * Mùng 1 Tết (Gregorian year, month, day), 2026 to 2040, in Vietnamese time (UTC+7), worked out with Hồ
     * Ngọc Đức's lunar calendar algorithm. Vietnam and China share every date in this range except 2030:
     * that new moon falls at 23:07 on 2 February in Hanoi, already 3 February in Beijing.
     */
    private static final int[][] TET_DAYS = {
            {2026, 2, 17}, {2027, 2, 6}, {2028, 1, 26}, {2029, 2, 13}, {2030, 2, 2},
            {2031, 1, 23}, {2032, 2, 11}, {2033, 1, 31}, {2034, 2, 19}, {2035, 2, 8},
            {2036, 1, 28}, {2037, 2, 15}, {2038, 2, 4}, {2039, 1, 24}, {2040, 2, 12},
    };

    /** Songs searched on YouTube when an occasion has no video of its own. */
    static final String BIRTHDAY_SONG = "Happy Birthday song";
    static final String CHRISTMAS_SONG = "Merry Christmas song";
    static final String NEW_YEAR_SONG = "ABBA Happy New Year";
    static final String TET_SONG = "Ngày Tết quê em";

    /** One owner's occasion: every year (year 0) or on one day only, with an optional YouTube video id. */
    static final class Entry {
        final int year, month, day;
        final String text;
        final String video;

        Entry(int year, int month, int day, String text) {
            this(year, month, day, text, "");
        }

        Entry(int year, int month, int day, String text, String video) {
            this.year = year;
            this.month = month;
            this.day = day;
            this.text = text;
            this.video = video == null ? "" : video;
        }

        /** "MM-DD" or "YYYY-MM-DD", as written in the config. */
        String date() {
            return year == 0 ? String.format(Locale.US, "%02d-%02d", month, day)
                    : String.format(Locale.US, "%04d-%02d-%02d", year, month, day);
        }
    }

    private Occasions() {
    }

    /**
     * The entries of a JSON array of {"date": "MM-DD" or "YYYY-MM-DD", "text": "…"}. Entries that don't
     * validate are left out and described in {@code skipped} (may be null); at most {@link #MAX_ENTRIES}.
     */
    static List<Entry> parse(String json, List<String> skipped) {
        if (json == null || json.trim().isEmpty()) return Collections.emptyList();
        JSONArray a;
        try {
            a = new JSONArray(json);
        } catch (Exception e) {
            if (skipped != null) skipped.add("occasions is not a JSON array");
            return Collections.emptyList();
        }
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            if (out.size() >= MAX_ENTRIES) {
                if (skipped != null) skipped.add("more than " + MAX_ENTRIES + " occasions, the rest are ignored");
                break;
            }
            JSONObject o = a.optJSONObject(i);
            Entry e = o == null ? null : entry(o.optString("date", ""), o.optString("text", ""), o.optString("video", ""));
            if (e == null) {
                if (skipped != null) skipped.add("occasion " + (i + 1) + " needs a date (MM-DD or YYYY-MM-DD) and a text");
                continue;
            }
            out.add(e);
        }
        return out;
    }

    /**
     * One validated entry, or null: a real calendar date and 1 to {@link #MAX_TEXT} characters of text. A
     * video that isn't a YouTube id or link is dropped (the entry stays, without it).
     */
    static Entry entry(String date, String text, String video) {
        Entry e = entry(date, text);
        return e == null ? null : new Entry(e.year, e.month, e.day, e.text, YouTube.videoId(video));
    }

    /** One validated entry without a video, or null. */
    static Entry entry(String date, String text) {
        String t = clean(text);
        if (t.isEmpty()) return null;
        String d = date == null ? "" : date.trim();
        int year = 0, month, day;
        try {
            if (d.matches("\\d{2}-\\d{2}")) {
                month = Integer.parseInt(d.substring(0, 2));
                day = Integer.parseInt(d.substring(3, 5));
                // Every year: check against a leap year, so 02-29 is allowed.
                LocalDate.of(2024, month, day);
            } else if (d.matches("\\d{4}-\\d{2}-\\d{2}")) {
                year = Integer.parseInt(d.substring(0, 4));
                month = Integer.parseInt(d.substring(5, 7));
                day = Integer.parseInt(d.substring(8, 10));
                if (year < 2000 || year > 2100) return null;
                LocalDate.of(year, month, day);
            } else {
                return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
        return new Entry(year, month, day, t);
    }

    /**
     * Control characters removed, trimmed, at most {@link #MAX_TEXT} code points. Works on code points, so
     * emoji (surrogate pairs) are counted once and never cut in half; ZWJ and variation selectors stay.
     */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (int i = 0; i < s.length() && kept < MAX_TEXT; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029) continue;
            sb.appendCodePoint(cp);
            kept++;
        }
        return sb.toString().trim();
    }

    /** The entries as the JSON array the config and the phone page use. */
    static String toJson(List<Entry> entries) {
        JSONArray a = new JSONArray();
        for (Entry e : entries) {
            try {
                JSONObject o = new JSONObject().put("date", e.date()).put("text", e.text);
                if (!e.video.isEmpty()) o.put("video", e.video);
                a.put(o);
            } catch (Exception ignored) {
                // put() only throws for non-finite numbers
            }
        }
        return a.toString();
    }

    /**
     * What the greeting line says on {@code date}: the owner's occasions that day, else the built-in
     * holidays (when {@code holidays} is on), joined with " · "; empty for an ordinary day.
     */
    static String on(LocalDate date, List<Entry> owner, boolean holidays) {
        List<String> found = new ArrayList<>();
        for (Entry e : owner) {
            if (matches(e, date) && !found.contains(e.text)) found.add(e.text);
        }
        if (found.isEmpty() && holidays) {
            int m = date.getMonthValue(), d = date.getDayOfMonth();
            if (m == 12 && (d == 24 || d == 25)) found.add(CHRISTMAS);
            // One "Happy New Year" a day: Tết's when its window reaches 31 December or 1 January.
            if (isTet(date)) found.add(TET);
            else if ((m == 12 && d == 31) || (m == 1 && d == 1)) found.add(NEW_YEAR);
        }
        return String.join(" · ", found);
    }

    /** What the Play card on an occasion day plays: a video id, or a search when there is none. */
    static final class Play {
        /** The card's second line: the song, or the occasion. */
        final String song;
        final String videoId;
        final String search;

        Play(String song, String videoId, String search) {
            this.song = song;
            this.videoId = videoId;
            this.search = search;
        }
    }

    /**
     * The Play card for {@code date}, following the same precedence as {@link #on}: the first of the owner's
     * occasions that day, else the built-in holiday (videos from {@code christmas}, {@code newYear},
     * {@code tet} when they are YouTube ids or links); null on an ordinary day.
     */
    static Play play(LocalDate date, List<Entry> owner, boolean holidays, String christmas, String newYear, String tet) {
        for (Entry e : owner) {
            if (!matches(e, date)) continue;
            boolean birthday = YouTube.isBirthday(e.text);
            String plain = YouTube.withoutEmoji(e.text);
            if (!e.video.isEmpty()) return new Play(birthday ? BIRTHDAY_SONG : plain, e.video, "");
            if (birthday) return new Play(BIRTHDAY_SONG, "", BIRTHDAY_SONG);
            if (!plain.isEmpty()) return new Play(plain, "", plain);
        }
        if (!holidays) return null;
        int m = date.getMonthValue(), d = date.getDayOfMonth();
        if (m == 12 && (d == 24 || d == 25)) return holiday(CHRISTMAS_SONG, christmas);
        if (isTet(date)) return holiday(TET_SONG, tet);
        if ((m == 12 && d == 31) || (m == 1 && d == 1)) return holiday(NEW_YEAR_SONG, newYear);
        return null;
    }

    private static Play holiday(String song, String video) {
        String id = YouTube.videoId(video);
        return new Play(song, id, id.isEmpty() ? song : "");
    }

    static boolean matches(Entry e, LocalDate date) {
        if (e.year != 0) {
            return e.year == date.getYear() && e.month == date.getMonthValue() && e.day == date.getDayOfMonth();
        }
        if (e.month == date.getMonthValue() && e.day == date.getDayOfMonth()) return true;
        // A 29 February occasion shows on the 28th in the other years.
        return e.month == 2 && e.day == 29 && !date.isLeapYear()
                && date.getMonthValue() == 2 && date.getDayOfMonth() == 28;
    }

    /**
     * Within the Tết greeting window, {@link #TET_BEFORE} days before mùng 1 to {@link #TET_AFTER} after,
     * for the years in the table. Every entry is checked, so a window may cross a month or year boundary.
     */
    static boolean isTet(LocalDate date) {
        for (int[] t : TET_DAYS) {
            LocalDate first = LocalDate.of(t[0], t[1], t[2]);
            if (!date.isBefore(first.minusDays(TET_BEFORE)) && !date.isAfter(first.plusDays(TET_AFTER))) return true;
        }
        return false;
    }
}
