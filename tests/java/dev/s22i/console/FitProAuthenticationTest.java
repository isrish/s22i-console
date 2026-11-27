package dev.s22i.console;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

public final class FitProAuthenticationTest {
    private static int passed;

    public static void main(String[] args) {
        knownHashFixtures();
        unsignedMetadataAndOffsets();
        malformedMetadataIsRejected();
        immutableMetadataAndPayloads();
        securityAcknowledgement();
        System.out.println("FitProAuthentication: " + passed + " assertions passed");
    }

    private static void knownHashFixtures() {

        fixture(0, 0, 0, 1,
                "01 00 05 08 11 18 2D 30 41 50 65 88 91 B8 DD E0 "
                + "01 20 45 68 B1 D8 ED 30 41 90 A5 E8 31 78 BD C0 08 00 00 00");

        fixture(0xffffffffL, 0, 0xffffffffL, 255,
                "01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 "
                + "11 12 13 14 15 16 17 18 19 1A 1B 1C 1D 1E 1F 20 F8 07 00 00");

        fixture(0x80018003L, 0x12345678L, 0x123456L, 42,
                "35 18 0B 60 C7 24 83 E0 47 BC 2B 80 F7 64 D3 E0 "
                + "25 2C AB 20 A7 24 A3 20 A7 5C CB 40 F7 64 13 20 50 01 00 00");

        fixture(0xffffffffL, 0xfedcba98L, 0x89abcdefL, 255,
                "DD 6C B4 DF E8 F0 FC F5 F7 75 34 13 82 C9 6C 21 "
                + "CD 7C A4 CF F8 E0 EC E5 E7 E5 E4 E3 E2 E1 E0 DF F8 07 00 00");
        fixture(0x80000000L, 0x80000000L, 0x80000000L, 128,
                "01 00 05 08 11 18 2D 30 41 50 65 88 91 B8 DD E0 "
                + "01 20 45 68 B1 D8 ED 30 41 90 A5 E8 31 78 BD DF 00 04 00 00");

        FitProAuthentication.Metadata unmodified = metadata(83, 0x80018003L, 0x123456L, 0x12345678L, 42, 0);
        check(unmodified.partNumber == 0x12345678L && unmodified.model == 0x123456L);
        equal("35 18 0B 60 C7 24 83 E0 47 BC 2B 80 F7 64 D3 E0 "
                + "25 2C AB 20 A7 24 A3 20 A7 5C CB 40 F7 64 13 20 50 01 00 00",
                unmodified.authenticationPayload());
    }

    private static void unsignedMetadataAndOffsets() {
        FitProAuthentication.Metadata meta = FitProAuthentication.Metadata.fromPayloads(
                bytes(0xff, 0xa1, 0xef, 0xcd, 0xab, 0x89, 0xee),
                bytes(0xa2, 0xa3, 0xa4, 0x98, 0xba, 0xdc, 0xfe, 0xff, 0xff, 0xff, 0xff, 0xed),
                bytes(0x80, 0x34, 0xf2, 0xec));
        check(meta.firmware == 255);
        check(meta.serialNumber == 0x89abcdefL);
        check(meta.model == 0xfedcba98L);
        check(meta.partNumber == 0xffffffffL);
        check(meta.masterLibraryVersion == 128);
        check(meta.masterLibraryBuild == 0xf234);
        check(Arrays.equals(bytes(0, 4, 0, 0),
                Arrays.copyOfRange(meta.authenticationPayload(), 32, 36)));
        check(metadata(76, 0, 0, 0, 1, 0xffff).masterLibraryBuild == 65535);
    }

    private static void malformedMetadataIsRejected() {
        byte[] device = bytes(83, 0, 3, 0, 0, 0);
        byte[] system = new byte[11];
        byte[] version = bytes(1, 0, 0);
        rejects(null, system, version);
        rejects(device, null, version);
        rejects(device, system, null);
        for (int length = 0; length < device.length; length++) {
            rejects(Arrays.copyOf(device, length), system, version);
        }
        for (int length = 0; length < system.length; length++) {
            rejects(device, Arrays.copyOf(system, length), version);
        }
        for (int length = 0; length < version.length; length++) {
            rejects(device, system, Arrays.copyOf(version, length));
        }
        for (int firmware = 0; firmware <= 75; firmware++) {
            device[0] = (byte) firmware;
            rejects(device, system, version);
        }
        device[0] = 76;
        rejects(device, system, bytes(0, 0xff, 0xff));
        check(FitProAuthentication.Metadata.fromPayloads(device, system, version).firmware == 76);
    }

