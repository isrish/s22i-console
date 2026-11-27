package dev.s22i.console;

import java.io.IOException;
import java.util.Arrays;

public final class ControllerDiagnosticsTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        modeNamesAndValues();
        typedFailureClassification();
        rejectedControlRetainsStatusDuringCleanup();
        rejectedActionSurvivesHealthyTelemetry();
        controllerStatuses();
        healthyAndMissingModes();
        staleAndDisconnectedModes();
        explicitIssuesTakePriority();
        observedGearIsInformational();
        profileEvidenceIsPreserved();
        System.out.println("ControllerDiagnostics: " + passed + " assertions passed");
    }

    private static void modeNamesAndValues() {
        int[] modes = {1, 2, 3, 4, 10, 12, 14};
        String[] names = {"Idle", "Running", "Paused", "Results", "Warmup", "Sleep", "Locked"};
        for (int index = 0; index < modes.length; index++) equal(names[index], ControllerDiagnostics.modeName(modes[index]));
        equal("Unavailable", ControllerDiagnostics.modeName(null));
        for (int mode : new int[] {-1, 0, 5, 11, 13, 15, 254, 255}) equal("Unknown", ControllerDiagnostics.modeName(mode));
        for (int mode = 0; mode <= 254; mode++) check(ControllerDiagnostics.modeValue((double) mode) == mode);
        for (Double invalid : new Double[] {null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                -1.0, -0.01, 0.01, 2.5, 254.1, 255.0, 256.0, Double.MAX_VALUE}) {
            check(ControllerDiagnostics.modeValue(invalid) == null);
        }
    }

    private static void typedFailureClassification() throws Exception {
        for (ControllerDiagnostics.Issue issue : ControllerDiagnostics.Issue.values()) {
            ControllerDiagnostics.Failure typed = new ControllerDiagnostics.Failure(issue,
                    "Misleading text: authentication required; USB asleep; malformed packet");
            check(typed instanceof IOException && typed.issue == issue);
            check(ControllerDiagnostics.classify(typed) == issue);
        }
        FitPro.ProtocolException cause = new FitPro.ProtocolException("USB timeout, not a protocol keyword");
        ControllerDiagnostics.Failure transfer = new ControllerDiagnostics.Failure(
                ControllerDiagnostics.Issue.USB_WRITE_TRANSFER, "Device locked or sleeping", cause);
        check(transfer.getCause() == cause);
        check(ControllerDiagnostics.classify(transfer) == ControllerDiagnostics.Issue.USB_WRITE_TRANSFER);
        check(ControllerDiagnostics.classify(cause) == ControllerDiagnostics.Issue.MALFORMED_PROTOCOL);
        for (String message : new String[] {"authentication required", "USB timeout", "sleep", "checksum mismatch"}) {
            check(ControllerDiagnostics.classify(new IOException(message)) == ControllerDiagnostics.Issue.SESSION_ERROR);
        }
        check(ControllerDiagnostics.classify(null) == ControllerDiagnostics.Issue.SESSION_ERROR);
        try {
            FitPro.parseResponse(FitPro.readField(2, 26), new byte[] {8, 5, 2, 2, 0x11}, 8);
            throw new AssertionError("Accepted empty gear payload");
        } catch (FitPro.FieldWidthException width) {
            check(ControllerDiagnostics.classify(width) == ControllerDiagnostics.Issue.MALFORMED_PROTOCOL);
        }
    }

    private static void rejectedControlRetainsStatusDuringCleanup() throws Exception {
        FitPro.Request request = FitPro.manualControl(BikeControlPolicy.idleLockoutTarget(
                8, 1.0, false, 10000, 10000));
        FitPro.Response securityBlock = FitPro.parseResponse(request,
                new byte[] {8, 5, 2, 8, 0x17}, 8);
        check(!securityBlock.isDone() && securityBlock.needsAuthentication());

        ControllerDiagnostics.ControlNotConfirmed rejection = new ControllerDiagnostics.ControlNotConfirmed(
                securityBlock.status, "field=95 requested=0 status=8");
        check(rejection instanceof IOException);
        check(ControllerDiagnostics.classify(rejection) == ControllerDiagnostics.Issue.AUTHENTICATION_REQUIRED);
        ControllerDiagnostics.Snapshot shutdown = ControllerDiagnostics.describe(false, 1, 10000, 10500,
                ControllerDiagnostics.classify(rejection), rejection.getMessage(), null);
        equal("AUTHENTICATION_REQUIRED", shutdown.controllerIssue);
        check(shutdown.controllerDiagnostic.startsWith("Controller requires authentication."));
        check(shutdown.controllerDiagnostic.contains("Last reported mode: Idle (1) — stale"));

        try {
            throw rejection;
        } catch (ControllerDiagnostics.ControlNotConfirmed caught) {
            check(caught == rejection);
            check(caught.issue == ControllerDiagnostics.Issue.AUTHENTICATION_REQUIRED);
        }
        check(ControllerDiagnostics.classify(new ControllerDiagnostics.ControlNotConfirmed("readback timed out"))
                == ControllerDiagnostics.Issue.CONTROLLER_REJECTED);
        for (int status : new int[] {2, 4, 5}) {
            check(ControllerDiagnostics.classify(new ControllerDiagnostics.ControlNotConfirmed(status, "not confirmed"))
                    == ControllerDiagnostics.Issue.CONTROLLER_REJECTED);
        }
    }

    private static void rejectedActionSurvivesHealthyTelemetry() throws Exception {
        ControllerDiagnostics.ActionFailureLatch latch = new ControllerDiagnostics.ActionFailureLatch();
        check(!latch.observeFailure(ControllerDiagnostics.Issue.AUTHENTICATION_REQUIRED));
        equal("Manual controls are off.", latch.controlMessage("Manual controls are off."));
        FitPro.Request warmup = FitPro.manualControl(BikeControlPolicy.workoutModeTarget(
                8, 1.0, 10, 10000, 10000));

        FitPro.Response rejection = FitPro.parseResponse(warmup, new byte[] {8, 5, 2, 8, 0x17}, 8);
        latch.begin("START_RIDE");
        check(latch.observeFailure(ControllerDiagnostics.statusIssue(rejection.status)));

        check(latch.observeFailure(ControllerDiagnostics.Issue.CONTROLLER_REJECTED));
        latch.end();
        byte[] profile3 = {0, 0, 0x27, 1, 3, 7, 0x1F, 0x19};
        for (int poll = 0; poll < 20; poll++) {
            long sampled = 11000 + poll * 1000;
            ControllerDiagnostics.Snapshot healthy = ControllerDiagnostics.describe(true, 1, sampled,
                    sampled + 500, ControllerDiagnostics.Issue.OK, "", profile3);
            equal("OK", healthy.controllerIssue);
            ControllerDiagnostics.Snapshot shown = latch.withTelemetry(healthy, false);
            equal("AUTHENTICATION_REQUIRED", shown.controllerIssue);
            check(shown.controllerDiagnostic.startsWith("Start rejected: controller authentication required."));
            check(shown.controllerDiagnostic.contains("Controller mode: Idle (1)."));
            check(!shown.controllerDiagnostic.contains("stale"));
            check(shown.controllerMode == 1 && shown.observedGear == 3);
            equal("00 00 27 01 03 07 1F 19", shown.gearProfile);
            equal("Start rejected: controller authentication required.",
                    latch.controlMessage("App resistance test stopped: unsupported profile."));
        }

        latch.begin("START_RIDE");
        latch.end();
        latch.begin("INCLINE_UP");
        latch.confirmed();
        latch.end();
        equal("Start rejected: controller authentication required.", latch.controlMessage("Incline reached 0.5%."));
        ControllerDiagnostics.Snapshot terminal = describe(false, 1, 10000, 20000,
                ControllerDiagnostics.Issue.USB_NO_RESPONSE);
        ControllerDiagnostics.Snapshot stopped = latch.withTelemetry(terminal, true);
        equal("USB_NO_RESPONSE", stopped.controllerIssue);
        check(stopped.controllerDiagnostic.contains("USB did not respond."));
        check(stopped.controllerDiagnostic.contains("Start rejected: controller authentication required."));

        latch.begin("START_RIDE");
        latch.confirmed();
        latch.end();
        equal("Ride started.", latch.controlMessage("Ride started."));
        check(latch.withTelemetry(terminal, true) == terminal);
        latch.begin("START_RIDE");
        latch.observeFailure(ControllerDiagnostics.Issue.AUTHENTICATION_REQUIRED);
        latch.end();
        latch.begin("END_RIDE");
        equal("Ending the ride…", latch.controlMessage("Ending the ride…"));
        latch.observeFailure(ControllerDiagnostics.Issue.AUTHENTICATION_REQUIRED);
        latch.end();
        equal("End ride rejected: controller authentication required.", latch.controlMessage("Unsupported profile."));
        ControllerDiagnostics.ActionFailureLatch newConnection = new ControllerDiagnostics.ActionFailureLatch();
        equal("Connected.", newConnection.controlMessage("Connected."));
    }

    private static void controllerStatuses() {
        for (int status = 0; status <= 255; status++) {
            ControllerDiagnostics.Issue expected = status == 2 ? ControllerDiagnostics.Issue.OK
                    : status == 8 ? ControllerDiagnostics.Issue.AUTHENTICATION_REQUIRED
                    : ControllerDiagnostics.Issue.CONTROLLER_REJECTED;
            check(ControllerDiagnostics.statusIssue(status) == expected);
        }
        check(ControllerDiagnostics.statusIssue(-1) == ControllerDiagnostics.Issue.CONTROLLER_REJECTED);
        check(ControllerDiagnostics.statusIssue(256) == ControllerDiagnostics.Issue.CONTROLLER_REJECTED);
    }

    private static void healthyAndMissingModes() {
        for (int mode : new int[] {0, 1, 2, 3, 4, 10, 12, 14, 99, 254}) {
            ControllerDiagnostics.Snapshot snapshot = describe(true, mode, 10000, 10500, ControllerDiagnostics.Issue.OK);
            equal("OK", snapshot.controllerIssue);
            check(snapshot.controllerMode == mode);
            equal("Controller mode: " + ControllerDiagnostics.modeName(mode) + " (" + mode + ").",
                    snapshot.controllerDiagnostic);
            check(snapshot.observedGear == null && snapshot.gearProfile.isEmpty());
        }
        ControllerDiagnostics.Snapshot missing = describe(true, null, 0, 10500, ControllerDiagnostics.Issue.OK);
        equal("MODE_UNAVAILABLE", missing.controllerIssue);
        check(missing.controllerMode == null && missing.controllerDiagnostic.contains("No controller mode reported"));
        ControllerDiagnostics.Snapshot sleep = describe(true, 12, 10000, 10500, ControllerDiagnostics.Issue.OK);
        check(sleep.controllerDiagnostic.contains("Sleep (12)") && !sleep.controllerDiagnostic.contains("Running"));
        ControllerDiagnostics.Snapshot locked = describe(true, 14, 10000, 10500, ControllerDiagnostics.Issue.OK);
        check(locked.controllerDiagnostic.contains("Locked (14)") && !locked.controllerDiagnostic.contains("Running"));
    }

    private static void staleAndDisconnectedModes() {
        for (long[] times : new long[][] {{0, 10000}, {-1, 10000}, {10000, 9999}, {10000, 13001}}) {
            ControllerDiagnostics.Snapshot stale = describe(true, 2, times[0], times[1], ControllerDiagnostics.Issue.OK);
            equal("STALE_TELEMETRY", stale.controllerIssue);
            check(stale.controllerMode == 2);
            check(stale.controllerDiagnostic.contains("Last reported mode: Running (2) — stale"));
        }
        for (long now : new long[] {10000, 12999, 13000}) {
            equal("OK", describe(true, 2, 10000, now, ControllerDiagnostics.Issue.OK).controllerIssue);
        }
        equal("OK", describe(true, 1, Long.MAX_VALUE, Long.MAX_VALUE, ControllerDiagnostics.Issue.OK).controllerIssue);
        ControllerDiagnostics.Snapshot disconnected = describe(false, 3, 10000, 10500, ControllerDiagnostics.Issue.OK);
        equal("DISCONNECTED", disconnected.controllerIssue);
        check(disconnected.controllerMode == 3);
        check(disconnected.controllerDiagnostic.contains("Last reported mode: Paused (3) — stale"));
        ControllerDiagnostics.Snapshot absent = describe(false, null, 0, 10500, ControllerDiagnostics.Issue.OK);
        equal("DISCONNECTED", absent.controllerIssue);
        check(absent.controllerDiagnostic.contains("No controller mode reported"));
    }

    private static void explicitIssuesTakePriority() {
        for (ControllerDiagnostics.Issue issue : ControllerDiagnostics.Issue.values()) {
            if (issue == ControllerDiagnostics.Issue.OK) continue;
            ControllerDiagnostics.Snapshot snapshot = describe(true, 2, 10000, 10500, issue);
            equal(issue.name(), snapshot.controllerIssue);
            check(snapshot.controllerMode == 2);
            check(snapshot.controllerDiagnostic.contains("Last reported mode: Running (2) — stale"));
            equal(issue.name(), describe(false, null, 0, 10500, issue).controllerIssue);
        }
        ControllerDiagnostics.Snapshot timeout = ControllerDiagnostics.describe(true, 2, 10000, 10500,
                ControllerDiagnostics.Issue.USB_NO_RESPONSE, "No complete controller response (0 bytes).", null);
        equal("USB_NO_RESPONSE", timeout.controllerIssue);
        check(timeout.controllerDiagnostic.startsWith("USB did not respond."));
        check(timeout.controllerDiagnostic.contains("No complete controller response (0 bytes)."));
        check(!timeout.controllerDiagnostic.contains("Sleep") && !timeout.controllerDiagnostic.contains("asleep"));
        ControllerDiagnostics.Snapshot previousSleep = describe(false, 12, 10000, 20000,
                ControllerDiagnostics.Issue.USB_NO_RESPONSE);
        equal("USB_NO_RESPONSE", previousSleep.controllerIssue);
        check(previousSleep.controllerDiagnostic.contains("Last reported mode: Sleep (12) — stale"));
    }

    private static void observedGearIsInformational() {
        byte[] one = {0, 0, 0x27, 1, 1, 7, 0x21, 0x19};
        byte[] two = {0, 0, 0x27, 1, 2, 7, 0x20, 0x19};

        byte[] three = {0, 0, 0x27, 1, 3, 7, 0x1F, 0x19};
        check(ControllerDiagnostics.observedGear(one) == 1);
        check(ControllerDiagnostics.observedGear(two) == 2);
        check(ControllerDiagnostics.observedGear(three) == 3);
        check(BikeControlPolicy.observedGearLevel(three) == null);
        check(ControllerDiagnostics.observedGear(new byte[8]) == 0);
        byte[] unsigned = new byte[8];
        unsigned[4] = (byte) 255;
        check(ControllerDiagnostics.observedGear(unsigned) == 255);
        byte[] sentinel = new byte[8];
        Arrays.fill(sentinel, (byte) 255);
        for (byte[] invalid : new byte[][] {null, new byte[0], new byte[7], new byte[9], sentinel}) {
            check(ControllerDiagnostics.observedGear(invalid) == null);
        }
    }

    private static void profileEvidenceIsPreserved() {
        byte[] profile = {0, 0, 0x27, 1, 3, 7, 0x1F, 0x19};
        ControllerDiagnostics.Snapshot snapshot = ControllerDiagnostics.describe(false, 2, 10000, 20000,
                ControllerDiagnostics.Issue.DISCONNECTED, null, profile);
        equal("00 00 27 01 03 07 1F 19", snapshot.gearProfile);
        check(snapshot.observedGear == 3 && snapshot.controllerMode == 2);
        profile[4] = 99;
        check(snapshot.observedGear == 3);
        equal("00 00 27 01 03 07 1F 19", snapshot.gearProfile);
        byte[] malformed = {1, (byte) 255, 3};
        ControllerDiagnostics.Snapshot raw = ControllerDiagnostics.describe(true, 2, 10000, 10500,
                ControllerDiagnostics.Issue.OK, "", malformed);
        equal("01 FF 03", raw.gearProfile);
        check(raw.observedGear == null);
        byte[] sentinel = new byte[8];
        Arrays.fill(sentinel, (byte) 255);
        ControllerDiagnostics.Snapshot noGear = ControllerDiagnostics.describe(true, 2, 10000, 10500,
                ControllerDiagnostics.Issue.OK, null, sentinel);
        equal("FF FF FF FF FF FF FF FF", noGear.gearProfile);
        check(noGear.observedGear == null);
    }

    private static ControllerDiagnostics.Snapshot describe(boolean connected, Integer mode, long sampled,
                                                           long now, ControllerDiagnostics.Issue issue) {
        return ControllerDiagnostics.describe(connected, mode, sampled, now, issue, null, null);
    }

    private static void equal(String expected, String actual) { check(expected.equals(actual)); }
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Assertion failed");
        passed++;
    }
}
