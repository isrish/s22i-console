package dev.s22i.console;

import java.util.Arrays;

public final class ResistanceRangeTest {
    private static int passed;
    private interface Operation { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        profilesAndCorruption();
        adjacentStepsAndFrames();
        setupGuards();
        writeGuards();
        System.out.println("ResistanceRangeTest: " + passed + " assertions passed");
    }

    private static byte[] profile(int level) {
        return new byte[]{0, 0, 39, 1, (byte) level, 7, (byte) (34 - level), 25};
    }

    private static void profilesAndCorruption() {
        for (int level = 1; level <= 24; level++) {
            byte[] value = profile(level);
            check(BikeControlPolicy.observedGearLevel(value, 24) == level);
            check(level <= 2 ? BikeControlPolicy.observedGearLevel(value) == level
                    : BikeControlPolicy.observedGearLevel(value) == null);
            for (int limit : new int[]{2, 3, 8, 16, 24}) {
                Integer accepted = BikeControlPolicy.observedGearLevel(value, limit);
                check(level <= limit ? accepted != null && accepted == level : accepted == null);
            }
            for (int offset = 0; offset < 8; offset++) {
                for (int replacement = 0; replacement < 256; replacement++) {
                    if (replacement == (value[offset] & 255)) continue;
                    byte[] corrupt = value.clone();
                    corrupt[offset] = (byte) replacement;
                    check(BikeControlPolicy.observedGearLevel(corrupt, 24) == null);
                }
            }
        }
        for (int level : new int[]{0, 25, 26, 127, 255}) check(BikeControlPolicy.observedGearLevel(profile(level), 24) == null);
        for (byte[] invalid : new byte[][]{null, new byte[0], new byte[7], new byte[8], new byte[9]}) {
            check(BikeControlPolicy.observedGearLevel(invalid, 24) == null);
        }
        for (int limit : new int[]{-1, 0, 1, 4, 7, 9, 17, 23, 25, 255}) {
            check(!BikeControlPolicy.validGearLimit(limit));
            check(BikeControlPolicy.observedGearLevel(profile(1), limit) == null);
        }
    }

    private static void adjacentStepsAndFrames() throws Exception {
        for (int limit : new int[]{2, 3, 8, 16, 24}) {
            for (int level = 1; level <= 24; level++) {
                for (int direction : new int[]{-1, 1}) {
                    final int current = level;
                    int next = current + direction;
                    if (current > limit || next < 1 || next > limit) {
                        reject(() -> step(current, direction, limit));
                        continue;
                    }
                    BikeControlPolicy.Target target = step(current, direction, limit);
                    check(target.value == next && target.gearLimit == limit);
                    check(Arrays.equals(target.sourceGearProfile(), profile(current)));
                    check(Arrays.equals(target.expectedGearProfile(), profile(next)));
                    FitPro.Request request = FitPro.manualControl(target);
                    byte[] bytes = request.bytes();
                    check(bytes.length == 22 && bytes[0] == 2 && bytes[1] == 22 && bytes[2] == 2);
                    check(bytes[3] == 4 && bytes[7] == 4 && bytes[16] == 4 && bytes[20] == 4);
                    for (int index = 8; index < 16; index++) check((bytes[index] & 255) == (index == 12 ? next : 0));
                    check((bytes[21] & 255) == 0x2a + next);
                    byte[] report = request.usbReportBytes();
                    check(report.length == 64);
                    for (int index = 22; index < 64; index++) check(report[index] == 0);
                    byte[] response = new byte[13];
                    response[0] = 8; response[1] = 13; response[2] = 2; response[3] = 2;
                    System.arraycopy(profile(next), 0, response, 4, 8);
                    for (int index = 0; index < 12; index++) response[12] += response[index];
                    FitPro.Response parsed = FitPro.parseResponse(request, response, 8);
                    check(parsed.isDone() && Arrays.equals(parsed.payload(), target.expectedGearProfile()));
                    byte[] escaped = target.expectedGearProfile(); escaped[4] = 99;
                    check(Arrays.equals(target.expectedGearProfile(), profile(next)));
                }
            }
        }
        check(FitPro.hex(FitPro.manualControl(step(23, 1, 24)).bytes()).equals(
                "02 16 02 04 00 00 00 04 00 00 00 00 18 00 00 00 04 00 00 00 04 42"));
        check(FitPro.hex(FitPro.manualControl(step(2, 1, 3)).bytes()).equals(
                "02 16 02 04 00 00 00 04 00 00 00 00 03 00 00 00 04 00 00 00 04 2D"));
        reject(() -> BikeControlPolicy.gearStep(8, profile(2), 2.0, 0.0, 10000, 10500, 1));
        byte[] source = profile(23);
        BikeControlPolicy.Target target = BikeControlPolicy.gearStep(8, source, 2.0, 0.0, 10000, 10500, 1, 24, 24);
        source[4] = 1;
        check(target.sourceGearProfile()[4] == 23);
    }

