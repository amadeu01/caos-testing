package io.github.amadeu01.chaos.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Every call from one caller to one callee within one second. */
final class DependencyStats {

    private final String caller;
    private final String callee;
    private final Map<String, Integer> outcomes = new TreeMap<>();
    private final List<Long> latencies = new ArrayList<>();

    DependencyStats(String caller, String callee) {
        this.caller = caller;
        this.callee = callee;
    }

    void record(String outcome, long latencyMs) {
        outcomes.merge(outcome, 1, Integer::sum);
        latencies.add(latencyMs);
    }

    DependencyView view() {
        int calls = latencies.size();
        int ok = outcomes.getOrDefault("OK", 0);
        int fallbacks = outcomes.getOrDefault("FALLBACK", 0);
        int errors = calls - ok - fallbacks;
        List<Long> sorted = latencies.stream().sorted().toList();
        long p99 = Percentiles.of(sorted, 0.99);
        return new DependencyView(caller, callee, calls, calls == 0 ? 0 : errors * 100.0 / calls,
                Percentiles.of(sorted, 0.50), p99, Map.copyOf(outcomes), HealthRule.forDependency(ok, errors, fallbacks, p99));
    }
}