    private static void immutableMetadataAndPayloads() {
        byte[] device = bytes(83, 9, 3, 0, 0, 0);
        byte[] system = bytes(9, 8, 7, 0x56, 0x34, 0x12, 0, 0x78, 0x56, 0x34, 0x12);
        byte[] version = bytes(42, 1, 2);
        FitProAuthentication.Metadata meta = FitProAuthentication.Metadata.fromPayloads(device, system, version);
        byte[] expected = meta.authenticationPayload();
        Arrays.fill(device, (byte) 0);
        Arrays.fill(system, (byte) 0);
        Arrays.fill(version, (byte) 0);
        check(meta.firmware == 83 && meta.serialNumber == 3 && meta.model == 0x123456L);
        check(meta.partNumber == 0x12345678L && meta.masterLibraryVersion == 42);
        check(meta.masterLibraryBuild == 513);
        check(Arrays.equals(expected, meta.authenticationPayload()));
        byte[] modified = meta.authenticationPayload();
        modified[0] ^= 0x7f;
        modified[35] = (byte) 0xff;
        check(Arrays.equals(expected, meta.authenticationPayload()));
        check(modified != meta.authenticationPayload());
        check(Modifier.isFinal(FitProAuthentication.Metadata.class.getModifiers()));
        for (Field field : FitProAuthentication.Metadata.class.getDeclaredFields()) {
            check(Modifier.isFinal(field.getModifiers()));
            if (field.getType().isArray()) check(Modifier.isPrivate(field.getModifiers()));
        }
        for (Constructor<?> constructor : FitProAuthentication.Metadata.class.getDeclaredConstructors()) {
            if (!constructor.isSynthetic()) check(Modifier.isPrivate(constructor.getModifiers()));
        }
    }

    private static void securityAcknowledgement() {
        check(!FitProAuthentication.validSecurityAcknowledgement(2, null));
        check(!FitProAuthentication.validSecurityAcknowledgement(2, bytes()));
        check(FitProAuthentication.validSecurityAcknowledgement(2, bytes(0)));
        check(FitProAuthentication.validSecurityAcknowledgement(2, bytes(255)));
        check(FitProAuthentication.validSecurityAcknowledgement(2, bytes(1, 2, 3)));
        for (int status = 0; status <= 255; status++) {
            if (status != 2) check(!FitProAuthentication.validSecurityAcknowledgement(status, bytes(1)));
        }
        check(!FitProAuthentication.validSecurityAcknowledgement(-1, bytes(1)));
        check(!FitProAuthentication.validSecurityAcknowledgement(258, bytes(1)));
    }

    private static void fixture(long serial, long part, long model, int master, String expected) {
        byte[] payload = metadata(83, serial, model, part, master, 1234).authenticationPayload();
        check(payload.length == 36);
        equal(expected, payload);
    }

    private static FitProAuthentication.Metadata metadata(int firmware, long serial, long model,
                                                         long part, int master, int build) {
        byte[] device = bytes(firmware, 0, 0, 0, 0, 0);
        byte[] system = new byte[11];
        put32(device, 2, serial);
        put32(system, 3, model);
        put32(system, 7, part);
        return FitProAuthentication.Metadata.fromPayloads(device, system, bytes(master, build, build >>> 8));
    }

    private static void put32(byte[] target, int offset, long value) {
        for (int index = 0; index < 4; index++) target[offset + index] = (byte) (value >>> (8 * index));
    }

    private static void rejects(byte[] device, byte[] system, byte[] version) {
        try {
            FitProAuthentication.Metadata.fromPayloads(device, system, version);
            throw new AssertionError("Accepted invalid authentication metadata");
        } catch (IllegalArgumentException expected) {
            passed++;
        }
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) bytes[index] = (byte) values[index];
        return bytes;
    }

    private static void equal(String expected, byte[] actual) {
        StringBuilder result = new StringBuilder();
        for (byte value : actual) {
            if (result.length() > 0) result.append(' ');
            result.append(String.format(java.util.Locale.ROOT, "%02X", value & 0xff));
        }
        if (!expected.equals(result.toString())) {
            throw new AssertionError("Expected " + expected + " but got " + result);
        }
        passed++;
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Authentication assertion " + (passed + 1) + " failed");
        passed++;
    }
}
