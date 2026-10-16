package io.github.amadeu01.chaos.libtelemetry;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryBufferTest {

    private final TelemetryBuffer buffer = new TelemetryBuffer("api", 2, () -> 1000L);

    @Test
    void recordsCallsAsTheConfiguredCaller() {
        buffer.recordCall("profile", Outcome.OK, 3);

        EventBatch batch = buffer.drain();
        assertThat(batch.service()).isEqualTo("api");
        assertThat(batch.calls()).containsExactly(new CallEvent(1000, "api", "profile", "none", Outcome.OK, 3));
    }

    @Test
    void fullBufferDropsInsteadOfBlocking() {
        buffer.recordCall("profile", Outcome.OK, 1);
        buffer.recordCall("profile", Outcome.OK, 1);
        buffer.recordCall("profile", Outcome.OK, 1);

        EventBatch batch = buffer.drain();
        assertThat(batch.calls()).hasSize(2);
        assertThat(batch.dropped()).isEqualTo(1);
    }

    @Test
    void drainResetsTheDropCount() {
        buffer.recordStart();
        buffer.recordStart();
        buffer.recordStart();
        buffer.drain();

        assertThat(buffer.drain().dropped()).isZero();
    }

    @Test
    void emptyBufferStillProducesABatchAsAHeartbeat() {
        AtomicReference<EventBatch> sent = new AtomicReference<>();
        buffer.flushTo(batch -> {
            sent.set(batch);
            return CompletableFuture.completedFuture(null);
        });

        assertThat(sent.get()).isNotNull();
        assertThat(sent.get().calls()).isEmpty();
    }

    @Test
    void failedSendCountsTheBatchAsDropped() {
        buffer.recordCall("profile", Outcome.OK, 1);
        buffer.recordStart();
        buffer.flushTo(batch -> CompletableFuture.failedFuture(new IllegalStateException("telemetry down")));

        assertThat(buffer.drain().dropped()).isEqualTo(2);
    }

    @Test
    void senderThatThrowsIsTreatedAsAFailedSend() {
        buffer.recordCall("profile", Outcome.OK, 1);
        buffer.flushTo(batch -> {
            throw new IllegalStateException("cannot even start");
        });

        assertThat(buffer.drain().dropped()).isEqualTo(1);
    }

    @Test
    void dropsCarriedByAFailedBatchAreNotLost() {
        buffer.recordStart();
        buffer.recordStart();
        buffer.recordStart();
        buffer.flushTo(batch -> CompletableFuture.failedFuture(new IllegalStateException("down")));

        assertThat(buffer.drain().dropped()).isEqualTo(3);
    }
}