    private static BikeControlPolicy.Target step(int level, int direction, int limit) throws Exception {
        return BikeControlPolicy.gearStep(8, profile(level), 2.0, 0.0, 10000, 10500, direction, limit, 24);
    }

    private static void setupGuards() throws Exception {
        for (int limit : new int[]{2, 3, 8, 16, 24}) {
            BikeControlPolicy.validateGearTestSetup(8, 1.0, 0.0, 24, profile(1), 10000, 10500, limit);
            passed++;
            for (Double mode : new Double[]{null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, 2.0, 3.0, 4.0, 10.0}) {
                reject(() -> BikeControlPolicy.validateGearTestSetup(8, mode, 0.0, 24, profile(1), 10000, 10500, limit));
            }
            for (Double cadence : new Double[]{null, Double.NaN, Double.NEGATIVE_INFINITY, -1.0, 0.1, 1.0, 60.0}) {
                reject(() -> BikeControlPolicy.validateGearTestSetup(8, 1.0, cadence, 24, profile(1), 10000, 10500, limit));
            }
            for (Integer maximum : new Integer[]{null, 0, 2, 23, 25, 255}) {
                reject(() -> BikeControlPolicy.validateGearTestSetup(8, 1.0, 0.0, maximum, profile(1), 10000, 10500, limit));
            }
            reject(() -> BikeControlPolicy.validateGearTestSetup(7, 1.0, 0.0, 24, profile(1), 10000, 10500, limit));
            reject(() -> BikeControlPolicy.validateGearTestSetup(8, 1.0, 0.0, 24, new byte[8], 10000, 10500, limit));
            reject(() -> BikeControlPolicy.validateGearTestSetup(8, 1.0, 0.0, 24, profile(1), 10000, 12000, limit));
        }
        reject(() -> BikeControlPolicy.validateGearTestSetup(8, 1.0, 0.0, 24, profile(4), 10000, 10500, 3));
        BikeControlPolicy.validateGearTestSetup(8, 1.0, 0.0, 24, profile(24), 10000, 10500, 2);
        passed++;
    }

    private static void writeGuards() throws Exception {
        for (int limit : new int[]{3, 8, 16, 24}) {
            for (Integer maximum : new Integer[]{null, 0, 2, 23, 25, 255}) {
                reject(() -> BikeControlPolicy.gearStep(8, profile(2), 2.0, 0.0, 10000, 10500, 1, limit, maximum));
            }
            for (Double mode : new Double[]{null, Double.NaN, Double.POSITIVE_INFINITY, 1.0, 3.0, 10.0}) {
                reject(() -> BikeControlPolicy.gearStep(8, profile(2), mode, 0.0, 10000, 10500, 1, limit, 24));
            }
            for (Double erg : new Double[]{null, Double.NaN, Double.NEGATIVE_INFINITY, 1.0, 2.0}) {
                reject(() -> BikeControlPolicy.gearStep(8, profile(2), 2.0, erg, 10000, 10500, 1, limit, 24));
            }
            for (long[] times : new long[][]{{0, 10500}, {10000, 9999}, {10000, 12000}}) {
                reject(() -> BikeControlPolicy.gearStep(8, profile(2), 2.0, 0.0, times[0], times[1], 1, limit, 24));
            }
            reject(() -> BikeControlPolicy.gearStep(7, profile(2), 2.0, 0.0, 10000, 10500, 1, limit, 24));
            for (int direction : new int[]{-2, 0, 2, 24}) {
                reject(() -> BikeControlPolicy.gearStep(8, profile(2), 2.0, 0.0, 10000, 10500, direction, limit, 24));
            }
        }
        for (int limit : new int[]{-1, 0, 1, 4, 25, 255}) {
            reject(() -> step(2, 1, limit));
            reject(() -> BikeControlPolicy.validateGearTestSetup(8, 1.0, 0.0, 24, profile(1), 10000, 10500, limit));
        }
    }

    private static void reject(Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Accepted an invalid resistance operation"); }
        catch (BikeControlPolicy.Rejected expected) { passed++; }
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Resistance range assertion failed");
        passed++;
    }
}
