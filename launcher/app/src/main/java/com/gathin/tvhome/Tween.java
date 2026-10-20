package com.gathin.tvhome;

import android.view.Choreographer;
import android.view.animation.PathInterpolator;

/**
 * One value moved towards a target over a duration, frame by frame on Choreographer, with each step handed
 * to a callback. Unlike an Animator it still moves on TVs where Developer options set the animator scale to 0.
 * A new target starts from wherever the value is, so a reversal mid-way is smooth.
 */
final class Tween implements Choreographer.FrameCallback {

    interface Step {
        void at(float value);
    }

    private static final PathInterpolator EASE = new PathInterpolator(0.3f, 0f, 0.2f, 1f);

    private final Step step;
    private float value, from, to;
    private long ms, start;
    private boolean running;

    Tween(float initial, Step step) {
        this.value = initial;
        this.step = step;
    }

    float value() {
        return value;
    }

    /** Moves to target over ms; 0 ms (or already there) sets it at once. */
    void to(float target, long ms) {
        if (running && target == to) return;
        cancel();
        if (ms <= 0 || target == value) {
            value = target;
            step.at(value);
            return;
        }
        from = value;
        to = target;
        this.ms = ms;
        start = 0;
        running = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    void cancel() {
        if (!running) return;
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void doFrame(long now) {
        if (!running) return;
        if (start == 0) start = now;
        float t = Math.min(1f, (now - start) / 1e6f / ms);
        value = from + (to - from) * EASE.getInterpolation(t);
        if (t >= 1f) {
            value = to;
            running = false;
        } else {
            Choreographer.getInstance().postFrameCallback(this);
        }
        step.at(value);
    }
}
