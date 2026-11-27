package dev.s22i.console;

import java.util.Arrays;

public final class BikeControlPolicy {
    public static final int EQUIPMENT_ID = 8;
    public static final int RESISTANCE_MIN = 1;
    public static final int RESISTANCE_MAX = 24;
    public static final double INCLINE_MIN = -10.0;
    public static final double INCLINE_MAX = 20.0;
    public static final long MAX_SAMPLE_AGE_MS = 2000;
    public static final int RESISTANCE_RAW_STEP = 10000 / RESISTANCE_MAX;
    public static final int RESISTANCE_RAW_MIN = RESISTANCE_RAW_STEP - 1;
    public static final int RESISTANCE_RAW_MAX = RESISTANCE_MAX * RESISTANCE_RAW_STEP - 1;

    private BikeControlPolicy() { }

    public enum Kind { RESISTANCE, INCLINE, WORKOUT_MODE, IDLE_LOCKOUT, GEAR, REQUIRE_START }
    public enum Encoding { RESISTANCE_LEVEL, RESISTANCE_RAW, GRADE_PERCENT, BYTE, GEAR_PROFILE }

    public static final class Target {
        public final Kind kind;
        public final int equipmentId;
        public final int fieldId;
        public final double value;
        public final Encoding encoding;
        public final int gearLimit;
        private final byte[] sourceGearProfile;
        private final byte[] expectedGearProfile;

        private Target(Kind kind, int fieldId, double value, Encoding encoding) {
            this(kind, fieldId, value, encoding, null, null, 0);
        }

        private Target(Kind kind, int fieldId, double value, Encoding encoding,
                       byte[] sourceGearProfile, byte[] expectedGearProfile, int gearLimit) {
            this.kind = kind;
            this.equipmentId = EQUIPMENT_ID;
            this.fieldId = fieldId;
            this.value = value;
            this.encoding = encoding;
            this.gearLimit = gearLimit;
            this.sourceGearProfile = sourceGearProfile == null ? null : sourceGearProfile.clone();
            this.expectedGearProfile = expectedGearProfile == null ? null : expectedGearProfile.clone();
        }

        public byte[] sourceGearProfile() { return sourceGearProfile == null ? null : sourceGearProfile.clone(); }
        public byte[] expectedGearProfile() { return expectedGearProfile == null ? null : expectedGearProfile.clone(); }
    }

    public static final class Rejected extends Exception {
        public Rejected(String message) { super(message); }
    }

    public static Integer observedGearLevel(byte[] profile) {
        return observedGearLevel(profile, RESISTANCE_MAX);
    }

    public static Integer observedGearLevel(byte[] profile, int limit) {
        if (!validGearLimit(limit) || profile == null || profile.length != 8) return null;
        int level = profile[4] & 255;
        if (level < 1 || level > limit) return null;
        return Arrays.equals(profile, gearProfile(level)) ? level : null;
    }

    private static byte[] gearProfile(int level) {
        return new byte[]{0, 0, 0x27, 1, (byte) level, 7, (byte) (0x22 - level), 0x19};
    }

    public static boolean validGearLimit(int limit) {
        return limit == RESISTANCE_MAX;
    }

    public static Target gearStep(int equipmentId, byte[] currentProfile, Double mode, Double erg,
                                  long sampledAtElapsedMs, long nowElapsedMs, int direction) throws Rejected {
        return gearStep(equipmentId, currentProfile, mode, erg, sampledAtElapsedMs, nowElapsedMs, direction, RESISTANCE_MAX, RESISTANCE_MAX);
    }

