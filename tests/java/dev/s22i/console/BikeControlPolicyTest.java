package dev.s22i.console;

import java.util.Arrays;

public final class BikeControlPolicyTest {
    private static int passed;
    private static final long SAMPLE = 10000;
    private static final long NOW = 10500;

    public static void main(String[] args) throws Exception {
        acceptedSteps();
        limitEdges();
        invalidContext();
        invalidReadings();
        invalidLimits();
        allStepsBounded();
        rawResistanceSteps();
        rideStateTransitions();
        requireStartTargets();
        gearPilotSteps();
        System.out.println("BikeControlPolicy: " + passed + " assertions passed");
    }

    private static void acceptedSteps() throws Exception {
        BikeControlPolicy.Target resistance = resistance(7.0, 1);
        check(resistance.kind == BikeControlPolicy.Kind.RESISTANCE);
        check(resistance.fieldId == 2 && resistance.equipmentId == 8 && resistance.value == 8);
        check(resistance(7.0, -1).value == 6);
        BikeControlPolicy.Target incline = incline(0.0, 1);
        check(incline.kind == BikeControlPolicy.Kind.INCLINE);
        check(incline.fieldId == 1 && incline.equipmentId == 8 && incline.value == 0.5);
        check(incline(0.0, -1).value == -0.5);
    }

    private static void limitEdges() throws Exception {
        check(resistance(23.0, 1).value == 24);
        check(resistance(2.0, -1).value == 1);
        check(incline(19.75, 1).value == 20);
        check(incline(-9.75, -1).value == -10);
        reject(() -> resistance(24.0, 1));
        reject(() -> resistance(1.0, -1));
        reject(() -> incline(20.0, 1));
        reject(() -> incline(-10.0, -1));
    }

    private static void invalidContext() throws Exception {
        reject(() -> BikeControlPolicy.resistanceStep(7, 7.0, 24, SAMPLE, NOW, 1));
        reject(() -> BikeControlPolicy.inclineStep(2, 0.0, -10.0, 20.0, SAMPLE, NOW, 1));
        reject(() -> BikeControlPolicy.resistanceStep(8, 7.0, 24, SAMPLE, NOW, 0));
        reject(() -> BikeControlPolicy.inclineStep(8, 0.0, -10.0, 20.0, SAMPLE, NOW, 2));
        reject(() -> BikeControlPolicy.resistanceStep(8, 7.0, 24, SAMPLE, SAMPLE + 2000, 1));
        reject(() -> BikeControlPolicy.inclineStep(8, 0.0, -10.0, 20.0, SAMPLE, SAMPLE + 2000, 1));
        reject(() -> BikeControlPolicy.resistanceStep(8, 7.0, 24, SAMPLE, SAMPLE - 1, 1));
        reject(() -> BikeControlPolicy.resistanceStep(8, 7.0, 24, 0, NOW, 1));
        reject(() -> BikeControlPolicy.resistanceStep(8, 7.0, 24, -100, NOW, 1));
        check(BikeControlPolicy.resistanceStep(8, 7.0, 24, SAMPLE, SAMPLE + 1999, 1).value == 8);
        check(BikeControlPolicy.inclineStep(8, 0.0, -10.0, 20.0, SAMPLE, SAMPLE, 1).value == 0.5);
    }

