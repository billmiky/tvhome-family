package com.gathin.tvhome;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BannerShapeTest {
    @Test
    public void wideBannersCountSquareOnesDoNot() {
        assertTrue(AppAdapter.isBannerShaped(320, 180));     // 16:9
        assertTrue(AppAdapter.isBannerShaped(140, 100));     // exactly 1.4
        assertFalse(AppAdapter.isBannerShaped(64, 64));      // a square icon declared as the banner
        assertFalse(AppAdapter.isBannerShaped(130, 100));
        assertTrue(AppAdapter.isBannerShaped(-1, -1));       // a colour or a shape fills the tile
    }
}
