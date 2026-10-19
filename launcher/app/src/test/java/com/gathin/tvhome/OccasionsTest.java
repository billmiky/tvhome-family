package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class OccasionsTest {

    private static List<Occasions.Entry> list(String json) {
        return Occasions.parse(json, null);
    }

    @Test
    public void everyYearAndOnce() {
        List<Occasions.Entry> l = list("[{\"date\":\"03-14\",\"text\":\"Happy birthday, An\"},"
                + "{\"date\":\"2027-06-01\",\"text\":\"First day of school\"}]");
        assertEquals("Happy birthday, An", Occasions.on(LocalDate.of(2026, 3, 14), l, true));
        assertEquals("Happy birthday, An", Occasions.on(LocalDate.of(2031, 3, 14), l, true));
        assertEquals("First day of school", Occasions.on(LocalDate.of(2027, 6, 1), l, true));
        assertEquals("", Occasions.on(LocalDate.of(2028, 6, 1), l, true));
        assertEquals("", Occasions.on(LocalDate.of(2026, 3, 15), l, true));
    }

    @Test
    public void leapDayShowsOnThe28thOtherwise() {
        List<Occasions.Entry> l = list("[{\"date\":\"02-29\",\"text\":\"Leap\"}]");
        assertEquals("Leap", Occasions.on(LocalDate.of(2028, 2, 29), l, false));
        assertEquals("", Occasions.on(LocalDate.of(2028, 2, 28), l, false));
        assertEquals("Leap", Occasions.on(LocalDate.of(2027, 2, 28), l, false));
    }

    @Test
    public void severalOnOneDayAndPrecedence() {
        List<Occasions.Entry> l = list("[{\"date\":\"01-01\",\"text\":\"Happy birthday, Bố\"},"
                + "{\"date\":\"01-01\",\"text\":\"Anniversary\"}]");
        // The owner's occasions win over the built-in New Year.
        assertEquals("Happy birthday, Bố · Anniversary", Occasions.on(LocalDate.of(2027, 1, 1), l, true));
        assertEquals("HAPPY NEW YEAR 🍾 🎉", Occasions.on(LocalDate.of(2027, 1, 1), Collections.emptyList(), true));
        assertEquals("", Occasions.on(LocalDate.of(2027, 1, 1), Collections.emptyList(), false));
    }

    @Test
    public void tetWindowFromThreeDaysBeforeToFiveAfter() {
        List<Occasions.Entry> none = Collections.emptyList();
        // Tết 2027 is 6 February: 3 to 11 February.
        assertEquals("", Occasions.on(LocalDate.of(2027, 2, 2), none, true));
        assertEquals("HAPPY NEW YEAR 🧧 🎉", Occasions.on(LocalDate.of(2027, 2, 3), none, true));
        assertEquals("HAPPY NEW YEAR 🧧 🎉", Occasions.on(LocalDate.of(2027, 2, 6), none, true));
        assertEquals("HAPPY NEW YEAR 🧧 🎉", Occasions.on(LocalDate.of(2027, 2, 11), none, true));
        assertEquals("", Occasions.on(LocalDate.of(2027, 2, 12), none, true));
        // Tết 2028 is 26 January: 23 January to 31 January, then out on 1 February.
        assertFalse(Occasions.isTet(LocalDate.of(2028, 1, 22)));
        assertTrue(Occasions.isTet(LocalDate.of(2028, 1, 23)));
        assertTrue(Occasions.isTet(LocalDate.of(2028, 1, 31)));
        assertFalse(Occasions.isTet(LocalDate.of(2028, 2, 1)));
        // Across a month boundary: Tết 2033 is 31 January, window 28 January to 5 February.
        assertFalse(Occasions.isTet(LocalDate.of(2033, 1, 27)));
        assertTrue(Occasions.isTet(LocalDate.of(2033, 1, 28)));
        assertTrue(Occasions.isTet(LocalDate.of(2033, 2, 5)));
        assertFalse(Occasions.isTet(LocalDate.of(2033, 2, 6)));
        // 2030: 2 February in Vietnam (China has the 3rd), so the window is 30 January to 7 February.
        assertTrue(Occasions.isTet(LocalDate.of(2030, 1, 30)));
        assertFalse(Occasions.isTet(LocalDate.of(2030, 1, 29)));
        assertTrue(Occasions.isTet(LocalDate.of(2030, 2, 7)));
        assertFalse(Occasions.isTet(LocalDate.of(2030, 2, 8)));
        assertFalse(Occasions.isTet(LocalDate.of(2041, 2, 1)));   // past the table
    }

    @Test
    public void christmasAndNewYear() {
        List<Occasions.Entry> none = Collections.emptyList();
        assertEquals("", Occasions.on(LocalDate.of(2026, 12, 23), none, true));
        assertEquals("MERRY CHRISTMAS 🎄 🎅", Occasions.on(LocalDate.of(2026, 12, 24), none, true));
        assertEquals("MERRY CHRISTMAS 🎄 🎅", Occasions.on(LocalDate.of(2026, 12, 25), none, true));
        assertEquals("", Occasions.on(LocalDate.of(2026, 12, 26), none, true));
        assertEquals("", Occasions.on(LocalDate.of(2026, 12, 30), none, true));
        assertEquals("HAPPY NEW YEAR 🍾 🎉", Occasions.on(LocalDate.of(2026, 12, 31), none, true));
        assertEquals("HAPPY NEW YEAR 🍾 🎉", Occasions.on(LocalDate.of(2027, 1, 1), none, true));
        assertEquals("", Occasions.on(LocalDate.of(2027, 1, 2), none, true));
        assertEquals("", Occasions.on(LocalDate.of(2026, 12, 25), none, false));
    }

    @Test
    public void tetAndNewYearOnTheSameDayShowOnce() {
        // No table year puts Tết near 1 January, so check the rule through a year-crossing window by hand:
        // isTet is false on 1 January in every table year, and the New Year text is then the only one.
        for (int y = 2026; y <= 2040; y++) {
            String s = Occasions.on(LocalDate.of(y, 1, 1), Collections.emptyList(), true);
            assertEquals("HAPPY NEW YEAR 🍾 🎉", s);
            assertEquals(1, s.split("HAPPY NEW YEAR", -1).length - 1);
        }
        // Within the Tết window only Tết's greeting shows, never both texts.
        String tet = Occasions.on(LocalDate.of(2031, 1, 23), Collections.emptyList(), true);
        assertEquals("HAPPY NEW YEAR 🧧 🎉", tet);
    }

    @Test
    public void invalidEntriesAreSkipped() {
        List<String> why = new ArrayList<>();
        List<Occasions.Entry> l = Occasions.parse("[{\"date\":\"02-30\",\"text\":\"x\"},{\"date\":\"3-1\",\"text\":\"x\"},"
                + "{\"date\":\"03-01\",\"text\":\"  \"},{\"text\":\"x\"},5,{\"date\":\"03-01\",\"text\":\"ok\"}]", why);
        assertEquals(1, l.size());
        assertEquals(5, why.size());
        assertTrue(Occasions.parse("not json", null).isEmpty());
        assertNull(Occasions.entry("1999-01-01", "too early"));
        assertNotNull(Occasions.entry("02-29", "leap ok"));
        assertNull(Occasions.entry("2027-02-29", "not a leap year"));
    }

    @Test
    public void emojiAreKeptAndCountedOnce() {
        String text = "Happy birthday, Phúc 🥳 🎁";
        Occasions.Entry e = Occasions.entry("08-20", text);
        assertNotNull(e);
        assertEquals(text, e.text);
        // A ZWJ family emoji and a variation selector survive; control characters don't.
        assertEquals("Nhà mình 👨‍👩‍👧 ❤️", Occasions.clean("Nhà\u0007 mình 👨‍👩‍👧 ❤️\n"));
        // 48 code points: 47 letters then emoji are cut by code point, never half a surrogate pair.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 47; i++) sb.append('a');
        String cut = Occasions.clean(sb + "🎉🎉");
        assertEquals(48, cut.codePointCount(0, cut.length()));
        assertTrue(cut.endsWith("🎉"));
        assertFalse(Character.isHighSurrogate(cut.charAt(cut.length() - 1)));
        // Round trip through the stored JSON.
        List<Occasions.Entry> back = Occasions.parse(Occasions.toJson(Collections.singletonList(e)), null);
        assertEquals(text, back.get(0).text);
        assertEquals("08-20", back.get(0).date());
    }
}
