package dev.s22i.console;

public final class RideHistoryTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static boolean sample(RideHistory h, long t) { return h.add(t,t,20.5,75.0,180.0,8); }
    public static void main(String[] args) {
        RideHistory h = new RideHistory();
        check(!sample(h,1000), "No samples before ride starts");
        h.reset(1000);
        check(sample(h,1000), "First ride sample");
        check(h.duration()==0, "Timeline begins at zero");
        check(!sample(h,1000), "Repeated control snapshots don't duplicate telemetry");
        check(!sample(h,999), "Ignore samples from before ride");
        check(!h.add(2000,5001,1.0,1.0,1.0,1), "Reject stale sample");
        check(!h.add(2000,1999,1.0,1.0,1.0,1), "Reject future sample");
        check(h.add(2000,5000,1.0,1.0,1.0,1), "Accept three-second freshness boundary");
        check(h.duration()==1000 && h.points().size()==2, "Rejected samples don't extend history");
        check(h.add(2500,2500,null,Double.NaN,Double.POSITIVE_INFINITY,255), "Keep timestamp with missing readings");
        for (double n:h.latest().values) check(Double.isNaN(n), "Missing must never be a false zero");
        check(h.add(3000,3000,-1.0,301.0,3001.0,0), "Record invalid-reading gap");
        for (double n:h.latest().values) check(Double.isNaN(n), "Reject out-of-range measurements");
        check(h.add(3500,3500,0.0,0.0,0.0,1), "Real stationary readings accepted");
        check(h.latest().values[0]==0 && h.latest().values[1]==0 && h.latest().values[2]==0, "Zero is different from missing");
        check(sample(h,7501) && h.latest().breakMask==15, "Delayed telemetry creates an explicit gap");
        RideHistory loaded = new RideHistory();
        loaded.restoreLine(h.csvLatest().trim());
        check(loaded.duration()==h.duration(), "Restore elapsed time");
        check(loaded.latest().breakMask==15 && loaded.latest().values[3]==8, "Restore gap and resistance");
        loaded.restoreLine("elapsed_ms,speed_kph,cadence_rpm,power_w,resistance_level,gap_before");
        loaded.restoreLine("junk");
        loaded.restoreLine("-1,1,1,1,1,0");
        loaded.restoreLine("7000,1,1,1,1,20");
        loaded.restoreLine("7000,1,1,1,1,broken");
        loaded.restoreLine("7000,1,1,1");
        check(loaded.points().size()==1, "Ignore headers and corrupt rows");
        loaded.restoreLine("7000,,,,,0");
        check(Double.isNaN(loaded.latest().values[0]), "Missing data survives serialization");
        loaded.restoreLine("8000,201,301,3001,1.5,0");
        for (double n:loaded.latest().values) check(Double.isNaN(n), "Validate restored values including fractional levels");
        loaded.restoreLine("9000,20,80,100,2");
        check(loaded.latest().values[3]==2, "Accept previous five-column format");
        loaded.restoreLine("8000,0,0,0,1,0");
        check(loaded.duration()==9000, "Reject unordered restore data");
        loaded.resume(100000);
        check(sample(loaded,100000), "Recover recording after process restart");
        check(loaded.duration()==9001 && loaded.latest().breakMask==15, "Recovery preserves timeline and marks unknown interval");
        check(!sample(loaded,100000), "Recovery does not repeat a timestamp");
        loaded.resume(1000);
        check(sample(loaded,1000) && loaded.duration()==9002, "Recovery after reboot supports a shorter system uptime");
        h.reset(0);
        for (int i=0;i<100000;i++) {
            check(sample(h,i*500L), "Accept long ride sample");
            check(h.points().size()<=RideHistory.LIMIT, "History memory is bounded");
        }
        check(h.points().get(0).time==0, "Keep beginning of long ride");
        check(h.duration()==99999*500L, "Keep end of long ride");
        long previous=-1;
        for (RideHistory.Point p:h.points()) { check(p.time>previous,"Thinning preserves order"); previous=p.time; }
        check(h.points().get(10).breakMask==0, "Thinning alone must not create graph gaps");
        h.reset(0);
        sample(h,0);
        h.add(500,500,1.0,null,1.0,1);
        for (int i=2;i<4200;i++) sample(h,i*500L);
        check((h.points().get(1).breakMask & 2)!=0, "Preserve missing cadence gap through thinning");
        check((h.points().get(1).breakMask & 1)==0, "A missing cadence doesn't break valid speed");
        h.reset(0);sample(h,0);
        for(int i=1;i<4200;i++) sample(h,5000+i*500L);
        check(h.points().get(1).breakMask==15, "Preserve a lost-telemetry gap through thinning");
        h.reset(1000);
        check(h.points().isEmpty() && h.duration()==0, "New ride resets old timeline");
        System.out.println("RideHistoryTest: " + checks + " assertions passed");
    }
}
