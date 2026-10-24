package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** The connected service, for RemoteRelay's standby without IP control; null when off. */
    static volatile HomeKeyService running;

    @Override
    protected void onServiceConnected() {
        running = this;
        // The process can start for this service alone (after an update, with another app in front):
        // the phone remote, the lock timer and the local servers shouldn't wait for Home to be opened.
        try {
            Config cfg = Config.load(this);
            RemoteRelay.start(this, cfg.remoteRelay);
            TvLock.start(this);
            // Likewise the home-network phone page (the remote's Screen time goes through it) and the
            // IPTV link proxy on 127.0.0.1:8790: both used to wait for Home's onCreate.
            WebControl.start(this, cfg.webControl, cfg.webPort);
            StreamProxy.start(this);
        } catch (Exception e) {
            android.util.Log.w(RemoteRelay.TAG, "remote not started: " + e);
        }
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
