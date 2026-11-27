package dev.s22i.console;

public final class ProtocolLog {
    private ProtocolLog() { }
    public static String frame(byte[] bytes) {
        if (bytes == null || bytes.length < 3) return "[short frame redacted]";
        int command = bytes[2] & 255;
        if (command == 129 || command == 130 || command == 132 || command == 144 || command == 149) {
            return "command=" + command + " [identity/auth payload redacted]";
        }

        if (command != 2 && command != 136) return "command=" + command + " [payload redacted]";
        return FitPro.hex(bytes);
    }
}
