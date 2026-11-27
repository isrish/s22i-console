package dev.s22i.console;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

public abstract class ConsoleActivity extends Activity {
    private static ConsoleActivity visible;
    private final IdleTimer idle = new IdleTimer();
    private final Handler dimHandler = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private final Runnable checkIdle = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            long now = SystemClock.elapsedRealtime();
            if (idle.expired(now, AutoDimSettings.minutes(ConsoleActivity.this) * 60000L,
                    !hasWindowFocus() || RideService.bikeInUse())) {
                idle.touch(now);
                startActivity(new Intent(ConsoleActivity.this, DimActivity.class));
            }
            if (resumed) dimHandler.postDelayed(this, 1000);
        }
    };
    public static void noteInteraction() {
        if (visible != null) visible.idle.touch(SystemClock.elapsedRealtime());
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        visible = this;
        idle.touch(SystemClock.elapsedRealtime());
        dimHandler.postDelayed(checkIdle, 1000);
    }
    @Override protected void onPause() {
        resumed = false;
        if (visible == this) visible = null;
        dimHandler.removeCallbacks(checkIdle);
        super.onPause();
    }
    @Override public void onUserInteraction() {
        idle.touch(SystemClock.elapsedRealtime());
        super.onUserInteraction();
    }
}
