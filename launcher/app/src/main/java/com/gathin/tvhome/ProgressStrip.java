package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

/**
 * The Now playing card's progress: an orange line along its bottom edge, growing from the left as the
 * track plays, on a transparent track.
 * Drawn as the card's foreground, which the card clips to its rounded outline.
 */
final class ProgressStrip extends Drawable {

    /** The played part, orange on either card so it reads against a light photo or the black focus. */
    private static final int ORANGE = 0xFFFF9F0A;
    private final Paint track = new Paint();
    private final Paint fill = new Paint();
    private final float height;
    /** 0 to 1; below 0 hides the strip (a live stream has no end to measure against). */
    private float fraction = -1f;

    ProgressStrip(float density) {
        height = 3f * density;
        track.setColor(0x00000000);   // transparent: only the played part shows
        fill.setColor(ORANGE);
    }

    /** Dark strip on the glass card; light on the focused, solid black one. */
    void setFocused(boolean focused) {
        track.setColor(0x00000000);
        fill.setColor(ORANGE);
        invalidateSelf();
    }

    void set(float f) {
        if (f == fraction) return;
        fraction = f;
        invalidateSelf();
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (fraction < 0f) return;
        float l = getBounds().left, r = getBounds().right, b = getBounds().bottom, t = b - height;
        canvas.drawRect(l, t, r, b, track);
        canvas.drawRect(l, t, l + (r - l) * fraction, b, fill);
    }

    @Override
    public void setAlpha(int alpha) {
        // The card fades as a whole view; the strip has no alpha of its own.
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
