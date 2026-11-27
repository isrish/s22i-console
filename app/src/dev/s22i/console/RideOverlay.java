package dev.s22i.console;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

public final class RideOverlay {
    private static final String[] NAMES = {"Speed", "Cadence", "Power", "Resistance"};
    private static final String[] UNITS = {"km/h", "rpm", "W", "level"};
    private static final int[] COLORS = {0xff69ccff, 0xffccff3f, 0xffffc46b, 0xffd7aaff};
    private final RideService service;
    private final WindowManager manager;
    private LinearLayout root;
    private TextView topHandle, bottomHandle, heading;
    private Graph graph;
    private boolean expanded = true, atBottom;
    private Button end;

    RideOverlay(RideService service) {
        this.service = service;
        manager = (WindowManager) service.getSystemService(RideService.WINDOW_SERVICE);
    }
    private int dp(int value) { return Math.round(value * service.getResources().getDisplayMetrics().density); }
    private WindowManager.LayoutParams window(int width, int height, boolean bottom) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = (bottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
        return params;
    }
    private GradientDrawable background(int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF219211B);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(1), 0xff526044);
        return background;
    }
    public void show() {
        if (root != null || topHandle != null) return;
        if (!expanded) { showHandles(); return; }
        root = new LinearLayout(service) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                ConsoleActivity.noteInteraction();
                return super.dispatchTouchEvent(event);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(4), dp(12), dp(10));
        root.setBackground(background(0));
        LinearLayout header = new LinearLayout(service);
        header.setGravity(Gravity.CENTER_VERTICAL);
        heading = new TextView(service);
        heading.setTextColor(0xffccff3f);
        heading.setTextSize(18);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heading.setFontFeatureSettings("tnum");
        heading.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(46), 1));
        header.addView(button("Open console", view -> service.openConsole()), new LinearLayout.LayoutParams(dp(135), dp(46)));
        header.addView(button("Dim screen", view -> service.startActivity(new Intent(service, DimActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))), new LinearLayout.LayoutParams(dp(125), dp(46)));
        end = button("End ride", view -> {
            BikeSession session = service.session();
            if (session != null && session.endRide()) end.setEnabled(false);
        });
        header.addView(end, new LinearLayout.LayoutParams(dp(115), dp(46)));
        Button fold = button(atBottom ? "↓" : "↑", view -> setExpanded(false, atBottom));
        fold.setTextSize(27);
        fold.setContentDescription("Hide ride stats to edge; keep recording");
        header.addView(fold, new LinearLayout.LayoutParams(dp(56), dp(46)));
        root.addView(header);
        graph = new Graph();
        root.addView(graph, new LinearLayout.LayoutParams(-1, dp(158)));
        try { manager.addView(root, window(-1, -2, atBottom)); }
        catch (RuntimeException error) { root = null; graph = null; throw error; }
        update();
    }
    private void setExpanded(boolean expanded, boolean bottom) {
        hide();
        this.expanded = expanded;
        atBottom = bottom;
        show();
    }
    private void showHandles() {
        topHandle = handle(false);
        bottomHandle = handle(true);
        try {
            manager.addView(topHandle, window(dp(220), dp(36), false));
            manager.addView(bottomHandle, window(dp(220), dp(36), true));
        } catch (RuntimeException error) { hide(); throw error; }
    }
    private TextView handle(boolean bottom) {
        TextView handle = new TextView(service);
        handle.setText(bottom ? "↑  Ride stats" : "↓  Ride stats");
        handle.setTextSize(13);
        handle.setTextColor(0xffccff3f);
        handle.setGravity(Gravity.CENTER);
        handle.setBackground(background(12));
        handle.setContentDescription(bottom ? "Swipe up or tap to show ride stats" : "Swipe down or tap to show ride stats");
        handle.setOnClickListener(view -> setExpanded(true, bottom));
        handle.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY;
            @Override public boolean onTouch(View view, MotionEvent event) {
                ConsoleActivity.noteInteraction();
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX(); startY = event.getRawY(); return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dy = event.getRawY() - startY;
                    if (Math.abs(dy) > dp(12) && Math.abs(dy) > Math.abs(event.getRawX() - startX)) {
                        setExpanded(true, bottom);
                    }
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    if (Math.abs(event.getRawX() - startX) < dp(12) && Math.abs(event.getRawY() - startY) < dp(12)) view.performClick();
                    return true;
                }
                return true;
            }
        });
        return handle;
    }
    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(service);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{0xff6c7a70, Color.WHITE}));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(listener);
        return button;
    }
    private void remove(View view) {
        if (view != null) try { manager.removeView(view); } catch (IllegalArgumentException ignored) { }
    }

    public void hide() {
        remove(root); remove(topHandle); remove(bottomHandle);
        root = null; graph = null; heading = null; topHandle = null; bottomHandle = null;
    }
    public void update() {
        if (graph == null) return;
        graph.invalidate();
        BikeSession.State state = service.state();
        end.setEnabled(service.session() != null && state != null && state.rideOwned);
        String title = service.history.latest() == null ? "RIDE TIME  00:00  •  Ready to ride"
                : (state != null && state.rideOwned ? "RIDE TIME  " : "LAST RIDE  ") + elapsed(service.history.duration());
        if (service.history.latest() != null) title += "  •  " + RideSummary.number(service.history.stats.distanceKm(), 2)
                + " km" + (service.history.stats.distanceComplete() ? "" : " recorded");
        if (service.session() == null) title += "  •  disconnected";
        else if (state == null || SystemClock.elapsedRealtime() - state.sampledAt > 3000) title += "  •  waiting for data";
        if (!title.contentEquals(heading.getText())) heading.setText(title);
        double[] values = currentValues();
        StringBuilder description = new StringBuilder("Ride stats and graphs from start. ");
        for (int i = 0; i < 4; i++) description.append(NAMES[i]).append(' ').append(format(values[i], i)).append(' ').append(UNITS[i]).append(". ");
        if (!description.toString().contentEquals(graph.getContentDescription() == null ? "" : graph.getContentDescription())) {
            graph.setContentDescription(description.toString());
        }
    }
    private double[] currentValues() {
        BikeSession.State state = service.state();
        long now = SystemClock.elapsedRealtime();
        boolean fresh = service.session() != null && state != null && state.running && state.sampledAt > 0
                && now >= state.sampledAt && now - state.sampledAt <= 3000;
        if (!fresh) return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        return new double[]{RideHistory.valid(state.values.get(16), 200), RideHistory.valid(state.values.get(5), 300),
                RideHistory.valid(state.values.get(3), 3000), state.observedGear != null && state.observedGear > 0
                && state.observedGear < 255 ? state.observedGear.doubleValue() : Double.NaN};
    }
    private static String format(double value, int metric) {
        return !Double.isFinite(value) ? "—" : String.format(Locale.US, metric == 0 ? "%.1f" : "%.0f", value);
    }
    private static String elapsed(long millis) { return RideSummary.elapsed(millis); }

    private final class Graph extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Graph() { super(service); }
        @Override protected void onDraw(Canvas canvas) {
            double[] values = currentValues();
            long duration = service.history.duration();
            float columnWidth = getWidth() / 4f;
            for (int metric = 0; metric < 4; metric++) {
                float left = metric * columnWidth + dp(12), right = (metric + 1) * columnWidth - dp(24);
                paint.setColor(COLORS[metric]);
                paint.setTextSize(dp(13));
                canvas.drawText(NAMES[metric].toUpperCase(Locale.US), left, dp(16), paint);
                paint.setTextSize(dp(32));
                String value = format(values[metric], metric);
                canvas.drawText(value, left, dp(54), paint);
                float valueWidth = paint.measureText(value);
                paint.setTextSize(dp(14));
                canvas.drawText(UNITS[metric], left + valueWidth + dp(9), dp(53), paint);
                double max = 1;
                boolean hasData = false;
                for (RideHistory.Point point : service.history.points()) if (Double.isFinite(point.values[metric])) {
                    max = Math.max(max, point.values[metric]); hasData = true;
                }
                paint.setTextSize(dp(11));
                paint.setColor(0xffb7c4b9);
                String scale = hasData ? "0–" + format(max, metric) : "No ride data yet";
                canvas.drawText(scale, right - paint.measureText(scale), dp(16), paint);
                paint.setColor(0xff344337);
                paint.setStrokeWidth(dp(1));
                canvas.drawLine(left, dp(126), right, dp(126), paint);
                if (metric > 0) canvas.drawLine(metric * columnWidth - dp(8), dp(8), metric * columnWidth - dp(8), dp(146), paint);
                Path path = new Path();
                boolean connected = false;
                float lastX = 0, lastY = 0;
                for (RideHistory.Point point : service.history.points()) {
                    double n = point.values[metric];
                    if (!Double.isFinite(n)) { connected = false; continue; }
                    float px = left + (float)(point.time / (double)Math.max(1, duration)) * (right - left);
                    float py = dp(124) - (float)(n / max) * dp(55);
                    if (!connected || (point.breakMask & (1 << metric)) != 0) path.moveTo(px, py);
                    else path.lineTo(px, py);
                    connected = true; lastX = px; lastY = py;
                }
                paint.setColor(COLORS[metric]);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                if (hasData) canvas.drawCircle(lastX, lastY, dp(2), paint);
                paint.setColor(0xffb7c4b9);
                paint.setTextSize(dp(11));
                canvas.drawText("0:00", left, dp(146), paint);
                String endTime = elapsed(duration);
                canvas.drawText(endTime, right - paint.measureText(endTime), dp(146), paint);
            }
        }
    }
}
