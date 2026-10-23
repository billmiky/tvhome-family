package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.LocalDate;

public class PublicHolidaysTest {

    @Test
    public void nextHolidayInEitherCountry() {
        assertEquals("8 NOV (SG)", PublicHolidays.next(LocalDate.of(2026, 10, 23)));
        assertEquals("24 NOV (VN)", PublicHolidays.next(LocalDate.of(2026, 11, 9)));     // today's is not "next"
        assertEquals("1 JAN (SG, VN)", PublicHolidays.next(LocalDate.of(2026, 12, 25)));
        assertEquals("6 FEB (SG)", PublicHolidays.next(LocalDate.of(2027, 1, 1)));   // Chinese New Year, as Singapore keeps it
        assertNull(PublicHolidays.next(LocalDate.of(2027, 12, 25)));
    }
}
