package dev.s22i.console;

public final class RideStatsTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static void near(double actual, double expected, String message) { check(Math.abs(actual-expected)<1e-8, message+": "+actual); }
    private static void add(RideHistory h, long t, Double speed, Double cadence, Double power) {
        check(h.add(t,t,speed,cadence,power,8), "sample accepted");
    }
    public static void main(String[] args) {
        RideHistory h = new RideHistory();
        check(Double.isNaN(h.stats.distanceKm()), "No invented distance without speed");
        h.reset(0);
        for (int i=0;i<=3600;i++) add(h,i*1000L,18.0,80.0,150.0);
        near(h.stats.distanceKm(),18,"18 km/h for one hour is 18 km");
        near(h.stats.average(0),18,"Time-weighted average speed");
        near(h.stats.average(2),150,"Time-weighted average power");
        check(h.stats.durationMillis()==3600000 && h.stats.distanceComplete(),"Full speed coverage");
        h.reset(0);
        add(h,0,0.0,0.0,0.0);add(h,1000,36.0,80.0,200.0);add(h,3000,36.0,80.0,200.0);
        near(h.stats.distanceKm(),.025,"Trapezoidal integration handles changing speed and unequal intervals");
        near(h.stats.average(2),500000.0/3000,"Averages are weighted by time, not sample count");
        near(h.stats.peak(2),200,"Peak power");near(h.stats.peak(3),8,"Peak reported resistance");
        add(h,7000,36.0,80.0,200.0);
        near(h.stats.distanceKm(),.025,"No extrapolation through a telemetry gap");
        check(!h.stats.distanceComplete(),"A gap marks distance partial");
        h.reset(0);
        add(h,0,36.0,80.0,200.0);add(h,1000,null,80.0,200.0);add(h,2000,36.0,80.0,200.0);add(h,3000,36.0,80.0,200.0);
        near(h.stats.distanceKm(),.01,"Missing speed skips both bordering intervals");
        check(h.stats.coveredMillis(2)==3000 && h.stats.coveredMillis(0)==1000,"Missing speed does not invalidate power");
        check(!h.add(4000,8000,36.0,80.0,200.0,8),"Stale sample rejected");
        near(h.stats.distanceKm(),.01,"Rejected sample cannot add distance");
        h.reset(0);add(h,0,null,null,null);add(h,1000,null,null,null);
        check(Double.isNaN(h.stats.distanceKm()) && Double.isNaN(h.stats.average(2)),"Unavailable stats stay unavailable");
        h.reset(0);add(h,0,0.0,0.0,0.0);add(h,1000,0.0,0.0,0.0);
        near(h.stats.distanceKm(),0,"Stationary ride is a real zero");
        near(h.stats.average(2),0,"Coasting zeroes count in average");
        check(h.stats.distanceComplete(),"Stationary zero speed has complete coverage");
        h.reset(0);
        StringBuilder csv = new StringBuilder();
        for(int i=0;i<10000;i++) {
            add(h,i*1000L,18.0,75.0,i==1?999.0:100.0);
            csv.append(h.csvLatest());
        }
        check(h.points().size()<10000,"Chart thinning happened");
        near(h.stats.distanceKm(),18*9999.0/3600,"Distance unaffected by thinning");
        near(h.stats.peak(2),999,"Peak preserved even if original sample thinned out");
        RideHistory restored = new RideHistory();
        for(String row:csv.toString().split("\n"))restored.restoreLine(row);
        near(restored.stats.distanceKm(),h.stats.distanceKm(),"CSV restore preserves distance");
        near(restored.stats.average(2),h.stats.average(2),"CSV restore preserves averages");
        near(restored.stats.peak(2),999,"CSV restore preserves peaks");
        double before=restored.stats.distanceKm();restored.resume(1000);add(restored,1000,200.0,75.0,100.0);
        near(restored.stats.distanceKm(),before,"Process recovery does not invent distance across a gap");
        check(!restored.stats.distanceComplete(),"Recovery marks partial distance");
        restored.reset(0);check(Double.isNaN(restored.stats.distanceKm()) && restored.stats.durationMillis()==0,"New ride clears stats");
        System.out.println("RideStatsTest: "+checks+" assertions passed");
    }
}
