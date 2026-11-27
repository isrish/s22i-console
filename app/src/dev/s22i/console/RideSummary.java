package dev.s22i.console;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class RideSummary {
    public long startedAt, duration;
    public double distance, avgSpeed, avgPower, peakPower, avgCadence, peakResistance;
    public boolean completeDistance, preview;
    public long[] times;
    public double[] powers;
    public boolean[] gaps;

    public static RideSummary from(RideHistory history, long startedAt) {
        RideStats stats = history.stats;
        RideSummary result = new RideSummary();
        result.startedAt = startedAt;
        result.duration = stats.durationMillis();
        result.distance = stats.distanceKm();
        result.completeDistance = stats.distanceComplete();
        result.avgSpeed = stats.average(0);
        result.avgPower = stats.average(2);
        result.peakPower = stats.peak(2);
        result.avgCadence = stats.average(1);
        result.peakResistance = stats.peak(3);
        int size = history.points().size();
        result.times = new long[size]; result.powers = new double[size]; result.gaps = new boolean[size];
        for (int i = 0; i < size; i++) {
            RideHistory.Point p = history.points().get(i);
            result.times[i] = p.time; result.powers[i] = p.values[2]; result.gaps[i] = (p.breakMask & 4) != 0;
        }
        return result;
    }
    public static String number(double value, int decimals) {
        return Double.isFinite(value) ? String.format(Locale.US, "%." + decimals + "f", value) : "—";
    }
    public static String elapsed(long millis) {
        long seconds = millis / 1000;
        return seconds < 3600 ? String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
                : String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
    private static AtomicFile file(Context context) { return new AtomicFile(new File(context.getFilesDir(), "last-ride-summary.json")); }
    public static boolean exists(Context context) { return file(context).getBaseFile().exists(); }
    private static Object jsonNumber(double n) { return Double.isFinite(n) ? n : JSONObject.NULL; }
    public void save(Context context) throws IOException, JSONException {
        JSONObject json = new JSONObject();
        json.put("version", 1).put("startedAt", startedAt).put("duration", duration)
                .put("distance", jsonNumber(distance)).put("completeDistance", completeDistance)
                .put("avgSpeed", jsonNumber(avgSpeed)).put("avgPower", jsonNumber(avgPower))
                .put("peakPower", jsonNumber(peakPower)).put("avgCadence", jsonNumber(avgCadence))
                .put("peakResistance", jsonNumber(peakResistance));
        JSONArray plot = new JSONArray();
        for (int i = 0; i < times.length; i++) plot.put(new JSONArray().put(times[i]).put(jsonNumber(powers[i])).put(gaps[i]));
        json.put("powerPlot", plot);
        AtomicFile target = file(context);
        FileOutputStream stream = null;
        try {
            stream = target.startWrite();
            stream.write(json.toString().getBytes(StandardCharsets.UTF_8));
            target.finishWrite(stream);
        } catch (IOException error) { if (stream != null) target.failWrite(stream); throw error; }
    }
    public static RideSummary load(Context context) throws IOException, JSONException {
        JSONObject json = new JSONObject(new String(file(context).readFully(), StandardCharsets.UTF_8));
        if (json.getInt("version") != 1) throw new IOException("Unsupported summary version");
        RideSummary result = new RideSummary();
        result.startedAt = json.getLong("startedAt"); result.duration = json.getLong("duration");
        result.distance = json.optDouble("distance", Double.NaN); result.completeDistance = json.getBoolean("completeDistance");
        result.avgSpeed = json.optDouble("avgSpeed", Double.NaN); result.avgPower = json.optDouble("avgPower", Double.NaN);
        result.peakPower = json.optDouble("peakPower", Double.NaN); result.avgCadence = json.optDouble("avgCadence", Double.NaN);
        result.peakResistance = json.optDouble("peakResistance", Double.NaN);
        JSONArray plot = json.getJSONArray("powerPlot");
        int size = plot.length();
        result.times = new long[size]; result.powers = new double[size]; result.gaps = new boolean[size];
        for (int i = 0; i < size; i++) {
            JSONArray point = plot.getJSONArray(i);
            result.times[i] = point.getLong(0); result.powers[i] = point.optDouble(1, Double.NaN); result.gaps[i] = point.getBoolean(2);
        }
        return result;
    }

    public static RideSummary preview() {
        RideHistory history = new RideHistory(); history.reset(0);
        for (int second = 0; second <= 1800; second++) {
            double effort = Math.max(0, Math.sin(Math.PI * second / 1800));
            double pulse = Math.sin(second / 50.0) * 16;
            history.add(second * 1000L, second * 1000L, 13 + effort * 12, 58 + effort * 28,
                    75 + effort * 135 + pulse, 1 + (int)(effort * 7));
        }
        RideSummary result = from(history, 0); result.preview = true; return result;
    }
}
