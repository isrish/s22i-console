package dev.s22i.console;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import dev.s22i.console.ControllerDiagnostics.ControlNotConfirmed;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BikeSession {
    private static final AtomicBoolean USB_OWNED = new AtomicBoolean();
    public interface Listener { void onState(State state); }

    public static final class State {
        public final boolean running;
        public final String title;
        public final String detail;
        public final Map<Integer, Double> values;
        public final Integer maxResistance;
        public final long sampledAt;
        public final int equipmentId;
        public final String supportedFields;
        public final String resistanceLabel;
        public final boolean controlsEnabled;
        public final boolean controlPending;
        public final String controlMessage;
        public final boolean rideOwned;
        public final Integer testGear;
        public final boolean gearControlsEnabled;
        public final int gearLimit;
        public final String gearMessage;
        public final String controllerDiagnostic;
        public final String controllerIssue;
        public final Integer controllerMode;
        public final Integer observedGear;
        public final String gearProfile;

        State(boolean running, String title, String detail, Map<Integer, Double> values,
              Integer maxResistance, long sampledAt, int equipmentId, Set<Integer> supportedFields,
              boolean controlsEnabled, boolean controlPending, String controlMessage, boolean rideOwned,
              Integer testGear, boolean gearControlsEnabled, int gearLimit, String gearMessage,
              ControllerDiagnostics.Snapshot diagnostic) {
            this.running = running;
            this.title = title;
            this.detail = detail;
            this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            this.maxResistance = maxResistance;
            this.sampledAt = sampledAt;
            this.equipmentId = equipmentId;
            this.supportedFields = supportedFields.toString();
            this.resistanceLabel = "Raw resistance";
            this.controlsEnabled = controlsEnabled;
            this.controlPending = controlPending;
            this.controlMessage = controlMessage;
            this.rideOwned = rideOwned;
            this.testGear = testGear;
            this.gearControlsEnabled = gearControlsEnabled;
            this.gearLimit = gearLimit;
            this.gearMessage = gearMessage;
            this.controllerDiagnostic = diagnostic.controllerDiagnostic;
            this.controllerIssue = diagnostic.controllerIssue;
            this.controllerMode = diagnostic.controllerMode;
            this.observedGear = diagnostic.observedGear;
            this.gearProfile = diagnostic.gearProfile;
        }
    }

    private final Context context;
    private final UsbDevice device;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Double> values = new LinkedHashMap<>();
    private final Map<Integer, String> diagnosticRawFields = new LinkedHashMap<>();
    private final Set<Integer> fields = new LinkedHashSet<>();
    private volatile boolean stopped;
    private volatile boolean closed;
    private Thread worker;
    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private UsbEndpoint input;
    private UsbEndpoint output;
    private FileWriter trace;
    private int equipmentId = -1;
    private boolean controllerAuthenticated;
    private Integer maxResistance;
    private int exchanges;
    private volatile boolean controlsEnabled;
    private volatile boolean gearWritesBlocked;
    private volatile int gearLimit = 2;
    private byte[] lastGearProfile;
    private String gearMessage = "App resistance test · levels 1–2 only";
    private volatile boolean controlBusy;
    private volatile Action pendingAction;
    private volatile Double inclineInProgress;
    private long inclineDeadline;
    private long lastSampleAt;

    private Integer lastReportedControllerMode;
    private long controllerModeSampledAt;
    private byte[] reportedGearProfile;
    private ControllerDiagnostics.Issue observedIssue = ControllerDiagnostics.Issue.OK;
    private String observedIssueDetail = "";
    private ControllerDiagnostics.Issue terminalIssue;
    private String terminalIssueDetail = "";
    private final ControllerDiagnostics.ActionFailureLatch actionFailure = new ControllerDiagnostics.ActionFailureLatch();
    private volatile boolean rideOwned;
    private RideInitialization.OriginalFlags originalFlags;
    private volatile boolean cleaningUp;
    private volatile boolean endingRide;
    private long cleanupDeadline;
    private String controlMessage = "Manual controls are off.";
    private enum Action {
        START_RIDE, END_RIDE, RESISTANCE_UP, RESISTANCE_DOWN, INCLINE_UP, INCLINE_DOWN,
        TEST_LIMIT_2(2), TEST_LIMIT_3(3), TEST_LIMIT_8(8), TEST_LIMIT_16(16), TEST_LIMIT_24(24);
        final int testLimit;
        Action() { this(0); }
        Action(int testLimit) { this.testLimit = testLimit; }
    }

    public BikeSession(Context context, UsbDevice device, Listener listener) {
        this.context = context.getApplicationContext();
        this.device = device;
        this.listener = listener;
    }

    public synchronized void start() {
        if (worker != null) return;
        worker = new Thread(this::run, "S22i-USB");
        worker.start();
    }

    public synchronized void stop() {
        if (stopped) return;
        stopped = true;
        controlsEnabled = false;
        pendingAction = null;
        Thread thread = worker;
        if (thread != null && !cleaningUp) thread.interrupt();

    }

    public boolean isClosed() { return closed; }

    public boolean configureResistanceTest(int limit) {
        for (Action action : Action.values()) if (action.testLimit == limit && limit > 0) return enqueue(action);
        return false;
    }

    public boolean startRide() { return enqueue(Action.START_RIDE); }
    public boolean endRide() { return enqueue(Action.END_RIDE); }
    public boolean stepResistance(int direction) {
        return direction == 1 ? enqueue(Action.RESISTANCE_UP)
                : direction == -1 && enqueue(Action.RESISTANCE_DOWN);
    }
    public boolean stepIncline(int direction) {
        return direction == 1 ? enqueue(Action.INCLINE_UP)
                : direction == -1 && enqueue(Action.INCLINE_DOWN);
    }
    private synchronized boolean enqueue(Action action) {
        if (stopped || closed) return false;
        if (action == Action.END_RIDE) {
            if (!rideOwned || endingRide || pendingAction == Action.END_RIDE) return false;
            controlsEnabled = false;
            pendingAction = action;
            return true;
        }
        if (action == Action.START_RIDE && rideOwned) return false;
        if (controlBusy) return false;
        if (pendingAction != null || inclineInProgress != null) return false;
        if (action.testLimit > 0 && (rideOwned || gearWritesBlocked || cleaningUp || endingRide)) return false;
        if (action != Action.START_RIDE && action.testLimit == 0 && !controlsEnabled) return false;
        if ((action == Action.RESISTANCE_UP || action == Action.RESISTANCE_DOWN)
                && (gearWritesBlocked || !rideOwned)) return false;
        pendingAction = action;
        return true;
    }

    private void run() {
        if (!USB_OWNED.compareAndSet(false, true)) {
            closed = true;
            State state = new State(false, "USB connection is closing", "Try connecting again in a moment.",
                    Collections.emptyMap(), null, 0, -1, Collections.emptySet(), false, false, "Controls off.", false,
                    null, false, 2, "App resistance test unavailable",
                    ControllerDiagnostics.describe(false, null, 0, SystemClock.elapsedRealtime(),
                            ControllerDiagnostics.Issue.USB_UNAVAILABLE, "USB connection is closing.", null));
            main.post(() -> { if (!stopped || !state.running) listener.onState(state); });
            return;
        }
        String sessionError = null;
        try {
            checkStopped();
            if (AppPrivacy.diagnostics(context))
                trace = new FileWriter(new File(context.getFilesDir(), "protocol-trace.txt"), false);

            if (context.getSharedPreferences("ride", 0).getBoolean("owned", false)) {
                rideOwned = true;
                android.content.SharedPreferences recovery = context.getSharedPreferences("ride", 0);
                originalFlags = new RideInitialization.OriginalFlags(recovery.getInt("originalLockout", -1),
                        recovery.contains("originalRequireStart") ? recovery.getInt("originalRequireStart", -1) : null);
                controlMessage = "A previous ride needs ending. Tap End ride before starting another.";
            }
            publish(true, "Connecting to bike", "Opening the USB controller for telemetry.");
            open();
            publish(true, "Identifying bike", "Reading equipment identity and supported fields.");
            FitPro.Response info = exchange(FitPro.deviceInfo(2));
            requireDone(info);
            equipmentId = info.deviceId;
            if (equipmentId != 7 && equipmentId != 8) {
                throw new IOException("Controller reported equipment " + equipmentId + "; expected a bike (7 or 8).");
            }
            byte[] payload = info.payload();
            if (payload.length < 9) throw new ControllerDiagnostics.Failure(
                    ControllerDiagnostics.Issue.MALFORMED_PROTOCOL, "Incomplete device information.");
            int maskBytes = payload[8] & 255;
            if (maskBytes == 0 || maskBytes > 16 || payload.length != 9 + maskBytes) {
                throw new ControllerDiagnostics.Failure(ControllerDiagnostics.Issue.MALFORMED_PROTOCOL,
                        "Device field mask has an unexpected length.");
            }
            for (int section = 0; section < maskBytes; section++) {
                for (int bit = 0; bit < 8; bit++) {
                    if (((payload[9 + section] & 255) & (1 << bit)) != 0) fields.add(section * 8 + bit);
                }
            }
            log("IDENTITY equipment=" + equipmentId + " firmware=" + (payload[0] & 255)
                    + " hardware=" + (payload[1] & 255) + " fields=" + fields);
            FitPro.Response commands = exchange(FitPro.supportedCommands(equipmentId));
            requireDone(commands);
            log("SUPPORTED_COMMANDS " + FitPro.hex(commands.payload()));
            authenticateController(payload, FitPro.supportedCommands(commands));
            if (fields.contains(42)) {
                FitPro.Response response = exchange(FitPro.readField(2, 42));
                requireDone(response);
                if (response.value != null && response.value >= 1 && response.value <= 100) {
                    maxResistance = response.value.intValue();
                }
            }
            for (int field : new int[] {27, 28, 61, 95, 108, 119, 12}) {
                if (!fields.contains(field)) continue;
                FitPro.Response response = exchange(FitPro.readField(2, field));
                requireDone(response);
                if (response.value != null) values.put(field, response.value);
            }
            int[] metrics = {16, 3, 5, 119, 2, 17, 12, 1, 18, 60, 7, 26};
            boolean hasMetrics = false;
            for (int field : metrics) hasMetrics |= fields.contains(field);
            if (!hasMetrics) throw new IOException("Bike did not advertise the expected telemetry fields.");
            while (!stopped) {
                observedIssue = ControllerDiagnostics.Issue.OK;
                observedIssueDetail = "";
                handleControl();
                if (pendingAction == Action.END_RIDE) continue;
                long start = SystemClock.elapsedRealtime();
                for (int field : metrics) {
                    if (!fields.contains(field)) continue;
                    FitPro.Response response;
                    try {
                        response = field == 26
                                ? exchangeGear(FitPro.readField(2, field), Long.MAX_VALUE)
                                : exchange(FitPro.readField(2, field));
                    } catch (FitPro.FieldWidthException wrongWidth) {
                        if (field != 16) throw wrongWidth;

                        log("SPEED_UNAVAILABLE " + wrongWidth.getMessage());
                        fields.remove(16);
                        values.remove(16);
                        continue;
                    }
                    if (response == null) continue;
                    if (field == 16) {

                        if (response.status == FitPro.DONE && response.value != null
                                && Double.isFinite(response.value) && response.value >= 0 && response.value <= 200) {
                            values.put(16, response.value);
                        } else values.remove(16);
                        continue;
                    }
                    if (field == 18 || field == 60 || field == 7 || field == 26) {

                        recordRawDiagnostic(field, response);
                        continue;
                    }
                    requireDone(response);
                    if (field == 2) {
                        byte[] raw = response.payload();
                        if (raw.length == 2 && !((raw[0] & 255) == 255 && (raw[1] & 255) == 255)) {
                            int resistanceRaw = (raw[0] & 255) | ((raw[1] & 255) << 8);
                            if (resistanceRaw <= 10000) {
                                if (!values.containsKey(field) || values.get(field) != resistanceRaw) {
                                    log("RESISTANCE_READ raw=" + resistanceRaw + " mode=" + values.get(12));
                                }
                                values.put(field, (double) resistanceRaw);
                            }
                            else values.remove(field);
                        } else values.remove(field);
                    } else if (response.value == null) values.remove(field);
                    else values.put(field, response.value);
                }

                lastSampleAt = start;
                if (inclineInProgress != null) {
                    Double actual = values.get(17);
                    if (actual != null && Math.abs(actual - inclineInProgress) < 0.1) {
                        controlMessage = "Incline reached " + inclineInProgress + "%.";
                        inclineInProgress = null;
                    } else if (SystemClock.elapsedRealtime() > inclineDeadline) {
                        controlsEnabled = false;
                        inclineInProgress = null;
                        controlMessage = "Incline target was accepted but not reached. Controls disabled; check the bike.";
                    }
                }
                publish(true, "Live bike telemetry", "Connected to equipment " + equipmentId
                        + ". Reading cadence, power, resistance setting and incline.");
                long delay = 500 - (SystemClock.elapsedRealtime() - start);
                if (delay > 0) Thread.sleep(delay);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            log("ERROR " + error.getClass().getSimpleName() + ": " + error.getMessage());
            recordTerminalIssue(error);
            values.clear();
            controlsEnabled = false;
            sessionError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        } finally {
            if (rideOwned && connection != null && usbInterface != null) {
                synchronized (this) {
                    cleaningUp = true;
                    Thread.interrupted();
                }
                cleanupDeadline = SystemClock.elapsedRealtime() + 5000;
                try {

                    requireDone(exchange(FitPro.deviceInfo(2)));
                    finishRide();
                } catch (Exception failure) {
                    recordTerminalIssue(failure);
                    controlMessage = "Could not confirm ride shutdown: " + failure.getMessage();
                    log("CLEANUP_FAILED " + controlMessage);
                } finally { cleaningUp = false; }
            }
            try {
                if (connection != null && usbInterface != null) connection.releaseInterface(usbInterface);
            } catch (RuntimeException failure) {
                log("USB_RELEASE_FAILED " + failure.getMessage());
            } finally {
                try {
                    if (connection != null) connection.close();
                } catch (RuntimeException failure) {
                    log("USB_CLOSE_FAILED " + failure.getMessage());
                } finally {
                    connection = null;
                    USB_OWNED.set(false);
                    closed = true;
                    values.clear();
                    if (stopped) publish(false, "Disconnected", rideOwned ? controlMessage : "USB interface released.");
                    else if (sessionError != null) publish(false, "Telemetry stopped", sessionError
                            + (rideOwned ? " " + controlMessage : ""));
                    if (trace != null) {
                        try { trace.close(); } catch (IOException ignored) { }
                    }
                }
            }
        }
    }

    private void authenticateController(byte[] identity, Set<Integer> commands) throws Exception {
        if ((identity[0] & 255) <= 75 || !commands.contains(FitPro.VERIFY_SECURITY)) {
            log("AUTH_SKIPPED firmware=" + (identity[0] & 255) + " declared="
                    + commands.contains(FitPro.VERIFY_SECURITY));
            return;
        }
        if (!commands.contains(FitPro.SYSTEM_INFO) || !commands.contains(FitPro.VERSION_INFO)) {
            throw new ControllerDiagnostics.Failure(ControllerDiagnostics.Issue.AUTHENTICATION_REQUIRED,
                    "Controller authentication metadata is not advertised. Connection stopped.");
        }
        publish(true, "Connecting to bike", "Authenticating the controller.");
        FitPro.Response system = exchange(FitPro.systemInfo(FitPro.MAIN_DEVICE));
        requireDone(system);

        if (system.payload().length < 11) throw new FitPro.ProtocolException("Incomplete SystemInfo metadata");
        FitPro.Response version = exchange(FitPro.versionInfo(FitPro.MAIN_DEVICE));
        requireDone(version);
        FitProAuthentication.Metadata metadata;
        try {
            metadata = FitProAuthentication.Metadata.fromPayloads(identity, system.payload(), version.payload());
        } catch (IllegalArgumentException invalid) {
            throw new ControllerDiagnostics.Failure(ControllerDiagnostics.Issue.MALFORMED_PROTOCOL,
                    "Invalid controller authentication metadata: " + invalid.getMessage(), invalid);
        }
        log("AUTH_METADATA validated [identity redacted]");
        FitPro.Response security = exchange(FitPro.verifySecurity(FitPro.MAIN_DEVICE, metadata));
        requireDone(security);
        FitPro.securityUnlockKey(security);
        controllerAuthenticated = true;
        log("AUTHENTICATION_CONFIRMED status=" + security.status);
    }

    private void open() throws IOException, InterruptedException {
        checkStopped();
        if (device.getVendorId() != 0x213c || device.getProductId() != 2) {
            throw new IOException("Selected USB device does not match the bike controller.");
        }
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (manager == null || !manager.hasPermission(device)) throw new ControllerDiagnostics.Failure(
                ControllerDiagnostics.Issue.USB_UNAVAILABLE, "USB permission is required.");
        connection = manager.openDevice(device);
        if (connection == null) throw new ControllerDiagnostics.Failure(
                ControllerDiagnostics.Issue.USB_UNAVAILABLE, "USB controller could not be opened.");
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            UsbInterface candidate = device.getInterface(index);
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            if (candidate.getInterfaceClass() != UsbConstants.USB_CLASS_HID) continue;
            for (int e = 0; e < candidate.getEndpointCount(); e++) {
                UsbEndpoint endpoint = candidate.getEndpoint(e);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_INT || endpoint.getMaxPacketSize() != 64) continue;
                if (endpoint.getAddress() == 0x81) in = endpoint;
                if (endpoint.getAddress() == 0x02) out = endpoint;
            }
            if (in != null && out != null) {

                if (!connection.claimInterface(candidate, false) && !connection.claimInterface(candidate, true)) {
                    throw new ControllerDiagnostics.Failure(ControllerDiagnostics.Issue.USB_UNAVAILABLE,
                            "USB interface is busy. Close the other bike-control app, then reconnect.");
                }
                usbInterface = candidate;
                input = in;
                output = out;

                for (int drain = 0; drain < 4; drain++) {
                    byte[] pending = new byte[64];
                    int count = connection.bulkTransfer(input, pending, 64, 20);
                    if (count <= 0) break;
                    log("DRAIN " + traceFrame(Arrays.copyOf(pending, count)));
                }
                return;
            }
        }
        throw new ControllerDiagnostics.Failure(ControllerDiagnostics.Issue.USB_UNAVAILABLE,
                "Expected USB interrupt endpoints are missing.");
    }

    private FitPro.Response exchange(FitPro.Request request) throws Exception {
        return exchange(request, Long.MAX_VALUE);
    }

    private FitPro.Response exchange(FitPro.Request request, long validUntil) throws Exception {
        checkStopped();
        Thread.sleep(80);
        checkStopped();
        if (SystemClock.elapsedRealtime() >= validUntil) throw new IOException("Control reading expired before transmission.");
        if (cleaningUp) {
            boolean readOnly = request.commandId == FitPro.DEVICE_INFO
                    || (request.commandId == FitPro.READ_WRITE_DATA && request.bytes()[3] == 0);
            boolean allowedEnd = request.fieldId == 12 || request.fieldId == 95 || request.fieldId == 108;
            if (!readOnly && !allowedEnd) throw new IOException("Unexpected command during ride cleanup.");
            if (SystemClock.elapsedRealtime() >= cleanupDeadline) throw new IOException("Ride cleanup timed out.");
        }
        byte[] frame = request.bytes();
        boolean manual = request.commandId == FitPro.READ_WRITE_DATA && frame.length > 3 && frame[3] != 0;
        boolean security = request.commandId == FitPro.VERIFY_SECURITY;
        boolean detailed = manual || security || exchanges++ < 80 || exchanges % 40 == 0;
        if (detailed) log("TX " + traceFrame(frame));
        if (manual) checkEndRequested();
        checkStopped();

        byte[] report = request.usbReportBytes();
        int written = connection.bulkTransfer(output, report, report.length, 300);
        if (written != report.length) throw new ControllerDiagnostics.Failure(
                ControllerDiagnostics.Issue.USB_WRITE_TRANSFER,
                "Incomplete USB request (" + written + "/" + report.length + ").");

        int readTimeout = 1000;
        if (request.commandId == FitPro.SYSTEM_INFO) {
            SystemClock.sleep(300);
            readTimeout = 2000;
        } else if (request.commandId == FitPro.VERSION_INFO || security) {
            SystemClock.sleep(400);
            readTimeout = 3500;
        }
        byte[] buffer = new byte[64];
        int received;
        int discarded = 0;
        while (true) {
            received = connection.bulkTransfer(input, buffer, buffer.length, readTimeout);
            if (cleaningUp && request.commandId == FitPro.DEVICE_INFO && received >= 5
                    && (buffer[2] & 255) != FitPro.DEVICE_INFO && discarded++ < 4
                    && SystemClock.elapsedRealtime() < cleanupDeadline) {

                log("CLEANUP_DRAIN " + traceFrame(Arrays.copyOf(buffer, received)));
                continue;
            }
            break;
        }
        if (received <= 0) throw new ControllerDiagnostics.Failure(ControllerDiagnostics.Issue.USB_NO_RESPONSE,
                "No controller response (" + received + " bytes).");
        if (received < 5) throw new ControllerDiagnostics.Failure(ControllerDiagnostics.Issue.MALFORMED_PROTOCOL,
                "Incomplete controller frame (" + received + " bytes).");
        if (detailed) log("RX " + traceFrame(Arrays.copyOf(buffer, received)));
        int length = buffer[1] & 255;
        if (length < 5 || length > received) throw new ControllerDiagnostics.Failure(
                ControllerDiagnostics.Issue.MALFORMED_PROTOCOL, "Controller response length is invalid.");
        for (int i = length; i < received; i++) {
            if (buffer[i] != 0 && (buffer[i] & 255) != 255) throw new ControllerDiagnostics.Failure(
                    ControllerDiagnostics.Issue.MALFORMED_PROTOCOL, "Unexpected data after controller frame.");
        }

        byte[] response = Arrays.copyOf(buffer, length);
        FitPro.Response parsed = equipmentId < 0 ? FitPro.parseResponse(request, response)
                : FitPro.parseResponse(request, response, equipmentId);
        recordControllerResponse(parsed);
        return parsed;
    }

    private void recordControllerResponse(FitPro.Response response) {
        if (response.field != null && response.field.id == 12) {
            Integer mode = response.isDone() ? ControllerDiagnostics.modeValue(response.value) : null;
            if (mode != null) {
                lastReportedControllerMode = mode;
                controllerModeSampledAt = SystemClock.elapsedRealtime();
            } else {

                controllerModeSampledAt = 0;
            }
        }
        if (response.field != null && response.field.id == 26) {
            reportedGearProfile = response.isDone() ? response.payload() : null;
        }
        if (!response.isDone()) {
            ControllerDiagnostics.Issue issue = ControllerDiagnostics.statusIssue(response.status);
            if (!actionFailure.observeFailure(issue)) {
                recordObservedIssue(issue, "Controller response status " + response.status
                        + " (" + FitPro.statusName(response.status) + ").");
            }
        }
    }

    private void recordObservedIssue(ControllerDiagnostics.Issue issue, String detail) {

        if (observedIssue == ControllerDiagnostics.Issue.AUTHENTICATION_REQUIRED
                && issue != ControllerDiagnostics.Issue.AUTHENTICATION_REQUIRED) return;
        observedIssue = issue;
        observedIssueDetail = detail == null ? "" : detail;
    }

    private void recordTerminalIssue(Throwable failure) {

        if (terminalIssue != null) return;
        terminalIssue = ControllerDiagnostics.classify(failure);
        terminalIssueDetail = failure.getMessage() == null
                ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private void requireDone(FitPro.Response response) throws IOException {
        if (!response.isDone()) throw new ControllerDiagnostics.Failure(ControllerDiagnostics.statusIssue(response.status),
                "Controller: " + FitPro.statusName(response.status) + ". The session has stopped.");
    }

    private void handleControl() throws Exception {
        Action action;
        synchronized (this) {
            action = pendingAction;
            pendingAction = null;
            if (action == null) return;
            controlBusy = true;
        }
        actionFailure.begin(action.name());
        try {
            long sampled;
            BikeControlPolicy.Target target;
            if (action.testLimit > 0) {
                if (configureGearRange(action.testLimit)) actionFailure.confirmed();
                return;
            } else if (action == Action.START_RIDE) {
                if (!fields.contains(1) || !fields.contains(2) || !fields.contains(17)
                        || values.get(27) == null || values.get(28) == null
                        || values.get(27) != 20.0 || values.get(28) != -10.0) {
                    throw new IOException("Bike control capabilities have not been verified.");
                }
                beginRide();
                actionFailure.confirmed();
                return;
            } else if (action == Action.END_RIDE) {
                finishRide();
                return;
            } else if (action == Action.RESISTANCE_UP || action == Action.RESISTANCE_DOWN) {
                handleGearControl(action == Action.RESISTANCE_UP ? 1 : -1);
                return;
            } else {
                Double currentTarget = readControlValue(1);
                sampled = SystemClock.elapsedRealtime();
                Double actual = readControlValue(17);
                if (actual == null || currentTarget == null || Math.abs(actual - currentTarget) > 0.1) {
                    throw new IOException("Wait for the current incline adjustment to finish.");
                }
                target = BikeControlPolicy.inclineStep(equipmentId, currentTarget, values.get(28), values.get(27),
                        sampled, SystemClock.elapsedRealtime(), action == Action.INCLINE_UP ? 1 : -1);
            }
            publish(true, "Live bike telemetry", "Checking the requested setting with the controller.");
            FitPro.Response reply = exchange(FitPro.manualControl(target), sampled + 2000);
            requireControlDone(reply, target);
            byte[] bytes = reply.payload();
            double acknowledged;
            if (target.fieldId == 2) {
                if (bytes.length != 2) throw new IOException("Resistance acknowledgement was incomplete.");
                acknowledged = (bytes[0] & 255) | ((bytes[1] & 255) << 8);
            } else {
                if (reply.value == null) throw new IOException("Incline acknowledgement was unavailable.");
                acknowledged = reply.value;
            }
            if (Math.abs(acknowledged - target.value) > 0.005) {

                for (int attempt = 0; attempt < 5 && Math.abs(acknowledged - target.value) > 0.005; attempt++) {
                    Thread.sleep(150);
                    Double refreshed = readControlValue(target.fieldId);
                    if (refreshed == null) throw new IOException("Requested setting has no valid readback.");
                    acknowledged = refreshed;
                }
            }
            if (Math.abs(acknowledged - target.value) > 0.005) {

                controlMessage = target.fieldId == 2
                        ? "Resistance change was not confirmed. Requested "
                                + String.format(java.util.Locale.US, "%.2f%%; read %.2f%%. You can retry or use the handlebars.", target.value / 100, acknowledged / 100)
                        : "Incline change was not confirmed. Other controls remain available.";
                log("CONTROL_REJECTED " + action + " field=" + target.fieldId + " requested="
                        + target.value + " returned=" + acknowledged + " mode=" + values.get(12));
                actionFailure.observeFailure(ControllerDiagnostics.Issue.CONTROLLER_REJECTED);
                return;
            }
            actionFailure.confirmed();
            values.put(target.fieldId, acknowledged);
            if (target.fieldId == 1) {
                inclineInProgress = target.value;
                inclineDeadline = SystemClock.elapsedRealtime() + 15000;
                controlMessage = "Incline target accepted: " + target.value + "%. Waiting for the bike.";
            } else {
                controlMessage = "Resistance setting confirmed at " + String.format(java.util.Locale.US, "%.2f", acknowledged / 100) + "%.";
            }
            log("CONTROL " + action + " field=" + target.fieldId + " target=" + target.value + " confirmed=" + acknowledged);
        } catch (EndRequested cancelled) {
            controlsEnabled = false;
            controlMessage = "Ending the ride…";
        } catch (ControlNotConfirmed rejected) {

            boolean rideAction = action == Action.START_RIDE || action == Action.END_RIDE;
            actionFailure.observeFailure(rejected.issue);
            if (rideAction) controlsEnabled = false;
            Double currentMode = readControlValue(12);
            if (currentMode == null || currentMode != 2.0) controlsEnabled = false;
            controlMessage = (action == Action.END_RIDE ? "End ride was not confirmed. "
                    : action == Action.START_RIDE ? "Start ride was not confirmed. " : "Adjustment was not confirmed. ")
                    + "Telemetry remains connected. " + (controlsEnabled
                            ? "Other controls remain available." : rideOwned ? "End ride remains available." : "");
            log("CONTROL_NOT_CONFIRMED action=" + action + " mode=" + currentMode + " " + rejected.getMessage());
        } catch (BikeControlPolicy.Rejected rejected) {
            controlMessage = rejected.getMessage();
            log("POLICY_REJECTED action=" + action + " message=" + rejected.getMessage());
        } catch (Exception error) {
            controlsEnabled = false;
            throw error;
        } finally {
            actionFailure.end();
            controlBusy = false;
        }
    }

    private boolean configureGearRange(int limit) throws Exception {
        if (rideOwned || cleaningUp || endingRide || gearWritesBlocked) throw new BikeControlPolicy.Rejected("End the ride and reconnect before changing the test range.");
        for (int field : new int[]{5, 12, 26, 42, 119}) {
            if (!fields.contains(field)) throw new BikeControlPolicy.Rejected("Required resistance test fields are unavailable.");
        }
        long sampled = SystemClock.elapsedRealtime();
        Double mode = readControlValue(12);
        Double cadence = readControlValue(5);
        Double maximum = readControlValue(42);
        FitPro.Response profile = exchangeGear(FitPro.readField(2, 26), Long.MAX_VALUE);
        if (profile == null) return false;
        recordRawDiagnostic(26, profile);
        if (gearWritesBlocked) return false;
        Integer confirmedMax = Double.valueOf(24).equals(maximum) ? 24 : null;
        BikeControlPolicy.validateGearTestSetup(equipmentId, mode, cadence, confirmedMax,
                profile.payload(), sampled, SystemClock.elapsedRealtime(), limit);
        checkStopped();
        gearLimit = limit;
        maxResistance = confirmedMax;
        gearMessage = "App resistance test · levels 1–" + limit + " · this connection only";
        controlMessage = "Test range selected. Start ride to test one level per tap.";
        return true;
    }

    private void handleGearControl(int direction) throws Exception {
        if (gearWritesBlocked || !rideOwned || !controlsEnabled) {
            controlMessage = "App resistance test is unavailable. End ride and incline remain separate controls.";
            return;
        }
        if (!fields.contains(26) || !fields.contains(12) || !fields.contains(119) || !fields.contains(42)) {
            blockGearWrites("Required resistance test fields are not advertised.");
            return;
        }

        long sampled = SystemClock.elapsedRealtime();
        Double mode = readControlValue(12);
        Double erg = readControlValue(119);
        Double maximum = readControlValue(42);
        FitPro.Response source = exchangeGear(FitPro.readField(2, 26), Long.MAX_VALUE);
        if (source == null) return;
        recordRawDiagnostic(26, source);
        if (gearWritesBlocked) return;
        BikeControlPolicy.Target target = BikeControlPolicy.gearStep(equipmentId, source.payload(), mode, erg,
                sampled, SystemClock.elapsedRealtime(), direction, gearLimit,
                Double.valueOf(24).equals(maximum) ? 24 : null);
        byte[] original = target.sourceGearProfile();
        byte[] expected = target.expectedGearProfile();
        log("GEAR_BEFORE direction=" + direction + " mode=" + mode + " erg=" + erg
                + " raw=" + FitPro.hex(source.payload()) + " expected=" + FitPro.hex(expected));
        publish(true, "Live bike telemetry", "Checking the app resistance test with the controller.");
        FitPro.Response reply = exchangeGear(FitPro.manualControl(target), sampled + 2000);
        if (reply == null) return;
        log("GEAR_AFTER status=" + reply.status + " raw=" + FitPro.hex(reply.payload())
                + " frame=" + FitPro.hex(reply.frame()));
        recordRawDiagnostic(26, reply);
        if (!reply.isDone() || (!Arrays.equals(expected, reply.payload()) && !Arrays.equals(original, reply.payload()))) {
            blockGearWrites("Resistance reply was outside the supported resistance test profiles.");
            return;
        }

        byte[] current = reply.payload();
        FitPro.Response confirmed = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            checkEndRequested();
            checkStopped();
            if (Arrays.equals(original, current)) Thread.sleep(150);
            checkEndRequested();
            checkStopped();
            FitPro.Response fresh = exchangeGear(FitPro.readField(2, 26), Long.MAX_VALUE);
            if (fresh == null) return;
            log("GEAR_CONFIRM attempt=" + (attempt + 1) + " status=" + fresh.status
                    + " raw=" + FitPro.hex(fresh.payload()) + " frame=" + FitPro.hex(fresh.frame()));
            recordRawDiagnostic(26, fresh);
            if (!fresh.isDone() || (!Arrays.equals(expected, fresh.payload()) && !Arrays.equals(original, fresh.payload()))) {
                blockGearWrites("Fresh resistance readback was outside the supported resistance test profiles.");
                return;
            }
            current = fresh.payload();
            if (Arrays.equals(expected, current)) {
                confirmed = fresh;
                break;
            }
        }
        if (confirmed == null) {
            blockGearWrites("Fresh resistance readback did not reach the observed target profile.");
            return;
        }
        gearMessage = "Resistance level " + (int) target.value + " confirmed · app test range 1–" + gearLimit;
        actionFailure.confirmed();
        controlMessage = gearMessage;
        log("GEAR_CONFIRMED target=" + (int) target.value + " before="
                + FitPro.hex(original) + " after=" + FitPro.hex(confirmed.payload()));
    }

    private void recordRawDiagnostic(int field, FitPro.Response response) {
        String raw = response.isDone() ? FitPro.hex(response.payload()) : "status=" + response.status;
        if (!raw.equals(diagnosticRawFields.get(field))) log("RAW_READ field=" + field + " hex=" + raw);
        diagnosticRawFields.put(field, raw);
        if (field == 26) {
            lastGearProfile = response.isDone() ? response.payload() : null;
            if (BikeControlPolicy.observedGearLevel(lastGearProfile, 24) == null) {
                blockGearWrites("Resistance data is outside the supported resistance test profiles.");
            }
        }
    }

    private FitPro.Response exchangeGear(FitPro.Request request, long validUntil) throws Exception {
        try {
            return exchange(request, validUntil);
        } catch (FitPro.FieldWidthException malformed) {
            if (malformed.fieldId != 26) throw malformed;

            String raw = FitPro.hex(malformed.frame());
            log("GEAR_INVALID_WIDTH expected=" + malformed.expectedWidth + " received="
                    + malformed.actualWidth + " frame=" + raw);
            recordObservedIssue(ControllerDiagnostics.Issue.MALFORMED_PROTOCOL, malformed.getMessage());
            byte[] frame = malformed.frame();
            reportedGearProfile = Arrays.copyOfRange(frame, 4, frame.length - 1);
            diagnosticRawFields.put(26, "invalid-width=" + malformed.actualWidth + " frame=" + raw);
            lastGearProfile = null;
            blockGearWrites("Resistance data length differed from the observed eight-byte profile.");
            return null;
        }
    }

    private void blockGearWrites(String reason) {
        if (!gearWritesBlocked) log("GEAR_BLOCKED " + reason);
        gearWritesBlocked = true;
        gearMessage = "App resistance test stopped for this connection. " + reason + " End ride and incline remain available.";
        controlMessage = gearMessage;
    }

    private void beginRide() throws Exception {
        if (rideOwned) throw new IOException("End the previous ride before starting another.");
        if (equipmentId != BikeControlPolicy.EQUIPMENT_ID) throw new IOException("Bike startup is not verified for this controller.");

        RideInitialization.prepare(initializationPort());
        setMode(10);
        setMode(2);
        checkEndRequested();
        controlsEnabled = true;
        controlMessage = "Ride started. Manual resistance and incline controls are enabled.";
        log("RIDE_STARTED");
    }

    private void finishRide() throws Exception {
        endingRide = true;
        try {
            controlsEnabled = false;
            pendingAction = null;
            inclineInProgress = null;
            Double mode = readControlValue(12);
            if (mode == null) throw new IOException("Ride state is unavailable.");
            log("RIDE_END_BEGIN mode=" + mode);
            if (mode != 1.0) {

                if (mode != 4.0) setMode(4);
                Thread.sleep(200);
                setMode(1);
            }

            if (originalFlags == null) throw new IOException("Saved ride recovery flags are unavailable.");
            RideInitialization.restore(initializationPort(), originalFlags);
            if (!context.getSharedPreferences("ride", 0).edit().putBoolean("owned", false).commit()) {
                throw new IOException("Could not clear ride recovery state.");
            }
            rideOwned = false;
            controlMessage = "Ride ended. Telemetry remains available.";
            log("RIDE_ENDED lockoutRestored=" + originalFlags.idleLockout
                    + " requireStartRestored=" + originalFlags.requireStart);
        } finally { endingRide = false; }
    }

    private void setMode(int desired) throws Exception {
        Double current = readControlValue(12);
        if (current != null && current == desired) return;
        long sampled = SystemClock.elapsedRealtime();
        BikeControlPolicy.Target target = BikeControlPolicy.workoutModeTarget(equipmentId, current, desired,
                sampled, SystemClock.elapsedRealtime());
        confirmStateWrite(target, sampled);
    }

    private RideInitialization.Port initializationPort() {
        return new RideInitialization.Port() {
            @Override public Double read(int field) throws Exception { return readControlValue(field); }
            @Override public void saveOriginals(RideInitialization.OriginalFlags flags) throws Exception {
                if (flags.requireStart == null) throw new IOException("New rides require both original flags.");
                if (!context.getSharedPreferences("ride", 0).edit().putBoolean("owned", true)
                        .putInt("originalLockout", flags.idleLockout)
                        .putInt("originalRequireStart", flags.requireStart).commit()) {
                    throw new IOException("Could not save ride recovery state.");
                }
                originalFlags = flags;
                rideOwned = true;
                controlMessage = "Starting the ride. End ride is available to cancel.";
                publish(true, "Starting ride", "Waiting for the bike to confirm its ride state.");
                log("RIDE_ORIGINAL_FLAGS lockout=" + flags.idleLockout + " requireStart=" + flags.requireStart);
            }
            @Override public void confirmFlag(int field, boolean enabled) throws Exception {
                setStartupFlag(field, enabled);
            }
            @Override public void checkpoint() throws Exception {
                checkStopped();
                checkEndRequested();
            }
        };
    }

    private void setStartupFlag(int field, boolean desired) throws Exception {
        if (field != 95 && field != 108) throw new IOException("Unexpected startup flag.");
        long sampled = SystemClock.elapsedRealtime();
        Double mode = readControlValue(12);
        if (mode == null || mode != 1.0) throw new IOException("Startup flags can only change while the bike is idle.");
        Double current = readControlValue(field);

        if ((endingRide || cleaningUp) && current != null && current == (desired ? 1.0 : 0.0)) return;
        BikeControlPolicy.Target target = field == 95
                ? BikeControlPolicy.idleLockoutTarget(equipmentId, current, desired, sampled, SystemClock.elapsedRealtime())
                : BikeControlPolicy.requireStartTarget(equipmentId, current, desired, sampled, SystemClock.elapsedRealtime());
        confirmStateWrite(target, sampled);
    }

    private void confirmStateWrite(BikeControlPolicy.Target target, long sampled) throws Exception {
        if ((target.fieldId == 95 || target.fieldId == 108) && !rideOwned) {
            throw new IOException("Startup flag changes require saved ride ownership.");
        }
        if (cleaningUp && target.fieldId == 12 && target.value != 1.0 && target.value != 4.0) {
            throw new IOException("Cleanup can only end a ride.");
        }
        if (cleaningUp && target.fieldId != 12 && (originalFlags == null
                || (target.fieldId != 95 && target.fieldId != 108)
                || (target.fieldId == 95 && target.value != originalFlags.idleLockout)
                || (target.fieldId == 108 && (originalFlags.requireStart == null
                    || target.value != originalFlags.requireStart)))) {
            throw new IOException("Cleanup may only restore recorded startup flags.");
        }
        FitPro.Response response = exchange(FitPro.manualControl(target), sampled + 2000);
        requireControlDone(response, target);
        long deadline = Math.min(SystemClock.elapsedRealtime() + 4000,
                cleaningUp ? cleanupDeadline : Long.MAX_VALUE);
        Double value = response.value;
        while ((value == null || value != target.value) && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(150);
            value = readControlValue(target.fieldId);
        }
        if (value == null || value != target.value) throw new ControlNotConfirmed("field=" + target.fieldId
                + " requested=" + target.value + " returned=" + value);
        values.put(target.fieldId, value);
        log("RIDE_STATE field=" + target.fieldId + " value=" + value);
    }

    private Double readControlValue(int field) throws Exception {
        if (!fields.contains(field)) throw new IOException("Required control readback is not advertised.");
        FitPro.Response response = exchange(FitPro.readField(2, field));
        requireDone(response);
        Double value = response.value;
        if (field == 2) {
            byte[] bytes = response.payload();
            value = bytes.length != 2 || ((bytes[0] & 255) == 255 && (bytes[1] & 255) == 255)
                    ? null : (double) ((bytes[0] & 255) | ((bytes[1] & 255) << 8));
        }
        if (value == null) values.remove(field);
        else values.put(field, value);
        return value;
    }

    private void requireControlDone(FitPro.Response response, BikeControlPolicy.Target target)
            throws ControlNotConfirmed {
        if (response.isDone()) return;
        String details = "field=" + target.fieldId + " requested=" + target.value + " status="
                + response.status + " (" + FitPro.statusName(response.status) + ") RX=" + FitPro.hex(response.frame());
        log("CONTROLLER_REJECTED " + details);
        throw new ControlNotConfirmed(response.status, details);
    }

    private void checkStopped() throws InterruptedException {
        if (!cleaningUp && (stopped || Thread.currentThread().isInterrupted())) throw new InterruptedException();
    }

    private static final class EndRequested extends Exception { }

    private void checkEndRequested() throws EndRequested {
        if (!endingRide && !cleaningUp && pendingAction == Action.END_RIDE) throw new EndRequested();
    }

    private void publish(boolean running, String title, String detail) {
        Integer testGear = BikeControlPolicy.observedGearLevel(lastGearProfile, gearLimit);
        Integer fullRangeLevel = BikeControlPolicy.observedGearLevel(lastGearProfile, 24);
        String rangeMessage = !gearWritesBlocked && testGear == null && fullRangeLevel != null
                ? "Reported level is outside app range 1–" + gearLimit + ". Change the test range in Settings while idle."
                : gearMessage;
        boolean gearAvailable = running && controlsEnabled && rideOwned && !gearWritesBlocked && testGear != null
                && equipmentId == BikeControlPolicy.EQUIPMENT_ID
                && Double.valueOf(2).equals(values.get(12)) && Double.valueOf(0).equals(values.get(119));
        ControllerDiagnostics.Snapshot diagnostic = ControllerDiagnostics.describe(running,
                lastReportedControllerMode, controllerModeSampledAt, SystemClock.elapsedRealtime(),
                terminalIssue == null ? observedIssue : terminalIssue,
                terminalIssue == null ? observedIssueDetail : terminalIssueDetail, reportedGearProfile);
        diagnostic = actionFailure.withTelemetry(diagnostic, terminalIssue != null);
        State state = new State(running, title, detail, values, maxResistance,
                lastSampleAt, equipmentId, fields, controlsEnabled,
                controlBusy || pendingAction != null || inclineInProgress != null,
                actionFailure.controlMessage(controlMessage), rideOwned,
                testGear, gearAvailable, gearLimit, rangeMessage, diagnostic);
        if (AppPrivacy.diagnostics(context)) try {
            JSONObject json = new JSONObject();
            json.put("running", running);
            json.put("title", title);
            json.put("detail", detail);
            json.put("equipmentId", equipmentId);
            json.put("controllerAuthenticated", controllerAuthenticated);
            json.put("maxResistance", maxResistance == null ? JSONObject.NULL : maxResistance);
            json.put("supportedFields", fields.toString());
            json.put("sampledAtElapsedMs", state.sampledAt);
            json.put("controlsEnabled", controlsEnabled);
            json.put("controlPending", state.controlPending);
            json.put("controlMessage", state.controlMessage);
            json.put("rideOwned", rideOwned);
            json.put("testGear", testGear == null ? JSONObject.NULL : testGear);
            json.put("gearControlsEnabled", gearAvailable);
            json.put("gearWritesBlocked", gearWritesBlocked);
            json.put("gearMessage", gearMessage);
            json.put("controllerDiagnostic", state.controllerDiagnostic);
            json.put("controllerIssue", state.controllerIssue);
            json.put("controllerMode", state.controllerMode == null ? JSONObject.NULL : state.controllerMode);
            json.put("observedGear", state.observedGear == null ? JSONObject.NULL : state.observedGear);
            json.put("gearProfile", state.gearProfile);
            JSONObject metrics = new JSONObject();
            for (Map.Entry<Integer, Double> entry : values.entrySet()) metrics.put(entry.getKey().toString(), entry.getValue());
            json.put("fields", metrics);
            JSONObject rawFields = new JSONObject();
            for (Map.Entry<Integer, String> entry : diagnosticRawFields.entrySet()) {
                rawFields.put(entry.getKey().toString(), entry.getValue());
            }
            json.put("diagnosticRawFields", rawFields);
            File temporary = new File(context.getFilesDir(), "telemetry-next.json");
            try (FileWriter file = new FileWriter(temporary, false)) {
                file.write(json.toString(2));
            }
            if (!temporary.renameTo(new File(context.getFilesDir(), "telemetry.json"))) {
                throw new IOException("Could not replace telemetry snapshot.");
            }
        } catch (Exception error) {
            Log.w("S22i", "Could not save telemetry snapshot");
        }
        main.post(() -> { if (!stopped || !state.running) listener.onState(state); });
    }

    private static String traceFrame(byte[] frame) {
        return ProtocolLog.frame(frame);
    }

    private void log(String message) {

        if (trace == null) return;
        try {
            if (new File(context.getFilesDir(), "protocol-trace.txt").length() > 512 * 1024) {
                trace.close();
                trace = new FileWriter(new File(context.getFilesDir(), "protocol-trace.txt"), false);
            }
            trace.write(SystemClock.elapsedRealtime() + " " + message + "\n");
            trace.flush();
        } catch (IOException error) {
            Log.w("S22i", "Could not write protocol trace");
        }
    }
}
