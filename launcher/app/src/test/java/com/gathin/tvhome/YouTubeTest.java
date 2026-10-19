package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

public class YouTubeTest {

    @Test
    public void videoIds() {
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42"));
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("https://youtu.be/dQw4w9WgXcQ?si=abc"));
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"));
        assertEquals("dQw4w9WgXcQ", YouTube.videoId("  https://www.youtube.com/embed/dQw4w9WgXcQ  "));
    }

    @Test
    public void refusesWhatIsNotAYouTubeVideo() {
        for (String s : new String[] {null, "", "dQw4w9WgXc", "dQw4w9WgXcQQ", "dQw4w9WgX!Q",
                "https://evil.example/watch?v=dQw4w9WgXcQ", "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
                "javascript:alert(1)", "ftp://youtu.be/dQw4w9WgXcQ", "https://www.youtube.com/watch?v=short",
                "https://www.youtube.com/results?search_query=happy", "https://youtu.be/"}) {
            assertEquals(String.valueOf(s), "", YouTube.videoId(s));
        }
    }

    @Test
    public void birthdaysAndEmoji() {
        assertTrue(YouTube.isBirthday("HAPPY BIRTHDAY, Phúc 🥳"));
        assertTrue(YouTube.isBirthday("Chúc mừng sinh nhật"));
        assertFalse(YouTube.isBirthday("Anniversary"));
        assertEquals("Happy birthday, Phúc", YouTube.withoutEmoji("Happy birthday, Phúc 🥳 🎁"));
        assertEquals("Kỷ niệm ngày cưới", YouTube.withoutEmoji("💍 Kỷ niệm ngày cưới ❤️"));
        assertEquals("Family", YouTube.withoutEmoji("Family 👨‍👩‍👧"));
    }

    @Test
    public void whatThePlayCardPlays() {
        LocalDate march18 = LocalDate.of(2027, 3, 18);
        List<Occasions.Entry> owner = Occasions.parse("[{\"date\":\"03-18\",\"text\":\"Happy birthday, Phúc 🥳 🎁\"},"
                + "{\"date\":\"05-01\",\"text\":\"Anniversary 💍\",\"video\":\"https://youtu.be/dQw4w9WgXcQ\"},"
                + "{\"date\":\"06-01\",\"text\":\"Picnic day 🧺\"},"
                + "{\"date\":\"07-01\",\"text\":\"Bad link\",\"video\":\"https://evil.example/x\"}]", null);
        Occasions.Play p = Occasions.play(march18, owner, true, "", "", "");
        assertEquals(Occasions.BIRTHDAY_SONG, p.search);
        assertEquals("", p.videoId);
        p = Occasions.play(LocalDate.of(2027, 5, 1), owner, true, "", "", "");
        assertEquals("dQw4w9WgXcQ", p.videoId);
        assertEquals("Anniversary", p.song);
        p = Occasions.play(LocalDate.of(2027, 6, 1), owner, true, "", "", "");
        assertEquals("Picnic day", p.search);
        // A bad link is dropped, the occasion stays: it searches its text instead.
        assertEquals("Bad link", Occasions.play(LocalDate.of(2027, 7, 1), owner, true, "", "", "").search);
        assertNull(Occasions.play(LocalDate.of(2027, 8, 1), owner, true, "", "", ""));
        // The video survives the phone page's JSON round trip.
        assertTrue(Occasions.toJson(owner).contains("\"video\":\"dQw4w9WgXcQ\""));
    }

    @Test
    public void holidaySongs() {
        List<Occasions.Entry> none = Collections.emptyList();
        assertEquals(Occasions.CHRISTMAS_SONG, Occasions.play(LocalDate.of(2026, 12, 24), none, true, "", "", "").search);
        assertEquals(Occasions.NEW_YEAR_SONG, Occasions.play(LocalDate.of(2026, 12, 31), none, true, "", "", "").search);
        assertEquals(Occasions.TET_SONG, Occasions.play(LocalDate.of(2027, 2, 6), none, true, "", "", "").search);
        Occasions.Play x = Occasions.play(LocalDate.of(2026, 12, 25), none, true, "https://youtu.be/dQw4w9WgXcQ", "", "");
        assertEquals("dQw4w9WgXcQ", x.videoId);
        assertEquals("", x.search);
        assertNull(Occasions.play(LocalDate.of(2026, 12, 25), none, false, "", "", ""));
    }
}
