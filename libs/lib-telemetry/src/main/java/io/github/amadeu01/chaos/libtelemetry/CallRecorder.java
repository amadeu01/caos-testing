package io.github.amadeu01.chaos.libtelemetry;

/** Records one Dependency call made by this Service. */
public interface CallRecorder {

    void recordCall(String callee, Outcome outcome, long latencyMs);
}
