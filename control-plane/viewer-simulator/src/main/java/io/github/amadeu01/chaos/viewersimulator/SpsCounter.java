package io.github.amadeu01.chaos.viewersimulator;

/** Playback Starts per wall-clock second, for the last {@value #SECONDS_KEPT} seconds. */
public final class SpsCounter {

    static final int SECONDS_KEPT = 60;

    private final long[] seconds = new long[SECONDS_KEPT];
    private final int[] counts = new int[SECONDS_KEPT];

    public SpsCounter() {
        java.util.Arrays.fill(seconds, -1);
    }

    public synchronized void recordStart(long epochMillis) {
        long second = epochMillis / 1000;
        int slot = (int) (second % SECONDS_KEPT);
        if (seconds[slot] != second) {
            seconds[slot] = second;
            counts[slot] = 0;
        }
        counts[slot]++;
    }

    public synchronized int startsIn(long epochSecond) {
        int slot = (int) (epochSecond % SECONDS_KEPT);
        return seconds[slot] == epochSecond ? counts[slot] : 0;
    }
}
