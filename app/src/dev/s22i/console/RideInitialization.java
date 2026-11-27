package dev.s22i.console;

import java.io.IOException;

public final class RideInitialization {
    private RideInitialization() { }

    public static final class OriginalFlags {
        public final int idleLockout;

        public final Integer requireStart;

        public OriginalFlags(int idleLockout, Integer requireStart) {
            if ((idleLockout != 0 && idleLockout != 1)
                    || (requireStart != null && requireStart != 0 && requireStart != 1)) {
                throw new IllegalArgumentException("Original controller flags must be 0 or 1.");
            }
            this.idleLockout = idleLockout;
            this.requireStart = requireStart;
        }
    }

    public interface Port {
        Double read(int field) throws Exception;

        void saveOriginals(OriginalFlags originals) throws Exception;

        void confirmFlag(int field, boolean enabled) throws Exception;

        void checkpoint() throws Exception;
    }

    public static OriginalFlags prepare(Port port) throws Exception {
        requirePort(port);
        port.checkpoint();
        requireValue(port.read(12), 1, "The bike must be idle before initialization.");
        port.checkpoint();
        requireValue(port.read(119), 0, "ERG mode must be off before initialization.");
        port.checkpoint();
        int lockout = flag(port.read(95), 95);
        port.checkpoint();
        int requireStart = flag(port.read(108), 108);
        OriginalFlags originals = new OriginalFlags(lockout, requireStart);
        port.checkpoint();
        port.saveOriginals(originals);
        port.checkpoint();
        port.confirmFlag(108, true);
        port.checkpoint();
        port.confirmFlag(95, true);
        port.checkpoint();
        port.confirmFlag(95, false);
        port.checkpoint();
        return originals;
    }

    public static void restore(Port port, OriginalFlags originals) throws Exception {
        requirePort(port);
        if (originals == null) throw new IllegalArgumentException("Original controller flags are required.");
        port.checkpoint();
        requireValue(port.read(12), 1, "The bike must confirm idle before restoring controller flags.");
        port.checkpoint();

        port.confirmFlag(95, originals.idleLockout == 1);
        port.checkpoint();
        if (originals.requireStart != null) {
            port.confirmFlag(108, originals.requireStart == 1);
            port.checkpoint();
        }
    }

    private static void requirePort(Port port) {
        if (port == null) throw new IllegalArgumentException("Initialization port is required.");
    }

    private static int flag(Double value, int field) throws IOException {
        if (value == null || (value != 0.0 && value != 1.0)) {
            throw new IOException("Controller flag " + field + " must be a confirmed 0 or 1.");
        }
        return value.intValue();
    }

    private static void requireValue(Double value, int expected, String message) throws IOException {
        if (value == null || value != expected) throw new IOException(message);
    }
}
