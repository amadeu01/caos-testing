package io.github.amadeu01.chaos.libtelemetry;

import java.util.concurrent.CompletableFuture;

/** Sends a batch without blocking the caller; a failed future means the batch was not delivered. */
public interface BatchSender {

    CompletableFuture<Void> send(EventBatch batch);
}
