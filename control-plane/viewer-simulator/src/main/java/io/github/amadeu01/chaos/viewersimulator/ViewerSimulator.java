package io.github.amadeu01.chaos.viewersimulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Launches Viewers at a steady rate and logs SPS once per second. */
@ApplicationScoped
public class ViewerSimulator {

    static final int MAX_VIEWERS_PER_SECOND = 500;
    static final int TICKS_PER_SECOND = 10;
    private static final Logger LOG = Logger.getLogger(ViewerSimulator.class);

    @ConfigProperty(name = "simulator.target-url")
    URI targetUrl;

    @ConfigProperty(name = "simulator.enabled")
    boolean enabled;

    @Inject
    ObjectMapper mapper;

    @Inject
    io.github.amadeu01.chaos.libtelemetry.Telemetry telemetry;

    private final SpsCounter sps = new SpsCounter();
    private ScheduledExecutorService ticker;
    private ViewerJourney journey;
    private volatile int viewersPerSecond;
    private double viewerBudget;

    @Inject
    ViewerSimulator(@ConfigProperty(name = "simulator.viewers-per-second") int initialViewersPerSecond) {
        this.viewersPerSecond = initialViewersPerSecond;
    }

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            return;
        }
        journey = new ViewerJourney(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), mapper, targetUrl, telemetry);
        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(this::launchViewers, 0, 1000 / TICKS_PER_SECOND, TimeUnit.MILLISECONDS);
        LOG.infof("Simulating %d Viewers/s against %s", viewersPerSecond, targetUrl);
    }

    void onStop(@Observes ShutdownEvent event) {
        if (ticker != null) {
            ticker.shutdownNow();
        }
    }

    private void launchViewers() {
        try {
            viewerBudget += viewersPerSecond / (double) TICKS_PER_SECOND;
            int launches = (int) viewerBudget;
            viewerBudget -= launches;
            for (int i = 0; i < launches; i++) {
                String viewerId = "v-" + ThreadLocalRandom.current().nextInt(10_000);
                journey.run(viewerId).thenAccept(started -> {
                    if (started) {
                        sps.recordStart(System.currentTimeMillis());
                        telemetry.recordStart();
                    }
                });
            }
        } catch (RuntimeException e) {
            // An uncaught exception would silently cancel the fixed-rate task and SPS would drop to zero.
            LOG.error("Viewer launch failed", e);
        }
    }

    @Scheduled(every = "1s")
    void reportSps() {
        if (enabled) {
            long lastSecond = System.currentTimeMillis() / 1000 - 1;
            LOG.infof("SPS total=%d viewersPerSecond=%d", sps.startsIn(lastSecond), viewersPerSecond);
        }
    }

    public int viewersPerSecond() {
        return viewersPerSecond;
    }

    public void setViewersPerSecond(int value) {
        if (value < 0 || value > MAX_VIEWERS_PER_SECOND) {
            throw new IllegalArgumentException("Viewers/s must be between 0 and " + MAX_VIEWERS_PER_SECOND);
        }
        viewersPerSecond = value;
    }
}
