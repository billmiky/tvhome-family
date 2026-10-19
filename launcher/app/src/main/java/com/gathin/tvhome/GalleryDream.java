package com.gathin.tvhome;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;
import java.util.Random;

/**
 * Screensaver: the Gallery photos from Pictures/Launcher, one every 30 seconds (screensaver_interval_s),
 * each with a slow pan and zoom, crossfading into the next, and a small clock, date and weather in the
 * bottom right corner. The pan and zoom animate the view's scale and translation only, so the GPU does
 * the work and no bitmap is touched per frame. Until the first photo is decoded, and for good when there
 * are no photos or no permission to read them, it is the Clock screensaver.
 */
public class GalleryDream extends ClockDream {

    private static final float ZOOM = 1.12f;
    private static final long FADE_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Gallery.ShuffleBag bag = new Gallery.ShuffleBag(new Random());
    private final Random random = new Random();
    private List<String> photos;
    private long intervalMs = 30_000L;
    private volatile boolean dreaming;
    private boolean loading;
    private FrameLayout layer;
    private ImageView front, back;
    private ObjectAnimator frontMove, backMove;

    private final Runnable next = this::loadNext;

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        if (checkSelfPermission(Gallery.permission()) != PackageManager.PERMISSION_GRANTED) return;   // stay the clock
        Config cfg = Config.load(this);
        intervalMs = cfg.screensaverIntervalS * 1000L;
        loadNext();
    }

    @Override
    public void onDreamingStopped() {
        stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    private void stop() {
        dreaming = false;
        handler.removeCallbacks(next);
        for (ObjectAnimator a : new ObjectAnimator[] {frontMove, backMove}) {
            if (a != null) a.cancel();
        }
        if (front != null) release(front);
        if (back != null) release(back);
    }

    /** Lists the folder once, then decodes the next photo off the main thread; the clock stays if none can be read. */
    private void loadNext() {
        if (loading || !dreaming) return;
        loading = true;
        final Context app = getApplicationContext();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int w = dm.widthPixels, h = dm.heightPixels;
        Gallery.EXECUTOR.execute(() -> {
            Bitmap bmp = null;
            try {
                if (photos == null) photos = Gallery.list(app);
                for (int tries = 0; tries < 5 && bmp == null; tries++) {
                    String item = bag.next(photos);
                    if (item == null) break;
                    bmp = Gallery.decode(app, item, w, h);
                }
            } catch (Exception e) {
                // No photos to show: the clock stays.
            }
            final Bitmap shown = bmp;
            handler.post(() -> {
                loading = false;
                if (!dreaming || shown == null) return;
                show(shown);
                handler.postDelayed(next, intervalMs);
            });
        });
    }

    /** The first photo replaces the clock with the gallery layout; each one fades in over the last. */
    private void show(Bitmap bmp) {
        if (layer == null) {
            stopClock();
            setContentView(R.layout.dream_gallery);
            layer = findViewById(R.id.dreamPhotos);
            front = new ImageView(this);
            back = new ImageView(this);
            for (ImageView v : new ImageView[] {front, back}) {
                v.setScaleType(ImageView.ScaleType.CENTER_CROP);
                layer.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            showWeather();
        }
        final ImageView incoming = back, outgoing = front;
        final ObjectAnimator outgoingMove = frontMove;
        incoming.animate().cancel();
        incoming.setImageBitmap(bmp);
        incoming.setAlpha(0f);
        layer.removeView(incoming);
        layer.addView(incoming);   // on top
        front = incoming;
        back = outgoing;
        backMove = outgoingMove;
        frontMove = kenBurns(incoming);
        incoming.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            if (back == outgoing) {
                if (outgoingMove != null) outgoingMove.cancel();
                release(outgoing);
            }
        }).start();
    }

    /** A slow zoom from 1 to ZOOM (or back), drifting towards a random corner, for as long as the photo stays. */
    private ObjectAnimator kenBurns(ImageView v) {
        boolean zoomIn = random.nextBoolean();
        float from = zoomIn ? 1f : ZOOM, to = zoomIn ? ZOOM : 1f;
        // At ZOOM the photo overhangs each edge by (ZOOM - 1) / 2 of the screen; drift within most of that.
        float maxX = v.getResources().getDisplayMetrics().widthPixels * (ZOOM - 1f) / 2f * 0.8f;
        float maxY = v.getResources().getDisplayMetrics().heightPixels * (ZOOM - 1f) / 2f * 0.8f;
        float dx = (random.nextBoolean() ? 1 : -1) * maxX, dy = (random.nextBoolean() ? 1 : -1) * maxY;
        float startX = zoomIn ? 0f : dx, endX = zoomIn ? dx : 0f;
        float startY = zoomIn ? 0f : dy, endY = zoomIn ? dy : 0f;
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, from, to),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, from, to),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, startX, endX),
                PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, startY, endY));
        a.setDuration(intervalMs + FADE_MS * 2);
        a.setInterpolator(new LinearInterpolator());
        a.start();
        return a;
    }

    /** The cached weather, when it is on; the screensaver never fetches it itself. */
    private void showWeather() {
        TextView view = findViewById(R.id.dreamWeather);
        Config cfg = Config.load(this);
        boolean on = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getBoolean(MainActivity.PREF_WEATHER, cfg.weather);
        Weather.Reading r = on && !cfg.weatherLocation.isEmpty()
                ? Weather.cached(this, cfg.weatherLocation, cfg.weatherUnit) : null;
        if (r == null) return;
        String icon = Weather.icon(r.code);
        if (!icon.isEmpty() && !view.getPaint().hasGlyph(icon)) icon = "";
        view.setText(Weather.line(r, icon));
        view.setVisibility(View.VISIBLE);
    }

    /** Lets go of a photo; not recycle(), a frame already recorded may still draw it. */
    private static void release(ImageView v) {
        v.setImageDrawable(null);
    }
}
