package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * When the TV comes on, the greeting types itself out large in the middle of the screen (TYPE_MS), stays
 * there a while (HOLD_MS), then shrinks and glides into the small greeting line, which it hands over to.
 * The big copy is laid out once at its full width, so the typed text never shifts as it grows.
 */
final class GreetingIntro {

    static final long TYPE_MS = 2000;
    static final long HOLD_MS = 5000;
    private static final long MOVE_MS = 800;
    private static final float MAX_SP = 72f;
    private static final String CARET = "|";

    private final FrameLayout root;
    private final TextView small;
    private final Handler handler;
    private TextView big;
    private String text;
    private int[] stops;   // char offsets after each code point
    private int typed;

    GreetingIntro(FrameLayout root, TextView small, Handler handler) {
        this.root = root;
        this.small = small;
        this.handler = handler;
    }

    boolean running() {
        return big != null;
    }

    /** Starts over with the greeting line's current text; nothing happens before the line is laid out. */
    void play() {
        if (running() || small.getWidth() == 0 || small.getText().length() == 0) return;
        Context c = root.getContext();
        text = small.getText().toString().toUpperCase(Locale.getDefault());
        stops = text.codePoints().map(Character::charCount).toArray();
        for (int i = 1; i < stops.length; i++) stops[i] += stops[i - 1];

        big = new TextView(c);
        big.setTypeface(small.getTypeface());
        big.setFontVariationSettings(small.getFontVariationSettings());
        big.setLetterSpacing(small.getLetterSpacing());
        big.setTextColor(Color.WHITE);
        big.setShadowLayer(16f, 0f, 2f, 0xB3000000);
        big.setSingleLine(true);
        big.setGravity(Gravity.START);
        big.setPivotX(0f);
        big.setPivotY(0f);
        // As large as fits in nine tenths of the screen, up to MAX_SP.
        float sp = MAX_SP;
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        float room = root.getWidth() * 0.9f;
        float full = big.getPaint().measureText(text + CARET);
        if (full > room) {
            big.setTextSize(TypedValue.COMPLEX_UNIT_PX, big.getTextSize() * room / full);
            full = big.getPaint().measureText(text + CARET);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) Math.ceil(full) + 2,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(big, lp);
        small.setAlpha(0f);
        typed = 0;
        big.setText(CARET);
        handler.postDelayed(type, TYPE_MS / Math.max(1, stops.length));
    }

    private final Runnable type = new Runnable() {
        @Override
        public void run() {
            if (big == null) return;
            typed++;
            if (typed < stops.length) {
                big.setText(text.substring(0, stops[typed - 1]) + CARET);
                handler.postDelayed(this, TYPE_MS / stops.length);
            } else {
                big.setText(text);
                handler.postDelayed(shrink, HOLD_MS);
            }
        }
    };

    /**
     * Glides the big text onto the small line: same top-left corner, scaled to its size. Driven frame by
     * frame rather than by an Animator, so it still moves on a TV with system animations turned off.
     */
    private final Runnable shrink = this::shrink;
    private final PathInterpolator ease = new PathInterpolator(0.3f, 0f, 0.2f, 1f);
    private boolean moving;
    private long moveStart;
    private float dx, dy, endScale, endAlpha;

    private void shrink() {
        if (big == null || moving) return;
        handler.removeCallbacks(type);
        big.setText(text);
        int[] from = new int[2], to = new int[2];
        big.getLocationInWindow(from);
        small.getLocationInWindow(to);
        dx = to[0] + small.getTotalPaddingLeft() - from[0];
        dy = to[1] + small.getTotalPaddingTop() - from[1];
        endScale = small.getTextSize() / big.getTextSize();
        endAlpha = Color.alpha(small.getCurrentTextColor()) / 255f;
        moving = true;
        moveStart = 0;
        Choreographer.getInstance().postFrameCallback(frame);
    }

    private final Choreographer.FrameCallback frame = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            if (big == null || !moving) return;
            if (moveStart == 0) moveStart = now;
            float t = Math.min(1f, (now - moveStart) / 1e6f / MOVE_MS);
            float e = ease.getInterpolation(t);
            big.setTranslationX(dx * e);
            big.setTranslationY(dy * e);
            float scale = 1f + (endScale - 1f) * e;
            big.setScaleX(scale);
            big.setScaleY(scale);
            big.setAlpha(1f + (endAlpha - 1f) * e);
            if (t < 1f) Choreographer.getInstance().postFrameCallback(this);
            else finish();
        }
    };

    /** A key press while typing or holding: move along to the small line now. */
    void skip() {
        if (big == null || moving) return;
        handler.removeCallbacks(type);
        handler.removeCallbacks(shrink);
        shrink.run();
    }

    /** Leaving Home or the screen going off: straight to the small line, no animation. */
    void finish() {
        handler.removeCallbacks(type);
        handler.removeCallbacks(shrink);
        Choreographer.getInstance().removeFrameCallback(frame);
        moving = false;
        if (big != null) {
            root.removeView(big);
            big = null;
        }
        small.setAlpha(1f);
    }
}
