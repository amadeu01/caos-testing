package io.github.amadeu01.chaos.telemetry;

import java.util.Map;

public record DependencyView(String caller, String callee, int callsPerSecond, double errorPercent,
                             long p50Ms, long p99Ms, Map<String, Integer> outcomes, Health health) {
}
