package io.github.amadeu01.chaos.libtelemetry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
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
    void noNewSendWhileOneIsInFlight() {
        AtomicInteger sends = new AtomicInteger();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        List<EventBatch> batches = new ArrayList<>();
        BatchSender sender = batch -> {
            sends.incrementAndGet();
            batches.add(batch);
            CompletableFuture<Void> f = new CompletableFuture<>();
            futures.add(f);
            return f;
        };

        buffer.recordCall("profile", Outcome.OK, 1);
        buffer.flushTo(sender);
        buffer.flushTo(sender);
        assertThat(sends).hasValue(1);

        futures.get(0).complete(null);
        buffer.recordCall("catalog", Outcome.OK, 2);
        buffer.flushTo(sender);

        assertThat(sends).hasValue(2);
        assertThat(batches.get(1).calls()).extracting(CallEvent::callee).containsExactly("catalog");
    }

    @Test
    void eventsWaitInTheBufferWhileASendIsInFlight() {
        CompletableFuture<Void> first = new CompletableFuture<>();
        List<EventBatch> batches = new ArrayList<>();
        buffer.flushTo(batch -> first);

        buffer.recordCall("profile", Outcome.OK, 1);
        buffer.flushTo(batch -> {
            batches.add(batch);
            return CompletableFuture.completedFuture(null);
        });
        assertThat(batches).isEmpty();

        first.complete(null);
        buffer.flushTo(batch -> {
            batches.add(batch);
            return CompletableFuture.completedFuture(null);
        });

        assertThat(batches).singleElement().satisfies(b -> {
            assertThat(b.calls()).hasSize(1);
            assertThat(b.dropped()).isZero();
        });
    }

    @Test
    void senderReturningNullIsAFailedSend() {
        buffer.recordCall("profile", Outcome.OK, 1);
        buffer.flushTo(batch -> null);

        AtomicReference<EventBatch> sent = new AtomicReference<>();
        buffer.flushTo(batch -> {
            sent.set(batch);
            return CompletableFuture.completedFuture(null);
        });

        assertThat(sent.get()).isNotNull();
        assertThat(sent.get().dropped()).isEqualTo(1);
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
