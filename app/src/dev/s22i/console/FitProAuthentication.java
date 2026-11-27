package dev.s22i.console;

public final class FitProAuthentication {
    private static final int DONE = 2;

    private FitProAuthentication() { }

    public static final class Metadata {
        public final int firmware;
        public final long serialNumber;
        public final long model;
        public final long partNumber;
        public final int masterLibraryVersion;
        public final int masterLibraryBuild;
        private final byte[] payload;

        private Metadata(int firmware, long serialNumber, long model, long partNumber,
                         int masterLibraryVersion, int masterLibraryBuild) {
            this.firmware = firmware;
            this.serialNumber = serialNumber;
            this.model = model;
            this.partNumber = partNumber;
            this.masterLibraryVersion = masterLibraryVersion;
            this.masterLibraryBuild = masterLibraryBuild;
            this.payload = calculatePayload(serialNumber, partNumber, model, masterLibraryVersion);
        }

        public static Metadata fromPayloads(byte[] deviceInfoPayload,
                                            byte[] systemInfoPayload,
                                            byte[] versionInfoPayload) {
            requireLength(deviceInfoPayload, 6, "DeviceInfo");
            requireLength(systemInfoPayload, 11, "SystemInfo");
            requireLength(versionInfoPayload, 3, "VersionInfo");
            int firmware = unsignedByte(deviceInfoPayload[0]);
            if (firmware <= 75) {
                throw new IllegalArgumentException("VerifySecurity requires firmware above 75");
            }
            int masterVersion = unsignedByte(versionInfoPayload[0]);
            if (masterVersion == 0) {
                throw new IllegalArgumentException("Master library version must be nonzero");
            }
            return new Metadata(firmware, unsigned32(deviceInfoPayload, 2),
                    unsigned32(systemInfoPayload, 3), unsigned32(systemInfoPayload, 7),
                    masterVersion, unsignedByte(versionInfoPayload[1])
                            | (unsignedByte(versionInfoPayload[2]) << 8));
        }

        public byte[] authenticationPayload() {
            return payload.clone();
        }
    }

    public static boolean validSecurityAcknowledgement(int status, byte[] payload) {
        return status == DONE && payload != null && payload.length >= 1;
    }

    private static byte[] calculatePayload(long serialNumber, long partNumber,
                                           long model, int masterLibraryVersion) {
        byte[] payload = new byte[36];
        int serialBits = (int) serialNumber;
        int partBits = (int) partNumber;
        int modelBits = (int) model;
        int rotatedPart = (partBits << 16) | (partBits >>> 16);
        for (int index = 0; index < 32; index++) {
            int position = index + 1;
            int mixed;
            if (((serialBits >>> index) & 1) == 1) {

                mixed = index < 16 ? rotatedPart >> index : partBits >> index;
            } else {
                mixed = (modelBits + index) * position;
            }
            payload[index] = (byte) (mixed ^ position);
        }
        int secret = masterLibraryVersion * 8;
        for (int index = 0; index < 4; index++) {
            payload[32 + index] = (byte) (secret >>> (8 * index));
        }
        return payload;
    }

    private static void requireLength(byte[] payload, int minimum, String name) {
        if (payload == null || payload.length < minimum) {
            throw new IllegalArgumentException(name + " payload needs at least " + minimum + " bytes");
        }
    }

    private static int unsignedByte(byte value) {
        return value & 0xff;
    }

    private static long unsigned32(byte[] payload, int offset) {
        return (payload[offset] & 0xffL)
                | ((payload[offset + 1] & 0xffL) << 8)
                | ((payload[offset + 2] & 0xffL) << 16)
                | ((payload[offset + 3] & 0xffL) << 24);
    }
}
