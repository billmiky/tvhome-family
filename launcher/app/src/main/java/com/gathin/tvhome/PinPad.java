package com.gathin.tvhome;

import android.app.Dialog;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextView;

/**
 * The PIN dialog for {@link AppLock}: four dots and a 3 by 4 pad (1 to 9, delete, 0) for the D-pad,
 * and the remote's own number keys work too. Once four digits are in, the listener decides: dismiss,
 * {@link #fail} or {@link #next}. Back cancels.
 */
final class PinPad {
    interface Listener {
        void entered(PinPad pad, String pin);
    }

    private static final String KEYS = "123456789<0";

    private final Dialog dialog;
    private final TextView title, dots, status;
    private final Listener listener;
    private final StringBuilder digits = new StringBuilder();

    private PinPad(Dialog dialog, View panel, String title, Listener listener) {
        this.dialog = dialog;
        this.title = panel.findViewById(R.id.title);
        this.dots = panel.findViewById(R.id.dots);
        this.status = panel.findViewById(R.id.status);
        this.listener = listener;
        this.title.setText(title);
        GridLayout grid = panel.findViewById(R.id.keys);
        LayoutInflater inflater = LayoutInflater.from(panel.getContext());
        for (int i = 0; i < KEYS.length(); i++) {
            final char k = KEYS.charAt(i);
            TextView key = (TextView) inflater.inflate(R.layout.item_pin_key, grid, false);
            key.setText(k == '<' ? "⌫" : String.valueOf(k));
            key.setOnClickListener(v -> press(k));
            grid.addView(key);
        }
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
                press((char) ('0' + keyCode - KeyEvent.KEYCODE_0));
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                press('<');
                return true;
            }
            return false;
        });
        drawDots();
    }

    /** Shows the pad over Home, focus on 5 (the middle key). */
    static PinPad show(Dialog dialog, View panel, String title, Listener listener) {
        PinPad pad = new PinPad(dialog, panel, title, listener);
        dialog.show();
        ((GridLayout) panel.findViewById(R.id.keys)).getChildAt(4).requestFocus();
        return pad;
    }

    /** Clears the digits and says what went wrong. */
    void fail(String message) {
        digits.setLength(0);
        drawDots();
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    /** Clears the digits for another round with a new title (enter, then confirm). */
    void next(String newTitle) {
        digits.setLength(0);
        drawDots();
        title.setText(newTitle);
        status.setVisibility(View.GONE);
    }

    void dismiss() {
        dialog.dismiss();
    }

    private void press(char k) {
        if (k == '<') {
            if (digits.length() > 0) digits.setLength(digits.length() - 1);
        } else if (digits.length() < AppLock.PIN_LENGTH) {
            digits.append(k);
        }
        drawDots();
        if (digits.length() == AppLock.PIN_LENGTH) listener.entered(this, digits.toString());
    }

    private void drawDots() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < AppLock.PIN_LENGTH; i++) {
            if (i > 0) sb.append("  ");
            sb.append(i < digits.length() ? '●' : '○');
        }
        dots.setText(sb);
    }
}
