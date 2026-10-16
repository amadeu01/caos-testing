package io.github.amadeu01.chaos.dashboard;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** Every 500 ms, sends the latest telemetry window to every open dashboard. */
@ApplicationScoped
public class LivePublisher {

    static final String UNAVAILABLE = "{\"type\":\"telemetry-unavailable\"}";
    private static final Logger LOG = Logger.getLogger(LivePublisher.class);

    @Inject
    @RestClient
    TelemetryClient telemetry;

    @Inject
    OpenConnections connections;

    @Scheduled(every = "0.5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void publish() {
        if (connections.listAll().isEmpty()) {
            return;
        }
        String message = message();
        for (WebSocketConnection connection : connections.listAll()) {
            try {
                connection.sendTextAndAwait(message);
            } catch (RuntimeException e) {
                LOG.debugf("Could not push to dashboard connection %s: %s", connection.id(), e.getMessage());
            }
        }
    }

    String message() {
        try {
            return "{\"type\":\"window\",\"window\":" + telemetry.latest() + "}";
        } catch (RuntimeException e) {
            LOG.debugf("telemetry unavailable: %s", e.getMessage());
            return UNAVAILABLE;
        }
    }
}
