package dev.s22i.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RideHistory {
    public static final int LIMIT = 4096;
    public static final class Point {
        public final long time;
        public final double[] values;
        public final int breakMask;
        Point(long time, double[] values, int breakMask) {
            this.time = time; this.values = values.clone(); this.breakMask = breakMask;
        }
    }
    public final RideStats stats = new RideStats();
    private final ArrayList<Point> points = new ArrayList<>();
    private long start = -1, last = -1;
    private boolean recovered, started;
    public void clear() { points.clear(); stats.reset(); start = -1; last = -1; recovered = false; started = false; }
    public void reset(long now) { points.clear(); stats.reset(); start = now; last = -1; recovered = false; started = true; }
    public boolean add(long sampled, long now, Double speed, Double cadence, Double watts, Integer resistance) {
        if (!started || sampled < start || sampled <= last || now < sampled || now - sampled > 3000) return false;
        int breaks = recovered || (last >= 0 && sampled - last > 3000) ? 15 : 0;
        last = sampled;
        recovered = false;
        addPoint(new Point(sampled - start, new double[]{valid(speed, 200), valid(cadence, 300), valid(watts, 3000),
                resistance == null || resistance < 1 || resistance >= 255 ? Double.NaN : resistance}, breaks));
        return true;
    }
    static double valid(Double n, double max) {
        return n == null || !Double.isFinite(n) || n < 0 || n > max ? Double.NaN : n;
    }
    private void addPoint(Point p) {
        stats.accept(p);
        if (points.size() >= LIMIT) {
            ArrayList<Point> smaller = new ArrayList<>();
            int breaks = 0;
            for (int i = 0; i < points.size(); i++) {
                Point old = points.get(i);
                breaks |= old.breakMask;
                if (i % 2 == 0 || i == points.size() - 1) {
                    smaller.add(new Point(old.time, old.values, breaks));
                    breaks = 0;
                } else {

                    for (int metric = 0; metric < 4; metric++) if (!Double.isFinite(old.values[metric])) breaks |= 1 << metric;
                }
            }
            points.clear(); points.addAll(smaller);
        }
        points.add(p);
    }
    public List<Point> points() { return Collections.unmodifiableList(points); }
    public Point latest() { return points.isEmpty() ? null : points.get(points.size()-1); }
    public long duration() { return latest() == null ? 0 : latest().time; }
    public String csvLatest() {
        Point p = latest(); if (p == null) return "";
        StringBuilder s = new StringBuilder(Long.toString(p.time));
        for (double n : p.values) s.append(',').append(Double.isFinite(n) ? Double.toString(n) : "");
        return s.append(',').append(p.breakMask).append('\n').toString();
    }
    public void restoreLine(String line) {
        String[] fields = line.split(",", -1); if (fields.length != 5 && fields.length != 6) return;
        try {
            long time = Long.parseLong(fields[0]);
            if (time < 0 || (latest() != null && time <= latest().time)) return;
            double[] values = new double[4];
            for (int i = 0; i < 4; i++) values[i] = fields[i+1].isEmpty() ? Double.NaN : Double.parseDouble(fields[i+1]);
            values[0] = valid(values[0], 200); values[1] = valid(values[1], 300); values[2] = valid(values[2], 3000);
            if (!Double.isFinite(values[3]) || values[3] < 1 || values[3] >= 255 || values[3] != Math.rint(values[3])) values[3] = Double.NaN;
            int breaks = fields.length == 6 ? Integer.parseInt(fields[5]) : 0;
            if (breaks < 0 || breaks > 15) return;
            addPoint(new Point(time, values, breaks));
        } catch (NumberFormatException ignored) { }
    }

    public void resume(long sampled) { start = sampled - duration() - 1; last = sampled - 1; recovered = true; started = true; }
}
