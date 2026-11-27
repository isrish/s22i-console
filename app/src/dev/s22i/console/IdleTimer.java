package dev.s22i.console;

public final class IdleTimer {
    private long lastUse = -1;
    public void touch(long now) { lastUse = now; }
    public boolean expired(long now, long timeout, boolean inUse) {
        if (lastUse < 0 || now < lastUse || timeout <= 0 || inUse) {
            touch(now);
            return false;
        }
        return now - lastUse >= timeout;
    }
}
