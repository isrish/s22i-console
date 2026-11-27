package dev.s22i.console;

import java.util.Arrays;

public final class RideStats {
    private final double[] area = new double[4];
    private final long[] covered = new long[4];
    private final double[] peak = new double[4];
    private RideHistory.Point previous;
    private long duration;
    public RideStats() { reset(); }
    public void reset() {
        Arrays.fill(area, 0); Arrays.fill(covered, 0); Arrays.fill(peak, Double.NaN);
        previous = null; duration = 0;
    }
    void accept(RideHistory.Point point) {
        if (previous != null && point.time <= previous.time) return;
        long dt = previous == null ? 0 : point.time - previous.time;
        for (int i = 0; i < 4; i++) {
            double value = point.values[i];
            if (Double.isFinite(value)) peak[i] = Double.isNaN(peak[i]) ? value : Math.max(peak[i], value);
            if (previous != null && dt > 0 && dt <= 3000 && (point.breakMask & (1 << i)) == 0
                    && Double.isFinite(value) && Double.isFinite(previous.values[i])) {
                area[i] += (previous.values[i] + value) * .5 * dt;
                covered[i] += dt;
            }
        }
        duration = point.time;
        previous = point;
    }
    public long durationMillis() { return duration; }
    public long coveredMillis(int metric) { return covered[metric]; }
    public double average(int metric) { return covered[metric] == 0 ? Double.NaN : area[metric] / covered[metric]; }
    public double peak(int metric) { return peak[metric]; }
    public double distanceKm() { return Double.isNaN(peak[0]) ? Double.NaN : area[0] / 3600000.0; }
    public boolean distanceComplete() { return !Double.isNaN(peak[0]) && covered[0] == duration; }
}
