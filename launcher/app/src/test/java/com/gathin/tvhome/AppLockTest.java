package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.security.SecureRandom;

public class AppLockTest {
    @Test
    public void storedPinMatchesOnlyItself() {
        String stored = AppLock.store("1234", new SecureRandom());
        assertTrue(AppLock.matches(stored, "1234"));
        assertFalse(AppLock.matches(stored, "1235"));
        assertFalse(AppLock.matches(stored, "12345"));
        assertFalse(AppLock.matches(stored, ""));
        assertFalse(AppLock.matches(stored, null));
    }

    @Test
    public void storedFormIsSaltedAndHasNoPin() {
        String a = AppLock.store("1234", new SecureRandom());
        String b = AppLock.store("1234", new SecureRandom());
        assertNotEquals(a, b);
        assertFalse(a.contains("1234"));
        assertEquals(32 + 1 + 64, a.length());
    }

    @Test
    public void brokenStoreMatchesNothing() {
        assertFalse(AppLock.matches("", "1234"));
        assertFalse(AppLock.matches(null, "1234"));
        assertFalse(AppLock.matches("abc:def", "1234"));
    }

    @Test
    public void onlyFourDigitsAreValid() {
        assertTrue(AppLock.valid("0000"));
        assertFalse(AppLock.valid("123"));
        assertFalse(AppLock.valid("12a4"));
        assertFalse(AppLock.valid("١٢٣٤"));
    }

    @Test
    public void graceCyclesThroughTheListAndBack() {
        long g = 0;
        for (int i = 1; i < AppLock.GRACE_MS.length; i++) {
            g = AppLock.nextGrace(g);
            assertEquals(AppLock.GRACE_MS[i], g);
        }
        assertEquals(24 * 3_600_000L, g);
        assertEquals(0, AppLock.nextGrace(g));
        assertEquals(0, AppLock.nextGrace(12345));
    }

    @Test
    public void graceWindow() {
        long five = 5 * 60_000L;
        assertTrue(AppLock.inGrace(1000, 1000, five));
        assertTrue(AppLock.inGrace(1000, 1000 + five - 1, five));
        assertFalse(AppLock.inGrace(1000, 1000 + five, five));
        assertFalse(AppLock.inGrace(1000, 2000, 0));        // every time
        assertFalse(AppLock.inGrace(0, 2000, five));        // no PIN typed yet
        assertFalse(AppLock.inGrace(5000, 2000, five));     // clock went back
    }
}