    private static void invalidReadings() throws Exception {
        for (Double value : new Double[] {null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            reject(() -> resistance(value, 1));
            reject(() -> incline(value, 1));
        }
        for (Double value : new Double[] {0.0, -1.0, 25.0, 7.25}) reject(() -> resistance(value, 1));
        for (Double value : new Double[] {-10.01, 20.01}) reject(() -> incline(value, -1));
    }

    private static void invalidLimits() throws Exception {
        for (Integer max : new Integer[] {null, 0, -1, 23, 25, 255}) {
            reject(() -> BikeControlPolicy.resistanceStep(8, 7.0, max, SAMPLE, NOW, 1));
        }
        for (Double min : new Double[] {null, Double.NaN, Double.NEGATIVE_INFINITY, -11.0, -9.0, 20.0}) {
            reject(() -> BikeControlPolicy.inclineStep(8, 0.0, min, 20.0, SAMPLE, NOW, 1));
        }
        for (Double max : new Double[] {null, Double.NaN, Double.POSITIVE_INFINITY, 19.0, 21.0, -10.0}) {
            reject(() -> BikeControlPolicy.inclineStep(8, 0.0, -10.0, max, SAMPLE, NOW, 1));
        }
    }

    private static void allStepsBounded() throws Exception {
        for (int level = 1; level <= 24; level++) {
            for (int direction : new int[] {-1, 1}) {
                if (level == 1 && direction == -1 || level == 24 && direction == 1) continue;
                double target = resistance((double) level, direction).value;
                check(target >= 1 && target <= 24 && target - level == direction);
            }
        }
        for (int halfPercent = -20; halfPercent <= 40; halfPercent++) {
            for (int direction : new int[] {-1, 1}) {
                if (halfPercent == -20 && direction == -1 || halfPercent == 40 && direction == 1) continue;
                double current = halfPercent / 2.0;
                double target = incline(current, direction).value;
                check(target >= -10 && target <= 20 && target - current == direction * 0.5);
            }
        }
    }

    private static void rawResistanceSteps() throws Exception {
        check(BikeControlPolicy.RESISTANCE_RAW_STEP == 416);
        check(BikeControlPolicy.RESISTANCE_RAW_MIN == 415 && BikeControlPolicy.RESISTANCE_RAW_MAX == 9983);
        BikeControlPolicy.Target up = raw(2500.0, 1);
        check(up.value == 2916 && up.encoding == BikeControlPolicy.Encoding.RESISTANCE_RAW);
        check(raw(2500.0, -1).value == 2084);
        check(raw(415.0, 1).value == 831);
        check(raw(9983.0, -1).value == 9567);
        check(raw(500.0, -1).value == 415);
        check(raw(9900.0, 1).value == 9983);
        reject(() -> raw(415.0, -1));
        reject(() -> raw(9983.0, 1));
        check(raw(0.0, 1).value == 416);
        for (double current : new double[] {0, 1, 100, 414}) {
            reject(() -> raw(current, -1));
            check(raw(current, 1).value == current + 416);
            reject(() -> BikeControlPolicy.confirmResistanceRaw(8, current, 24, SAMPLE, NOW));
        }
        for (Double invalid : new Double[] {null, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, -1.0, 9984.0, 65535.0, 2500.5}) {
            reject(() -> raw(invalid, 1));
            reject(() -> BikeControlPolicy.confirmResistanceRaw(8, invalid, 24, SAMPLE, NOW));
        }
        reject(() -> BikeControlPolicy.resistanceRawStep(8, 2500.0, 24, SAMPLE, SAMPLE + 2000, 1));
        reject(() -> BikeControlPolicy.confirmResistanceRaw(8, 2500.0, 24, SAMPLE, SAMPLE + 2000));
        reject(() -> BikeControlPolicy.resistanceRawStep(7, 2500.0, 24, SAMPLE, NOW, 1));
        reject(() -> BikeControlPolicy.resistanceRawStep(8, 2500.0, 23, SAMPLE, NOW, 1));
        reject(() -> BikeControlPolicy.confirmResistanceRaw(8, 2500.0, null, SAMPLE, NOW));
        BikeControlPolicy.Target unchanged = BikeControlPolicy.confirmResistanceRaw(8, 2500.0, 24, SAMPLE, NOW);
        check(unchanged.value == 2500 && unchanged.encoding == BikeControlPolicy.Encoding.RESISTANCE_RAW);
        for (double current : new double[] {0, 1, 414, 415, 500, 831, 2500, 4096, 9567, 9900, 9983}) {
            for (int direction : new int[] {-1, 1}) {
                if (current <= 415 && direction == -1 || current == 9983 && direction == 1) continue;
                double target = raw(current, direction).value;
                check(target >= 415 && target <= 9983 && Math.abs(target - current) <= 416);
                check((target - current) * direction > 0);
            }
        }
    }

    private static BikeControlPolicy.Target raw(Double setting, int direction) throws Exception {
        return BikeControlPolicy.resistanceRawStep(8, setting, 24, SAMPLE, NOW, direction);
    }

    private static void gearPilotSteps() throws Exception {
        byte[] one = {0, 0, 0x27, 1, 1, 7, 0x21, 0x19};
        byte[] two = {0, 0, 0x27, 1, 2, 7, 0x20, 0x19};
        check(BikeControlPolicy.observedGearLevel(one) == 1);
        check(BikeControlPolicy.observedGearLevel(two) == 2);
        BikeControlPolicy.Target up = BikeControlPolicy.gearStep(8, one, 2.0, 0.0, SAMPLE, NOW, 1);
        BikeControlPolicy.Target down = BikeControlPolicy.gearStep(8, two, 2.0, 0.0, SAMPLE, NOW, -1);
        check(up.kind == BikeControlPolicy.Kind.GEAR && up.encoding == BikeControlPolicy.Encoding.GEAR_PROFILE);
        check(up.fieldId == 26 && up.equipmentId == 8 && up.value == 2 && down.value == 1);
        check(Arrays.equals(one, up.sourceGearProfile()) && Arrays.equals(two, up.expectedGearProfile()));
        check(Arrays.equals(two, down.sourceGearProfile()) && Arrays.equals(one, down.expectedGearProfile()));
        byte[] sourceCopy = up.sourceGearProfile();
        byte[] expectedCopy = up.expectedGearProfile();
        sourceCopy[0] = 99;
        expectedCopy[0] = 99;
        check(Arrays.equals(one, up.sourceGearProfile()) && Arrays.equals(two, up.expectedGearProfile()));
        byte[] supplied = one.clone();
        BikeControlPolicy.Target copied = BikeControlPolicy.gearStep(8, supplied, 2.0, 0.0, SAMPLE, NOW, 1);
        supplied[4] = 22;
        check(Arrays.equals(one, copied.sourceGearProfile()) && Arrays.equals(two, copied.expectedGearProfile()));
        check(resistance(1.0, 1).sourceGearProfile() == null && incline(0.0, 1).expectedGearProfile() == null);
        for (java.lang.reflect.Constructor<?> constructor : BikeControlPolicy.Target.class.getDeclaredConstructors()) {
            if (!constructor.isSynthetic()) check(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        }
        reject(() -> BikeControlPolicy.gearStep(8, one, 2.0, 0.0, SAMPLE, NOW, -1));
        check(BikeControlPolicy.gearStep(8, two, 2.0, 0.0, SAMPLE, NOW, 1).value == 3);
        for (byte[] invalid : new byte[][] {null, new byte[0], new byte[7], new byte[8], new byte[9],
                new byte[] {0, 0, 0, 0, 1, 0, 0, 0}, new byte[] {0, 0, 0x27, 1, 3, 7, 0x21, 0x19}}) {
            check(BikeControlPolicy.observedGearLevel(invalid) == null);
            reject(() -> BikeControlPolicy.gearStep(8, invalid, 2.0, 0.0, SAMPLE, NOW, 1));
        }

        for (byte[] profile : new byte[][] {one, two}) {
            for (int index = 0; index < profile.length; index++) {
                byte[] altered = profile.clone();
                altered[index] ^= 0x40;
                check(BikeControlPolicy.observedGearLevel(altered) == null);
                reject(() -> BikeControlPolicy.gearStep(8, altered, 2.0, 0.0, SAMPLE, NOW, 1));
            }
        }
        for (Double mode : new Double[] {null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                -1.0, 0.0, 1.0, 2.5, 3.0, 4.0, 10.0}) {
            reject(() -> BikeControlPolicy.gearStep(8, one, mode, 0.0, SAMPLE, NOW, 1));
        }
        for (Double erg : new Double[] {null, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                -1.0, 0.5, 1.0, 2.0}) {
            reject(() -> BikeControlPolicy.gearStep(8, one, 2.0, erg, SAMPLE, NOW, 1));
        }
        for (int equipment : new int[] {-1, 0, 2, 7, 9}) {
            reject(() -> BikeControlPolicy.gearStep(equipment, one, 2.0, 0.0, SAMPLE, NOW, 1));
        }
        for (int direction : new int[] {-2, 0, 2}) {
            reject(() -> BikeControlPolicy.gearStep(8, one, 2.0, 0.0, SAMPLE, NOW, direction));
        }
        reject(() -> BikeControlPolicy.gearStep(8, one, 2.0, 0.0, SAMPLE, SAMPLE + 2000, 1));
        reject(() -> BikeControlPolicy.gearStep(8, one, 2.0, 0.0, SAMPLE, SAMPLE - 1, 1));
        reject(() -> BikeControlPolicy.gearStep(8, one, 2.0, 0.0, 0, NOW, 1));
        check(BikeControlPolicy.gearStep(8, one, 2.0, 0.0, SAMPLE, SAMPLE + 1999, 1).value == 2);
        check(Arrays.equals(one, new byte[] {0, 0, 0x27, 1, 1, 7, 0x21, 0x19}));
    }

    private static void rideStateTransitions() throws Exception {
        BikeControlPolicy.Target warmup = BikeControlPolicy.workoutModeTarget(8, 1.0, 10, SAMPLE, NOW);
        check(warmup.kind == BikeControlPolicy.Kind.WORKOUT_MODE && warmup.encoding == BikeControlPolicy.Encoding.BYTE);
        check(warmup.fieldId == 12 && warmup.value == 10);
        check(BikeControlPolicy.workoutModeTarget(8, 10.0, 2, SAMPLE, NOW).value == 2);
        for (double current : new double[] {2, 3, 10}) {
            BikeControlPolicy.Target results = BikeControlPolicy.workoutModeTarget(8, current, 4, SAMPLE, NOW);
            check(results.kind == BikeControlPolicy.Kind.WORKOUT_MODE && results.fieldId == 12);
            check(results.encoding == BikeControlPolicy.Encoding.BYTE && results.value == 4);
            reject(() -> BikeControlPolicy.workoutModeTarget(8, current, 1, SAMPLE, NOW));
        }
        for (double current : new double[] {1, 4}) {
            check(BikeControlPolicy.workoutModeTarget(8, current, 1, SAMPLE, NOW).value == 1);
        }
        for (int current = 0; current <= 20; current++) {
            for (int target = 0; target <= 20; target++) {
                boolean allowed = current == 1 && target == 10 || current == 10 && target == 2
                        || target == 4 && (current == 2 || current == 3 || current == 10)
                        || target == 1 && (current == 1 || current == 4);
                final double c = current;
                final int t = target;
                if (!allowed) reject(() -> BikeControlPolicy.workoutModeTarget(8, c, t, SAMPLE, NOW));
            }
        }
        for (Double current : new Double[] {null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1.5}) {
            reject(() -> BikeControlPolicy.workoutModeTarget(8, current, 1, SAMPLE, NOW));
        }
        reject(() -> BikeControlPolicy.workoutModeTarget(8, 1.0, -1, SAMPLE, NOW));
        reject(() -> BikeControlPolicy.workoutModeTarget(7, 1.0, 10, SAMPLE, NOW));
        reject(() -> BikeControlPolicy.workoutModeTarget(8, 1.0, 10, SAMPLE, SAMPLE + 2000));
        reject(() -> BikeControlPolicy.workoutModeTarget(8, 10.0, 2, SAMPLE, SAMPLE - 1));
        reject(() -> BikeControlPolicy.workoutModeTarget(8, 2.0, 4, SAMPLE, SAMPLE + 2000));
        reject(() -> BikeControlPolicy.workoutModeTarget(7, 3.0, 4, SAMPLE, NOW));
        reject(() -> BikeControlPolicy.workoutModeTarget(8, 4.0, 1, SAMPLE, SAMPLE - 1));
        for (double current : new double[] {0, 1}) {
            for (boolean locked : new boolean[] {false, true}) {
                BikeControlPolicy.Target target = BikeControlPolicy.idleLockoutTarget(8, current, locked, SAMPLE, NOW);
                check(target.kind == BikeControlPolicy.Kind.IDLE_LOCKOUT && target.fieldId == 95);
                check(target.encoding == BikeControlPolicy.Encoding.BYTE && target.value == (locked ? 1 : 0));
            }
        }
        for (Double current : new Double[] {null, Double.NaN, Double.NEGATIVE_INFINITY, -1.0, 0.5, 2.0, 255.0}) {
            reject(() -> BikeControlPolicy.idleLockoutTarget(8, current, false, SAMPLE, NOW));
        }
        reject(() -> BikeControlPolicy.idleLockoutTarget(7, 1.0, false, SAMPLE, NOW));
        reject(() -> BikeControlPolicy.idleLockoutTarget(8, 1.0, false, SAMPLE, SAMPLE + 2000));
    }

    private static void requireStartTargets() throws Exception {
        for (double current : new double[] {0, 1}) {
            for (boolean desired : new boolean[] {false, true}) {
                BikeControlPolicy.Target target = BikeControlPolicy.requireStartTarget(8, current, desired, SAMPLE, NOW);
                check(target.kind == BikeControlPolicy.Kind.REQUIRE_START && target.fieldId == 108);
                check(target.equipmentId == 8 && target.encoding == BikeControlPolicy.Encoding.BYTE);
                check(target.value == (desired ? 1 : 0));
                check(target.sourceGearProfile() == null && target.expectedGearProfile() == null);
                BikeControlPolicy.Target lockout = BikeControlPolicy.idleLockoutTarget(8, current, desired, SAMPLE, NOW);
                check(lockout.kind == BikeControlPolicy.Kind.IDLE_LOCKOUT && lockout.fieldId == 95);
                check(lockout.value == target.value && lockout.encoding == BikeControlPolicy.Encoding.BYTE);
            }
        }
        for (Double current : new Double[] {null, Double.NaN, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, -1.0, -0.1, 0.5, 1.5, 2.0, 255.0}) {
            for (boolean desired : new boolean[] {false, true}) {
                reject(() -> BikeControlPolicy.requireStartTarget(8, current, desired, SAMPLE, NOW));
            }
        }
        for (int equipment : new int[] {-1, 0, 2, 7, 9, 255}) {
            reject(() -> BikeControlPolicy.requireStartTarget(equipment, 0.0, true, SAMPLE, NOW));
        }
        reject(() -> BikeControlPolicy.requireStartTarget(8, 0.0, true, SAMPLE, SAMPLE + 2000));
        reject(() -> BikeControlPolicy.requireStartTarget(8, 1.0, false, SAMPLE, SAMPLE - 1));
        reject(() -> BikeControlPolicy.requireStartTarget(8, 0.0, true, 0, NOW));
        reject(() -> BikeControlPolicy.requireStartTarget(8, 0.0, true, -1, NOW));
        check(BikeControlPolicy.requireStartTarget(8, 0.0, true, SAMPLE, SAMPLE + 1999).value == 1);
        check(BikeControlPolicy.requireStartTarget(8, 1.0, false, SAMPLE, SAMPLE).value == 0);

        check(java.lang.reflect.Modifier.isFinal(BikeControlPolicy.Target.class.getModifiers()));
        for (java.lang.reflect.Field field : BikeControlPolicy.Target.class.getDeclaredFields()) {
            check(java.lang.reflect.Modifier.isFinal(field.getModifiers()));
        }
        for (java.lang.reflect.Constructor<?> constructor : BikeControlPolicy.Target.class.getDeclaredConstructors()) {
            if (!constructor.isSynthetic()) check(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        }
    }

    private static BikeControlPolicy.Target resistance(Double level, int direction) throws Exception {
        return BikeControlPolicy.resistanceStep(8, level, 24, SAMPLE, NOW, direction);
    }

    private static BikeControlPolicy.Target incline(Double grade, int direction) throws Exception {
        return BikeControlPolicy.inclineStep(8, grade, -10.0, 20.0, SAMPLE, NOW, direction);
    }

    private interface CheckedAction { void run() throws Exception; }

    private static void reject(CheckedAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Accepted invalid/unchanged control target");
        } catch (BikeControlPolicy.Rejected expected) { passed++; }
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Assertion failed");
        passed++;
    }
}
