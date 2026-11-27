package dev.s22i.console;

public final class IdleTimerTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++; if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        IdleTimer timer = new IdleTimer();
        long halfHour = 30 * 60000L;
        check(!timer.expired(1000, halfHour, false), "First observation starts a fresh deadline");
        check(!timer.expired(1000 + halfHour - 1, halfHour, false), "No early dim");
        check(timer.expired(1000 + halfHour, halfHour, false), "Dim at configured inactivity boundary");
        timer.touch(halfHour + 500);
        check(!timer.expired(halfHour + 1000, halfHour, false), "Touch resets deadline");
        check(!timer.expired(10 * halfHour, halfHour, true), "An active ride or obscured window cannot dim");
        check(!timer.expired(11 * halfHour - 1, halfHour, false), "Full idle delay after activity ends");
        check(timer.expired(11 * halfHour, halfHour, false), "Dim after full post-activity interval");
        check(!timer.expired(20 * halfHour, 0, false), "Never option disables dimming");
        check(!timer.expired(20 * halfHour + 1, halfHour, false), "Reenable starts from recent activity");
        timer.touch(2000);
        check(!timer.expired(1000, halfHour, false), "Clock rollback cannot immediately dim");
        check(!timer.expired(60999, 60000, false), "One-minute option boundary");
        check(timer.expired(61000, 60000, false), "One-minute option expires");
        System.out.println("IdleTimerTest: " + checks + " assertions passed");
    }
}
