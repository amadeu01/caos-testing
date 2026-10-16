package io.github.amadeu01.chaos.telemetry;

import java.util.List;

/** Spec 3.4, the one rule both the dashboard and the lecture guide use. Grey (stopped) arrives in slice 3. */
final class HealthRule {

    static final long HEARTBEAT_TIMEOUT_MS = 3_000;
    static final double RED_ERROR_PERCENT = 50;
    static final double AMBER_ERROR_PERCENT = 5;
    static final long AMBER_P99_MS = 1_000;

    private HealthRule() {
    }

    /**
     * @param incoming   calls this Service received in the window
     * @param lastSeenMs when it last sent a batch, or null if it never reports (the emulators)
     */
    static Health forService(List<DependencyView> incoming, Long lastSeenMs, long nowMs) {
        if (lastSeenMs != null && nowMs - lastSeenMs > HEARTBEAT_TIMEOUT_MS) {
            return Health.RED;
        }
        int ok = 0;
        int fallbacks = 0;
        int calls = 0;
        long p99 = 0;
        for (DependencyView view : incoming) {
            calls += view.callsPerSecond();
            ok += view.outcomes().getOrDefault("OK", 0);
            fallbacks += view.outcomes().getOrDefault("FALLBACK", 0);
            p99 = Math.max(p99, view.p99Ms());
        }
        return forDependency(ok, calls - ok - fallbacks, fallbacks, p99);
    }

    static Health forDependency(int ok, int errors, int fallbacks, long p99Ms) {
        int calls = ok + errors + fallbacks;
        if (calls == 0) {
            return Health.GREEN;
        }
        double errorPercent = errors * 100.0 / calls;
        if (errorPercent >= RED_ERROR_PERCENT) {
            return Health.RED;
        }
        if (fallbacks > 0 || errorPercent >= AMBER_ERROR_PERCENT || p99Ms > AMBER_P99_MS) {
            return Health.AMBER;
        }
        return Health.GREEN;
    }
}
