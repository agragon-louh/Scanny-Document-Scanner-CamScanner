package com.scanny.scanner.activity;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.scanny.scanner.R;
import com.scanny.scanner.main_utils.AppStorage;

public class SplashActivity extends BaseActivity {
    private static final long SPLASH_MS = 1500;

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContentView(R.layout.splash_acvtivity);
        final long start = SystemClock.uptimeMillis();
        // Moving scans to internal storage can take a while on first launch after the update, and
        // the main screen must not read the database before the paths are rewritten.
        new Thread(new Runnable() {
            @Override
            public void run() {
                AppStorage.migrateFromExternal(getApplicationContext());
                AppStorage.clearStaleCache(getApplicationContext());
                long delay = Math.max(0, SPLASH_MS - (SystemClock.uptimeMillis() - start));
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        startActivity(new Intent(SplashActivity.this, MainActivity.class));
                        finish();
                    }
                }, delay);
            }
        }).start();
    }
}
