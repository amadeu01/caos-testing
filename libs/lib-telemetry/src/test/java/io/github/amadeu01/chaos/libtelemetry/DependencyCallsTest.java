package io.github.amadeu01.chaos.libtelemetry;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DependencyCallsTest {

    record Recorded(String callee, Outcome outcome, long latencyMs) {
    }

    private final List<Recorded> recorded = new ArrayList<>();
    private final DependencyCalls calls = new DependencyCalls((callee, outcome, latencyMs) ->
            recorded.add(new Recorded(callee, outcome, latencyMs)));

    @Test
    void successfulCallIsRecordedAsOk() {
        String result = calls.call("profile", () -> "Viewer v-1");

        assertThat(result).isEqualTo("Viewer v-1");
        assertThat(recorded).singleElement().satisfies(r -> {
            assertThat(r.callee()).isEqualTo("profile");
            assertThat(r.outcome()).isEqualTo(Outcome.OK);
            assertThat(r.latencyMs()).isNotNegative();
        });
    }

    @Test
    void failedCallIsRecordedAsErrorAndRethrownUnchanged() {
        IllegalStateException failure = new IllegalStateException("recommendations is down");

        assertThatThrownBy(() -> calls.call("recommendations", () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(recorded).singleElement().extracting(Recorded::outcome).isEqualTo(Outcome.ERROR);
    }

    @Test
    void runRecordsLikeCall() {
        calls.run("queue", () -> {
        });

        assertThat(recorded).singleElement().extracting(Recorded::callee).isEqualTo("queue");
    }

    @Test
    void httpServerErrorStatusIsAnError() {
        calls.callHttp("api", () -> Response.status(503).build());

        assertThat(recorded).singleElement().extracting(Recorded::outcome).isEqualTo(Outcome.ERROR);
    }

    @Test
    void httpClientErrorStatusIsNotTheCalleesFault() {
        calls.callHttp("api", () -> Response.status(400).build());

        assertThat(recorded).singleElement().extracting(Recorded::outcome).isEqualTo(Outcome.OK);
    }

    @Test
    void aFailingRecorderNeverFailsTheCall() {
        DependencyCalls broken = new DependencyCalls((callee, outcome, latencyMs) -> {
            throw new IllegalStateException("recorder is broken");
        });

        assertThat(broken.call("profile", () -> "Viewer v-1")).isEqualTo("Viewer v-1");
        assertThat(broken.callHttp("api", () -> Response.ok().build()).getStatus()).isEqualTo(200);
        IllegalArgumentException failure = new IllegalArgumentException("the call's own failure");
        assertThatThrownBy(() -> broken.call("profile", () -> {
            throw failure;
        })).isSameAs(failure);
    }
}