    public static Target gearStep(int equipmentId, byte[] currentProfile, Double mode, Double erg,
            long sampledAtElapsedMs, long nowElapsedMs, int direction, int limit, Integer maximum) throws Rejected {
        validateContext(equipmentId, sampledAtElapsedMs, nowElapsedMs, direction);
        if (!validGearLimit(limit)) throw new Rejected("Resistance range must be 1–24.");
        if (maximum == null || maximum != RESISTANCE_MAX) throw new Rejected("The reported resistance maximum must be 24.");
        if (requireFinite(mode, "workout mode") != 2) throw new Rejected("Resistance level changes require a running ride.");
        if (requireFinite(erg, "ERG mode") != 0) throw new Rejected("Resistance level changes require ERG mode to be off.");
        byte[] source = currentProfile == null ? null : currentProfile.clone();
        Integer current = observedGearLevel(source, limit);
        if (current == null) throw new Rejected("Resistance data is outside the supported resistance profiles.");
        int target = current + direction;
        if (target < 1 || target > limit) throw new Rejected("Resistance level is already at the supported limit of 1–" + limit + ".");
        return new Target(Kind.GEAR, 26, target, Encoding.GEAR_PROFILE, source,
                gearProfile(target), limit);
    }

    public static Target resistanceStep(int equipmentId, Double currentLevel, Integer reportedMax,
                                        long sampledAtElapsedMs, long nowElapsedMs, int direction)
            throws Rejected {
        validateContext(equipmentId, sampledAtElapsedMs, nowElapsedMs, direction);
        if (reportedMax == null || reportedMax != RESISTANCE_MAX) {
            throw new Rejected("The bike's resistance limit has not been confirmed as 24.");
        }
        double current = requireFinite(currentLevel, "resistance");
        if (current != Math.rint(current)) throw new Rejected("Resistance must be a confirmed whole level.");
        if (current < RESISTANCE_MIN || current > reportedMax) {
            throw new Rejected("Current resistance is outside the confirmed range.");
        }
        double target = Math.max(RESISTANCE_MIN, Math.min(reportedMax, current + direction));
        if (target == current) throw new Rejected("Resistance is already at that limit.");
        return new Target(Kind.RESISTANCE, 2, target, Encoding.RESISTANCE_LEVEL);
    }

    public static Target resistanceRawStep(int equipmentId, Double currentRaw, Integer reportedMax,
                                           long sampledAtElapsedMs, long nowElapsedMs, int direction)
            throws Rejected {
        validateContext(equipmentId, sampledAtElapsedMs, nowElapsedMs, direction);

        double current = validResistanceRaw(currentRaw, reportedMax, 0);
        if (direction == -1 && current < RESISTANCE_RAW_MIN) {
            throw new Rejected("Resistance cannot be decreased from this low setting.");
        }
        double target = Math.max(RESISTANCE_RAW_MIN,
                Math.min(RESISTANCE_RAW_MAX, current + direction * RESISTANCE_RAW_STEP));
        if (target == current) throw new Rejected("Resistance is already at that limit.");
        return new Target(Kind.RESISTANCE, 2, target, Encoding.RESISTANCE_RAW);
    }

    public static Target confirmResistanceRaw(int equipmentId, Double currentRaw, Integer reportedMax,
                                              long sampledAtElapsedMs, long nowElapsedMs) throws Rejected {
        validateContext(equipmentId, sampledAtElapsedMs, nowElapsedMs, 1);
        double current = validResistanceRaw(currentRaw, reportedMax);
        return new Target(Kind.RESISTANCE, 2, current, Encoding.RESISTANCE_RAW);
    }

    private static double validResistanceRaw(Double currentRaw, Integer reportedMax) throws Rejected {
        return validResistanceRaw(currentRaw, reportedMax, RESISTANCE_RAW_MIN);
    }

    private static double validResistanceRaw(Double currentRaw, Integer reportedMax, int minimum) throws Rejected {
        if (reportedMax == null || reportedMax != RESISTANCE_MAX) {
            throw new Rejected("The bike's resistance limit has not been confirmed as 24.");
        }
        double current = requireFinite(currentRaw, "raw resistance");
        if (current != Math.rint(current) || current < minimum || current > RESISTANCE_RAW_MAX) {
            throw new Rejected("Raw resistance is outside the permitted integer range " + minimum + "..9983.");
        }
        return current;
    }

