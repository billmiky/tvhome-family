package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * The UI font for views built in code (the font_body string: Outfit, or the system sans-serif in values-vi),
 * and a watcher for text that may be Vietnamese whatever the TV's language: Outfit lacks most Vietnamese accented
 * letters, so a holiday name, a greeting with a Vietnamese name or a song title would mix two fonts in one word.
 * Such a view switches to the system sans-serif while its text has those letters, and back after.
 */
final class UiFont {
    private UiFont() {
    }

    static Typeface get(Context context) {
        Resources res = context.getResources();
        TypedValue value = new TypedValue();
        res.getValue(R.string.font_body, value, true);
        String name = String.valueOf(value.string);
        if (name.startsWith("res/")) return res.getFont(value.resourceId);
        return Typeface.create(name, Typeface.NORMAL);
    }

    /** Letters Outfit can't draw: the Vietnamese ones with a breve, horn, circumflex or tone mark, and đ. */
    static boolean vietnamese(CharSequence s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x1EA0 && c <= 0x1EF9) || (c >= 0x0300 && c <= 0x0323)
                    || c == 'ă' || c == 'Ă' || c == 'đ' || c == 'Đ' || c == 'ơ' || c == 'Ơ' || c == 'ư' || c == 'Ư') {
                return true;
            }
        }
        return false;
    }

    /** Keeps v in its own font, or the system sans-serif (same style) while its text is Vietnamese. */
    static void watch(final TextView v) {
        final Typeface own = v.getTypeface();
        final Typeface system = Typeface.create("sans-serif", own != null ? own.getStyle() : Typeface.NORMAL);
        TextWatcher w = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                Typeface want = vietnamese(s) ? system : own;
                if (v.getTypeface() != want) v.setTypeface(want);
            }
        };
        v.addTextChangedListener(w);
        if (vietnamese(v.getText())) v.setTypeface(system);
    }
}
