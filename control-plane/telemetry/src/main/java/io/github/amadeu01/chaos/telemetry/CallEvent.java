package io.github.amadeu01.chaos.telemetry;

public record CallEvent(long timestamp, String caller, String callee, String group, String outcome, long latencyMs) {
}
