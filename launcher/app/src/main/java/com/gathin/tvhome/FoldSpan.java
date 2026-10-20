package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;

/**
 * Text (or another replacement span) that folds away: at a fraction below 1 it takes that share of its
 * width, clipped from the end, and fades. Set the fraction, then set the text again to lay it out anew.
 */
final class FoldSpan extends ReplacementSpan {

    float fraction = 1f;
    private final ReplacementSpan inner;
    private final int color;

    /** inner: a span to fold instead of plain text, or null; color: the text's own colour, or 0 for the view's. */
    FoldSpan(ReplacementSpan inner, int color) {
        this.inner = inner;
        this.color = color;
    }

    private float fullWidth(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        if (inner != null) return inner.getSize(paint, text, start, end, fm);
        if (fm != null) paint.getFontMetricsInt(fm);
        return paint.measureText(text, start, end);
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        return Math.round(fullWidth(paint, text, start, end, fm) * fraction);
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                     int bottom, @NonNull Paint paint) {
        if (fraction <= 0f) return;
        float full = fullWidth(paint, text, start, end, null);
        int old = paint.getColor();
        int base = color != 0 ? color : old;
        paint.setColor((base & 0xFFFFFF) | (Math.round(Color.alpha(base) * fraction * fraction) << 24));
        canvas.save();
        canvas.clipRect(x, top, x + full * fraction, bottom);
        if (inner != null) inner.draw(canvas, text, start, end, x, top, y, bottom, paint);
        else canvas.drawText(text, start, end, x, y, paint);
        canvas.restore();
        paint.setColor(old);
    }
}
