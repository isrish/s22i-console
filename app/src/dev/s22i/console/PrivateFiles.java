package dev.s22i.console;

import java.io.File;
import java.io.IOException;

public final class PrivateFiles {
    private PrivateFiles() { }
    public static void deleteDiagnostics(File directory) throws IOException {
        delete(directory, new String[]{"protocol-trace.txt", "telemetry.json", "telemetry-next.json"});
    }
    public static void deleteRides(File directory) throws IOException {
        delete(directory, new String[]{"ride-history.csv", "last-ride-summary.json",
                "last-ride-summary.json.bak", "last-ride-summary.json.new"});
    }
    private static void delete(File directory, String[] names) throws IOException {
        for (String name : names) {
            File file = new File(directory, name);
            if (file.exists() && !file.delete()) throw new IOException("Could not remove saved app file");
        }
    }
    public static boolean mayDelete(boolean recording, boolean closing, boolean bikeInUse,
                                    boolean recoveryOwned, boolean historyActive) {
        return !recording && !closing && !bikeInUse && !recoveryOwned && !historyActive;
    }
}
