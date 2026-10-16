package io.github.amadeu01.chaos.telemetry;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Everything telemetry knows about one wall-clock second. */
final class Window {

    final long second;
    private final Map<String, DependencyStats> dependencies = new TreeMap<>();
    private int startsTotal;
    private int startsControl;
    private int startsExperiment;

    Window(long second) {
        this.second = second;
    }

    void recordCall(CallEvent event) {
        dependencies.computeIfAbsent(event.caller() + "->" + event.callee(),
                key -> new DependencyStats(event.caller(), event.callee())).record(event.outcome(), event.latencyMs());
    }

    void recordStart(String group) {
        startsTotal++;
        if ("control".equals(group)) {
            startsControl++;
        } else if ("experiment".equals(group)) {
            startsExperiment++;
        }
    }

    Sps sps() {
        return new Sps(startsTotal, startsControl, startsExperiment);
    }

    List<DependencyView> dependencyViews() {
        return dependencies.values().stream().map(DependencyStats::view).toList();
    }
}
