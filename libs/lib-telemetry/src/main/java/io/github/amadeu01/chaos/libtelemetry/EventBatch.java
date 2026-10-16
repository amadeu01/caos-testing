package io.github.amadeu01.chaos.libtelemetry;

import java.util.List;

/** What a Service sends to telemetry every 250 ms; an empty batch is a heartbeat. */
public record EventBatch(String service, long dropped, List<CallEvent> calls, List<StartEvent> starts) {

    int eventCount() {
        return calls.size() + starts.size();
    }
}
