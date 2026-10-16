package io.github.amadeu01.chaos.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatorTest {

    private final AtomicLong now = new AtomicLong(1_000_500_000L);
    private final Aggregator aggregator = new Aggregator(now::get);

    private long settledSecondMs() {
        return Aggregator.lastSettledSecond(now.get()) * 1000;
    }

    private static CallEvent call(long timestamp, String caller, String callee, String outcome, long latencyMs) {
        return new CallEvent(timestamp, caller, callee, "none", outcome, latencyMs);
    }

    @Test
    void latestWindowSummarisesTheLastSettledSecond() {
        long t = settledSecondMs() + 100;
        aggregator.ingest(new EventBatch("api", 0, List.of(
                call(t, "api", "profile", "OK", 10),
                call(t, "api", "profile", "OK", 20),
                call(t, "api", "profile", "ERROR", 30)), List.of()));

        Snapshot snapshot = aggregator.latest();

        assertThat(snapshot.timestamp()).isEqualTo(settledSecondMs());
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
    void batchWithoutServiceIsIgnoredAndDoesNotBreakTheLatestWindow() {
        long t = settledSecondMs() + 100;
        aggregator.ingest(new EventBatch(null, 0, List.of(call(t, "ghost", "phantom", "OK", 1)), List.of()));
        aggregator.ingest(new EventBatch("  ", 0, List.of(call(t, "ghost", "phantom", "OK", 1)), List.of()));
        aggregator.ingest(new EventBatch("api", 0, List.of(call(t, "api", "profile", "OK", 1)), List.of()));

        Snapshot snapshot = aggregator.latest();

        assertThat(snapshot.dependencies()).extracting(DependencyView::caller).containsExactly("api");
        assertThat(snapshot.services()).extracting(ServiceHealth::name).containsExactly("api", "profile");
    }

    @Test
    void invalidEventsAreSkippedAndValidOnesKept() {
        long t = settledSecondMs() + 100;
        java.util.ArrayList<CallEvent> calls = new java.util.ArrayList<>();
        calls.add(call(t, "api", null, "OK", 1));
        calls.add(call(t, "api", "profile", null, 1));
        calls.add(null);
        calls.add(call(t, "api", "profile", "OK", 5));
        java.util.ArrayList<StartEvent> starts = new java.util.ArrayList<>();
        starts.add(null);
        aggregator.ingest(new EventBatch("api", 0, calls, starts));

        assertThat(aggregator.latest().dependencies()).singleElement().satisfies(d -> {
            assertThat(d.callsPerSecond()).isEqualTo(1);
            assertThat(d.p99Ms()).isEqualTo(5);
        });
    }

    @Test
    void countsPlaybackStartsPerGroup() {
        long t = settledSecondMs() + 100;
        aggregator.ingest(new EventBatch("viewer-simulator", 0, List.of(), List.of(
                new StartEvent(t, "none"), new StartEvent(t, "control"), new StartEvent(t, "experiment"),
                new StartEvent(t, "experiment"))));

        assertThat(aggregator.latest().sps()).isEqualTo(new Sps(4, 1, 2));
    }

    @Test
    void eventsOlderThanTheRingAreIgnored() {
        long t = settledSecondMs() + 100;
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
        long t = settledSecondMs() + 100;
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
        long t = settledSecondMs() + 100;
        aggregator.ingest(new EventBatch("viewer-simulator", 0, List.of(), List.of(new StartEvent(t, "none"))));

        List<SpsPoint> series = aggregator.sps("total", settledSecondMs() - 2_000);

        assertThat(series).extracting(SpsPoint::sps).containsExactly(0, 0, 1);
        assertThat(series).extracting(SpsPoint::timestamp).last().isEqualTo(settledSecondMs());
    }

    @Test
    void latestWaitsUntilASecondHasSettled() {
        long stillFilling = (now.get() / 1000 - 1) * 1000 + 100;
        long settled = settledSecondMs() + 100;
        aggregator.ingest(new EventBatch("api", 0, List.of(
                call(stillFilling, "api", "profile", "OK", 1),
                call(settled, "api", "profile", "OK", 1)), List.of()));

        assertThat(aggregator.latest().dependencies()).singleElement()
                .extracting(DependencyView::callsPerSecond).isEqualTo(1);

        now.addAndGet(1_000);

        assertThat(aggregator.latest().dependencies()).singleElement()
                .extracting(DependencyView::callsPerSecond).isEqualTo(1);
        assertThat(aggregator.latest().timestamp()).isEqualTo(stillFilling / 1000 * 1000);
    }

    @Test
    void spsSeriesEndsAtTheLastSettledSecond() {
        List<SpsPoint> series = aggregator.sps("total", settledSecondMs() - 2_000);

        assertThat(series).extracting(SpsPoint::timestamp)
                .containsExactly(settledSecondMs() - 2_000, settledSecondMs() - 1_000, settledSecondMs());
    }

    @Test
    void windowsReturnsOneSnapshotPerSettledSecond() {
        long last = settledSecondMs();
        aggregator.ingest(new EventBatch("api", 0, List.of(call(last - 1_000 + 100, "api", "profile", "OK", 5)),
                List.of(new StartEvent(last + 100, "none"))));

        List<Snapshot> windows = aggregator.windows(last - 2_000);

        assertThat(windows).extracting(Snapshot::timestamp).containsExactly(last - 2_000, last - 1_000, last);
        assertThat(windows.get(1).dependencies()).singleElement().satisfies(d -> assertThat(d.callee()).isEqualTo("profile"));
        assertThat(windows.get(2).sps().total()).isEqualTo(1);
        assertThat(windows.get(2)).isEqualTo(aggregator.latest());
    }

    @Test
    void windowsIsClampedToTheRing() {
        List<Snapshot> windows = aggregator.windows(0);

        assertThat(windows).hasSize(Aggregator.WINDOWS_KEPT);
        assertThat(windows.get(windows.size() - 1).timestamp()).isEqualTo(settledSecondMs());
    }

    @Test
    void windowsSecondWithoutDataIsEmpty() {
        Snapshot only = aggregator.windows(settledSecondMs()).get(0);

        assertThat(only.sps().total()).isZero();
        assertThat(only.dependencies()).isEmpty();
    }
}
