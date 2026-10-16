package io.github.amadeu01.chaos.telemetry;

import java.util.List;

public record Snapshot(long timestamp, Sps sps, List<DependencyView> dependencies, List<ServiceHealth> services) {
}
