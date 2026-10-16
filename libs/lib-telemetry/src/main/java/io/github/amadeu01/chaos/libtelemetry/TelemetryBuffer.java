package io.github.amadeu01.chaos.libtelemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Bounded buffer between a Service and telemetry. Recording never blocks: a full buffer drops the event
 * and counts it, so observability can never become a Critical Dependency.
 */
public final class TelemetryBuffer {

    private final String service;
    private final LongSupplier clock;
    private final BlockingQueue<CallEvent> calls;
    private final BlockingQueue<StartEvent> starts;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean inFlight = new AtomicBoolean();

    public TelemetryBuffer(String service, int capacity) {
        this(service, capacity, System::currentTimeMillis);
    }

    TelemetryBuffer(String service, int capacity, LongSupplier clock) {
        this.service = service;
        this.clock = clock;
        this.calls = new ArrayBlockingQueue<>(capacity);
        this.starts = new ArrayBlockingQueue<>(capacity);
    }

    public void recordCall(String callee, Outcome outcome, long latencyMs) {
        if (!calls.offer(new CallEvent(clock.getAsLong(), service, callee, CallEvent.NO_GROUP, outcome, latencyMs))) {
            dropped.incrementAndGet();
        }
    }

    public void recordStart() {
        if (!starts.offer(new StartEvent(clock.getAsLong(), CallEvent.NO_GROUP))) {
            dropped.incrementAndGet();
        }
    }

    EventBatch drain() {
        List<CallEvent> drainedCalls = new ArrayList<>();
        List<StartEvent> drainedStarts = new ArrayList<>();
        calls.drainTo(drainedCalls);
        starts.drainTo(drainedStarts);
        return new EventBatch(service, dropped.getAndSet(0), drainedCalls, drainedStarts);
    }

    /**
     * Sends everything recorded so far, one batch at a time: while a send is in flight nothing is drained and
     * events stay buffered (bounded by capacity). An undelivered batch is added to the next batch's drop count.
     */
    public void flushTo(BatchSender sender) {
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }
        EventBatch batch = drain();
        CompletableFuture<Void> sent;
        try {
            sent = sender.send(batch);
            if (sent == null) {
                sent = CompletableFuture.failedFuture(new IllegalStateException("sender returned no future"));
            }
        } catch (Throwable e) {
            sent = CompletableFuture.failedFuture(e);
        }
        sent.whenComplete((ignored, failure) -> {
            if (failure != null) {
                dropped.addAndGet(batch.eventCount() + batch.dropped());
            }
            inFlight.set(false);
        });
    }
}
