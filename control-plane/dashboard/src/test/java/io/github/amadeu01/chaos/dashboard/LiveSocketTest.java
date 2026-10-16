package io.github.amadeu01.chaos.dashboard;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@WithTestResource(value = WireMockResource.class, initArgs = @ResourceArg(name = WireMockResource.URL_PROPERTIES,
        value = "quarkus.rest-client.telemetry.url,quarkus.rest-client.viewer-simulator.url"))
class LiveSocketTest {

    private static final String SNAPSHOT =
            "{\"timestamp\":1000,\"sps\":{\"total\":42,\"control\":0,\"experiment\":0},\"dependencies\":[],\"services\":[]}";

    @InjectWireMock
    WireMockServer wireMock;

    @TestHTTPResource("/live")
    URI live;

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void pushesTheLatestTelemetryWindow() throws Exception {
        wireMock.stubFor(get("/windows/latest").willReturn(okJson(SNAPSHOT)));

        String message = firstMessageMatching(m -> m.contains("\"type\":\"window\""));

        assertThat(message).contains("\"total\":42");
    }

    @Test
    void saysSoWhenTelemetryIsUnavailable() throws Exception {
        wireMock.stubFor(get("/windows/latest").willReturn(aResponse().withStatus(503)));

        assertThat(firstMessageMatching(m -> m.contains("telemetry-unavailable")))
                .isEqualTo(LivePublisher.UNAVAILABLE);
    }

    private String firstMessageMatching(Predicate<String> wanted) throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        URI ws = URI.create(live.toString().replaceFirst("^http", "ws"));
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(ws, new WebSocket.Listener() {
                    private final StringBuilder partial = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            messages.add(partial.toString());
                            partial.setLength(0);
                        }
                        webSocket.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                String message = messages.poll(1, TimeUnit.SECONDS);
                if (message != null && wanted.test(message)) {
                    return message;
                }
            }
            throw new AssertionError("No matching message within 5 s");
        } finally {
            socket.abort();
        }
    }
}
