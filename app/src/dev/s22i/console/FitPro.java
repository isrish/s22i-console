package dev.s22i.console;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class FitPro {
    public static final int MAX_FRAME_SIZE = 64;
    public static final int MAIN_DEVICE = 2;
    public static final int READ_WRITE_DATA = 2;
    public static final int SUPPORTED_DEVICES = 128;
    public static final int DEVICE_INFO = 129;
    public static final int SYSTEM_INFO = 130;
    public static final int VERSION_INFO = 132;
    public static final int SUPPORTED_COMMANDS = 136;
    public static final int VERIFY_SECURITY = 144;
    public static final int DONE = 2;
    public static final int SECURITY_BLOCK = 8;

    public static final Field GRADE = new Field(1, "Target incline", "GradeConverter", 2, 0.01, true, "%");

    public static final Field RESISTANCE = new Field(2, "Resistance readback", "ResistanceConverter", 2, Double.NaN, false, "");
    public static final Field WATTS = new Field(3, "Watts", "ShortConverter", 2, 1.0, false, "W");
    public static final Field RPM = new Field(5, "RPM", "ShortConverter", 2, 1.0, false, "rpm");

    public static final Field KEY_OBJECT = new Field(7, "Key object", "KeyObjectConverter", 14, Double.NaN, false, "");
    public static final Field WORKOUT_MODE = new Field(12, "Workout mode", "ByteConverter", 1, 1.0, false, "");
    public static final Field ACTUAL_KPH = new Field(16, "Actual speed", "SpeedConverter", 2, 0.01, false, "km/h");
    public static final Field ACTUAL_INCLINE = new Field(17, "Actual incline", "GradeConverter", 2, 0.01, true, "%");

    public static final Field ACTUAL_RESISTANCE = new Field(18, "Actual resistance", "ResistanceConverter", 0, Double.NaN, false, "");
    public static final Field GEAR = new Field(26, "Gear", "GearConverter", 8, Double.NaN, false, "");
    public static final Field MAX_GRADE = new Field(27, "Maximum incline", "GradeConverter", 2, 0.01, true, "%");
    public static final Field MIN_GRADE = new Field(28, "Minimum incline", "GradeConverter", 2, 0.01, true, "%");
    public static final Field MAX_RESISTANCE_LEVEL = new Field(42, "Maximum resistance", "ByteConverter", 1, 1.0, false, "levels");
    public static final Field WATT_GOAL = new Field(61, "Watt goal", "ShortConverter", 2, 1.0, false, "W");
    public static final Field IDLE_MODE_LOCKOUT = new Field(95, "Idle mode lockout", "BoolConverter", 1, 1.0, false, "");
    public static final Field REQUIRE_START_REQUESTED = new Field(108, "Require start request", "BoolConverter", 1, 1.0, false, "");
    public static final Field IS_CONSTANT_WATTS_MODE = new Field(119, "Constant watts mode", "ByteConverter", 1, 1.0, false, "");

    private FitPro() { }

    public static final class Field {
        public final int id;
        public final String name;
        public final String converter;
        public final int width;
        public final double scale;
        public final boolean signed;
        public final String unit;

        private Field(int id, String name, String converter, int width,
                      double scale, boolean signed, String unit) {
            this.id = id;
            this.name = name;
            this.converter = converter;
            this.width = width;
            this.scale = scale;
            this.signed = signed;
            this.unit = unit;
        }

        public boolean hasKnownEncoding() {
            return width > 0 && !Double.isNaN(scale);
        }
    }

    public static final class Request {
        public final int deviceId;
        public final int commandId;

        public final int fieldId;
        public final Field field;
        private final byte[] frame;

        private Request(int deviceId, int commandId, Field field, byte[] payload) {
            requireByte(deviceId, "device id");
            if (commandId != READ_WRITE_DATA && commandId != SUPPORTED_DEVICES
                    && commandId != DEVICE_INFO && commandId != SYSTEM_INFO
                    && commandId != VERSION_INFO && commandId != VERIFY_SECURITY
                    && commandId != SUPPORTED_COMMANDS) {
                throw new IllegalArgumentException("Command is outside the permitted protocol operations");
            }
            this.deviceId = deviceId;
            this.commandId = commandId;
            this.field = field;
            this.fieldId = field == null ? -1 : field.id;
            int length = payload.length + 4;
            if (length > MAX_FRAME_SIZE) throw new IllegalArgumentException("Request exceeds frame limit");
            frame = new byte[length];
            frame[0] = (byte) deviceId;
            frame[1] = (byte) length;
            frame[2] = (byte) commandId;
            System.arraycopy(payload, 0, frame, 3, payload.length);
            frame[length - 1] = (byte) checksum(frame, length - 1);
        }

        public byte[] bytes() {
            return frame.clone();
        }

        public byte[] usbReportBytes() {
            return Arrays.copyOf(frame, MAX_FRAME_SIZE);
        }
    }

    public static Request supportedDevices() {
        return new Request(MAIN_DEVICE, SUPPORTED_DEVICES, null, new byte[0]);
    }

    public static Request deviceInfo(int deviceId) {
        return new Request(deviceId, DEVICE_INFO, null, new byte[0]);
    }

    public static Request supportedCommands(int deviceId) {
        return new Request(deviceId, SUPPORTED_COMMANDS, null, new byte[0]);
    }

    public static Request systemInfo(int deviceId) {
        return new Request(deviceId, SYSTEM_INFO, null, new byte[] {0, 0, 0});
    }

    public static Request versionInfo(int deviceId) {
        return new Request(deviceId, VERSION_INFO, null, new byte[] {0, 0});
    }

    public static Request verifySecurity(int deviceId, FitProAuthentication.Metadata metadata) {
        if (deviceId != MAIN_DEVICE || metadata == null) {
            throw new IllegalArgumentException("Main controller and validated authentication metadata required");
        }
        return new Request(deviceId, VERIFY_SECURITY, null, metadata.authenticationPayload());
    }

    public static int securityUnlockKey(Response response) throws ProtocolException {
        if (response == null || response.commandId != VERIFY_SECURITY || !response.isDone()
                || response.payload.length < 1) {
            throw new ProtocolException("Controller authentication acknowledgement is incomplete or rejected");
        }
        return unsigned(response.payload[0]);
    }

    public static Request readField(int deviceId, int fieldId) {
        Field field = field(fieldId);
        int maskLength = fieldId / 8 + 1;
        byte[] payload = new byte[2 + maskLength];
        payload[0] = 0;
        payload[1] = (byte) maskLength;
        payload[2 + fieldId / 8] = (byte) (1 << (fieldId % 8));
        return new Request(deviceId, READ_WRITE_DATA, field, payload);
    }

    public static Request manualControl(BikeControlPolicy.Target target) {
        if (target == null || target.equipmentId != BikeControlPolicy.EQUIPMENT_ID
                || Double.isNaN(target.value) || Double.isInfinite(target.value)) {
            throw new IllegalArgumentException("A validated bike control target is required");
        }
        final int raw;
        final Field field;
        if (target.encoding == BikeControlPolicy.Encoding.RESISTANCE_RAW
                && target.kind == BikeControlPolicy.Kind.RESISTANCE && target.fieldId == 2) {
            if (target.value != Math.rint(target.value)
                    || target.value < BikeControlPolicy.RESISTANCE_RAW_MIN
                    || target.value > BikeControlPolicy.RESISTANCE_RAW_MAX) {
                throw new IllegalArgumentException("Raw resistance target is outside validated bounds");
            }
            raw = (int) target.value;
            field = RESISTANCE;
        } else if (target.encoding == BikeControlPolicy.Encoding.GRADE_PERCENT
                && target.kind == BikeControlPolicy.Kind.INCLINE && target.fieldId == 1) {
            if (target.value < BikeControlPolicy.INCLINE_MIN || target.value > BikeControlPolicy.INCLINE_MAX) {
                throw new IllegalArgumentException("Incline target is outside validated bounds");
            }
            raw = (int) Math.round(target.value * 100.0);
            field = GRADE;
        } else if (target.encoding == BikeControlPolicy.Encoding.GEAR_PROFILE
                && target.kind == BikeControlPolicy.Kind.GEAR && target.fieldId == 26
                && BikeControlPolicy.validGearLimit(target.gearLimit)
                && target.value >= 1 && target.value <= target.gearLimit) {
            Integer sourceGear = BikeControlPolicy.observedGearLevel(target.sourceGearProfile(), target.gearLimit);
            Integer expectedGear = BikeControlPolicy.observedGearLevel(target.expectedGearProfile(), target.gearLimit);
            if (sourceGear == null || expectedGear == null || expectedGear != target.value
                    || Math.abs(sourceGear - expectedGear) != 1) {
                throw new IllegalArgumentException("Gear target must retain the verified source and expected profiles");
            }
            raw = (int) target.value;
            field = GEAR;
        } else if (target.encoding == BikeControlPolicy.Encoding.BYTE
                && target.kind == BikeControlPolicy.Kind.WORKOUT_MODE && target.fieldId == 12
                && (target.value == 1 || target.value == 2 || target.value == 4 || target.value == 10)) {
            raw = (int) target.value;
            field = WORKOUT_MODE;
        } else if (target.encoding == BikeControlPolicy.Encoding.BYTE
                && target.kind == BikeControlPolicy.Kind.IDLE_LOCKOUT && target.fieldId == 95
                && (target.value == 0 || target.value == 1)) {
            raw = (int) target.value;
            field = IDLE_MODE_LOCKOUT;
        } else if (target.encoding == BikeControlPolicy.Encoding.BYTE
                && target.kind == BikeControlPolicy.Kind.REQUIRE_START && target.fieldId == 108
                && (target.value == 0 || target.value == 1)) {
            raw = (int) target.value;
            field = REQUIRE_START_REQUESTED;
        } else {
            throw new IllegalArgumentException("Target is not an allowed manual bike control");
        }
        int maskLength = field.id / 8 + 1;
        int maskIndex = field.id / 8;
        int mask = 1 << (field.id % 8);
        byte[] payload = new byte[2 + 2 * maskLength + field.width];
        payload[0] = (byte) maskLength;
        payload[1 + maskIndex] = (byte) mask;
        if (field == GEAR) {

            payload[1 + maskLength + 4] = (byte) raw;
        } else {
            for (int index = 0; index < field.width; index++) payload[1 + maskLength + index] = (byte) (raw >> (8 * index));
        }
        int readOffset = 1 + maskLength + field.width;
        payload[readOffset] = (byte) maskLength;
        payload[readOffset + 1 + maskIndex] = (byte) mask;
        return new Request(MAIN_DEVICE, READ_WRITE_DATA, field, payload);
    }

    public static Field field(int fieldId) {
        if (fieldId < 0 || fieldId > 127) throw new IllegalArgumentException("field id must be 0..127");
        switch (fieldId) {
            case 1: return GRADE;
            case 2: return RESISTANCE;
            case 3: return WATTS;
            case 5: return RPM;
            case 7: return KEY_OBJECT;
            case 12: return WORKOUT_MODE;
            case 16: return ACTUAL_KPH;
            case 17: return ACTUAL_INCLINE;
            case 18: return ACTUAL_RESISTANCE;
            case 26: return GEAR;
            case 27: return MAX_GRADE;
            case 28: return MIN_GRADE;
            case 42: return MAX_RESISTANCE_LEVEL;
            case 61: return WATT_GOAL;
            case 95: return IDLE_MODE_LOCKOUT;
            case 108: return REQUIRE_START_REQUESTED;
            case 119: return IS_CONSTANT_WATTS_MODE;
            default: return new Field(fieldId, "Field " + fieldId, "Unknown", 0, Double.NaN, false, "");
        }
    }

    public static final class Response {
        public final int deviceId;
        public final int commandId;
        public final int status;
        public final Field field;

        public final Double value;

        public final String valueUnavailableReason;
        private final byte[] frame;
        private final byte[] payload;

        private Response(Request request, byte[] frame, Double value, String reason) {
            this.deviceId = unsigned(frame[0]);
            this.commandId = request.commandId;
            this.status = unsigned(frame[3]);
            this.field = request.field;
            this.value = value;
            this.valueUnavailableReason = reason;
            this.frame = frame.clone();
            this.payload = Arrays.copyOfRange(frame, 4, frame.length - 1);
        }

        public boolean isDone() { return status == DONE; }
        public boolean needsAuthentication() { return status == SECURITY_BLOCK; }
        public byte[] frame() { return frame.clone(); }
        public byte[] payload() { return payload.clone(); }
    }

    public static class ProtocolException extends Exception {
        public ProtocolException(String message) { super(message); }
    }

    public static final class FieldWidthException extends ProtocolException {
        public final int fieldId;
        public final int expectedWidth;
        public final int actualWidth;
        private final byte[] frame;

        private FieldWidthException(Field field, int actualWidth, byte[] frame) {
            super("Field " + field.id + " requires " + field.width + " bytes; received " + actualWidth);
            this.fieldId = field.id;
            this.expectedWidth = field.width;
            this.actualWidth = actualWidth;
            this.frame = frame.clone();
        }

        public byte[] frame() { return frame.clone(); }
    }

    public static Response parseResponse(Request request, byte[] frame) throws ProtocolException {
        return parseResponseBound(request, frame, -1);
    }

    public static Response parseResponse(Request request, byte[] frame, int discoveredEquipmentId)
            throws ProtocolException {
        if (discoveredEquipmentId != MAIN_DEVICE && discoveredEquipmentId != 7 && discoveredEquipmentId != 8) {
            throw new IllegalArgumentException("Discovered equipment must be main (2) or bike (7/8)");
        }
        return parseResponseBound(request, frame, discoveredEquipmentId);
    }

    private static Response parseResponseBound(Request request, byte[] frame, int discoveredEquipmentId)
            throws ProtocolException {
        if (request == null) throw new IllegalArgumentException("Request is required");
        if (frame == null || frame.length < 5) throw new ProtocolException("Response is shorter than five bytes");
        if (frame.length > MAX_FRAME_SIZE) throw new ProtocolException("Response exceeds 64 bytes");
        int declared = unsigned(frame[1]);
        if (declared != frame.length) {
            throw new ProtocolException("Declared length " + declared + " differs from received " + frame.length);
        }
        if (checksum(frame, frame.length - 1) != unsigned(frame[frame.length - 1])) {
            throw new ProtocolException("Response checksum mismatch");
        }
        int actualDevice = unsigned(frame[0]);

        boolean bikeDiscovery = request.deviceId == MAIN_DEVICE && request.commandId == DEVICE_INFO
                && (actualDevice == 7 || actualDevice == 8);
        boolean boundBikeRead = request.deviceId == MAIN_DEVICE
                && (request.commandId == READ_WRITE_DATA || request.commandId == SYSTEM_INFO
                    || request.commandId == VERSION_INFO || request.commandId == VERIFY_SECURITY)
                && (discoveredEquipmentId == 7 || discoveredEquipmentId == 8)
                && actualDevice == discoveredEquipmentId;
        if (actualDevice != request.deviceId && !bikeDiscovery && !boundBikeRead) {
            throw new ProtocolException("Response device does not match request or discovered bike");
        }
        if (unsigned(frame[2]) != request.commandId) throw new ProtocolException("Response command does not match request");

        int status = unsigned(frame[3]);
        if (status != DONE) {
            return new Response(request, frame, null,
                    status == SECURITY_BLOCK ? "Authentication required" : "Controller status: " + statusName(status));
        }
        if (request.field == null) return new Response(request, frame, null, "Discovery/identity payload is raw");
        int payloadLength = frame.length - 5;
        Field field = request.field;
        if (field.width > 0 && payloadLength != field.width) {
            throw new FieldWidthException(field, payloadLength, frame);
        }
        if (payloadLength == 0) return new Response(request, frame, null, "No field data returned");
        boolean sentinel = true;
        for (int offset = 4; offset < frame.length - 1; offset++) {
            if (unsigned(frame[offset]) != 255) sentinel = false;
        }
        if (sentinel) return new Response(request, frame, null, "No-value sentinel");
        if (!field.hasKnownEncoding()) {
            return new Response(request, frame, null, "Unknown converter width/scale; raw bytes only");
        }

        long raw = 0;
        for (int index = 0; index < field.width; index++) raw |= (long) unsigned(frame[4 + index]) << (8 * index);
        if (field.signed && (raw & (1L << (8 * field.width - 1))) != 0) raw -= 1L << (8 * field.width);
        return new Response(request, frame, raw * field.scale, null);
    }

    public static Set<Integer> supportedFields(Response response) throws ProtocolException {
        requireDoneCommand(response, DEVICE_INFO);
        byte[] payload = response.payload;
        if (payload.length < 9) throw new ProtocolException("DeviceInfo is too short for supported fields");
        int maskLength = unsigned(payload[8]);
        if (maskLength > 16) throw new ProtocolException("DeviceInfo advertises fields outside 0..127");
        if (payload.length != 9 + maskLength) throw new ProtocolException("DeviceInfo field mask length mismatch");
        Set<Integer> fields = new LinkedHashSet<>();
        for (int index = 0; index < maskLength; index++) {
            int mask = unsigned(payload[9 + index]);
            for (int bit = 0; bit < 8; bit++) if ((mask & (1 << bit)) != 0) fields.add(index * 8 + bit);
        }
        return Collections.unmodifiableSet(fields);
    }

    public static Set<Integer> supportedCommands(Response response) throws ProtocolException {
        requireDoneCommand(response, SUPPORTED_COMMANDS);
        Set<Integer> commands = new LinkedHashSet<>();
        for (byte command : response.payload) commands.add(unsigned(command));
        return Collections.unmodifiableSet(commands);
    }

    private static void requireDoneCommand(Response response, int command) throws ProtocolException {
        if (response == null || response.commandId != command || !response.isDone()) {
            throw new ProtocolException("Expected Done response to command " + command);
        }
    }

    public static String statusName(int status) {
        switch (status) {
            case 0: return "device not supported";
            case 1: return "command not supported";
            case DONE: return "done";
            case 3: return "in progress";
            case 4: return "failed";
            case 5: return "time left";
            case 7: return "unknown failure";
            case SECURITY_BLOCK: return "authentication required";
            case 9: return "communication failed";
            default: return "unknown (" + status + ")";
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder();
        for (byte value : bytes) {
            if (output.length() > 0) output.append(' ');
            output.append(String.format(Locale.US, "%02X", unsigned(value)));
        }
        return output.toString();
    }

    private static int checksum(byte[] bytes, int endExclusive) {
        int sum = 0;
        for (int index = 0; index < endExclusive; index++) sum += unsigned(bytes[index]);
        return sum & 255;
    }

    private static int unsigned(byte value) { return value & 255; }

    private static void requireByte(int value, String name) {
        if (value < 0 || value > 255) throw new IllegalArgumentException(name + " must be 0..255");
    }
}
