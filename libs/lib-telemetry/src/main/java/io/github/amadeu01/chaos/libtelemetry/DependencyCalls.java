package io.github.amadeu01.chaos.libtelemetry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The one seam every Dependency call goes through: {@code calls.call("profile", () -> profiles.get(id))}.
 * Today it only measures. Slice 3 injects Faults here and slice 4 adds Resilience Policies here (ADR 0005).
 */
@ApplicationScoped
public class DependencyCalls {

    private final CallRecorder recorder;

    @Inject
    public DependencyCalls(CallRecorder recorder) {
        this.recorder = recorder;
    }

    public <T> T call(String callee, Supplier<T> call) {
        return measured(callee, call, result -> Outcome.OK);
    }

    public void run(String callee, Runnable call) {
        call(callee, () -> {
            call.run();
            return null;
        });
    }

    /** For callers that receive raw HTTP responses (edge-gateway): a 5xx is the callee's failure, a 4xx is not. */
    public Response callHttp(String callee, Supplier<Response> call) {
        return measured(callee, call, response -> response.getStatus() >= 500 ? Outcome.ERROR : Outcome.OK);
    }

    /**
     * The single code path every Dependency call takes, so slice 4 wraps it once. The outcome is picked inside
     * the try and recorded in {@code finally}: whatever the call does, it is recorded, and a failing recorder
     * can never fail the call.
     */
    private <T> T measured(String callee, Supplier<T> call, Function<T, Outcome> classify) {
        long start = System.nanoTime();
        Outcome outcome = Outcome.ERROR;
        try {
            T result = call.get();
            outcome = classify.apply(result);
            return result;
        } finally {
            safeRecord(callee, outcome, elapsedMs(start));
        }
    }

    private void safeRecord(String callee, Outcome outcome, long latencyMs) {
        try {
            recorder.recordCall(callee, outcome, latencyMs);
        } catch (RuntimeException ignored) {
            // Observability is never a Critical Dependency: measuring must not break the call being measured.
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
