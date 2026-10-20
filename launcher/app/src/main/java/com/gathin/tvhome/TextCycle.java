package com.gathin.tvhome;

import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * One line that takes turns showing a few texts (greeting, date, Bitcoin price): each stays HOLD_MS,
 * then is quickly erased back into a caret, and the next one types out from it. Spans (colours) survive,
 * since partial texts are subSequences. Handler-driven, so it runs with system animations off.
 */
final class TextCycle {

    static final long HOLD_MS = 6000;
    private static final long ERASE_MS = 18, TYPE_MS = 28, CARET_MS = 160;
    private static final String CARET = "|";

    private final TextView view;
    private final Handler handler;
    private final Supplier<List<CharSequence>> items;
    private int index;
    private boolean running, moving;
    private CharSequence from, to;
    private int at;

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
        handler.removeCallbacks(step);
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

    private void advance() {
        List<CharSequence> list = live();
        if (list.size() < 2) {
            if (running) handler.postDelayed(next, HOLD_MS);
            return;
        }
        from = view.getText();
        index = (index + 1) % list.size();
        to = list.get(index);
        at = from.length();
        moving = true;
        handler.post(step);
    }

    /** Erase one character at a time down to the caret, pause, then type the next text out. */
    private final Runnable step = new Runnable() {
        @Override
        public void run() {
            if (!moving) return;
            if (from != null) {
                at = Math.max(0, at - 1);
                // Don't leave half a surrogate pair (an emoji) behind.
                if (at > 0 && Character.isHighSurrogate(from.charAt(at - 1))) at--;
                view.setText(withCaret(from, at));
                if (at == 0) {
                    from = null;
                    handler.postDelayed(this, CARET_MS);
                } else {
                    handler.postDelayed(this, ERASE_MS);
                }
                return;
            }
            at = Math.min(to.length(), at + 1);
            if (at < to.length() && Character.isHighSurrogate(to.charAt(at - 1))) at++;
            if (at >= to.length()) {
                view.setText(to);
                moving = false;
                if (running) handler.postDelayed(next, HOLD_MS);
            } else {
                view.setText(withCaret(to, at));
                handler.postDelayed(this, TYPE_MS);
            }
        }
    };

    private static CharSequence withCaret(CharSequence s, int n) {
        return new SpannableStringBuilder(s.subSequence(0, n)).append(CARET);
    }
}
