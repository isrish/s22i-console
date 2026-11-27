package dev.s22i.console;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.view.Gravity;

public final class DimActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams params=getWindow().getAttributes();
        params.screenBrightness=0.01f; getWindow().setAttributes(params);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        TextView black=new TextView(this); black.setBackgroundColor(Color.BLACK); black.setTextColor(Color.DKGRAY);
        black.setGravity(Gravity.CENTER); black.setText("Tap anywhere to wake"); black.setTextSize(16);
        black.setOnClickListener(v->finish());setContentView(black);
        black.postDelayed(()->black.setText(""),2500);
    }
    @Override protected void onStart(){super.onStart(); RideService.dimmed=true; RideService.refreshOverlay();}
    @Override protected void onStop(){RideService.dimmed=false; RideService.refreshOverlay();super.onStop();}
}
