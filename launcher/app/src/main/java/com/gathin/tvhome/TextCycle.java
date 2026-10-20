package com.gathin.tvhome;

import android.os.Handler;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * One line that takes turns showing a few texts (greeting, date, Bitcoin price): each stays HOLD_MS,
 * then fades into the next. Frame by frame (Tween), so it runs with system animations off.
 */
final class TextCycle {

    static final long HOLD_MS = 6000;
    private static final long FADE_MS = 700;

    private final TextView view;
    private final Handler handler;
    private final Supplier<List<CharSequence>> items;
    private int index;
    private boolean running, moving;
    private CharSequence to;

    TextCycle(TextView view, Handler handler, Supplier<List<CharSequence>> items) {
        this.view = view;
        this.handler = handler;
        this.items = items;
    }

    /** Back to the first text (the greeting), shown at once, and stopped. */
    void reset() {
        stop();
        index = 0;
        refresh();
    }

    void start() {
        if (running) return;
        running = true;
        handler.postDelayed(next, HOLD_MS);
    }

    void stop() {
        running = false;
        moving = false;
        handler.removeCallbacks(next);
        if (fade != null) fade.cancel();
        to = null;
        view.setAlpha(1f);
        view.setTranslationY(0f);
    }

    /** The current text again, when it changed (the minute, a new price) and nothing is moving. */
    void refresh() {
        if (moving) return;
        List<CharSequence> list = live();
        if (list.isEmpty()) return;
        if (index >= list.size()) index = 0;
        view.setText(list.get(index));
    }

    private List<CharSequence> live() {
        List<CharSequence> out = new ArrayList<>();
        for (CharSequence c : items.get()) if (c != null && c.length() > 0) out.add(c);
        return out;
    }

    private final Runnable next = this::advance;
    private Tween fade;

    /** A quiet change: the old text fades out drifting up a little, the new one fades in from just below. */
    private void advance() {
        List<CharSequence> list = live();
        if (list.size() < 2) {
            if (running) handler.postDelayed(next, HOLD_MS);
            return;
        }
        index = (index + 1) % list.size();
        to = list.get(index);
        moving = true;
        final float drift = 4 * view.getResources().getDisplayMetrics().density;
        if (fade == null) {
            fade = new Tween(0f, v -> {
                if (v < 0.5f) {
                    float t = v * 2f;
                    view.setAlpha(1f - t);
                    view.setTranslationY(-drift * t);
                } else {
                    if (to != null) {
                        view.setText(to);
                        to = null;
                    }
                    float t = (v - 0.5f) * 2f;
                    view.setAlpha(t);
                    view.setTranslationY(drift * (1f - t));
                }
                if (v >= 1f) {
                    moving = false;
                    if (running) handler.postDelayed(next, HOLD_MS);
                }
            });
        }
        fade.to(0f, 0);
        fade.to(1f, FADE_MS);
    }
}
