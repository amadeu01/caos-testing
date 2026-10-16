package io.github.amadeu01.chaos.libtelemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpBatchSenderTest {

    private WireMockServer telemetry;
    private HttpBatchSender sender;

    @BeforeEach
    void start() {
        telemetry = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        telemetry.start();
        sender = new HttpBatchSender(HttpClient.newHttpClient(), new ObjectMapper(),
                URI.create(telemetry.baseUrl() + "/events"), Duration.ofMillis(300));
    }

    @AfterEach
    void stop() {
        telemetry.stop();
    }

    @Test
    void postsTheBatchAsJson() {
        telemetry.stubFor(post("/events").willReturn(aResponse().withStatus(202)));

        sender.send(new EventBatch("api", 2,
                List.of(new CallEvent(1000, "api", "profile", "none", Outcome.OK, 3)),
                List.of(new StartEvent(1000, "none")))).join();

        telemetry.verify(postRequestedFor(urlEqualTo("/events"))
                .withHeader("Content-Type", com.github.tomakehurst.wiremock.client.WireMock.equalTo("application/json"))
                .withRequestBody(equalToJson(EventBatchJsonTest.WIRE_FORMAT)));
    }

    @Test
    void errorStatusFailsTheSend() {
        telemetry.stubFor(post("/events").willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> sender.send(new EventBatch("api", 0, List.of(), List.of())).join())
                .isInstanceOf(CompletionException.class);
    }

    @Test
    void stalledResponseBodyStillEndsTheSend() {
        telemetry.stubFor(post("/events").willReturn(aResponse().withStatus(202).withBody("accepted, thank you")
                .withChunkedDribbleDelay(5, 3000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> sender.send(new EventBatch("api", 0, List.of(), List.of())).join())
                .isInstanceOf(CompletionException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void slowTelemetryTimesOutInsteadOfHanging() {
        telemetry.stubFor(post("/events").willReturn(aResponse().withStatus(202).withFixedDelay(2000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> sender.send(new EventBatch("api", 0, List.of(), List.of())).join())
                .isInstanceOf(CompletionException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }
}
