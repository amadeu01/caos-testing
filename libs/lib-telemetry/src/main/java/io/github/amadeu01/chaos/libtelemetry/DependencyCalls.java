package io.github.amadeu01.chaos.libtelemetry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

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
        long start = System.nanoTime();
        try {
            T result = call.get();
            recorder.recordCall(callee, Outcome.OK, elapsedMs(start));
            return result;
        } catch (RuntimeException e) {
            recorder.recordCall(callee, Outcome.ERROR, elapsedMs(start));
            throw e;
        }
    }

    public void run(String callee, Runnable call) {
        call(callee, () -> {
            call.run();
            return null;
        });
    }

    /** For callers that receive raw HTTP responses (edge-gateway): a 5xx is the callee's failure, a 4xx is not. */
    public Response callHttp(String callee, Supplier<Response> call) {
        long start = System.nanoTime();
        try {
            Response response = call.get();
            recorder.recordCall(callee, response.getStatus() >= 500 ? Outcome.ERROR : Outcome.OK, elapsedMs(start));
            return response;
        } catch (RuntimeException e) {
            recorder.recordCall(callee, Outcome.ERROR, elapsedMs(start));
            throw e;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
