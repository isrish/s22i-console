package dev.s22i.console;

import java.io.IOException;

public final class ControllerDiagnostics {
    private ControllerDiagnostics() { }

    public enum Issue {
        OK, MODE_UNAVAILABLE, DISCONNECTED, STALE_TELEMETRY, AUTHENTICATION_REQUIRED,
        USB_NO_RESPONSE, USB_WRITE_TRANSFER, MALFORMED_PROTOCOL, CONTROLLER_REJECTED,
        USB_UNAVAILABLE, SESSION_ERROR
    }

    public static class Failure extends IOException {
        public final Issue issue;

        public Failure(Issue issue, String message) {
            super(message);
            this.issue = issue == null ? Issue.SESSION_ERROR : issue;
        }

        public Failure(Issue issue, String message, Throwable cause) {
            super(message, cause);
            this.issue = issue == null ? Issue.SESSION_ERROR : issue;
        }
    }

    public static final class ControlNotConfirmed extends Failure {
        public ControlNotConfirmed(String message) {
            super(Issue.CONTROLLER_REJECTED, message);
        }

        public ControlNotConfirmed(int status, String message) {

            super(status == FitPro.DONE ? Issue.CONTROLLER_REJECTED : statusIssue(status), message);
        }
    }

    public static Issue classify(Throwable failure) {
        if (failure instanceof Failure) return ((Failure) failure).issue;
        if (failure instanceof FitPro.ProtocolException) return Issue.MALFORMED_PROTOCOL;
        return Issue.SESSION_ERROR;
    }

    public static Issue statusIssue(int status) {
        if (status == 2) return Issue.OK;
        if (status == 8) return Issue.AUTHENTICATION_REQUIRED;
        return Issue.CONTROLLER_REJECTED;
    }

    public static String modeName(Integer mode) {
        if (mode == null) return "Unavailable";
        switch (mode) {
            case 1: return "Idle";
            case 2: return "Running";
            case 3: return "Paused";
            case 4: return "Results";
            case 10: return "Warmup";
            case 12: return "Sleep";
            case 14: return "Locked";
            default: return "Unknown";
        }
    }

    public static Integer modeValue(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()
                || value < 0 || value > 254 || value != Math.rint(value)) return null;
        return value.intValue();
    }

    public static Integer observedGear(byte[] profile) {
        if (profile == null || profile.length != 8) return null;
        boolean sentinel = true;
        for (byte value : profile) if ((value & 255) != 255) sentinel = false;
        return sentinel ? null : profile[4] & 255;
    }

    public static final class ActionFailureLatch {
        private String activeAction;
        private String failedAction;
        private Issue issue;
        private String message;

        public void begin(String action) {
            activeAction = action;
            if ("END_RIDE".equals(action)) clearFailure();
        }

        public void end() { activeAction = null; }

        public boolean observeFailure(Issue failure) {
            if (activeAction == null) return false;
            if (activeAction.equals(failedAction) && issue == Issue.AUTHENTICATION_REQUIRED
                    && failure != Issue.AUTHENTICATION_REQUIRED) return true;
            failedAction = activeAction;
            issue = failure == null || failure == Issue.OK ? Issue.CONTROLLER_REJECTED : failure;
            String label = "START_RIDE".equals(activeAction) ? "Start"
                    : "END_RIDE".equals(activeAction) ? "End ride"
                    : activeAction.startsWith("RESISTANCE_") ? "Resistance adjustment" : "Incline adjustment";
            message = label + (issue == Issue.AUTHENTICATION_REQUIRED
                    ? " rejected: controller authentication required." : " was not confirmed.");
            return true;
        }

        public void confirmed() {
            if (activeAction != null && activeAction.equals(failedAction)) clearFailure();
        }

        public String controlMessage(String currentMessage) {
            return failedAction == null ? currentMessage : message;
        }

        public Snapshot withTelemetry(Snapshot telemetry, boolean terminalFailure) {
            if (failedAction == null) return telemetry;
            return new Snapshot(message + " " + telemetry.controllerDiagnostic,
                    terminalFailure ? telemetry.controllerIssue : issue.name(), telemetry.controllerMode,
                    telemetry.observedGear, telemetry.gearProfile);
        }

        private void clearFailure() {
            failedAction = null;
            issue = null;
            message = null;
        }
    }

    public static final class Snapshot {
        public final String controllerDiagnostic;
        public final String controllerIssue;
        public final Integer controllerMode;
        public final Integer observedGear;
        public final String gearProfile;

        private Snapshot(String diagnostic, Issue issue, Integer mode, byte[] profile) {
            this(diagnostic, issue.name(), mode, ControllerDiagnostics.observedGear(profile),
                    profile == null ? "" : FitPro.hex(profile));
        }

        private Snapshot(String diagnostic, String issue, Integer mode, Integer gear, String profile) {
            this.controllerDiagnostic = diagnostic;
            this.controllerIssue = issue;
            this.controllerMode = mode;
            this.observedGear = gear;
            this.gearProfile = profile;
        }
    }

    public static Snapshot describe(boolean connected, Integer lastMode, long modeSampledAt,
                                    long now, Issue issue, String issueDetail, byte[] profile) {
        Issue effective = issue == null ? Issue.SESSION_ERROR : issue;
        boolean fresh = modeSampledAt > 0 && now >= modeSampledAt && now - modeSampledAt <= 3000;
        if (effective == Issue.OK) {
            if (!connected) effective = Issue.DISCONNECTED;
            else if (lastMode == null) effective = Issue.MODE_UNAVAILABLE;
            else if (!fresh) effective = Issue.STALE_TELEMETRY;
        }
        String modeDescription;
        if (lastMode == null) {
            modeDescription = "No controller mode reported.";
        } else if (connected && effective == Issue.OK && fresh) {
            modeDescription = "Controller mode: " + modeName(lastMode) + " (" + lastMode + ").";
        } else {
            modeDescription = "Last reported mode: " + modeName(lastMode) + " (" + lastMode + ") — stale.";
        }
        String summary = issueSummary(effective);
        String diagnostic = summary.isEmpty() ? modeDescription : summary + " " + modeDescription;
        if (issueDetail != null && !issueDetail.trim().isEmpty()) diagnostic += " " + issueDetail.trim();
        return new Snapshot(diagnostic, effective, lastMode, profile);
    }

    private static String issueSummary(Issue issue) {
        switch (issue) {
            case OK: return "";
            case MODE_UNAVAILABLE: return "Controller mode is unavailable.";
            case DISCONNECTED: return "USB disconnected.";
            case STALE_TELEMETRY: return "Telemetry is stale.";
            case AUTHENTICATION_REQUIRED: return "Controller requires authentication.";
            case USB_NO_RESPONSE: return "USB did not respond.";
            case USB_WRITE_TRANSFER: return "USB request transfer was incomplete.";
            case MALFORMED_PROTOCOL: return "Controller reply failed protocol validation.";
            case CONTROLLER_REJECTED: return "Controller rejected the request.";
            case USB_UNAVAILABLE: return "USB controller is unavailable.";
            default: return "Controller session error.";
        }
    }
}
