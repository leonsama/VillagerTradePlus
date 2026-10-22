package dev.villagertradeplus;

/** Main-thread, per-window detector using monotonic time. */
final class DoublePress {
    private long first;
    private boolean armed;
    void reset() { armed = false; }
    boolean press(long now, long intervalMillis, boolean doublePressEnabled) {
        if (!doublePressEnabled) { reset(); return true; }
        return press(now, intervalMillis);
    }
    boolean press(long now, long intervalMillis) {
        if (armed && now >= first && now - first <= intervalMillis * 1_000_000L) {
            armed = false;
            return true;
        }
        first = now;
        armed = true;
        return false;
    }
}
