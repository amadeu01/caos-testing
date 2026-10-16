package io.github.amadeu01.chaos.libtelemetry;

/** How one Dependency call ended, from the caller's point of view (spec 3.1). */
public enum Outcome {
    OK, ERROR, TIMEOUT, REJECTED, SHORT_CIRCUITED, FALLBACK
}
