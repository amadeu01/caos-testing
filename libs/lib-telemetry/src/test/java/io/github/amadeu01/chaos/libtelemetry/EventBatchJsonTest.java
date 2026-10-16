package io.github.amadeu01.chaos.libtelemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the wire format; control-plane/telemetry pins the same literal on the receiving side. */
class EventBatchJsonTest {

    static final String WIRE_FORMAT = "{\"service\":\"api\",\"dropped\":2,\"calls\":[{\"timestamp\":1000,\"caller\":\"api\","
            + "\"callee\":\"profile\",\"group\":\"none\",\"outcome\":\"OK\",\"latencyMs\":3}],"
            + "\"starts\":[{\"timestamp\":1000,\"group\":\"none\"}]}";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serialisesToTheWireFormat() throws Exception {
        EventBatch batch = new EventBatch("api", 2,
                List.of(new CallEvent(1000, "api", "profile", "none", Outcome.OK, 3)),
                List.of(new StartEvent(1000, "none")));

        assertThat(mapper.readTree(mapper.writeValueAsString(batch))).isEqualTo(mapper.readTree(WIRE_FORMAT));
    }
}
