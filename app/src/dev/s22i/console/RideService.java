package dev.s22i.console;

import android.app.*;
import android.content.*;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import java.io.*;
import java.util.ArrayList;

public final class RideService extends Service {
    private static final String CHANNEL = "ride";
    private static final int NOTIFICATION = 22;
    private static final String KEEP = "dev.s22i.console.KEEP";
    private static final String END = "dev.s22i.console.END";
    private static final String DISCONNECT = "dev.s22i.console.DISCONNECT";
    private static RideService instance;
    static boolean dimmed;
    static int summaryWindows;
    private long rideStartedAt;
    final RideHistory history = new RideHistory();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Runnable> listeners = new ArrayList<>();
    private final LocalBinder binder = new LocalBinder();
    private BikeSession session;
    private BikeSession.State state;
    private RideOverlay overlay;
    private PowerManager.WakeLock wake;
    private boolean closing, foreground, recording, overlayEnabled, destroyed;
    private BufferedWriter historyFile;
    private String notificationText = "";
    public final class LocalBinder extends Binder { RideService service() { return RideService.this; } }
    public BikeSession session() { return closing ? null : session; }
    public BikeSession.State state() { return state; }
    public boolean isClosing() { return closing; }
    public boolean overlayEnabled() { return overlayEnabled; }
    static boolean bikeInUse() {
        BikeSession.State current = instance == null ? null : instance.state;
        if (current == null) return false;
        if (current.rideOwned) return true;
        Integer mode = current.controllerMode;
        if (mode != null && (mode == 2 || mode == 3 || mode == 10)) return true;
        Double cadence = current.values.get(5);
        return cadence != null && cadence > 0;
    }
    public void addListener(Runnable listener) { if (!listeners.contains(listener)) listeners.add(listener); listener.run(); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
    private void changed() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
    public boolean canDeleteSavedRides() {
        return PrivateFiles.mayDelete(recording, closing, bikeInUse(),
                getSharedPreferences("ride", 0).getBoolean("owned", false),
                getSharedPreferences("history", 0).getBoolean("active", false));
    }
    public void deleteSavedRides() throws IOException {
        if (!canDeleteSavedRides()) throw new IOException("End the ride and finish recovery before deleting saved rides.");
        closeHistory();
        PrivateFiles.deleteRides(getFilesDir());
        history.clear(); rideStartedAt = 0;

        getSharedPreferences("history", 0).edit().remove("startedAt").remove("active").apply();
        updateOverlay(); changed();
    }

    @Override public void onCreate() {
        super.onCreate();
        AppPrivacy.initialize(this);
        instance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Bike ride", NotificationManager.IMPORTANCE_LOW));
        wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "S22i:ride");
        wake.setReferenceCounted(false);
        overlay = new RideOverlay(this);

        boolean loadedHistory = false;
        try (BufferedReader reader = new BufferedReader(new FileReader(new File(getFilesDir(), "ride-history.csv")))) {
            String line; while ((line = reader.readLine()) != null) history.restoreLine(line);
            loadedHistory = true;
        } catch (FileNotFoundException ignored) { }
        catch (IOException error) { Log.w("S22iRide", "Could not restore ride history"); }

        android.content.SharedPreferences saved = getSharedPreferences("history", 0);
        if (loadedHistory && history.latest() != null && saved.contains("active") && !saved.getBoolean("active", true)
                && !getSharedPreferences("ride", 0).getBoolean("owned", false) && !RideSummary.exists(this)) {
            try { RideSummary.from(history, saved.getLong("startedAt", 0)).save(this); }
            catch (Exception error) { Log.w("S22iRide", "Could not summarize previous recording"); }
        }
        handler.post(tick);
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        ensureForeground();
        if (intent != null && END.equals(intent.getAction()) && session() != null) session.endRide();
        if (intent != null && DISCONNECT.equals(intent.getAction())) disconnect();
        return START_NOT_STICKY;
    }
    private void keepStarted() {
        startForegroundService(new Intent(this, RideService.class).setAction(KEEP));
        ensureForeground();
    }
    private void ensureForeground() {
        foreground = true;
        startForeground(NOTIFICATION, notification());
    }
    public boolean connect(UsbDevice device) {
        if (session != null || closing || device == null || device.getVendorId() != 0x213c || device.getProductId() != 2) return false;
        UsbManager usb = (UsbManager)getSystemService(USB_SERVICE);
        if (usb == null || !usb.hasPermission(device)) return false;
        keepStarted();
        wake.acquire();
        state = null;
        final BikeSession[] owner = new BikeSession[1];
        owner[0] = new BikeSession(this, device, snapshot -> {
            if (destroyed || session != owner[0]) return;
            state = snapshot;
            record(snapshot);
            if (!snapshot.running) {
                session = null;
                closing = false;
                if (wake.isHeld()) wake.release();
            }
            updateOverlay();
            updateNotification();
            changed();
            stopIfIdle();
        });
        session = owner[0];
        session.start();
        changed();
        return true;
    }
    public void disconnect() {
        if (session != null && !closing) {
            closing = true;
            session.stop();
            changed();
            updateNotification();
            updateOverlay();
        }
        stopIfIdle();
    }
    public void setOverlay(boolean enabled) {
        if (enabled && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow display over other apps to show ride stats.", Toast.LENGTH_LONG).show();
            return;
        }
        overlayEnabled = enabled;
        if (enabled) keepStarted();
        updateOverlay();
        changed();
        stopIfIdle();
    }
    static void refreshOverlay() { if (instance != null) instance.updateOverlay(); }
    private void updateOverlay() {
        if (overlayEnabled && !Settings.canDrawOverlays(this)) {
            overlayEnabled = false;
            changed();
        }
        try {
            if (overlayEnabled && !dimmed && summaryWindows == 0) overlay.show(); else overlay.hide();
            overlay.update();
        } catch (RuntimeException error) {
            Log.w("S22iRide", "Overlay could not be displayed");
            overlayEnabled = false;
            overlay.hide();
            Toast.makeText(this, "Ride overlay unavailable. Check display-over-apps permission.", Toast.LENGTH_LONG).show();
            changed();
        }
    }
    public void openConsole() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
    private PendingIntent action(String action, int id) {
        return PendingIntent.getService(this, id, new Intent(this, RideService.class).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification() {
        String text = closing ? "Ending ride and disconnecting…" : session == null
                ? (state != null && state.rideOwned ? "Reconnect to check ride shutdown" : "Disconnected · last ride stats available")
                : state != null && state.rideOwned ? "Ride continues in other apps" : "Bike connected · ready to ride";
        notificationText = text;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_ride).setContentTitle("S22i Console").setContentText(text)
                .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        if (session() != null && state != null && state.rideOwned) builder.addAction(new Notification.Action.Builder(null, "End ride", action(END, 1)).build());
        if (session != null) builder.addAction(new Notification.Action.Builder(null, "Disconnect", action(DISCONNECT, 2)).build());
        return builder.build();
    }
    private void updateNotification() {
        if (!foreground) return;
        String before = notificationText;
        Notification notification = notification();
        if (!before.equals(notificationText)) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification);
    }
    private void stopIfIdle() {
        if (session == null && !overlayEnabled && (state == null || !state.rideOwned)) {
            if (foreground) { stopForeground(true); foreground = false; }
            stopSelf();
        }
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateOverlay();
            stopIfIdle();
            handler.postDelayed(this, 1000);
        }
    };
    private void record(BikeSession.State snapshot) {
        if (!snapshot.running || !snapshot.rideOwned) {
            boolean completed = recording && !snapshot.rideOwned && history.latest() != null;
            if (recording) { recording = false; closeHistory(); }
            if (completed) {
                try {
                    RideSummary.from(history, rideStartedAt).save(this);
                    startActivity(new Intent(this, RideSummaryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                } catch (Exception error) {
                    Log.w("S22iRide", "Could not save or open ride summary");
                    Toast.makeText(this, "Could not open ride summary. Ride history is still recorded.", Toast.LENGTH_LONG).show();
                }
            }
            if (!snapshot.rideOwned) getSharedPreferences("history", 0).edit().putBoolean("active", false).apply();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (snapshot.sampledAt <= 0 || now - snapshot.sampledAt > 3000) return;
        if (!recording && Integer.valueOf(2).equals(snapshot.controllerMode)) {
            boolean recover = getSharedPreferences("history", 0).getBoolean("active", false) && history.latest() != null;
            if (recover) history.resume(snapshot.sampledAt); else history.reset(snapshot.sampledAt);
            rideStartedAt = recover ? getSharedPreferences("history", 0).getLong("startedAt", 0) : System.currentTimeMillis();
            getSharedPreferences("history", 0).edit().putLong("startedAt", rideStartedAt).apply();
            recording = true;
            getSharedPreferences("history", 0).edit().putBoolean("active", true).apply();
            try {
                historyFile = new BufferedWriter(new FileWriter(new File(getFilesDir(), "ride-history.csv"), recover));
                if (!recover) historyFile.write("elapsed_ms,speed_kph,cadence_rpm,power_w,resistance_level,gap_before\n");
            } catch (IOException error) { historyError(error); }
        }
        if (recording && history.add(snapshot.sampledAt, now, snapshot.values.get(16), snapshot.values.get(5), snapshot.values.get(3), snapshot.observedGear)) {
            if (historyFile != null) try { historyFile.write(history.csvLatest()); historyFile.flush(); }
            catch (IOException error) { historyError(error); }
        }
    }
    private void historyError(IOException error) {
        Log.w("S22iRide", "History remains available in memory; file write failed");
        closeHistory();
        Toast.makeText(this, "Could not save ride history. Live stats are still available.", Toast.LENGTH_LONG).show();
    }
    private void closeHistory() {
        if (historyFile != null) try { historyFile.close(); } catch (IOException error) { Log.w("S22iRide", "History close failed"); }
        historyFile = null;
    }
    @Override public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (session != null) {

            if (wake.isHeld()) wake.acquire(10000);
            session.stop();
        } else if (wake.isHeld()) wake.release();
        closeHistory();
        overlay.hide();
        listeners.clear();
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
