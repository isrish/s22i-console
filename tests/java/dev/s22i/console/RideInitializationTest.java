package dev.s22i.console;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RideInitializationTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        startupOrderAndOriginals();
        invalidReadingsNeverMutate();
        everyStartupFailureStopsTheSequence();
        restorationOrderAndLegacyRecords();
        everyRestorationFailureStopsTheSequence();
        unsafeRestorationNeverMutates();
        invalidOriginals();
        System.out.println("RideInitialization: " + passed + " assertions passed");
    }

    private static void startupOrderAndOriginals() throws Exception {
        for (int lockout : new int[] {0, 1}) {
            for (int requireStart : new int[] {0, 1}) {
                FakePort port = new FakePort(lockout, requireStart);
                RideInitialization.OriginalFlags result = RideInitialization.prepare(port);
                check(result == port.originals);
                check(result.idleLockout == lockout && result.requireStart == requireStart);
                check(port.owned);
                equal(Arrays.asList("read12", "read119", "read95", "read108", "save",
                        "flag108=1", "flag95=1", "flag95=0"), withoutCheckpoints(port.events));
                equal(Arrays.asList("flag108=1", "flag95=1", "flag95=0"), port.writes);
                check(port.values.get(108) == 1.0 && port.values.get(95) == 0.0);
                check(port.values.get(12) == 1.0 && port.values.get(119) == 0.0);
                check(port.events.get(0).equals("check") && port.events.get(port.events.size() - 1).equals("check"));
            }
        }
    }

    private static void invalidReadingsNeverMutate() throws Exception {
        Double[] unknown = {null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, 0.5, 2.0, 255.0};
        for (int field : new int[] {95, 108}) {
            for (Double value : unknown) {
                FakePort port = new FakePort(0, 0);
                port.values.put(field, value);
                fails(() -> RideInitialization.prepare(port));
                check(port.writes.isEmpty() && port.originals == null && !port.owned);
            }
        }
        for (int field : new int[] {12, 119}) {
            for (Double value : new Double[] {null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.5, 2.0, 3.0, 10.0, 12.0, 255.0}) {
                FakePort port = new FakePort(1, 1);
                port.values.put(field, value);
                fails(() -> RideInitialization.prepare(port));
                check(port.writes.isEmpty() && port.originals == null && !port.owned);
            }
        }
        FakePort active = new FakePort(1, 1);
        active.values.put(12, 0.0);
        fails(() -> RideInitialization.prepare(active));
        check(active.writes.isEmpty());
        FakePort erg = new FakePort(1, 1);
        erg.values.put(119, 1.0);
        fails(() -> RideInitialization.prepare(erg));
        check(erg.writes.isEmpty());
    }

    private static void everyStartupFailureStopsTheSequence() throws Exception {
        FakePort baseline = new FakePort(1, 0);
        RideInitialization.prepare(baseline);
        for (int step = 0; step < baseline.events.size(); step++) {
            for (boolean afterEffect : new boolean[] {false, true}) {
                for (boolean cancelled : new boolean[] {false, true}) {
                    FakePort port = new FakePort(1, 0);
                    Exception failure = cancelled ? new InterruptedException("cancelled") : new IOException("port failure");
                    port.inject(step, afterEffect, failure);
                    sameFailure(failure, () -> RideInitialization.prepare(port));
                    equal(baseline.events.subList(0, step + 1), port.events);
                    check(port.writes.size() <= 3);
                    if (!port.writes.isEmpty()) check(port.originals != null && port.owned);
                    if (port.originals != null) {
                        check(port.originals.idleLockout == 1 && port.originals.requireStart == 0);

                        FakePort recovery = new FakePort(port.values.get(95).intValue(), port.values.get(108).intValue());
                        recovery.owned = true;
                        RideInitialization.restore(recovery, port.originals);
                        check(recovery.values.get(95) == 1.0 && recovery.values.get(108) == 0.0);
                        check(recovery.owned);
                    }
                }
            }
        }
    }

    private static void restorationOrderAndLegacyRecords() throws Exception {
        for (int lockout : new int[] {0, 1}) {
            for (Integer requireStart : new Integer[] {null, 0, 1}) {
                FakePort port = new FakePort(0, 1);
                port.owned = true;
                RideInitialization.OriginalFlags originals = new RideInitialization.OriginalFlags(lockout, requireStart);
                RideInitialization.restore(port, originals);
                List<String> expected = new ArrayList<>(Arrays.asList("read12", "flag95=" + lockout));
                if (requireStart != null) expected.add("flag108=" + requireStart);
                equal(expected, withoutCheckpoints(port.events));
                check(port.values.get(95) == lockout);
                check(port.values.get(108) == (requireStart == null ? 1 : requireStart));
                check(port.owned && port.originals == null);
                check(port.events.get(port.events.size() - 1).equals("check"));
            }
        }
    }

    private static void everyRestorationFailureStopsTheSequence() throws Exception {
        for (Integer requireStart : new Integer[] {null, 0, 1}) {
            RideInitialization.OriginalFlags originals = new RideInitialization.OriginalFlags(1, requireStart);
            FakePort baseline = new FakePort(0, 1);
            baseline.owned = true;
            RideInitialization.restore(baseline, originals);
            for (int step = 0; step < baseline.events.size(); step++) {
                for (boolean afterEffect : new boolean[] {false, true}) {
                    for (boolean cancelled : new boolean[] {false, true}) {
                        FakePort port = new FakePort(0, 1);
                        port.owned = true;
                        Exception failure = cancelled ? new InterruptedException("cancelled") : new IOException("port failure");
                        port.inject(step, afterEffect, failure);
                        sameFailure(failure, () -> RideInitialization.restore(port, originals));
                        equal(baseline.events.subList(0, step + 1), port.events);
                        check(port.owned && port.originals == null);
                        if (requireStart == null) check(!port.writes.contains("flag108=0") && !port.writes.contains("flag108=1"));
                    }
                }
            }
        }
    }

    private static void unsafeRestorationNeverMutates() throws Exception {
        for (Double mode : new Double[] {null, Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 0.5, 2.0, 3.0, 4.0, 10.0, 12.0, 14.0}) {
            FakePort port = new FakePort(0, 1);
            port.owned = true;
            port.values.put(12, mode);
            fails(() -> RideInitialization.restore(port, new RideInitialization.OriginalFlags(1, 0)));
            check(port.writes.isEmpty() && port.owned);
        }
    }

    private static void invalidOriginals() throws Exception {
        for (int value : new int[] {-1, 2, 255, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            illegal(() -> new RideInitialization.OriginalFlags(value, 0));
            illegal(() -> new RideInitialization.OriginalFlags(0, value));
        }
        illegal(() -> RideInitialization.prepare(null));
        illegal(() -> RideInitialization.restore(null, new RideInitialization.OriginalFlags(1, 1)));
        FakePort port = new FakePort(1, 1);
        illegal(() -> RideInitialization.restore(port, null));
        check(port.events.isEmpty());
    }

    private static final class FakePort implements RideInitialization.Port {
        final Map<Integer, Double> values = new LinkedHashMap<>();
        final List<String> events = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        RideInitialization.OriginalFlags originals;
        boolean owned;
        int failAt = -1;
        boolean afterEffect;
        Exception failure;

        FakePort(int lockout, int requireStart) {
            values.put(12, 1.0);
            values.put(119, 0.0);
            values.put(95, (double) lockout);
            values.put(108, (double) requireStart);
        }

        void inject(int step, boolean afterEffect, Exception failure) {
            failAt = step;
            this.afterEffect = afterEffect;
            this.failure = failure;
        }

        public Double read(int field) throws Exception {
            event("read" + field, () -> { });
            return values.get(field);
        }

        public void saveOriginals(RideInitialization.OriginalFlags value) throws Exception {
            event("save", () -> {
                originals = value;
                owned = true;
            });
        }

        public void confirmFlag(int field, boolean enabled) throws Exception {
            if (field != 95 && field != 108) throw new AssertionError("Unexpected controller field " + field);
            if (!owned) throw new AssertionError("Write before durable ownership");
            String label = "flag" + field + "=" + (enabled ? 1 : 0);
            event(label, () -> {
                writes.add(label);
                values.put(field, enabled ? 1.0 : 0.0);
            });
        }

        public void checkpoint() throws Exception { event("check", () -> { }); }

        private void event(String label, Runnable effect) throws Exception {
            int step = events.size();
            events.add(label);
            if (step == failAt && !afterEffect) throw failure;
            effect.run();
            if (step == failAt && afterEffect) throw failure;
        }
    }

    private static List<String> withoutCheckpoints(List<String> events) {
        List<String> result = new ArrayList<>();
        for (String event : events) if (!event.equals("check")) result.add(event);
        return result;
    }

    private interface Operation { void run() throws Exception; }

    private static void sameFailure(Exception failure, Operation operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("Expected injected failure");
        } catch (Exception actual) {
            check(actual == failure);
        }
    }

    private static void fails(Operation operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("Expected invalid reading rejection");
        } catch (IOException expected) { passed++; }
    }

    private static void illegal(Operation operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("Expected invalid argument rejection");
        } catch (IllegalArgumentException expected) { passed++; }
    }

    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + "; got " + actual);
        passed++;
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Assertion failed");
        passed++;
    }
}
