package com.gathin.tvhome;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * When the TV was last turned off, for the greeting intro's "Last TV usage" line. Recorded on SCREEN_OFF by
 * Home and, when it is on, by the Input button service, which stays alive while other apps are in front.
 */
final class LastUse {

    private static final String PREF = "last_screen_off";

    private LastUse() {
    }

    static void record(Context c) {
        c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
                .putLong(PREF, System.currentTimeMillis()).apply();
    }

    /** "Last TV usage: 1:25 AM 20 OCT", or "" when nothing was recorded yet. */
    static String line(Context c) {
        long at = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getLong(PREF, 0);
        return at == 0 ? "" : format(at);
    }

    static String format(long at) {
        return "Last TV usage: " + new SimpleDateFormat("h:mm a d MMM", Locale.ENGLISH).format(new Date(at))
                .toUpperCase(Locale.ENGLISH);
    }
}
