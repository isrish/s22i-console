package dev.s22i.console;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import java.io.IOException;

public final class AppPrivacy {
    private AppPrivacy() { }
    public static boolean debug(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
    public static boolean diagnostics(Context context) {
        return debug(context) && context.getSharedPreferences("privacy", 0).getBoolean("diagnostics", false);
    }
    public static boolean showDate(Context context) {
        return context.getSharedPreferences("privacy", 0).getBoolean("show_summary_date", false);
    }
    public static void setShowDate(Context context, boolean show) {
        context.getSharedPreferences("privacy", 0).edit().putBoolean("show_summary_date", show).apply();
    }
    public static void initialize(Context context) {
        if (!debug(context)) context.getSharedPreferences("privacy", 0).edit().putBoolean("diagnostics", false).apply();
        if (!diagnostics(context)) try { PrivateFiles.deleteDiagnostics(context.getFilesDir()); }
        catch (IOException error) { Log.w("S22iPrivacy", "Could not remove legacy diagnostics"); }
    }
}
