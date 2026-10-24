package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

/**
 * Asks for screen capture for the phone remote's live view and hands the answer to {@link LiveService}.
 * Invisible (translucent, no animation) and gone at once. With `appops set com.gathin.tvhome
 * PROJECT_MEDIA allow` (the install script) the system answers without showing its dialog.
 */
public class LiveCapture extends Activity {
    private static final int REQUEST = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST);
        } catch (Exception e) {
            Log.w(LiveService.TAG, "no screen capture: " + e);
            LiveService.failed("This TV can't capture its screen.");
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST && resultCode == RESULT_OK && data != null) {
            Intent s = new Intent(this, LiveService.class)
                    .putExtra(LiveService.EXTRA_CODE, resultCode)
                    .putExtra(LiveService.EXTRA_DATA, data);
            try {
                startForegroundService(s);
            } catch (Exception e) {
                Log.w(LiveService.TAG, "live service not started: " + e);
                LiveService.failed("The live view could not start.");
            }
        } else {
            LiveService.failed("Screen capture was not allowed on the TV.");
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
