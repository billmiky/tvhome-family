package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * The phone remote's live view: mirrors the screen into a small ImageReader and sends a JPEG through
 * {@link RemoteRelay} a couple of times a second while someone watches. Started by {@link LiveCapture}
 * (Android needs a foreground service of type mediaProjection to hold the capture); stops by itself three
 * seconds after the last viewer leaves, and whenever the system ends the capture (screen off, say).
 * DRM video, HDMI and the tuner come out black: Android keeps secure layers out of every capture.
 */
public class LiveService extends Service {
    static final String TAG = "TvHomeLive";
    static final String EXTRA_CODE = "code";
    static final String EXTRA_DATA = "data";
    private static final String CHANNEL = "live";
    private static final int WIDTH = 640;
    private static final int QUALITY = 60;
    /** A few seconds after the last viewer leaves (a tab switch and back keeps it): Android's red capture icon goes too. */
    private static final long IDLE_STOP_MS = 3_000L;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean wanted;
    private static volatile long intervalMs = 500;
    private static volatile LiveService running;
    private static volatile boolean starting;
    private static Context app;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private long lastFrame;

    /** Someone opened (or closed) the live view; fps 1 to 5. Main thread or any. */
    static void setWanted(Context ctx, boolean on, int fps) {
        app = ctx.getApplicationContext();
        intervalMs = 1000L / Math.max(1, Math.min(5, fps));
        wanted = on;
        main.removeCallbacks(IDLE_STOP);
        if (!on) {
            main.postDelayed(IDLE_STOP, IDLE_STOP_MS);
            return;
        }
        if (running != null || starting) return;
        starting = true;
        main.postDelayed(() -> starting = false, 10_000);
        Intent i = new Intent(app, LiveCapture.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        try {
            app.startActivity(i);
        } catch (Exception e) {
            starting = false;
            failed("The live view could not start: " + e.getMessage());
        }
    }

    private static final Runnable IDLE_STOP = () -> {
        LiveService s = running;
        if (s != null && !wanted) s.stopSelf();
    };

    /** Tells the watching phones why there is no picture. */
    static void failed(String why) {
        starting = false;
        RemoteRelay.liveError(why);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = notification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        else startForeground(1, n);
        starting = false;
        if (projection != null) return START_NOT_STICKY;
        try {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) throw new IllegalStateException("no projection");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSelf();
                }
            }, main);
            begin();
            running = this;
        } catch (Exception e) {
            Log.w(TAG, "capture failed: " + e);
            failed("The live view could not start: " + e.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void begin() {
        DisplayMetrics dm = new DisplayMetrics();
        getSystemService(WindowManager.class).getDefaultDisplay().getRealMetrics(dm);
        int w = WIDTH, h = Math.round(WIDTH * (float) dm.heightPixels / dm.widthPixels) & ~1;
        thread = new HandlerThread("live-frames");
        thread.start();
        Handler frames = new Handler(thread.getLooper());
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> frame(r, w, h), frames);
        display = projection.createVirtualDisplay("tvhome-live", w, h, Math.max(1, dm.densityDpi * w / dm.widthPixels),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, frames);
    }

    private Bitmap scratch;
    private final ByteArrayOutputStream jpeg = new ByteArrayOutputStream(64 * 1024);

    private void frame(ImageReader r, int w, int h) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            long now = SystemClock.elapsedRealtime();
            if (!wanted || now - lastFrame < intervalMs || !RemoteRelay.canSendFrame()) return;
            lastFrame = now;
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int rowPx = p.getRowStride() / p.getPixelStride();
            if (scratch == null || scratch.getWidth() != rowPx) scratch = Bitmap.createBitmap(rowPx, h, Bitmap.Config.ARGB_8888);
            scratch.copyPixelsFromBuffer(buf);
            Bitmap out = rowPx == w ? scratch : Bitmap.createBitmap(scratch, 0, 0, w, h);
            jpeg.reset();
            out.compress(Bitmap.CompressFormat.JPEG, QUALITY, jpeg);
            if (out != scratch) out.recycle();
            RemoteRelay.sendFrame(jpeg.toByteArray());
        } catch (Exception e) {
            Log.w(TAG, "frame failed: " + e);
        } finally {
            if (img != null) img.close();
        }
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.live_channel), NotificationManager.IMPORTANCE_MIN));
        }
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle(getString(R.string.live_channel))
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        try {
            if (display != null) display.release();
            if (reader != null) reader.close();
            if (projection != null) projection.stop();
        } catch (Exception ignored) {
            // Already gone.
        }
        if (thread != null) thread.quitSafely();
        if (scratch != null) scratch.recycle();
        projection = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
