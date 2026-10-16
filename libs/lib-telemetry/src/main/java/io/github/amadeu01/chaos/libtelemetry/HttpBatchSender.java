package io.github.amadeu01.chaos.libtelemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** POSTs a batch to telemetry asynchronously, with a short timeout so a slow telemetry never piles up requests. */
final class HttpBatchSender implements BatchSender {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;

    HttpBatchSender(HttpClient http, ObjectMapper mapper, URI endpoint, Duration timeout) {
        this.http = http;
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    @Override
    public CompletableFuture<Void> send(EventBatch batch) {
        String json;
        try {
            json = mapper.writeValueAsString(batch);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenAccept(response -> {
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("telemetry answered " + response.statusCode());
            }
        });
    }
}
