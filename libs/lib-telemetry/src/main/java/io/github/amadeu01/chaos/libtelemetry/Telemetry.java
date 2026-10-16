package io.github.amadeu01.chaos.libtelemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** This Service's connection to telemetry: buffers events and flushes them every 250 ms. */
@ApplicationScoped
public class Telemetry implements CallRecorder {

    static final int BUFFER_CAPACITY = 10_000;
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(1);

    @ConfigProperty(name = "quarkus.application.name")
    String service;

    @ConfigProperty(name = "telemetry.url", defaultValue = "http://telemetry:8080")
    URI url;

    @ConfigProperty(name = "telemetry.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    ObjectMapper mapper;

    private TelemetryBuffer buffer;
    private BatchSender sender;

    @PostConstruct
    void init() {
        buffer = new TelemetryBuffer(service, BUFFER_CAPACITY);
        sender = new HttpBatchSender(HttpClient.newBuilder().connectTimeout(SEND_TIMEOUT).build(),
                mapper, url.resolve("/events"), SEND_TIMEOUT);
    }

    @Override
    public void recordCall(String callee, Outcome outcome, long latencyMs) {
        buffer.recordCall(callee, outcome, latencyMs);
    }

    public void recordStart() {
        buffer.recordStart();
    }

    /** Sends buffered events now; the scheduler calls it every 250 ms, tests without a scheduler call it directly. */
    @Scheduled(every = "0.25s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void flush() {
        if (enabled) {
            buffer.flushTo(sender);
        }
    }
}
