package dev.s22i.console;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.Typeface;

public final class AutoDimSettings {
    private static final int[] MINUTES = {1, 5, 10, 15, 30, 60, 120, 0};
    private static final String[] LABELS = {"1 minute", "5 minutes", "10 minutes", "15 minutes",
            "30 minutes (default)", "1 hour", "2 hours", "Never"};
    private AutoDimSettings() { }
    public static int minutes(Context context) {
        int value = context.getSharedPreferences("display", 0).getInt("auto_dim_minutes", 30);
        for (int allowed : MINUTES) if (value == allowed) return value;
        return 30;
    }
    public static void show(Activity activity) {
        int current = minutes(activity), selected = 4;
        for (int i = 0; i < MINUTES.length; i++) if (MINUTES[i] == current) selected = i;
        float density = activity.getResources().getDisplayMetrics().density;
        int padding = Math.round(24 * density);
        LinearLayout title = new LinearLayout(activity);
        title.setOrientation(LinearLayout.VERTICAL);
        title.setPadding(padding, padding, padding, Math.round(12 * density));
        TextView heading = new TextView(activity);
        heading.setText("Settings · Auto-dim"); heading.setTextSize(24);
        heading.setTextColor(0xffccff3f); heading.setTypeface(Typeface.DEFAULT_BOLD);
        title.addView(heading);
        TextView description = new TextView(activity);
        description.setText("Dim the idle console after this delay. Active rides and other apps stay visible. Tap the dimmed screen to wake.");
        description.setTextColor(Color.LTGRAY); description.setTextSize(16);
        description.setPadding(0, Math.round(12 * density), 0, 0);
        title.addView(description);
        new AlertDialog.Builder(activity).setCustomTitle(title)
                .setSingleChoiceItems(LABELS, selected, (dialog, which) -> {
                    activity.getSharedPreferences("display", 0).edit()
                            .putInt("auto_dim_minutes", MINUTES[which]).apply();
                    ConsoleActivity.noteInteraction();
                    dialog.dismiss();
                }).setNegativeButton("Close", null).show();
    }
}
