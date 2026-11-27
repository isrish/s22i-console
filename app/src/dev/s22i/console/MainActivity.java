package dev.s22i.console;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.net.Uri;
import android.provider.Settings;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends ConsoleActivity {
    private static final int BACKGROUND = Color.rgb(13, 17, 15);
    private static final int SURFACE = Color.rgb(23, 29, 25);
    private static final int BORDER = Color.rgb(48, 57, 50);
    private static final int WHITE = Color.rgb(244, 247, 240);
    private static final int MUTED = Color.rgb(163, 175, 165);
    private static final int LIME = Color.rgb(204, 255, 63);
    private static final int AMBER = Color.rgb(244, 194, 119);
    private static final int VENDOR_ID = 0x213c;
    private static final int PRODUCT_ID = 0x0002;
    private static final String USB_PERMISSION = "dev.s22i.console.USB_PERMISSION";
    private static final String USB_RESULT_PERMISSION = "dev.s22i.console.USB_RESULT";
    private final String usbPermissionAction = USB_PERMISSION + "." + java.util.UUID.randomUUID();
    private BikeSession session;
    private RideService rideService;
    private boolean bound, connectOnBind, overlayPermissionPending;
    private Button overlayButton;
    private TextView distanceValue;
    private TextView rideTimeValue, rideTimeCaption;
    private TextView gearLimitHint;
    private Button summaryButton;
    private final Runnable serviceChanged = () -> {
        if (!this.foreground || rideService == null) return;
        session = rideService.session();
        if (rideService.state() != null) showSessionState(rideService.state());
        if (rideService.isClosing()) {
            clearMetrics();
            this.enableControlsButton.setEnabled(false);
            showStatus("Disconnecting", "Ending this app's ride and releasing the controller…", false);
        }
        if (distanceValue != null) {
            RideStats stats = rideService.history.stats;
            setLabel(distanceValue, (rideService.state() != null && rideService.state().rideOwned ? "DISTANCE  " : "LAST RIDE  ")
                    + RideSummary.number(stats.distanceKm(), 2) + " km"
                    + (rideService.history.latest() != null && !stats.distanceComplete() ? "  · partial" : ""));
        }
        if (summaryButton != null) setLabel(summaryButton, RideSummary.exists(this) ? "Last ride summary" : "Preview summary");
        if (rideTimeValue != null) {
            boolean active = rideService.state() != null && rideService.state().rideOwned;
            setLabel(rideTimeCaption, !active && rideService.history.latest() != null ? "LAST RIDE TIME" : "RIDE TIME");
            setLabel(rideTimeValue, RideSummary.elapsed(rideService.history.duration()));
        }
        updateButtons();
    };
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            rideService = ((RideService.LocalBinder) binder).service();
            rideService.addListener(serviceChanged);
            checkOverlayPermission();
            if (connectOnBind) { connectOnBind = false; connectTelemetry(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            rideService = null; session = null; clearMetrics(); updateButtons();
            showStatus("Ride service unavailable", "Reconnect to check the bike's ride state.", false);
        }
    };
    private long lastSampleAt;
    private final Handler freshnessHandler = new Handler(Looper.getMainLooper());
    private final Runnable freshnessCheck = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            if (session != null && lastSampleAt > 0 && SystemClock.elapsedRealtime() - lastSampleAt > 3000) {
                clearMetrics();
                metricCaption.setText("Waiting for fresh data");
                showStatus("Telemetry delayed", "Previous readings have been cleared while the bike responds.", false);
                controlStatus.setText(rideOwned
                        ? "Readings are delayed. Adjustments are off; End ride is available."
                        : "Readings are delayed. Start ride and manual adjustments are unavailable.");
            }
            freshnessHandler.postDelayed(this, 1000);
        }
    };
    private boolean foreground;
    private boolean permissionPending;
    private boolean receiverRegistered;
    private UsbDevice permissionDevice;
    private Button connectButton;
    private Button refreshButton;
    private LinearLayout statusPanel;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView metricCaption;
    private TextView rawResistanceValue;
    private TextView controllerDiagnosticValue;
    private TextView rawGearValue;
    private TextView gearStatus;
    private TextView sessionDetails;
    private Button enableControlsButton;
    private TextView controlStatus;
    private TextView rideTitle;
    private View statusDot;
    private LinearLayout diagnostics;
    private Button detailsButton;
    private boolean compact;
    private boolean statusConnected;
    private final Button[] controlButtons = new Button[4];
    private boolean rideOwned;
    private final TextView[] metricValues = new TextView[4];
    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!usbPermissionAction.equals(intent.getAction()) || !permissionPending) return;
            UsbDevice returned;
            boolean granted;
            try {
                android.os.Parcelable extra = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (!(extra instanceof UsbDevice)) return;
                returned = (UsbDevice) extra;
                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            } catch (RuntimeException malformed) { return; }
            if (returned == null || permissionDevice == null
                    || !returned.getDeviceName().equals(permissionDevice.getDeviceName())) return;
            permissionPending = false;
            permissionDevice = null;
            updateButtons();
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (granted && manager != null && manager.hasPermission(returned)) {

                connectOnBind = true;
                if (foreground && rideService != null) {
                    connectOnBind = false;
                    startSession(returned);
                }
            } else {
                showStatus("USB permission not granted", "Tap Connect bike to request access again.", false);
                clearMetrics();
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setNavigationBarColor(BACKGROUND);
        render();
        IntentFilter filter = new IntentFilter(usbPermissionAction);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, USB_RESULT_PERMISSION, null, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(permissionReceiver, filter, USB_RESULT_PERMISSION, null);
        receiverRegistered = true;

    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        bound = bindService(new Intent(this, RideService.class), serviceConnection, Context.BIND_AUTO_CREATE);
        freshnessHandler.postDelayed(freshnessCheck, 1000);
    }

    @Override protected void onResume() {
        super.onResume();
        checkOverlayPermission();
    }

    @Override protected void onStop() {
        foreground = false;
        freshnessHandler.removeCallbacks(freshnessCheck);
        if (rideService != null) rideService.removeListener(serviceChanged);
        if (bound) { unbindService(serviceConnection); bound = false; }
        rideService = null;
        session = null;
        super.onStop();
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(permissionReceiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    private void toggleOverlay() {
        if (rideService == null) return;
        if (rideService.overlayEnabled()) { rideService.setOverlay(false); return; }
        if (Settings.canDrawOverlays(this)) rideService.setOverlay(true);
        else {
            overlayPermissionPending = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }
    }

    private void checkOverlayPermission() {
        if (overlayPermissionPending && rideService != null) {
            overlayPermissionPending = false;
            if (Settings.canDrawOverlays(this)) rideService.setOverlay(true);
        }
    }

    private void render() {
        Snapshot snapshot = inspect();
        compact = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density < 1100;
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout body = column();

        body.setPadding(dp(compact ? 28 : 56), dp(30), dp(compact ? 28 : 56), dp(100));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_launcher_foreground);
        mark.setBackground(panel(SURFACE, BORDER, 18));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(mark, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout brand = column();
        brand.setPadding(dp(16), 0, 0, 0);
        brand.addView(label("S22i", 30, WHITE, true));
        TextView brandCaption = eyebrow("CONSOLE", MUTED);
        brandCaption.setPadding(0, dp(4), 0, 0);
        brand.addView(brandCaption);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));

        statusPanel = row();
        statusPanel.setGravity(Gravity.CENTER_VERTICAL);
        statusPanel.setPadding(dp(18), dp(12), dp(18), dp(12));
        statusConnected = false;
        statusDot = new View(this);
        statusDot.setBackground(panel(MUTED, MUTED, 5));
        statusPanel.addView(statusDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        statusTitle = label(snapshot.device != null ? "Bike detected" : "Bike not detected", 16, MUTED, false);
        statusTitle.setPadding(dp(10), 0, 0, 0);
        statusTitle.setMaxWidth(dp(350));
        statusPanel.addView(statusTitle);
        if (!compact) header.addView(statusPanel, new LinearLayout.LayoutParams(-2, -2));
        connectButton = actionButton("Connect bike", false);
        connectButton.setOnClickListener(view -> {
            if (session != null) disconnectTelemetry();
            else connectTelemetry();
        });
        LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(dp(compact ? 180 : 205), dp(58));
        connectionParams.leftMargin = dp(14);
        header.addView(connectButton, connectionParams);
        detailsButton = actionButton("Details  +", false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(dp(130), dp(58));
        detailParams.leftMargin = dp(12);
        header.addView(detailsButton, detailParams);
        body.addView(header);
        if (compact) {
            spacer(body, 12);
            body.addView(statusPanel);
        }
        overlayButton = actionButton("Show ride overlay", false);
        overlayButton.setOnClickListener(view -> toggleOverlay());
        Button dimButton = actionButton("Dim screen", false);
        dimButton.setContentDescription("Dim screen; tap anywhere to wake");
        dimButton.setOnClickListener(view -> startActivity(new Intent(this, DimActivity.class)));
        LinearLayout utilities = compact ? row() : header;
        LinearLayout.LayoutParams overlayParams = new LinearLayout.LayoutParams(dp(220), dp(58));
        overlayParams.leftMargin = dp(12);
        utilities.addView(overlayButton, overlayParams);
        LinearLayout.LayoutParams dimParams = new LinearLayout.LayoutParams(dp(160), dp(58));
        dimParams.leftMargin = dp(12);
        utilities.addView(dimButton, dimParams);
        Button settingsButton = actionButton("Settings", false);
        settingsButton.setOnClickListener(view -> ConsoleSettings.show(this, rideService));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(135), dp(58));
        settingsParams.leftMargin = dp(12);
        utilities.addView(settingsButton, settingsParams);
        if (compact) {
            spacer(body, 18);
            utilities.setGravity(Gravity.RIGHT);
            body.addView(utilities);
        }
        spacer(body, 30);

        LinearLayout intro = row();
        intro.setGravity(Gravity.BOTTOM);
        LinearLayout introText = column();
        introText.addView(eyebrow("MANUAL RIDE", LIME));
        TextView headline = label("Your ride. Your rhythm.", compact ? 34 : 44, WHITE, false);
        headline.setLetterSpacing(-0.035f);
        headline.setPadding(0, dp(7), 0, 0);
        introText.addView(headline);
        intro.addView(introText, new LinearLayout.LayoutParams(0, -2, 1));
        summaryButton = actionButton(RideSummary.exists(this) ? "Last ride summary" : "Preview summary", false);
        summaryButton.setOnClickListener(view -> startActivity(new Intent(this, RideSummaryActivity.class)
                .putExtra("preview", !RideSummary.exists(this))));
        intro.addView(summaryButton, new LinearLayout.LayoutParams(dp(220), dp(54)));
        body.addView(intro);
        spacer(body, 26);

        LinearLayout dashboard = compact ? column() : row();
        FrameLayout performance = performanceCard();
        LinearLayout.LayoutParams performanceParams = compact
                ? new LinearLayout.LayoutParams(-1, dp(410))
                : new LinearLayout.LayoutParams(0, dp(480), 1.55f);
        dashboard.addView(performance, performanceParams);
        LinearLayout adjustments = column();
        adjustments.addView(adjustmentCard(true), new LinearLayout.LayoutParams(-1, dp(230)));
        spacer(adjustments, 20);
        adjustments.addView(adjustmentCard(false), new LinearLayout.LayoutParams(-1, dp(230)));
        LinearLayout.LayoutParams adjustmentsParams = compact
                ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 1);
        if (compact) adjustmentsParams.topMargin = dp(20);
        else adjustmentsParams.leftMargin = dp(24);
        dashboard.addView(adjustments, adjustmentsParams);
        body.addView(dashboard);
        spacer(body, 24);

        LinearLayout rideBar = row();
        rideBar.setGravity(Gravity.CENTER_VERTICAL);
        rideBar.setPadding(dp(28), dp(22), dp(24), dp(22));
        rideBar.setBackground(panel(SURFACE, BORDER, 24));
        LinearLayout guidance = column();
        guidance.setPadding(0, 0, dp(32), 0);
        rideTitle = label("Ready when you are", 23, WHITE, true);
        guidance.addView(rideTitle);
        controlStatus = label("Connect your bike, then start a ride to unlock the controls.", 17, MUTED, false);
        controlStatus.setPadding(0, dp(8), 0, 0);
        guidance.addView(controlStatus);
        rideBar.addView(guidance, new LinearLayout.LayoutParams(0, -2, 1));
        enableControlsButton = actionButton("Start ride", true);
        enableControlsButton.setTextSize(23);
        enableControlsButton.setContentDescription("Start ride; when a ride is active this becomes End ride");
        enableControlsButton.setOnClickListener(view -> {
            boolean ending = rideOwned;
            boolean accepted = session != null && (ending ? session.endRide() : session.startRide());
            if (accepted) {
                setControlButtons(false);
                enableControlsButton.setEnabled(false);
                controlStatus.setText(ending ? "Ending the ride…" : "Starting the bike's ride mode…");
            } else {
                controlStatus.setText(session == null
                        ? (ending ? "Reconnect telemetry to retry End ride." : "Connect bike before starting a ride.")
                        : "The bike is busy or unavailable. Try again when the current action finishes.");
            }
        });
        rideBar.addView(enableControlsButton, new LinearLayout.LayoutParams(dp(compact ? 200 : 300), dp(82)));
        body.addView(rideBar, new LinearLayout.LayoutParams(-1, -2));
        setControlButtons(false);
        enableControlsButton.setEnabled(false);
        spacer(body, 20);

        LinearLayout footer = row();
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(label("Ride continues in other apps  ·  End ride or Disconnect to stop", 14, MUTED, false),
                new LinearLayout.LayoutParams(0, -2, 1));
        footer.addView(label("S22i / " + appVersion(), 14, MUTED, false));
        body.addView(footer);

        diagnostics = column();
        diagnostics.setVisibility(View.GONE);
        spacer(diagnostics, 28);
        LinearLayout diagnosticHeading = row();
        diagnosticHeading.setGravity(Gravity.CENTER_VERTICAL);
        diagnosticHeading.addView(eyebrow("DEVICE DETAILS", MUTED), new LinearLayout.LayoutParams(0, -2, 1));
        refreshButton = actionButton("Refresh details", false);
        refreshButton.setContentDescription("Refresh tablet and USB device information while disconnected");
        refreshButton.setOnClickListener(view -> {
            render();
            diagnostics.setVisibility(View.VISIBLE);
            detailsButton.setText("Details  −");
        });
        diagnosticHeading.addView(refreshButton, new LinearLayout.LayoutParams(dp(190), dp(54)));
        Button closeDetails = actionButton("Back to ride", false);
        closeDetails.setOnClickListener(view -> {
            diagnostics.setVisibility(View.GONE);
            detailsButton.setText("Details  +");
            scroll.smoothScrollTo(0, 0);
        });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(180), dp(54));
        closeParams.leftMargin = dp(12);
        diagnosticHeading.addView(closeDetails, closeParams);
        diagnostics.addView(diagnosticHeading);
        spacer(diagnostics, 16);
        statusDetail = label(snapshot.error != null ? snapshot.error : snapshot.device != null
                ? "Bike controller detected. Connect bike to read live data."
                : "Expected controller 213c:0002. Refresh to check the USB device list.", 17, MUTED, false);
        diagnostics.addView(statusDetail);
        spacer(diagnostics, 16);
        LinearLayout details = compact ? column() : row();
        LinearLayout controller = detailCard("CONTROLLER");
        controllerDiagnosticValue = label("No telemetry session", 17, WHITE, false);
        controllerDiagnosticValue.setPadding(0, dp(12), 0, 0);
        controller.addView(controllerDiagnosticValue);
        rawGearValue = label("Reported resistance level: —", 15, MUTED, false);
        rawGearValue.setPadding(0, dp(10), 0, 0);
        controller.addView(rawGearValue);
        if (snapshot.device != null) {
            field(controller, "USB identity", "213c:0002  ·  ICON Fitness");
            field(controller, "Device", nonEmpty(snapshot.device.getProductName(), "USB HID controller"));
            field(controller, "Interface", snapshot.interfaces);
            TextView endpoints = label(snapshot.endpoints, 16, MUTED, false);
            endpoints.setTypeface(Typeface.MONOSPACE);
            endpoints.setPadding(0, dp(8), 0, 0);
            controller.addView(endpoints);
        } else {
            field(controller, "USB identity", "Waiting for 213c:0002");
            field(controller, "Android USB devices visible", String.valueOf(snapshot.deviceCount));
        }
        rawResistanceValue = label("Raw resistance: —", 16, MUTED, false);
        rawResistanceValue.setPadding(0, dp(12), 0, 0);
        controller.addView(rawResistanceValue);
        sessionDetails = label("No telemetry session", 15, MUTED, false);
        sessionDetails.setPadding(0, dp(12), 0, 0);
        controller.addView(sessionDetails);
        LinearLayout tablet = detailCard("TABLET");
        field(tablet, "Model", Build.MODEL);
        field(tablet, "Android", Build.VERSION.RELEASE + "  ·  API " + Build.VERSION.SDK_INT);
        DisplayMetrics display = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(display);
        field(tablet, "Display / architecture", display.widthPixels + " × " + display.heightPixels + "  ·  "
                + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "Unknown ABI"));
        field(tablet, "System build", Build.DISPLAY);
        details.addView(controller, compact ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams tabletParams = compact ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -1, 1);
        if (compact) tabletParams.topMargin = dp(20);
        else tabletParams.leftMargin = dp(20);
        details.addView(tablet, tabletParams);
        diagnostics.addView(details);
        body.addView(diagnostics);
        detailsButton.setOnClickListener(view -> {
            boolean opening = diagnostics.getVisibility() != View.VISIBLE;
            diagnostics.setVisibility(opening ? View.VISIBLE : View.GONE);
            detailsButton.setText(opening ? "Details  −" : "Details  +");

            if (opening) scroll.post(() -> scroll.smoothScrollTo(0, diagnostics.getTop()));
            else scroll.smoothScrollTo(0, 0);
        });
        setContentView(scroll);
        clearMetrics();
        updateButtons();
    }

    private FrameLayout performanceCard() {
        FrameLayout frame = new FrameLayout(this);
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(34, 43, 32), SURFACE});
        surface.setCornerRadius(dp(26));
        surface.setStroke(dp(1), Color.rgb(63, 76, 53));
        frame.setBackground(surface);
        frame.setClipToOutline(true);
        frame.addView(new ConsoleArtwork(this), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout content = column();
        content.setPadding(dp(36), dp(30), dp(36), dp(28));
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(eyebrow("PERFORMANCE", LIME), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout timer = column();
        timer.setGravity(Gravity.RIGHT);
        rideTimeCaption = eyebrow("RIDE TIME", MUTED);
        timer.addView(rideTimeCaption);
        rideTimeValue = label("00:00", 36, WHITE, true);
        rideTimeValue.setFontFeatureSettings("tnum");
        timer.addView(rideTimeValue);
        top.addView(timer);
        content.addView(top);
        LinearLayout readings = row();
        readings.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout power = metric(1, "POWER", "watts", compact ? 130 : 164, WHITE);
        readings.addView(power, new LinearLayout.LayoutParams(0, -2, 1.25f));
        View divider = new View(this);
        divider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), dp(130));
        dividerParams.leftMargin = dp(14);
        dividerParams.rightMargin = dp(32);
        readings.addView(divider, dividerParams);
        readings.addView(metric(0, "CADENCE", "rpm", compact ? 90 : 112, LIME),
                new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(readings, new LinearLayout.LayoutParams(-1, 0, 1));
        View rule = new View(this);
        rule.setBackgroundColor(BORDER);
        content.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
        spacer(content, 22);
        LinearLayout footer = row();
        metricCaption = label("Connect to see your readings", 16, MUTED, false);
        footer.addView(metricCaption, new LinearLayout.LayoutParams(0, -2, 1));
        distanceValue = label("DISTANCE  — km", 20, LIME, true);
        distanceValue.setPadding(dp(12), 0, 0, 0);
        footer.addView(distanceValue);
        content.addView(footer);
        frame.addView(content, new FrameLayout.LayoutParams(-1, -1));
        return frame;
    }

    private LinearLayout metric(int index, String name, String unit, int size, int color) {
        LinearLayout block = column();
        block.addView(eyebrow(name, MUTED));
        metricValues[index] = label("—", size, color, false);
        metricValues[index].setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        metricValues[index].setFontFeatureSettings("tnum");
        metricValues[index].setLetterSpacing(-0.045f);
        metricValues[index].setSingleLine(true);
        metricValues[index].setAutoSizeTextTypeUniformWithConfiguration(48, size, 2,
                android.util.TypedValue.COMPLEX_UNIT_SP);
        metricValues[index].setGravity(Gravity.CENTER_VERTICAL);
        block.addView(metricValues[index], new LinearLayout.LayoutParams(-1, dp(compact ? 152 : 186)));
        block.addView(label(unit, 21, MUTED, false));
        return block;
    }

    private LinearLayout adjustmentCard(boolean gear) {
        LinearLayout card = column();
        card.setPadding(dp(26), dp(20), dp(26), dp(18));
        card.setBackground(panel(SURFACE, BORDER, 24));
        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(label(gear ? "Resistance" : "Incline", 23, WHITE, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView hint = label(gear ? "APP TEST 1–2" : "0.5% / STEP", 12, gear ? LIME : MUTED, true);
        if (gear) gearLimitHint = hint;
        hint.setLetterSpacing(0.06f);
        hint.setPadding(dp(12), dp(7), dp(12), dp(7));
        hint.setBackground(panel(gear ? Color.rgb(39, 48, 29) : SURFACE, BORDER, 10));
        heading.addView(hint);
        card.addView(heading);
        spacer(card, 12);
        LinearLayout stepper = row();
        stepper.setGravity(Gravity.CENTER_VERTICAL);
        int first = gear ? 0 : 2;
        controlButtons[first] = adjustmentButton(first);
        stepper.addView(controlButtons[first], new LinearLayout.LayoutParams(dp(84), dp(80)));
        LinearLayout valueBlock = column();
        valueBlock.setGravity(Gravity.CENTER);
        int index = gear ? 2 : 3;
        metricValues[index] = label("—", 62, WHITE, false);
        metricValues[index].setFontFeatureSettings("tnum");
        metricValues[index].setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        metricValues[index].setGravity(Gravity.CENTER);
        valueBlock.addView(metricValues[index]);
        TextView unit = label(gear ? "reported level" : "% grade", 14, MUTED, false);
        unit.setGravity(Gravity.CENTER);
        valueBlock.addView(unit);
        stepper.addView(valueBlock, new LinearLayout.LayoutParams(0, -2, 1));
        controlButtons[first + 1] = adjustmentButton(first + 1);
        stepper.addView(controlButtons[first + 1], new LinearLayout.LayoutParams(dp(84), dp(80)));
        card.addView(stepper, new LinearLayout.LayoutParams(-1, 0, 1));
        if (gear) {
            gearStatus = label("App resistance test 1–2 · connect to begin", 14, MUTED, false);
            gearStatus.setGravity(Gravity.CENTER);
            card.addView(gearStatus);
        } else {
            TextView note = label("Small steps. Find your climb.", 14, MUTED, false);
            note.setGravity(Gravity.CENTER);
            card.addView(note);
        }
        return card;
    }

    private Button adjustmentButton(int action) {
        String[] names = {"Decrease resistance level", "Increase resistance level", "Decrease incline by 0.5 percent", "Increase incline by 0.5 percent"};
        Button button = actionButton(action % 2 == 0 ? "−" : "+", false);
        button.setTextSize(36);
        button.setContentDescription(names[action]);
        button.setOnClickListener(view -> {
            boolean queued = session != null && (action < 2 ? session.stepResistance(action == 0 ? -1 : 1)
                    : session.stepIncline(action == 2 ? -1 : 1));
            if (queued) {
                setControlButtons(false);
                controlStatus.setText("Waiting for the bike to confirm the requested setting…");
            } else {
                controlStatus.setText(session == null ? "Connect bike before adjusting the bike."
                        : "Adjustment unavailable. Wait for the current action to finish and check the ride status.");
            }
        });
        return button;
    }

    private void connectTelemetry() {
        if (session != null || permissionPending || !foreground) return;
        if (rideService == null) { connectOnBind = true; return; }
        if (rideService.isClosing()) {
            showStatus("USB connection is closing", "Try Connect bike again in a moment.", false);
            return;
        }
        Snapshot snapshot = inspect();
        if (snapshot.device == null) {
            showStatus("Bike USB controller not detected", snapshot.error == null
                    ? "Refresh device details and check the bike is powered on." : snapshot.error, false);
            clearMetrics();
            return;
        }
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            showStatus("USB service unavailable", "Android did not provide its USB service.", false);
            return;
        }
        if (manager.hasPermission(snapshot.device)) {
            startSession(snapshot.device);
            return;
        }
        permissionPending = true;
        permissionDevice = snapshot.device;
        showStatus("USB permission needed", "Allow S22i Console to access the bike controller in the Android prompt.", false);
        updateButtons();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent permission = PendingIntent.getBroadcast(this, 0,
                new Intent(usbPermissionAction).setPackage(getPackageName()), flags);
        try {
            manager.requestPermission(snapshot.device, permission);
        } catch (RuntimeException error) {
            permissionPending = false;
            permissionDevice = null;
            showStatus("USB permission request failed", error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage(), false);
            updateButtons();
        }
    }

    private void startSession(UsbDevice device) {
        if (!foreground || session != null || device.getVendorId() != VENDOR_ID
                || device.getProductId() != PRODUCT_ID) return;
        clearMetrics();
        if (rideService != null && rideService.connect(device)) {
            session = rideService.session();
        } else showStatus("Connection unavailable", "Wait for the previous connection to finish, then try again.", false);
        updateButtons();
    }

    private void disconnectTelemetry() {
        permissionPending = false;
        permissionDevice = null;
        if (rideService != null) rideService.disconnect();
        session = null;
        lastSampleAt = 0;
        clearMetrics();
        updateButtons();
    }

    private void showSessionState(BikeSession.State state) {
        showStatus(state.title, state.detail, state.running);
        rideOwned = state.rideOwned;
        setLabel(rideTitle, rideOwned ? "Your ride is in progress" : "Ready when you are");
        if (!state.running) {
            session = null;
            lastSampleAt = 0;
            clearMetrics();
            setLabel(controllerDiagnosticValue, state.controllerDiagnostic);
            String message = nonEmpty(state.detail, nonEmpty(state.controlMessage, "Manual controls are off."));
            String diagnostic = nonEmpty(state.controllerDiagnostic, message);
            String failure = diagnostic.equals(message) ? diagnostic : diagnostic + " " + message;
            setLabel(controlStatus, rideOwned ? failure
                    + " Reconnect telemetry to retry End ride." : failure);
            updateButtons();
            return;
        }
        lastSampleAt = state.sampledAt;
        long age = SystemClock.elapsedRealtime() - state.sampledAt;
        if (state.sampledAt <= 0 || age < 0 || age > 3000) {
            clearMetrics();
            setLabel(controllerDiagnosticValue, state.controllerDiagnostic);
            setLabel(controlStatus, state.controlMessage);
            if (state.sampledAt > 0) setLabel(metricCaption, "Waiting for fresh data");
            return;
        }
        setLabel(metricValues[0], number(state.values.get(5), 0));
        setLabel(metricValues[1], number(state.values.get(3), 0));
        setLabel(metricValues[3], number(state.values.get(17), 1));
        Double raw = state.values.get(2);

        long sampleAge = SystemClock.elapsedRealtime() - state.sampledAt;
        Integer reportedLevel = state.observedGear;
        boolean levelAvailable = state.sampledAt > 0 && sampleAge >= 0 && sampleAge <= 3000
                && reportedLevel != null && reportedLevel > 0 && reportedLevel < 255;
        setLabel(metricValues[2], levelAvailable ? Integer.toString(reportedLevel) : "—");
        setLabel(rawResistanceValue, "Raw resistance: " + number(raw != null && raw >= 0 && raw <= 10000 ? raw : null, 0));
        setLabel(enableControlsButton, rideOwned ? "End ride" : "Start ride");
        enableControlsButton.setContentDescription(rideOwned ? "End ride" : "Start ride");
        enableControlsButton.setEnabled(rideOwned || (!state.controlPending && state.values.containsKey(2)));
        setControlButtons(state.controlsEnabled && !state.controlPending, state.gearControlsEnabled, state.testGear, state.gearLimit);
        setLabel(gearLimitHint, "APP TEST 1–" + state.gearLimit);
        boolean reportedInactiveMode = state.controllerMode != null
                && (state.controllerMode == 12 || state.controllerMode == 14);
        boolean controllerNeedsAttention = reportedInactiveMode || (!"OK".equals(state.controllerIssue)
                && !"MODE_UNAVAILABLE".equals(state.controllerIssue));
        setLabel(controlStatus, controllerNeedsAttention
                ? state.controllerDiagnostic + (rideOwned ? " End ride remains available." : "")
                : state.controlMessage);
        setLabel(gearStatus, state.gearMessage);
        setLabel(metricCaption, state.values.isEmpty() ? "Waiting for telemetry"
                : !"OK".equals(state.controllerIssue) ? "Check controller status"
                : "Live readings · Controller " + ControllerDiagnostics.modeName(state.controllerMode));
        setLabel(controllerDiagnosticValue, state.controllerDiagnostic);
        setLabel(rawGearValue, "Reported resistance level: " + (state.observedGear == null ? "—" : state.observedGear)
                + " · read only\nProtocol field 26: " + nonEmpty(state.gearProfile, "—"));
        setLabel(sessionDetails, state.equipmentId > 0
                ? "Spin bike " + state.equipmentId + "  ·  App resistance test 1–" + state.gearLimit + "  ·  Incline −10% to +20%"
                : "Identifying equipment");
    }

    private void showStatus(String title, String detail, boolean connected) {
        setLabel(statusTitle, title);

        setLabel(statusDetail, detail);
        if (!connected && controlStatus != null) setLabel(controlStatus, detail);
        if (statusConnected != connected) {
            statusConnected = connected;
            statusTitle.setTextColor(connected ? LIME : MUTED);
            statusDot.setBackground(panel(connected ? LIME : MUTED, connected ? LIME : MUTED, 5));
        }
    }

    private void clearMetrics() {
        if (gearLimitHint != null) setLabel(gearLimitHint, "APP TEST 1–2");
        for (TextView value : metricValues) if (value != null) setLabel(value, "—");
        if (rawResistanceValue != null) setLabel(rawResistanceValue, "Raw resistance: —");
        if (rawGearValue != null) setLabel(rawGearValue, "Reported resistance level: —\nProtocol field 26: —");
        if (controllerDiagnosticValue != null) setLabel(controllerDiagnosticValue, "No fresh controller state");
        if (gearStatus != null) setLabel(gearStatus, "App resistance test 1–2 · fresh telemetry required");
        if (metricCaption != null) setLabel(metricCaption, "Connect to see your readings");
        if (rideTitle != null) setLabel(rideTitle, rideOwned ? "Check your ride connection" : "Ready when you are");
        setControlButtons(false);
        if (enableControlsButton != null) {
            enableControlsButton.setEnabled(session != null && rideOwned);
            setLabel(enableControlsButton, rideOwned
                    ? (session != null ? "End ride" : "Reconnect to end ride") : "Start ride");
            enableControlsButton.setContentDescription(enableControlsButton.getText());
        }
    }

    private void setControlButtons(boolean enabled) {
        setControlButtons(enabled, false, null, 2);
    }

    private void setControlButtons(boolean enabled, boolean gearEnabled, Integer gear, int limit) {
        for (int index = 0; index < controlButtons.length; index++) {
            Button button = controlButtons[index];
            if (button == null) continue;
            boolean available = enabled && (index >= 2 || (gearEnabled && gear != null
                    && (index == 0 ? gear > 1 : gear < limit)));
            button.setEnabled(available);
            button.setAlpha(1f);
        }
    }

    private void updateButtons() {
        if (connectButton == null || refreshButton == null) return;
        setLabel(connectButton, permissionPending ? "Waiting for permission" : session == null ? "Connect bike" : "Disconnect");
        connectButton.setEnabled(!permissionPending && rideService != null && !rideService.isClosing());
        if (overlayButton != null) {
            overlayButton.setEnabled(rideService != null);
            setLabel(overlayButton, rideService != null && rideService.overlayEnabled() ? "Hide ride overlay" : "Show ride overlay");
        }
        refreshButton.setEnabled(session == null && !permissionPending);
        refreshButton.setAlpha(refreshButton.isEnabled() ? 1f : 0.5f);
    }

    private void setLabel(TextView view, CharSequence value) {
        if (!android.text.TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException unavailable) {
            return "Unknown version";
        }
    }

    private String number(Double value, int decimals) {
        if (value == null || value.isNaN() || value.isInfinite()) return "—";
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    private Button actionButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(17);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{Color.rgb(112, 126, 115), primary ? BACKGROUND : WHITE}));
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        StateListDrawable backgrounds = new StateListDrawable();
        backgrounds.addState(new int[]{-android.R.attr.state_enabled},
                panel(Color.rgb(27, 34, 29), BORDER, 18));
        backgrounds.addState(new int[]{}, panel(primary ? LIME : Color.rgb(35, 44, 37),
                primary ? LIME : Color.rgb(67, 81, 69), 18));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(38, 244, 247, 240)),
                backgrounds, panel(Color.WHITE, Color.WHITE, 18)));
        return button;
    }

    private TextView eyebrow(String text, int color) {
        TextView view = label(text, 12, color, true);
        view.setLetterSpacing(0.14f);
        return view;
    }

    private Snapshot inspect() {
        Snapshot result = new Snapshot();
        try {
            UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
            if (manager == null) {
                result.error = "Android USB service is unavailable.";
                return result;
            }
            Map<String, UsbDevice> devices = manager.getDeviceList();
            result.deviceCount = devices.size();
            List<String> names = new ArrayList<>(devices.keySet());
            Collections.sort(names);
            for (String name : names) {
                UsbDevice device = devices.get(name);
                if (device != null && device.getVendorId() == VENDOR_ID
                        && device.getProductId() == PRODUCT_ID) {
                    result.device = device;
                    break;
                }
            }
            if (result.device != null) {
                StringBuilder interfaces = new StringBuilder();
                StringBuilder endpoints = new StringBuilder();
                for (int index = 0; index < result.device.getInterfaceCount(); index++) {
                    UsbInterface usbInterface = result.device.getInterface(index);
                    if (interfaces.length() > 0) interfaces.append("; ");
                    interfaces.append(usbInterface.getId()).append(" · ")
                            .append(usbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_HID
                                    ? "HID" : "class " + usbInterface.getInterfaceClass());
                    for (int end = 0; end < usbInterface.getEndpointCount(); end++) {
                        UsbEndpoint endpoint = usbInterface.getEndpoint(end);
                        if (endpoints.length() > 0) endpoints.append('\n');
                        endpoints.append(String.format(Locale.US, "0x%02X", endpoint.getAddress()))
                                .append(endpoint.getDirection() == UsbConstants.USB_DIR_IN ? " IN " : " OUT")
                                .append("  ·  ")
                                .append(endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_INT
                                        ? "interrupt" : "type " + endpoint.getType())
                                .append("  ·  ").append(endpoint.getMaxPacketSize()).append(" bytes");
                    }
                }
                result.interfaces = interfaces.length() > 0 ? interfaces.toString() : "No interfaces reported";
                result.endpoints = endpoints.length() > 0 ? endpoints.toString() : "No endpoints reported";
            }
        } catch (RuntimeException exception) {
            result.device = null;
            result.error = "USB details could not be read (" + exception.getClass().getSimpleName() + ").";
        }
        return result;
    }

    private LinearLayout detailCard(String title) {
        LinearLayout card = column();
        card.setPadding(dp(24), dp(22), dp(24), dp(22));
        card.setBackground(panel(SURFACE, BORDER, 16));
        card.addView(label(title, 14, LIME, true));
        return card;
    }

    private void field(LinearLayout parent, String name, String value) {
        TextView nameView = label(name, 14, MUTED, false);
        nameView.setPadding(0, dp(16), 0, dp(3));
        parent.addView(nameView);
        parent.addView(label(value, 21, WHITE, false));
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        view.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        return view;
    }

    private GradientDrawable panel(int fill, int stroke, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(1), stroke);
        return background;
    }

    private void spacer(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static final class Snapshot {
        UsbDevice device;
        int deviceCount;
        String interfaces;
        String endpoints;
        String error;
    }
}
