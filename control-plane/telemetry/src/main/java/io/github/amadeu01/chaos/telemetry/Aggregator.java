package io.github.amadeu01.chaos.telemetry;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/** Ring of one-second windows (10 minutes) plus when each Service last reported. In memory only. */
@ApplicationScoped
public class Aggregator {

    static final int WINDOWS_KEPT = 600;

    /** Batches arrive every 250 ms, one in flight at a time; a second is complete once this much time has passed after it ends. */
    static final long SETTLE_MS = 1_000;

    private final LongSupplier clock;
    private final Window[] windows = new Window[WINDOWS_KEPT];
    private final Map<String, Long> lastSeen = new HashMap<>();

    public Aggregator() {
        this(System::currentTimeMillis);
    }

    Aggregator(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized void ingest(EventBatch batch) {
        if (isBlank(batch.service())) {
            return;
        }
        long now = clock.getAsLong();
        lastSeen.put(batch.service(), now);
        if (batch.calls() != null) {
            for (CallEvent call : batch.calls()) {
                if (call == null || isBlank(call.caller()) || isBlank(call.callee()) || isBlank(call.outcome())) {
                    continue;
                }
                Window window = windowFor(call.timestamp(), now);
                if (window != null) {
                    window.recordCall(call);
                }
            }
        }
        if (batch.starts() != null) {
            for (StartEvent start : batch.starts()) {
                if (start == null) {
                    continue;
                }
                Window window = windowFor(start.timestamp(), now);
                if (window != null) {
                    window.recordStart(start.group());
                }
            }
        }
    }

    static long lastSettledSecond(long nowMs) {
        return (nowMs - SETTLE_MS) / 1000 - 1;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public synchronized Snapshot latest() {
        long now = clock.getAsLong();
        return snapshot(lastSettledSecond(now), now);
    }

    /** One snapshot per settled second from {@code fromMs}, clamped to the ring, oldest first. */
    public synchronized List<Snapshot> windows(long fromMs) {
        long now = clock.getAsLong();
        long last = lastSettledSecond(now);
        long first = Math.max(fromMs / 1000, last - WINDOWS_KEPT + 1);
        List<Snapshot> history = new ArrayList<>();
        for (long second = first; second <= last; second++) {
            history.add(snapshot(second, now));
        }
        return history;
    }

    private Snapshot snapshot(long second, long now) {
        Window window = existing(second);
        List<DependencyView> dependencies = window == null ? List.of() : window.dependencyViews();
        TreeSet<String> names = new TreeSet<>(lastSeen.keySet());
        dependencies.forEach(d -> {
            names.add(d.caller());
            names.add(d.callee());
        });
        List<ServiceHealth> services = new ArrayList<>();
        for (String name : names) {
            List<DependencyView> incoming = dependencies.stream().filter(d -> d.callee().equals(name)).toList();
            services.add(new ServiceHealth(name, HealthRule.forService(incoming, lastSeen.get(name), now)));
        }
        return new Snapshot(second * 1000, window == null ? new Sps(0, 0, 0) : window.sps(), dependencies, services);
    }

    /** @param group total, control or experiment */
    public synchronized List<SpsPoint> sps(String group, long fromMs) {
        if (!List.of("total", "control", "experiment").contains(group)) {
            throw new IllegalArgumentException("group must be total, control or experiment");
        }
        long last = lastSettledSecond(clock.getAsLong());
        long first = Math.max(fromMs / 1000, last - WINDOWS_KEPT + 1);
        List<SpsPoint> series = new ArrayList<>();
        for (long second = first; second <= last; second++) {
            Window window = existing(second);
            Sps sps = window == null ? new Sps(0, 0, 0) : window.sps();
            int value = switch (group) {
                case "control" -> sps.control();
                case "experiment" -> sps.experiment();
                default -> sps.total();
            };
            series.add(new SpsPoint(second * 1000, value));
        }
        return series;
    }

    /** The window for an event's second, or null when the event is outside the ring or in the future. */
    private Window windowFor(long timestampMs, long nowMs) {
        long second = timestampMs / 1000;
        long current = nowMs / 1000;
        if (second > current || second <= current - WINDOWS_KEPT) {
            return null;
        }
        int slot = (int) (second % WINDOWS_KEPT);
        if (windows[slot] == null || windows[slot].second != second) {
            windows[slot] = new Window(second);
        }
        return windows[slot];
    }

    private Window existing(long second) {
        Window window = windows[(int) (second % WINDOWS_KEPT)];
        return window != null && window.second == second ? window : null;
    }
}
