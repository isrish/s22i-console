package dev.s22i.console;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;
import java.io.IOException;
import java.util.ArrayList;

public final class ConsoleSettings {
    private ConsoleSettings() { }
    public static void show(Activity activity, RideService service) {
        ArrayList<String> items = new ArrayList<>();
        int minutes = AutoDimSettings.minutes(activity);
        items.add("Auto-dim: " + (minutes == 0 ? "Never" : minutes + " minutes"));
        items.add("Summary date/time: " + (AppPrivacy.showDate(activity) ? "Shown" : "Hidden"));
        items.add("Delete saved rides…");
        if (AppPrivacy.debug(activity)) items.add("Private diagnostic recording: " + (AppPrivacy.diagnostics(activity) ? "On" : "Off"));
        new AlertDialog.Builder(activity).setTitle("Settings")
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) AutoDimSettings.show(activity);
                    else if (which == 1) dateSetting(activity);
                    else if (which == 2) confirmDelete(activity, service);
                    else diagnosticSetting(activity, service);
                }).setNegativeButton("Close", null).show();
    }
    private static void dateSetting(Activity activity) {
        new AlertDialog.Builder(activity).setTitle("Summary privacy")
                .setMessage("Choose whether to include the ride date and start time on the summary and in photos. Your ride stats remain visible.")
                .setPositiveButton("Hide date/time", (d, w) -> AppPrivacy.setShowDate(activity, false))
                .setNeutralButton("Show date/time", (d, w) -> AppPrivacy.setShowDate(activity, true))
                .setNegativeButton("Cancel", null).show();
    }
    private static void confirmDelete(Activity activity, RideService service) {
        if (service == null || !service.canDeleteSavedRides()) {
            Toast.makeText(activity, "End the ride and finish recovery before deleting saved rides.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(activity).setTitle("Delete saved rides?")
                .setMessage("Permanently remove this app’s saved ride history, graphs and summary. Screenshots, diagnostic captures and copies on your computer are not removed. Bike settings and recovery information are kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete saved rides", (d, w) -> {
                    try {
                        service.deleteSavedRides();
                        Toast.makeText(activity, "Saved rides deleted", Toast.LENGTH_SHORT).show();
                    } catch (IOException error) {
                        Toast.makeText(activity, "Could not finish deleting saved rides. Check that the ride has ended and try again.", Toast.LENGTH_LONG).show();
                    }
                }).show();
    }
    private static void diagnosticSetting(Activity activity, RideService service) {
        if (!AppPrivacy.debug(activity)) return;
        if (service == null || service.session() != null || service.isClosing()) {
            Toast.makeText(activity, "Disconnect the bike before changing diagnostic recording.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean enabled = AppPrivacy.diagnostics(activity);
        new AlertDialog.Builder(activity).setTitle("Private diagnostic recording")
                .setMessage("Debug builds only. Records ride readings and redacted protocol traces inside the app for troubleshooting. Turning it off deletes the on-device diagnostic files.")
                .setPositiveButton(enabled ? "Turn off and delete" : "Enable recording", (d, w) -> {

                    if (service.session() != null || service.isClosing()) return;
                    activity.getSharedPreferences("privacy", 0).edit().putBoolean("diagnostics", !enabled).apply();
                    AppPrivacy.initialize(activity);
                }).setNegativeButton("Cancel", null).show();
    }
}