    public static Target inclineStep(int equipmentId, Double currentPercent,
                                     Double reportedMin, Double reportedMax,
                                     long sampledAtElapsedMs, long nowElapsedMs, int direction)
            throws Rejected {
        validateContext(equipmentId, sampledAtElapsedMs, nowElapsedMs, direction);
        double minimum = requireFinite(reportedMin, "minimum incline limit");
        double maximum = requireFinite(reportedMax, "maximum incline limit");
        if (minimum != INCLINE_MIN || maximum != INCLINE_MAX) {
            throw new Rejected("The bike's incline limits have not been confirmed as -10% to 20%.");
        }
        double current = requireFinite(currentPercent, "incline");
        if (current < minimum || current > maximum) {
            throw new Rejected("Current incline is outside the confirmed range.");
        }
        double target = Math.max(minimum, Math.min(maximum, current + direction * 0.5));
        if (target == current) throw new Rejected("Incline is already at that limit.");
        return new Target(Kind.INCLINE, 1, target, Encoding.GRADE_PERCENT);
    }

    public static Target workoutModeTarget(int equipmentId, Double currentMode, int targetMode,
                                           long sampledAtElapsedMs, long nowElapsedMs) throws Rejected {
        validateContext(equipmentId, sampledAtElapsedMs, nowElapsedMs, 1);
        double current = requireFinite(currentMode, "workout mode");
        if (current != Math.rint(current)) throw new Rejected("Workout mode must be a known whole value.");
        boolean toWarmUp = current == 1 && targetMode == 10;
        boolean toRunning = current == 10 && targetMode == 2;
        boolean toResults = targetMode == 4 && (current == 2 || current == 3 || current == 10);
        boolean toIdle = targetMode == 1 && (current == 1 || current == 4);
        if (!toWarmUp && !toRunning && !toResults && !toIdle) {
            throw new Rejected("That workout mode transition is not permitted for this bike.");
        }
        return new Target(Kind.WORKOUT_MODE, 12, targetMode, Encoding.BYTE);
    }

    public static Target idleLockoutTarget(int equipmentId, Double currentFlag, boolean locked,
                                           long sampledAtElapsedMs, long nowElapsedMs) throws Rejected {
        validateContext(equipmentId, sampledAtElapsedMs, nowElapsedMs, 1);
        double current = requireFinite(currentFlag, "idle lockout flag");
        if (current != 0 && current != 1) throw new Rejected("Idle lockout must be confirmed as 0 or 1.");
        return new Target(Kind.IDLE_LOCKOUT, 95, locked ? 1 : 0, Encoding.BYTE);
    }

    public static Target requireStartTarget(int equipmentId, Double currentFlag, boolean desired,
                                            long sampledAtElapsedMs, long nowElapsedMs) throws Rejected {
        validateContext(equipmentId, sampledAtElapsedMs, nowElapsedMs, 1);
        double current = requireFinite(currentFlag, "require start request flag");
        if (current != 0 && current != 1) throw new Rejected("Require start request must be confirmed as 0 or 1.");
        return new Target(Kind.REQUIRE_START, 108, desired ? 1 : 0, Encoding.BYTE);
    }

    private static void validateContext(int equipmentId, long sampledAt, long now, int direction)
            throws Rejected {
        if (equipmentId != EQUIPMENT_ID) throw new Rejected("The connected controller is not the verified S22i bike.");
        if (direction != -1 && direction != 1) throw new Rejected("Choose one up or down step.");

        if (sampledAt <= 0 || now < sampledAt || now - sampledAt >= MAX_SAMPLE_AGE_MS) {
            throw new Rejected("A fresh bike reading is required before changing the setting.");
        }
    }

    private static double requireFinite(Double value, String name) throws Rejected {
        if (value == null || value.isNaN() || value.isInfinite()) {
            throw new Rejected("A valid " + name + " reading is required.");
        }
        return value;
    }
}
