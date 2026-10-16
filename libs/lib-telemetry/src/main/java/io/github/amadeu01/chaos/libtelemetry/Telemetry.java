package io.github.amadeu01.chaos.libtelemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This Service's connection to telemetry: buffers events and flushes them every 250 ms from its own thread.
 * Not from the request pool: when every request thread is stuck on a hung Dependency, the Service must still be
 * able to say so (ADR 0002, ADR 0005).
 */
@ApplicationScoped
public class Telemetry implements CallRecorder {

    private static final Logger LOG = Logger.getLogger(Telemetry.class.getName());

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
    private ScheduledExecutorService flusher;

    @PostConstruct
    void init() {
        buffer = new TelemetryBuffer(service, BUFFER_CAPACITY);
        sender = new HttpBatchSender(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(SEND_TIMEOUT).build(),
                mapper, url.resolve("/events"), SEND_TIMEOUT);
    }

    @Override
    public void recordCall(String callee, Outcome outcome, long latencyMs) {
        if (!enabled) {
            return;
        }
        buffer.recordCall(callee, outcome, latencyMs);
    }

    public void recordStart() {
        if (!enabled) {
            return;
        }
        buffer.recordStart();
    }

    void start(@Observes StartupEvent event) {
        if (!enabled) {
            return;
        }
        flusher = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("telemetry-flush").daemon().factory());
        flusher.scheduleWithFixedDelay(this::flushSafely, 0, 250, TimeUnit.MILLISECONDS);
    }

    void stop(@Observes ShutdownEvent event) {
        if (flusher != null) {
            flusher.shutdownNow();
        }
    }

    /** An uncaught exception would cancel the scheduled task and silence this Service for good. */
    private void flushSafely() {
        try {
            flush();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "telemetry flush failed", e);
        }
    }

    /** Sends buffered events now; the flush thread calls it every 250 ms, tests call it directly. */
    public void flush() {
        if (enabled) {
            buffer.flushTo(sender);
        }
    }
}
