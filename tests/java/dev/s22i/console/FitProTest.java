package dev.s22i.console;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;

public final class FitProTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        zeroSpeedFixture();
        readFactories();
        authenticationFramesAndBinding();
        securityAcknowledgements();
        readMasksHaveNoWriteSection();
        numericConverters();
        resistanceAndUnknownStayRaw();
        statusesNeverProduceValues();
        sentinelNeverProducesValues();
        malformedFramesAreRejected();
        responseMustMatchRequest();
        discoveryAndBoundReads();
        capabilityPayloads();
        syntheticBikeFixtures();
        manualControlFixtures();
        ergReadMetadata();
        keyAndGearStayRaw();
        gearPilotControlFixtures();
        fieldWidthFailuresRemainDistinct();
        rideStateControlFixtures();
        requireStartControlFixtures();
        requestsCannotBeConstructedOrMutated();
        usbReportsPreserveLogicalFrames();
        inputBounds();
        System.out.println("FitPro: " + passed + " assertions passed");
    }

    private static void zeroSpeedFixture() throws Exception {
        FitPro.Request request = FitPro.readField(4, 16);
        equal("04 09 02 00 03 00 00 01 13", FitPro.hex(request.bytes()));
        FitPro.Response response = FitPro.parseResponse(request, bytes(4, 7, 2, 2, 0, 0, 15));
        close(0.0, response.value);
        check(response.isDone() && !response.needsAuthentication());
    }

    private static void readFactories() throws Exception {
        equal("02 04 80 86", FitPro.hex(FitPro.supportedDevices().bytes()));
        equal("02 04 81 87", FitPro.hex(FitPro.deviceInfo(2).bytes()));
        equal("02 07 82 00 00 00 8B", FitPro.hex(FitPro.systemInfo(2).bytes()));
        equal("02 06 84 00 00 8C", FitPro.hex(FitPro.versionInfo(2).bytes()));
        equal("02 04 88 8E", FitPro.hex(FitPro.supportedCommands(2).bytes()));
        byte[] identityPayload = bytes(9, 2, 40, 80, 16);
        FitPro.Response response = FitPro.parseResponse(FitPro.deviceInfo(2), reply(2, 129, 2, identityPayload));
        check(response.value == null && response.field == null);
        check(Arrays.equals(identityPayload, response.payload()));
        byte[] payloadCopy = response.payload();
        payloadCopy[0] = 0;
        check(response.payload()[0] == 9);
        byte[] frameCopy = response.frame();
        frameCopy[0] = 0;
        check(response.frame()[0] == 2);
    }

    private static void authenticationFramesAndBinding() throws Exception {
        FitPro.Request security = FitPro.verifySecurity(2, authenticationMetadata());

        equal("02 28 90 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 "
                + "11 12 13 14 15 16 17 18 19 1A 1B 1C 1D 1E 1F 20 50 01 00 00 1B",
                FitPro.hex(security.bytes()));
        check(security.bytes().length == 40 && security.deviceId == 2);
        check(security.commandId == FitPro.VERIFY_SECURITY && security.fieldId == -1 && security.field == null);
        invalid(() -> FitPro.verifySecurity(2, null));
        for (int address : new int[] {-1, 0, 4, 7, 8, 255, 256}) {
            invalid(() -> FitPro.verifySecurity(address, authenticationMetadata()));
        }

        FitPro.Request[] requests = {FitPro.systemInfo(2), FitPro.versionInfo(2), security};
        byte[][] payloads = {new byte[11], bytes(42, 0x34, 0x12), bytes(0)};
        for (int index = 0; index < requests.length; index++) {
            FitPro.Request request = requests[index];
            byte[] payload = payloads[index];
            for (int equipment : new int[] {7, 8}) {
                byte[] frame = reply(equipment, request.commandId, FitPro.DONE, payload);
                rejects(request, frame);
                FitPro.Response accepted = FitPro.parseResponse(request, frame, equipment);
                check(accepted.isDone() && accepted.deviceId == equipment);
                check(accepted.commandId == request.commandId && accepted.field == null);
                check(Arrays.equals(payload, accepted.payload()));
                rejects(request, frame, equipment == 7 ? 8 : 7);
                rejects(request, reply(4, request.commandId, FitPro.DONE, payload), equipment);
                rejects(request, reply(equipment, FitPro.DEVICE_INFO, FitPro.DONE, payload), equipment);
                byte[] corrupt = frame.clone();
                corrupt[corrupt.length - 1] ^= 1;
                rejects(request, corrupt, equipment);
                byte[] incorrectLength = frame.clone();
                incorrectLength[1]--;
                incorrectLength[incorrectLength.length - 1]--;
                rejects(request, incorrectLength, equipment);
                rejects(request, Arrays.copyOf(frame, 64), equipment);
            }
        }
    }

    private static void securityAcknowledgements() throws Exception {
        FitPro.Request security = FitPro.verifySecurity(2, authenticationMetadata());
        for (int equipment : new int[] {7, 8}) {
            for (int token : new int[] {0, 1, 127, 255}) {
                FitPro.Response response = FitPro.parseResponse(security,
                        reply(equipment, FitPro.VERIFY_SECURITY, FitPro.DONE, bytes(token)), equipment);
                check(FitPro.securityUnlockKey(response) == token);
            }
            rejectSecurityKey(FitPro.parseResponse(security,
                    reply(equipment, FitPro.VERIFY_SECURITY, FitPro.DONE, bytes()), equipment));
            for (int status : new int[] {0, 3, 4, FitPro.SECURITY_BLOCK, 255}) {
                FitPro.Response refused = FitPro.parseResponse(security,
                        reply(equipment, FitPro.VERIFY_SECURITY, status, bytes(1)), equipment);
                rejectSecurityKey(refused);
                check(refused.needsAuthentication() == (status == FitPro.SECURITY_BLOCK));
            }
            rejectSecurityKey(FitPro.parseResponse(FitPro.versionInfo(2),
                    reply(equipment, FitPro.VERSION_INFO, FitPro.DONE, bytes(42, 0, 0)), equipment));
        }
        rejectSecurityKey(null);
    }

    private static FitProAuthentication.Metadata authenticationMetadata() {
        return FitProAuthentication.Metadata.fromPayloads(
                bytes(83, 1, 255, 255, 255, 255), new byte[11], bytes(42, 0, 0));
    }

    private static void rejectSecurityKey(FitPro.Response response) throws Exception {
        try {
            FitPro.securityUnlockKey(response);
            throw new AssertionError("Accepted an incomplete, wrong-command or rejected security reply");
        } catch (FitPro.ProtocolException expected) { passed++; }
    }

    private static void readMasksHaveNoWriteSection() {

        for (int id = 0; id <= 127; id++) {
            byte[] request = FitPro.readField(2, id).bytes();
            check(request[2] == 2 && request[3] == 0);
            check((request[1] & 255) == request.length && request.length <= 64);
            check((request[4] & 255) == id / 8 + 1);
            for (int index = 0; index < id / 8 + 1; index++) {
                check((request[5 + index] & 255) == (index == id / 8 ? 1 << (id % 8) : 0));
            }
        }
    }

    private static void numericConverters() throws Exception {
        close(250, decode(3, 250, 0).value);
        close(90, decode(5, 90, 0).value);
        close(40000, decode(3, 0x40, 0x9c).value);
        close(-3.0, decode(17, 0xd4, 0xfe).value);
        close(12.5, decode(17, 0xe2, 0x04).value);
        close(18.75, decode(16, 0x53, 0x07).value);
        close(24, decode(42, 24).value);
        close(1, decode(12, 1).value);
        close(-10, decode(28, 0x18, 0xfc).value);
        check(decode(2, 0xe7, 0x03).value == null);
    }

    private static void resistanceAndUnknownStayRaw() throws Exception {
        FitPro.Response resistance = decode(18, 12, 0);
        check(resistance.value == null && resistance.field.width == 0);
        equal("ResistanceConverter", resistance.field.converter);
        check(Arrays.equals(bytes(12, 0), resistance.payload()));
        check(resistance.valueUnavailableReason.contains("raw bytes"));
        FitPro.Response unknown = decode(126, 1, 2, 3, 4, 5);
        check(unknown.value == null && unknown.payload().length == 5);
        FitPro.Response empty = decode(18);
        check(empty.value == null && empty.valueUnavailableReason.equals("No field data returned"));
    }

    private static void statusesNeverProduceValues() throws Exception {
        FitPro.Request request = FitPro.readField(4, 16);
        FitPro.Response locked = FitPro.parseResponse(request, bytes(4, 5, 2, 8, 19));
        check(locked.needsAuthentication() && !locked.isDone() && locked.value == null);
        equal("Authentication required", locked.valueUnavailableReason);
        for (int status = 0; status <= 255; status++) {
            if (status == FitPro.DONE) continue;
            FitPro.Response response = FitPro.parseResponse(request, reply(4, 2, status, bytes(0, 0)));
            check(response.value == null);
        }
    }

    private static void sentinelNeverProducesValues() throws Exception {
        for (int field : new int[] {3, 5, 16, 17, 18, 126}) {
            FitPro.Response response = decode(field, 255, 255);
            check(response.value == null);
            equal("No-value sentinel", response.valueUnavailableReason);
        }
        close(-0.02, decode(17, 0xfe, 0xff).value);
    }

    private static void malformedFramesAreRejected() throws Exception {
        FitPro.Request request = FitPro.readField(4, 16);
        rejects(request, null);
        rejects(request, bytes());
        rejects(request, bytes(4, 4, 2, 10));
        rejects(request, bytes(4, 7, 2, 2, 0, 0, 14));
        rejects(request, bytes(4, 6, 2, 2, 0, 0, 14));
        rejects(request, bytes(4, 8, 2, 2, 0, 0, 16));
        rejects(request, bytes(4, 7, 2, 2, 0, 0, 15, 0));
        rejects(request, reply(4, 2, 2, bytes()));
        rejects(request, reply(4, 2, 2, bytes(0)));
        rejects(request, reply(4, 2, 2, bytes(0, 0, 0)));
        rejects(request, new byte[65]);
    }

    private static void responseMustMatchRequest() throws Exception {
        FitPro.Request request = FitPro.readField(4, 16);
        rejects(request, reply(2, 2, 2, bytes(0, 0)));
        rejects(request, reply(4, 129, 2, bytes(0, 0)));
        rejects(FitPro.deviceInfo(2), reply(2, 130, 2, bytes(0, 0)));
    }

    private static void requestsCannotBeConstructedOrMutated() {
        for (Constructor<?> constructor : FitPro.Request.class.getDeclaredConstructors()) {
            if (!constructor.isSynthetic()) check(Modifier.isPrivate(constructor.getModifiers()));
        }
        FitPro.Request request = FitPro.readField(2, 3);
        byte[] changed = request.bytes();
        changed[2] = 9;
        changed[3] = 1;
        check(request.bytes()[2] == 2 && request.bytes()[3] == 0);
    }

    private static void usbReportsPreserveLogicalFrames() throws Exception {
        FitPro.Request read = FitPro.readField(2, 7);
        FitPro.Request write = FitPro.manualControl(
                BikeControlPolicy.confirmResistanceRaw(8, 2500.0, 24, 10000, 10500));
        FitPro.Request longMask = FitPro.readField(2, 127);
        FitPro.Request security = FitPro.verifySecurity(2, authenticationMetadata());
        equal("02 07 02 00 01 80 8C", FitPro.hex(read.bytes()));
        equal("02 0A 02 01 04 C4 09 01 04 E5", FitPro.hex(write.bytes()));
        check(longMask.bytes().length == 22);

        for (FitPro.Request request : new FitPro.Request[] {read, write, longMask, security}) {
            byte[] logical = request.bytes();
            byte[] first = request.usbReportBytes();
            byte[] second = request.usbReportBytes();
            check(first.length == 64 && second.length == 64 && first != second);
            check(logical.length < 64 && request.bytes().length == logical.length);
            check(Arrays.equals(logical, Arrays.copyOf(first, logical.length)));
            check(Arrays.equals(first, second));
            check((first[1] & 255) == logical.length);
            int checksum = 0;
            for (int index = 0; index < logical.length - 1; index++) checksum += logical[index] & 255;
            check((first[logical.length - 1] & 255) == (checksum & 255));
            for (int index = logical.length; index < first.length; index++) check(first[index] == 0);

            first[0] ^= 0x7f;
            first[63] = 0x55;
            check(Arrays.equals(second, request.usbReportBytes()));
            check(Arrays.equals(logical, request.bytes()));
            second[2] ^= 0x3f;
            byte[] third = request.usbReportBytes();
            check(third != first && third != second);
            check(Arrays.equals(logical, Arrays.copyOf(third, logical.length)));
            check(third[63] == 0 && Arrays.equals(logical, request.bytes()));
        }
    }

    private static void syntheticBikeFixtures() throws Exception {
        int[] advertised = {2, 3, 5, 12, 17, 18, 27, 28, 42, 119};
        byte[] payload = new byte[24];
        payload[0] = 83;
        payload[1] = 1;
        payload[2] = 0x12;
        payload[3] = 0x34;
        payload[4] = 0x56;
        payload[5] = 0x78;
        payload[8] = 15;
        for (int field : advertised) payload[9 + field / 8] |= (byte) (1 << (field % 8));
        byte[] identity = reply(8, 129, 2, payload);
        FitPro.Response info = FitPro.parseResponse(FitPro.deviceInfo(2), identity);
        check(info.deviceId == 8);
        java.util.Set<Integer> fields = FitPro.supportedFields(info);
        check(fields.containsAll(Arrays.asList(2, 3, 5, 12, 17, 18, 27, 28, 42)));
        check(!fields.contains(29) && fields.contains(119) && fields.size() == advertised.length);
        close(24, FitPro.parseResponse(FitPro.readField(2, 42), fromHex("08 06 02 02 18 2A"), 8).value);
        close(20, FitPro.parseResponse(FitPro.readField(2, 27), fromHex("08 07 02 02 D0 07 EA"), 8).value);
        close(-10, FitPro.parseResponse(FitPro.readField(2, 28), fromHex("08 07 02 02 18 FC 27"), 8).value);
        FitPro.Response resistance = FitPro.parseResponse(FitPro.readField(2, 2), fromHex("08 07 02 02 C4 09 E0"), 8);
        check(resistance.value == null);
        check(((resistance.payload()[0] & 255) | ((resistance.payload()[1] & 255) << 8)) == 2500);
        byte[] zero = fromHex("08 07 02 02 00 00 13");
        close(0, FitPro.parseResponse(FitPro.readField(2, 3), zero, 8).value);
        close(0, FitPro.parseResponse(FitPro.readField(2, 5), zero, 8).value);
        byte[] padded = new byte[64];
        Arrays.fill(padded, (byte) 255);
        System.arraycopy(identity, 0, padded, 0, identity.length);
        rejects(FitPro.deviceInfo(2), padded);
    }

    private static void manualControlFixtures() throws Exception {
        BikeControlPolicy.Target unchanged = BikeControlPolicy.confirmResistanceRaw(8, 2500.0, 24, 10000, 10500);
        FitPro.Request request = FitPro.manualControl(unchanged);
        equal("02 0A 02 01 04 C4 09 01 04 E5", FitPro.hex(request.bytes()));
        check(request.deviceId == 2 && request.commandId == 2 && request.fieldId == 2);
        FitPro.Response response = FitPro.parseResponse(request, fromHex("08 07 02 02 C4 09 E0"), 8);
        check(response.isDone() && response.field == FitPro.RESISTANCE && response.value == null);
        FitPro.Request fromZero = FitPro.manualControl(BikeControlPolicy.resistanceRawStep(8, 0.0, 24, 10000, 10500, 1));
        equal("02 0A 02 01 04 A0 01 01 04 B9", FitPro.hex(fromZero.bytes()));
        FitPro.Response zeroStepReply = FitPro.parseResponse(fromZero, fromHex("08 07 02 02 A0 01 B4"), 8);
        check(zeroStepReply.isDone() && Arrays.equals(zeroStepReply.payload(), bytes(0xA0, 1)));
        BikeControlPolicy.Target lowerIncline = BikeControlPolicy.inclineStep(8, 0.0, -10.0, 20.0, 10000, 10500, -1);
        FitPro.Request incline = FitPro.manualControl(lowerIncline);
        equal("02 0A 02 01 02 CE FF 01 02 E1", FitPro.hex(incline.bytes()));
        check(incline.fieldId == 1 && incline.field == FitPro.GRADE);
        close(-0.5, FitPro.parseResponse(incline, fromHex("08 07 02 02 CE FF E0"), 8).value);
        BikeControlPolicy.Target upperIncline = BikeControlPolicy.inclineStep(8, 0.0, -10.0, 20.0, 10000, 10500, 1);
        equal("02 0A 02 01 02 32 00 01 02 46", FitPro.hex(FitPro.manualControl(upperIncline).bytes()));
        BikeControlPolicy.Target legacyLevel = BikeControlPolicy.resistanceStep(8, 7.0, 24, 10000, 10500, 1);
        invalid(() -> FitPro.manualControl(legacyLevel));
        invalid(() -> FitPro.manualControl(null));
    }

    private static void ergReadMetadata() throws Exception {
        FitPro.Request goal = FitPro.readField(2, 61);
        check(goal.field == FitPro.WATT_GOAL && goal.field.width == 2);
        check(goal.bytes()[3] == 0);
        close(0, decode(61, 0, 0).value);
        close(250, decode(61, 250, 0).value);
        close(40000, decode(61, 0x40, 0x9c).value);
        check(decode(61, 255, 255).value == null);
        FitPro.Request erg = FitPro.readField(2, 119);
        check(erg.field == FitPro.IS_CONSTANT_WATTS_MODE && erg.field.width == 1);
        check(erg.bytes()[3] == 0);
        close(0, decode(119, 0).value);
        close(1, decode(119, 1).value);
        check(decode(119, 255).value == null);
        rejects(goal, reply(2, 2, 2, bytes(0)));
        rejects(erg, reply(2, 2, 2, bytes(0, 0)));
    }

    private static void keyAndGearStayRaw() throws Exception {
        FitPro.Request key = FitPro.readField(2, 7);
        FitPro.Request gear = FitPro.readField(2, 26);
        equal("02 07 02 00 01 80 8C", FitPro.hex(key.bytes()));
        equal("02 0A 02 00 04 00 00 00 04 16", FitPro.hex(gear.bytes()));
        check(key.field == FitPro.KEY_OBJECT && key.field.width == 14);
        check(gear.field == FitPro.GEAR && gear.field.width == 8);
        byte[][] payloads = {
                bytes(0x10, 0, 0x20, 0x80, 0x40, 0xff, 0x60, 0, 0x70, 0x08, 0x90, 0x0a, 0xb0, 0x0c),
                bytes(0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef)
        };
        FitPro.Request[] requests = {key, gear};
        for (int index = 0; index < requests.length; index++) {
            FitPro.Request request = requests[index];
            check(request.bytes()[3] == 0);
            check(!request.field.hasKnownEncoding() && Double.isNaN(request.field.scale));
            byte[] payload = payloads[index];
            FitPro.Response response = FitPro.parseResponse(request, reply(8, 2, 2, payload), 8);
            check(response.isDone() && response.deviceId == 8 && response.field == request.field);
            check(response.value == null && Arrays.equals(payload, response.payload()));
            check(response.valueUnavailableReason.contains("raw bytes"));

            for (int length : new int[] {0, request.field.width - 1, request.field.width + 1}) {
                try {
                    FitPro.parseResponse(request, reply(8, 2, 2, new byte[length]), 8);
                    throw new AssertionError("Accepted wrong raw-field width " + length);
                } catch (FitPro.ProtocolException expected) { passed++; }
            }
            byte[] sentinel = new byte[request.field.width];
            Arrays.fill(sentinel, (byte) 255);
            FitPro.Response missing = FitPro.parseResponse(request, reply(8, 2, 2, sentinel), 8);
            check(missing.value == null && Arrays.equals(sentinel, missing.payload()));
            equal("No-value sentinel", missing.valueUnavailableReason);

            for (int status : new int[] {0, 4, 8}) {
                byte[] errorPayload = bytes(0x44, 0x02);
                FitPro.Response failed = FitPro.parseResponse(request, reply(8, 2, status, errorPayload), 8);
                check(!failed.isDone() && failed.status == status && failed.value == null);
                check(Arrays.equals(errorPayload, failed.payload()));
            }
        }
    }

    private static void gearPilotControlFixtures() throws Exception {
        byte[] one = fromHex("00 00 27 01 01 07 21 19");
        byte[] two = fromHex("00 00 27 01 02 07 20 19");
        BikeControlPolicy.Target up = BikeControlPolicy.gearStep(8, one, 2.0, 0.0, 10000, 10500, 1);
        BikeControlPolicy.Target down = BikeControlPolicy.gearStep(8, two, 2.0, 0.0, 10000, 10500, -1);
        equal("02 16 02 04 00 00 00 04 00 00 00 00 02 00 00 00 04 00 00 00 04 2C",
                FitPro.hex(FitPro.manualControl(up).bytes()));
        equal("02 16 02 04 00 00 00 04 00 00 00 00 01 00 00 00 04 00 00 00 04 2B",
                FitPro.hex(FitPro.manualControl(down).bytes()));
        for (BikeControlPolicy.Target target : new BikeControlPolicy.Target[] {up, down}) {
            FitPro.Request request = FitPro.manualControl(target);
            check(request.field == FitPro.GEAR && request.fieldId == 26 && request.deviceId == 2);
            byte[] frame = request.bytes();
            check(frame.length == 22 && frame[3] == 4 && frame[7] == 4 && frame[16] == 4 && frame[20] == 4);
            check(frame[4] == 0 && frame[5] == 0 && frame[6] == 0);
            check(frame[17] == 0 && frame[18] == 0 && frame[19] == 0);
            for (int index = 0; index < 8; index++) {
                check(frame[8 + index] == (index == 4 ? (byte) target.value : 0));
            }
            byte[] report = request.usbReportBytes();
            check(report.length == 64 && Arrays.equals(frame, Arrays.copyOf(report, frame.length)));
            for (int index = frame.length; index < report.length; index++) check(report[index] == 0);
            byte[] expected = target.expectedGearProfile();

            FitPro.Response confirmed = FitPro.parseResponse(request, reply(8, 2, 2, expected), 8);
            check(confirmed.isDone() && confirmed.value == null);
            check(Arrays.equals(expected, confirmed.payload()));
            byte[] zeroedMetadata = new byte[8];
            zeroedMetadata[4] = (byte) target.value;
            FitPro.Response incomplete = FitPro.parseResponse(request, reply(8, 2, 2, zeroedMetadata), 8);
            check(incomplete.value == null && !Arrays.equals(expected, incomplete.payload()));
            check(BikeControlPolicy.observedGearLevel(incomplete.payload()) == null);
            expected[0] = 99;
            check(Arrays.equals(confirmed.payload(), target.expectedGearProfile()));
        }
    }

    private static void fieldWidthFailuresRemainDistinct() throws Exception {
        FitPro.Request gear = FitPro.readField(2, 26);
        for (int actualWidth : new int[] {0, 7, 9}) {
            byte[] original = reply(8, 2, 2, new byte[actualWidth]);
            byte[] expectedFrame = original.clone();
            try {
                FitPro.parseResponse(gear, original, 8);
                throw new AssertionError("Accepted mismatched gear width");
            } catch (FitPro.FieldWidthException expected) {
                check(expected.fieldId == 26 && expected.expectedWidth == 8 && expected.actualWidth == actualWidth);
                check(Arrays.equals(expectedFrame, expected.frame()));
                original[0] = 77;
                byte[] copied = expected.frame();
                copied[0] = 88;
                check(Arrays.equals(expectedFrame, expected.frame()));
                check(copied != expected.frame());
            }
        }
        for (Constructor<?> constructor : FitPro.FieldWidthException.class.getDeclaredConstructors()) {
            if (!constructor.isSynthetic()) check(Modifier.isPrivate(constructor.getModifiers()));
        }
        byte[] corrupt = reply(8, 2, 2, new byte[7]);
        corrupt[corrupt.length - 1] ^= 1;
        byte[] wrongLength = reply(8, 2, 2, new byte[7]);
        wrongLength[1]--;
        wrongLength[wrongLength.length - 1]--;
        for (byte[] invalid : new byte[][] {corrupt, wrongLength, reply(7, 2, 2, new byte[7]),
                reply(8, 129, 2, new byte[7]), bytes(8, 4, 2, 2)}) {
            try {
                FitPro.parseResponse(gear, invalid, 8);
                throw new AssertionError("Accepted invalid frame before field-width validation");
            } catch (FitPro.FieldWidthException wrongKind) {
                throw new AssertionError("Unvalidated frame reported as field-width mismatch", wrongKind);
            } catch (FitPro.ProtocolException expected) { passed++; }
        }
        for (int status : new int[] {0, 4, 8}) {
            FitPro.Response rejected = FitPro.parseResponse(gear, reply(8, 2, status, new byte[7]), 8);
            check(!rejected.isDone() && rejected.status == status && rejected.value == null);
            check(rejected.payload().length == 7);
        }
    }

    private static void rideStateControlFixtures() throws Exception {
        FitPro.Request warmup = FitPro.manualControl(BikeControlPolicy.workoutModeTarget(8, 1.0, 10, 10000, 10500));
        equal("02 0B 02 02 00 10 0A 02 00 10 3D", FitPro.hex(warmup.bytes()));
        FitPro.Request running = FitPro.manualControl(BikeControlPolicy.workoutModeTarget(8, 10.0, 2, 10000, 10500));
        equal("02 0B 02 02 00 10 02 02 00 10 35", FitPro.hex(running.bytes()));
        FitPro.Request results = FitPro.manualControl(BikeControlPolicy.workoutModeTarget(8, 2.0, 4, 10000, 10500));
        equal("02 0B 02 02 00 10 04 02 00 10 37", FitPro.hex(results.bytes()));
        check(results.field == FitPro.WORKOUT_MODE && results.fieldId == 12);
        close(4, FitPro.parseResponse(results, fromHex("08 06 02 02 04 16"), 8).value);
        FitPro.Request idle = FitPro.manualControl(BikeControlPolicy.workoutModeTarget(8, 4.0, 1, 10000, 10500));
        equal("02 0B 02 02 00 10 01 02 00 10 34", FitPro.hex(idle.bytes()));
        check(warmup.field == FitPro.WORKOUT_MODE && warmup.fieldId == 12);
        close(10, FitPro.parseResponse(warmup, reply(8, 2, 2, bytes(10)), 8).value);
        close(2, FitPro.parseResponse(running, reply(8, 2, 2, bytes(2)), 8).value);
        close(1, FitPro.parseResponse(idle, reply(8, 2, 2, bytes(1)), 8).value);

        FitPro.Response failed = FitPro.parseResponse(idle, fromHex("08 05 02 04 13"), 8);
        check(failed.status == 4 && !failed.isDone() && !failed.needsAuthentication());
        check(failed.value == null && failed.payload().length == 0);
        equal("failed", FitPro.statusName(failed.status));
        FitPro.Request unlock = FitPro.manualControl(BikeControlPolicy.idleLockoutTarget(8, 1.0, false, 10000, 10500));
        byte[] frame = unlock.bytes();
        check(frame.length == 31 && frame[1] == 31 && frame[2] == 2);
        check(frame[3] == 12 && (frame[15] & 255) == 128 && frame[16] == 0);
        check(frame[17] == 12 && (frame[29] & 255) == 128 && (frame[30] & 255) == 0x3b);
        for (int index = 4; index <= 14; index++) check(frame[index] == 0);
        for (int index = 18; index <= 28; index++) check(frame[index] == 0);
        check(unlock.fieldId == 95 && unlock.field == FitPro.IDLE_MODE_LOCKOUT);
        close(0, FitPro.parseResponse(unlock, reply(8, 2, 2, bytes(0)), 8).value);
        FitPro.Request restore = FitPro.manualControl(BikeControlPolicy.idleLockoutTarget(8, 0.0, true, 10000, 10500));
        check(restore.bytes()[16] == 1 && (restore.bytes()[30] & 255) == 0x3c);
        close(1, FitPro.parseResponse(restore, reply(8, 2, 2, bytes(1)), 8).value);
    }

    private static void requireStartControlFixtures() throws Exception {
        FitPro.Request read = FitPro.readField(2, 108);
        equal("02 14 02 00 0E 00 00 00 00 00 00 00 00 00 00 00 00 00 10 36", FitPro.hex(read.bytes()));
        check(read.field == FitPro.REQUIRE_START_REQUESTED && read.field.width == 1);
        equal("BoolConverter", read.field.converter);
        close(0, FitPro.parseResponse(read, fromHex("08 06 02 02 00 12"), 8).value);
        close(1, FitPro.parseResponse(read, fromHex("08 06 02 02 01 13"), 8).value);
        check(FitPro.parseResponse(read, reply(8, 2, 2, bytes(255)), 8).value == null);
        rejects(read, reply(8, 2, 2, bytes()), 8);
        rejects(read, reply(8, 2, 2, bytes(0, 0)), 8);

        FitPro.Request lockout = FitPro.manualControl(BikeControlPolicy.idleLockoutTarget(8, 1.0, false, 10000, 10500));
        byte[] originalLockout = lockout.bytes();
        FitPro.Request enabled = FitPro.manualControl(BikeControlPolicy.requireStartTarget(8, 0.0, true, 10000, 10500));
        FitPro.Request disabled = FitPro.manualControl(BikeControlPolicy.requireStartTarget(8, 1.0, false, 10000, 10500));
        equal("02 23 02 0E 00 00 00 00 00 00 00 00 00 00 00 00 00 10 01 "
                + "0E 00 00 00 00 00 00 00 00 00 00 00 00 00 10 64", FitPro.hex(enabled.bytes()));
        equal("02 23 02 0E 00 00 00 00 00 00 00 00 00 00 00 00 00 10 00 "
                + "0E 00 00 00 00 00 00 00 00 00 00 00 00 00 10 63", FitPro.hex(disabled.bytes()));
        for (FitPro.Request request : new FitPro.Request[] {enabled, disabled}) {
            check(request.deviceId == 2 && request.commandId == 2 && request.fieldId == 108);
            check(request.field == FitPro.REQUIRE_START_REQUESTED && request.bytes().length == 35);
            byte[] before = request.bytes();
            byte[] changed = request.bytes();
            changed[18] = 9;
            changed[15] = (byte) 0x80;
            check(Arrays.equals(before, request.bytes()));
        }
        close(1, FitPro.parseResponse(enabled, fromHex("08 06 02 02 01 13"), 8).value);
        close(0, FitPro.parseResponse(disabled, fromHex("08 06 02 02 00 12"), 8).value);
        check(Arrays.equals(originalLockout, lockout.bytes()));
        check(lockout.field == FitPro.IDLE_MODE_LOCKOUT && lockout.fieldId == 95);
        check(Arrays.equals(originalLockout,
                FitPro.manualControl(BikeControlPolicy.idleLockoutTarget(8, 1.0, false, 10000, 10500)).bytes()));
    }

    private static void discoveryAndBoundReads() throws Exception {
        FitPro.Request discovery = FitPro.deviceInfo(2);
        for (int bike : new int[] {2, 7, 8}) {
            FitPro.Response response = FitPro.parseResponse(discovery, reply(bike, 129, 2, bytes(1, 2)));
            check(response.deviceId == bike);
        }
        rejects(discovery, reply(4, 129, 2, bytes(1, 2)));
        rejects(FitPro.deviceInfo(7), reply(8, 129, 2, bytes(1, 2)));
        FitPro.Request read = FitPro.readField(2, 5);
        byte[] bike7 = reply(7, 2, 2, bytes(90, 0));
        rejects(read, bike7);
        close(90, FitPro.parseResponse(read, bike7, 7).value);
        try {
            FitPro.parseResponse(read, bike7, 8);
            throw new AssertionError("Accepted a different bike from the bound identity");
        } catch (FitPro.ProtocolException expected) { passed++; }
        rejects(FitPro.supportedCommands(2), reply(7, 136, 2, bytes(2, 129)));
    }

    private static void capabilityPayloads() throws Exception {
        byte[] info = bytes(1, 2, 3, 4, 5, 6, 7, 8, 3, 0x2c, 0x10, 0x02);
        FitPro.Response response = FitPro.parseResponse(FitPro.deviceInfo(2), reply(7, 129, 2, info));
        check(FitPro.supportedFields(response).equals(new java.util.LinkedHashSet<>(Arrays.asList(2, 3, 5, 12, 17))));
        FitPro.Response commands = FitPro.parseResponse(FitPro.supportedCommands(2), reply(2, 136, 2, bytes(2, 128, 129, 136)));
        check(FitPro.supportedCommands(commands).equals(new java.util.LinkedHashSet<>(Arrays.asList(2, 128, 129, 136))));
        byte[] truncated = Arrays.copyOf(info, info.length - 1);
        FitPro.Response shortInfo = FitPro.parseResponse(FitPro.deviceInfo(2), reply(7, 129, 2, truncated));
        try {
            FitPro.supportedFields(shortInfo);
            throw new AssertionError("Accepted truncated mask");
        } catch (FitPro.ProtocolException expected) { passed++; }
        FitPro.Response failed = FitPro.parseResponse(FitPro.deviceInfo(2), reply(7, 129, 8, info));
        try {
            FitPro.supportedFields(failed);
            throw new AssertionError("Accepted capabilities from non-Done status");
        } catch (FitPro.ProtocolException expected) { passed++; }
    }

    private static void inputBounds() {
        invalid(new Runnable() { public void run() { FitPro.readField(2, -1); } });
        invalid(new Runnable() { public void run() { FitPro.readField(2, 128); } });
        invalid(new Runnable() { public void run() { FitPro.deviceInfo(-1); } });
        invalid(new Runnable() { public void run() { FitPro.systemInfo(256); } });
    }

    private static FitPro.Response decode(int field, int... data) throws Exception {
        return FitPro.parseResponse(FitPro.readField(2, field), reply(2, 2, 2, bytes(data)));
    }

    private static byte[] reply(int device, int command, int status, byte[] payload) {
        byte[] frame = new byte[payload.length + 5];
        frame[0] = (byte) device;
        frame[1] = (byte) frame.length;
        frame[2] = (byte) command;
        frame[3] = (byte) status;
        System.arraycopy(payload, 0, frame, 4, payload.length);
        int sum = 0;
        for (int index = 0; index < frame.length - 1; index++) sum += frame[index] & 255;
        frame[frame.length - 1] = (byte) sum;
        return frame;
    }

    private static byte[] bytes(int... values) {
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) bytes[index] = (byte) values[index];
        return bytes;
    }

    private static byte[] fromHex(String input) {
        String[] values = input.split(" ");
        byte[] bytes = new byte[values.length];
        for (int index = 0; index < values.length; index++) bytes[index] = (byte) Integer.parseInt(values[index], 16);
        return bytes;
    }

    private static void rejects(FitPro.Request request, byte[] frame) throws Exception {
        try {
            FitPro.parseResponse(request, frame);
            throw new AssertionError("Accepted malformed/mismatched response");
        } catch (FitPro.ProtocolException expected) { passed++; }
    }

    private static void rejects(FitPro.Request request, byte[] frame, int equipment) throws Exception {
        try {
            FitPro.parseResponse(request, frame, equipment);
            throw new AssertionError("Accepted malformed/mismatched response for bound equipment " + equipment);
        } catch (FitPro.ProtocolException expected) { passed++; }
    }

    private static void invalid(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Accepted invalid argument");
        } catch (IllegalArgumentException expected) { passed++; }
    }

    private static void close(double expected, Double actual) {
        check(actual != null && Math.abs(expected - actual) < 0.000001);
    }

    private static void equal(String expected, String actual) { check(expected.equals(actual)); }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Assertion failed");
        passed++;
    }
}
