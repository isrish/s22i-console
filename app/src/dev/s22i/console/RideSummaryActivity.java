package dev.s22i.console;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class RideSummaryActivity extends ConsoleActivity {
    private RideSummary summary;
    private LinearLayout toolbar;
    private boolean photo;
    private SummaryView summaryView;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setNavigationBarColor(0xff0b100d);
        try { summary = getIntent().getBooleanExtra("preview", false) ? RideSummary.preview() : RideSummary.load(this); }
        catch (Exception error) {
            Toast.makeText(this, "The last ride summary could not be opened.", Toast.LENGTH_LONG).show();
            finish(); return;
        }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xff0b100d);

        root.setPadding(0, 0, 0, Math.round(64 * getResources().getDisplayMetrics().density));
        SummaryView card = new SummaryView(); summaryView = card; root.addView(card, new LinearLayout.LayoutParams(-1, 0, 1));
        card.setOnClickListener(view -> { if (photo) setPhoto(false); });
        toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER); toolbar.setPadding(0, 0, 0, 16);
        Button back = action("Back to console");
        back.setOnClickListener(view -> { startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish(); });
        toolbar.addView(back, new LinearLayout.LayoutParams(220, 60));
        Button clean = action("Photo view"); clean.setOnClickListener(view -> {
            setPhoto(true); Toast.makeText(this, "Tap the screen to restore controls", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams spacing = new LinearLayout.LayoutParams(220, 60); spacing.leftMargin = 20;
        toolbar.addView(clean, spacing); root.addView(toolbar, new LinearLayout.LayoutParams(-1, 80)); setContentView(root);
        if (saved != null && saved.getBoolean("photo")) setPhoto(true);
    }
    private Button action(String text) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextColor(0xffccff3f);
        button.setBackgroundColor(0xff202a22); return button;
    }
    private void setPhoto(boolean value) {
        photo = value; toolbar.setVisibility(value ? View.GONE : View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(value ? View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : View.SYSTEM_UI_FLAG_FULLSCREEN);
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putBoolean("photo", photo); }
    @Override protected void onResume() {
        super.onResume();
        if (summary != null && !summary.preview && !RideSummary.exists(this)) { finish(); return; }
        if (summaryView != null) summaryView.invalidate();
    }
    @Override protected void onStart() { super.onStart(); RideService.summaryWindows++; RideService.refreshOverlay(); }
    @Override protected void onStop() { RideService.summaryWindows = Math.max(0, RideService.summaryWindows - 1); RideService.refreshOverlay(); super.onStop(); }
    @Override public void onBackPressed() { if (photo) setPhoto(false); else super.onBackPressed(); }

    private final class SummaryView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int white = 0xfff3f6eb, muted = 0xffa9b7a8, lime = 0xffccff3f;
        SummaryView() {
            super(RideSummaryActivity.this);
            setContentDescription((summary.preview ? "Sample ride preview. " : "Ride complete. ")
                    + "Distance " + RideSummary.number(summary.distance, 2) + " kilometers. Duration "
                    + RideSummary.elapsed(summary.duration) + ". Average power " + RideSummary.number(summary.avgPower, 0)
                    + " watts. Average cadence " + RideSummary.number(summary.avgCadence, 0) + " rpm.");
        }
        private void text(Canvas c, String value, float x, float y, float size, int color, boolean bold) {
            paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setTextSize(size);
            paint.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            c.drawText(value, x, y, paint);
        }
        private void line(Canvas c, float x, float y, float right, int color) {
            paint.setColor(color); paint.setStrokeWidth(1); c.drawLine(x, y, right, y, paint);
        }
        private void metric(Canvas c, String label, double value, String unit, float x, int decimals, int color) {
            text(c, label, x, 449, 15, muted, true);
            String number = RideSummary.number(value, decimals);
            text(c, number, x, 510, 48, color, false);
            float width = paint.measureText(number);
            text(c, unit, x + width + 10, 508, 17, muted, false);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float scale = Math.min(getWidth() / 1600f, getHeight() / 900f);
            canvas.save(); canvas.translate((getWidth() - 1600 * scale) / 2, (getHeight() - 900 * scale) / 2); canvas.scale(scale, scale);
            paint.setShader(new LinearGradient(0, 0, 1600, 900, 0xff263425, 0xff111a15, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(40, 30, 1560, 840, 32, 32, paint); paint.setShader(null);

            paint.setColor(0xff35462c); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f);
            for (int i = 0; i < 5; i++) canvas.drawArc(1050 + i * 28, -240 + i * 28, 1850 + i * 28, 470 + i * 28, 80, 100, false, paint);
            paint.setStyle(Paint.Style.FILL);
            text(canvas, "S22i  /  RIDE RECAP", 92, 86, 18, lime, true);
            String date = summary.preview ? "PREVIEW  •  SAMPLE DATA" : AppPrivacy.showDate(RideSummaryActivity.this) && summary.startedAt > 0
                    ? new SimpleDateFormat("MMM d, yyyy  •  h:mm a", Locale.getDefault()).format(new Date(summary.startedAt)) : "YOUR LAST RIDE";
            text(canvas, date, 1050, 86, 17, muted, false);
            text(canvas, summary.preview ? "Your ride, in focus." : "Ride complete.", 92, 159, 54, white, true);
            text(canvas, summary.preview ? "A preview of your post-ride keepsake." : "Time well spent. Every pedal stroke counts.", 94, 197, 21, muted, false);
            text(canvas, summary.completeDistance ? "ESTIMATED DISTANCE" : "RECORDED DISTANCE", 94, 253, 16, muted, true);
            String distance = RideSummary.number(summary.distance, 2);
            text(canvas, distance, 86, 366, 118, lime, false);
            float distanceWidth = paint.measureText(distance);
            text(canvas, "km", 100 + distanceWidth, 362, 34, lime, false);
            text(canvas, "RIDE TIME", 785, 253, 16, muted, true);
            text(canvas, RideSummary.elapsed(summary.duration), 780, 354, 86, white, false);
            text(canvas, "AVG SPEED   " + RideSummary.number(summary.avgSpeed, 1) + " km/h", 790, 390, 17, muted, false);
            line(canvas, 92, 414, 1508, 0xff40503c);
            metric(canvas, "AVERAGE POWER", summary.avgPower, "W", 94, 0, white);
            metric(canvas, "PEAK POWER", summary.peakPower, "W", 455, 0, 0xffffc46b);
            metric(canvas, "AVERAGE CADENCE", summary.avgCadence, "rpm", 816, 0, white);
            metric(canvas, "MAX RESISTANCE", summary.peakResistance, "level", 1177, 0, 0xffd7aaff);
            text(canvas, "YOUR EFFORT", 94, 567, 15, muted, true);
            text(canvas, "POWER / WATTS", 1356, 567, 13, muted, false);
            drawPower(canvas);
            text(canvas, "0:00", 94, 752, 14, muted, false);
            String duration = RideSummary.elapsed(summary.duration);
            text(canvas, duration, 1508 - measure(duration, 14), 752, 14, muted, false);
            line(canvas, 92, 774, 1508, 0xff35432f);
            String note = summary.preview ? "SAMPLE RIDE  •  No bike data was recorded for this preview"
                    : !Double.isFinite(summary.distance) ? "Distance unavailable  •  The bike did not report usable speed"
                    : !summary.completeDistance ? "Partial distance  •  Missing speed data excluded"
                    : "Distance estimated from bike-reported speed";
            text(canvas, note, 94, 808, 15, muted, false);
            text(canvas, "S22i CONSOLE", 1330, 808, 15, lime, true);
            canvas.restore();
        }
        private float measure(String text, float size) { paint.setTextSize(size); return paint.measureText(text); }
        private void drawPower(Canvas c) {
            final float left = 94, right = 1508, bottom = 724, height = 126;
            double max = 1; boolean any = false;
            for (double power : summary.powers) if (Double.isFinite(power)) { max = Math.max(max, power); any = true; }
            for (int i = 0; i < 3; i++) line(c, left, bottom - i * height / 2, right, 0xff2a382a);
            Path path = new Path(); boolean connected = false;
            for (int i = 0; i < summary.times.length; i++) {
                double power = summary.powers[i]; if (!Double.isFinite(power)) { connected = false; continue; }
                float x = left + (float)(summary.times[i] / (double)Math.max(1, summary.duration)) * (right - left);
                float y = bottom - (float)(power / max) * height;
                if (!connected || summary.gaps[i]) path.moveTo(x, y); else path.lineTo(x, y);
                connected = true;
            }
            paint.setColor(lime); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3);
            paint.setStrokeJoin(Paint.Join.ROUND); c.drawPath(path, paint); paint.setStyle(Paint.Style.FILL);
            if (!any) text(c, "No power samples recorded", left, bottom - 40, 20, muted, false);
        }
    }
}
