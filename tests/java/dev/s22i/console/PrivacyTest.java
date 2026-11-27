package dev.s22i.console;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

public final class PrivacyTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++; if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        for (int command : new int[]{129,130,132,144,149,255}) {
            byte[] secret = new byte[]{8,10,(byte)command,2,0x12,0x34,0x56,0x78,0,0};
            String logged = ProtocolLog.frame(secret);
            check(logged.contains("redacted"), "Sensitive or unknown commands must be redacted");
            check(!logged.contains("12 34 56 78"), "No synthetic identity bytes in trace");
            check(!logged.contains("305419896"), "No decimal identity in trace");
        }
        check(ProtocolLog.frame(new byte[]{8,7}).contains("redacted"), "Truncated header redacted");
        check(ProtocolLog.frame(null).contains("redacted"), "Null frame redacted");
        byte[] telemetry = {8,6,2,2,1,19};
        check(ProtocolLog.frame(telemetry).equals(FitPro.hex(telemetry)), "Opt-in telemetry remains usable");
        for (int mask=0; mask<32; mask++) {
            check(PrivateFiles.mayDelete((mask&1)!=0,(mask&2)!=0,(mask&4)!=0,(mask&8)!=0,(mask&16)!=0)==(mask==0),
                    "Every active/recovery guard prevents deletion");
        }
        File directory = Files.createTempDirectory("s22i-privacy-").toFile();
        String[] rides={"ride-history.csv","last-ride-summary.json","last-ride-summary.json.bak","last-ride-summary.json.new"};
        String[] diagnostics={"protocol-trace.txt","telemetry.json","telemetry-next.json"};
        try {
            for (String name:rides) Files.write(new File(directory,name).toPath(),new byte[]{1});
            for (String name:diagnostics) Files.write(new File(directory,name).toPath(),new byte[]{2});
            File recovery=new File(directory,"ride.xml"); Files.write(recovery.toPath(),new byte[]{3});
            PrivateFiles.deleteDiagnostics(directory);
            for (String name:diagnostics) check(!new File(directory,name).exists(),"Remove legacy diagnostics");
            for (String name:rides) check(new File(directory,name).exists(),"Diagnostic cleanup preserves rides");
            PrivateFiles.deleteRides(directory);
            for (String name:rides) check(!new File(directory,name).exists(),"Delete ride and atomic summary remnants");
            check(Arrays.equals(Files.readAllBytes(recovery.toPath()),new byte[]{3}),"Preserve unrelated recovery data");
            PrivateFiles.deleteRides(directory); PrivateFiles.deleteDiagnostics(directory);
            check(recovery.exists(),"Repeated deletion remains bounded");
        } finally {
            for (File file:directory.listFiles()) Files.delete(file.toPath());
            Files.delete(directory.toPath());
        }
        RideHistory history=new RideHistory(); history.reset(0);
        history.add(0,0,10.0,50.0,20.0,2); history.add(1000,1000,10.0,50.0,20.0,2);
        history.clear();
        check(history.latest()==null && history.duration()==0 && history.points().isEmpty(),"Delete in-memory graph and time");
        check(Double.isNaN(history.stats.distanceKm()),"Delete calculated stats");
        check(!history.add(2000,2000,10.0,50.0,20.0,2),"Deleted history cannot resume without a new ride");
        System.out.println("PrivacyTest: "+checks+" assertions passed");
    }
}
