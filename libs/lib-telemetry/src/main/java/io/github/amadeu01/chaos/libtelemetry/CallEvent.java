package io.github.amadeu01.chaos.libtelemetry;

/** One Dependency call, written by the caller. {@code group} is "none" until slice 5 adds Experiments. */
public record CallEvent(long timestamp, String caller, String callee, String group, Outcome outcome, long latencyMs) {

    public static final String NO_GROUP = "none";
}
