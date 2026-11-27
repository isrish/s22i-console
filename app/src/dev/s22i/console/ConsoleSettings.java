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
        items.add("Resistance test range: 1–" + (service != null && service.state() != null ? service.state().gearLimit : 2));
        if (AppPrivacy.debug(activity)) items.add("Private diagnostic recording: " + (AppPrivacy.diagnostics(activity) ? "On" : "Off"));
        new AlertDialog.Builder(activity).setTitle("Settings")
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) AutoDimSettings.show(activity);
                    else if (which == 1) dateSetting(activity);
                    else if (which == 2) confirmDelete(activity, service);
                    else if (which == 3) resistanceTest(activity, service);
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
    private static void resistanceTest(Activity activity, RideService service) {
        BikeSession session = service == null ? null : service.session();
        BikeSession.State state = service == null ? null : service.state();
        if (session == null || state == null || !state.running || state.rideOwned || state.controlPending
                || state.controllerMode == null || state.controllerMode != 1) {
            Toast.makeText(activity, "Connect the bike and end the ride before selecting a test range.", Toast.LENGTH_LONG).show();
            return;
        }
        int[] limits = {2, 3, 8, 16, 24};
        String[] names = {"1–2: default", "1–3: first test", "1–8: lower range", "1–16: middle range", "1–24: full range"};
        new AlertDialog.Builder(activity).setTitle("Supervised resistance test")
                .setItems(names, (d, which) -> {
                    int limit = limits[which];
                    new AlertDialog.Builder(activity).setTitle("Use levels 1–" + limit + "?")
                            .setMessage("Levels above 2 are being validated. Stay beside the bike, stop pedaling to select the range, then test one level per tap. Wait for each confirmation and end the ride if anything feels wrong. This setting resets when the bike connection closes.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Use test range", (confirm, button) -> {
                                boolean queued = service.session() == session && session.configureResistanceTest(limit);
                                Toast.makeText(activity, queued ? "Checking the idle controller before enabling the range…"
                                        : "Range not changed. End the ride and wait for the current action to finish.", Toast.LENGTH_LONG).show();
                            }).show();
                }).setNegativeButton("Cancel", null).show();
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
