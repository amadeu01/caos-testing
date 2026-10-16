package io.github.amadeu01.chaos.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatorTest {

    private final AtomicLong now = new AtomicLong(1_000_500_000L);
    private final Aggregator aggregator = new Aggregator(now::get);

    private long lastCompleteSecondMs() {
        return (now.get() / 1000 - 1) * 1000;
    }

    private static CallEvent call(long timestamp, String caller, String callee, String outcome, long latencyMs) {
        return new CallEvent(timestamp, caller, callee, "none", outcome, latencyMs);
    }

    @Test
    void latestWindowSummarisesTheLastCompleteSecond() {
        long t = lastCompleteSecondMs() + 100;
        aggregator.ingest(new EventBatch("api", 0, List.of(
                call(t, "api", "profile", "OK", 10),
                call(t, "api", "profile", "OK", 20),
                call(t, "api", "profile", "ERROR", 30)), List.of()));

        Snapshot snapshot = aggregator.latest();

        assertThat(snapshot.timestamp()).isEqualTo(lastCompleteSecondMs());
        assertThat(snapshot.dependencies()).singleElement().satisfies(d -> {
            assertThat(d.caller()).isEqualTo("api");
            assertThat(d.callee()).isEqualTo("profile");
            assertThat(d.callsPerSecond()).isEqualTo(3);
            assertThat(d.errorPercent()).isEqualTo(100.0 / 3);
            assertThat(d.p50Ms()).isEqualTo(20);
            assertThat(d.p99Ms()).isEqualTo(30);
            assertThat(d.outcomes()).containsEntry("OK", 2).containsEntry("ERROR", 1);
            assertThat(d.health()).isEqualTo(Health.AMBER);
        });
    }

    @Test
    void countsPlaybackStartsPerGroup() {
        long t = lastCompleteSecondMs() + 100;
        aggregator.ingest(new EventBatch("viewer-simulator", 0, List.of(), List.of(
                new StartEvent(t, "none"), new StartEvent(t, "control"), new StartEvent(t, "experiment"),
                new StartEvent(t, "experiment"))));

        assertThat(aggregator.latest().sps()).isEqualTo(new Sps(4, 1, 2));
    }

    @Test
    void eventsOlderThanTheRingAreIgnored() {
        long t = lastCompleteSecondMs() + 100;
        aggregator.ingest(new EventBatch("api", 0, List.of(call(t, "api", "profile", "OK", 1)), List.of()));
        long sameSlotTenMinutesEarlier = t - Aggregator.WINDOWS_KEPT * 1000L;
        aggregator.ingest(new EventBatch("api", 0, List.of(call(sameSlotTenMinutesEarlier, "api", "profile", "OK", 1)), List.of()));

        assertThat(aggregator.latest().dependencies()).singleElement()
                .extracting(DependencyView::callsPerSecond).isEqualTo(1);
    }

    @Test
    void eventsFromTheFutureAreIgnored() {
        aggregator.ingest(new EventBatch("api", 0, List.of(call(now.get() + 60_000, "api", "profile", "OK", 1)), List.of()));
        now.addAndGet(61_000);

        assertThat(aggregator.latest().dependencies()).isEmpty();
    }

    @Test
    void servicesIncludeReportersCallersAndCallees() {
        long t = lastCompleteSecondMs() + 100;
        aggregator.ingest(new EventBatch("catalog", 0, List.of(call(t, "catalog", "store", "OK", 1)), List.of()));
        aggregator.ingest(new EventBatch("license", 0, List.of(), List.of()));

        assertThat(aggregator.latest().services()).extracting(ServiceHealth::name)
                .containsExactly("catalog", "license", "store");
    }

    @Test
    void serviceThatStopsReportingTurnsRed() {
        aggregator.ingest(new EventBatch("license", 0, List.of(), List.of()));
        now.addAndGet(3_500);

        assertThat(aggregator.latest().services()).singleElement()
                .extracting(ServiceHealth::health).isEqualTo(Health.RED);
    }

    @Test
    void spsSeriesHasOnePointPerSecond() {
        long t = lastCompleteSecondMs() + 100;
        aggregator.ingest(new EventBatch("viewer-simulator", 0, List.of(), List.of(new StartEvent(t, "none"))));

        List<SpsPoint> series = aggregator.sps("total", lastCompleteSecondMs() - 2_000);

        assertThat(series).extracting(SpsPoint::sps).containsExactly(0, 0, 1);
        assertThat(series).extracting(SpsPoint::timestamp).last().isEqualTo(lastCompleteSecondMs());
    }
}
