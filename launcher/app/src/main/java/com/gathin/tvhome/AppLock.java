package com.gathin.tvhome;

import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * App lock: apps the owner picked (hold OK on Settings, then App lock) open from Home only after a
 * 4-digit PIN. Only Home's tiles ask: voice search, the Recents list or another app can still start
 * them. The PIN is kept as a salted SHA-256 in the launcher's private preferences; nothing leaves the TV.
 */
final class AppLock {
    static final String PREF_PIN = "app_lock_pin";
    static final String PREF_APPS = "app_lock_apps";
    static final int PIN_LENGTH = 4;
    /** Wrong PINs in a row before the pad makes you wait. */
    static final int MAX_TRIES = 5;
    static final long WAIT_MS = 30_000;
    static final String PREF_GRACE = "app_lock_grace";
    /** How long a PIN typed for a tile keeps every locked app open; 0 asks every time. */
    static final long[] GRACE_MS = {0, 5 * 60_000L, 15 * 60_000L, 30 * 60_000L, 3_600_000L, 4 * 3_600_000L, 24 * 3_600_000L};

    /** elapsedRealtime of the last PIN typed for a tile, 0 for none. Memory only: a restart locks again. */
    static long unlockedAt;

    private AppLock() {
    }

    /** The locked packages (a copy: the preference's own set must not be changed). */
    static Set<String> apps(SharedPreferences p) {
        return new HashSet<>(p.getStringSet(PREF_APPS, Collections.emptySet()));
    }

    static boolean hasPin(SharedPreferences p) {
        return parse(p.getString(PREF_PIN, "")) != null;
    }

    /** Locked needs a PIN too, so a lost preference file opens everything rather than nothing. */
    static boolean locked(SharedPreferences p, String pkg) {
        return pkg != null && hasPin(p) && apps(p).contains(pkg);
    }

    static void setLocked(SharedPreferences p, String pkg, boolean on) {
        Set<String> apps = apps(p);
        if (on) apps.add(pkg);
        else apps.remove(pkg);
        p.edit().putStringSet(PREF_APPS, apps).apply();
    }

    static void setPin(SharedPreferences p, String pin) {
        p.edit().putString(PREF_PIN, store(pin, new SecureRandom())).apply();
        unlockedAt = 0;
    }

    /** The chosen grace, one of {@link #GRACE_MS}; anything else asks every time. */
    static long grace(SharedPreferences p) {
        long g = p.getLong(PREF_GRACE, 0);
        for (long x : GRACE_MS) if (x == g) return g;
        return 0;
    }

    /** The next grace in the list, after the longest back to every time. */
    static long nextGrace(long current) {
        for (int i = 0; i < GRACE_MS.length - 1; i++) if (GRACE_MS[i] == current) return GRACE_MS[i + 1];
        return GRACE_MS[0];
    }

    static void setGrace(SharedPreferences p, long grace) {
        p.edit().putLong(PREF_GRACE, grace).apply();
        unlockedAt = 0;
    }

    /** A PIN typed at {@code at} still counts at {@code now}. */
    static boolean inGrace(long at, long now, long grace) {
        return at > 0 && grace > 0 && now >= at && now - at < grace;
    }

    static boolean checkPin(SharedPreferences p, String pin) {
        return matches(p.getString(PREF_PIN, ""), pin);
    }

    /** "salt:hash", both hex. */
    static String store(String pin, SecureRandom random) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String s = hex(salt);
        return s + ":" + hash(s, pin);
    }

    static boolean matches(String stored, String pin) {
        String[] parts = parse(stored);
        if (parts == null || !valid(pin)) return false;
        return MessageDigest.isEqual(parts[1].getBytes(StandardCharsets.US_ASCII),
                hash(parts[0], pin).getBytes(StandardCharsets.US_ASCII));
    }

    static boolean valid(String pin) {
        if (pin == null || pin.length() != PIN_LENGTH) return false;
        for (int i = 0; i < pin.length(); i++) if (pin.charAt(i) < '0' || pin.charAt(i) > '9') return false;
        return true;
    }

    private static String[] parse(String stored) {
        if (stored == null) return null;
        String[] parts = stored.split(":");
        return parts.length == 2 && parts[0].length() == 32 && parts[1].length() == 64 ? parts : null;
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest((salt + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // every Android has SHA-256
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }
}
