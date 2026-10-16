package io.github.amadeu01.chaos.telemetry;

import java.util.List;

public record EventBatch(String service, long dropped, List<CallEvent> calls, List<StartEvent> starts) {
}
