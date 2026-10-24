package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Lock TV from the phone remote: Home comes to the front with "This TV is locked" and stays there until
 * someone types the PIN (the App lock PIN, AppLock). The lock timer locks the TV when its time is up and
 * counts down in a small pill over whatever is on screen (with the overlay permission the install script
 * grants; without it, a toast at the start and in the last minutes). Both survive a restart: the state is
 * in the launcher's preferences, the timer as a wall-clock time. All on the main thread.
 */
final class TvLock {
    private static final String TAG = "TvHomeLock";
    static final String PREF_LOCKED = "tv_locked";
    static final String PREF_LOCK_AT = "tv_lock_at";
    static final int MAX_TIMER_MIN = 12 * 60;

    /** What Home does when the TV locks or unlocks while it runs; set by MainActivity. */
    interface Screen {
        void locked();

        void unlocked();
    }

    static volatile Screen screen;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static TextView pill;
    private static long lastToastMin = -1;
    private static final Runnable TICK = TvLock::tick;

    private TvLock() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
    }

    static boolean locked(Context ctx) {
        return prefs(ctx).getBoolean(PREF_LOCKED, false) && AppLock.hasPin(prefs(ctx));
    }

    /** Wall-clock time the timer locks the TV, 0 for none. */
    static long lockAt(Context ctx) {
        return prefs(ctx).getLong(PREF_LOCK_AT, 0);
    }

    /** Once per process: picks up a timer that was running before a restart. */
    static void start(Context ctx) {
        if (app != null) return;
        app = ctx.getApplicationContext();
        if (lockAt(app) > 0) main.post(TICK);
    }

    /** Locks now. False without a PIN (there would be no way back). */
    static boolean lock(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (!AppLock.hasPin(p)) return false;
        p.edit().putBoolean(PREF_LOCKED, true).putLong(PREF_LOCK_AT, 0).apply();
        RemoteRelay.event(null);
        main.post(() -> {
            removePill();
            bringHome(ctx);
            Screen s = screen;
            if (s != null) s.locked();
        });
        return true;
    }

    static void unlock(Context ctx) {
        prefs(ctx).edit().putBoolean(PREF_LOCKED, false).apply();
        RemoteRelay.event(null);
        main.post(() -> {
            Screen s = screen;
            if (s != null) s.unlocked();
        });
    }

    /** Starts (or restarts) the timer; minutes 1 to MAX_TIMER_MIN. False without a PIN. */
    static boolean startTimer(Context ctx, int minutes) {
        SharedPreferences p = prefs(ctx);
        if (!AppLock.hasPin(p)) return false;
        p.edit().putLong(PREF_LOCK_AT, System.currentTimeMillis() + minutes * 60_000L).apply();
        RemoteRelay.event(null);
        lastToastMin = -1;
        main.post(() -> {
            main.removeCallbacks(TICK);
            tick();
        });
        return true;
    }

    static void cancelTimer(Context ctx) {
        prefs(ctx).edit().putLong(PREF_LOCK_AT, 0).apply();
        RemoteRelay.event(null);
        main.post(() -> {
            main.removeCallbacks(TICK);
            removePill();
        });
    }

    /** For the phone page: locked, PIN set, and the timer's end. */
    static JSONObject state(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("locked", locked(ctx)).put("hasPin", AppLock.hasPin(prefs(ctx))).put("lockAt", lockAt(ctx));
        } catch (Exception ignored) {
            // Never fails for these types.
        }
        return o;
    }

    /** Brings Home to the front, which shows the lock. */
    static void bringHome(Context ctx) {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(ctx.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(home);
        } catch (Exception e) {
            Log.w(TAG, "could not start Home: " + e);
        }
    }

    // ---- the countdown ------------------------------------------------------------------------------

    private static void tick() {
        if (app == null) return;
        long at = lockAt(app);
        if (at <= 0) {
            removePill();
            return;
        }
        long left = at - System.currentTimeMillis();
        if (left <= 0) {
            removePill();
            if (!lock(app)) cancelTimer(app);   // the PIN was removed meanwhile
            return;
        }
        long secs = (left + 999) / 1000;
        String text = "🔒 " + (secs >= 3600
                ? String.format("%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60)
                : String.format("%d:%02d", secs / 60, secs % 60));
        if (!showPill(text, secs <= 60)) {
            // No overlay permission: say so at the start and in the last minutes.
            long min = (secs + 59) / 60;
            if (lastToastMin < 0 || (min != lastToastMin && (min == 5 || min == 1))) {
                Toast.makeText(app, app.getString(R.string.lock_timer_toast, (int) min), Toast.LENGTH_LONG).show();
            }
            lastToastMin = min;
        }
        main.postDelayed(TICK, 1000 - System.currentTimeMillis() % 1000 + 5);
    }

    private static boolean showPill(String text, boolean soon) {
        if (!Settings.canDrawOverlays(app)) return false;
        try {
            if (pill == null) {
                float d = app.getResources().getDisplayMetrics().density;
                TextView v = new TextView(app);
                v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                v.setTextColor(Color.WHITE);
                v.setPadding((int) (18 * d), (int) (8 * d), (int) (18 * d), (int) (8 * d));
                v.setFontFeatureSettings("tnum");
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.x = (int) (32 * d);
                lp.y = (int) (28 * d);
                app.getSystemService(WindowManager.class).addView(v, lp);
                pill = v;
            }
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(100);
            bg.setColor(soon ? 0xE6FF9F0A : 0xCC1C1C22);
            pill.setBackground(bg);
            pill.setTextColor(soon ? 0xFF1A1000 : Color.WHITE);
            pill.setText(text);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "no countdown overlay: " + e);
            pill = null;
            return false;
        }
    }

    private static void removePill() {
        if (pill == null) return;
        try {
            app.getSystemService(WindowManager.class).removeView(pill);
        } catch (Exception ignored) {
            // Already gone.
        }
        pill = null;
    }
}
