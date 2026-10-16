package io.github.amadeu01.chaos.viewersimulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ViewerJourneyTest {

    private WireMockServer edge;
    private ViewerJourney journey;

    @BeforeEach
    void start() {
        edge = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        edge.start();
        journey = new ViewerJourney(HttpClient.newHttpClient(), new ObjectMapper(), URI.create(edge.baseUrl()));
        edge.stubFor(post("/play").willReturn(okJson("{\"licenseId\":\"lic-1\"}")));
        edge.stubFor(post("/ratings").willReturn(aResponse().withStatus(202)));
    }

    @AfterEach
    void stop() {
        edge.stop();
    }

    @Test
    void completedJourneyIsAPlaybackStart() {
        edge.stubFor(get(urlPathEqualTo("/home")).willReturn(okJson(
                "{\"titles\":[{\"id\":\"t01\"}],\"recommendedTitleIds\":[\"t09\"]}")));

        assertThat(journey.run("v-1").join()).isTrue();
        edge.verify(postRequestedFor(urlEqualTo("/play")).withRequestBody(matchingJsonPath("$.titleId", equalTo("t09"))));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> edge.verify(postRequestedFor(urlEqualTo("/ratings"))));
    }

    @Test
    void picksFirstTitleWhenThereAreNoRecommendations() {
        edge.stubFor(get(urlPathEqualTo("/home")).willReturn(okJson(
                "{\"titles\":[{\"id\":\"t01\"}],\"recommendedTitleIds\":[]}")));

        assertThat(journey.run("v-1").join()).isTrue();
        edge.verify(postRequestedFor(urlEqualTo("/play")).withRequestBody(matchingJsonPath("$.titleId", equalTo("t01"))));
    }

    @Test
    void failedHomeMeansNoStartAndNoPlay() {
        edge.stubFor(get(urlPathEqualTo("/home")).willReturn(aResponse().withStatus(500)));

        assertThat(journey.run("v-1").join()).isFalse();
        edge.verify(0, postRequestedFor(urlEqualTo("/play")));
    }

    @Test
    void failedPlayMeansNoStart() {
        edge.stubFor(get(urlPathEqualTo("/home")).willReturn(okJson(
                "{\"titles\":[{\"id\":\"t01\"}],\"recommendedTitleIds\":[\"t09\"]}")));
        edge.stubFor(post("/play").willReturn(aResponse().withStatus(503)));

        assertThat(journey.run("v-1").join()).isFalse();
        edge.verify(0, postRequestedFor(urlPathEqualTo("/ratings")));
    }

    @Test
    void malformedHomeMeansNoStart() {
        edge.stubFor(get(urlPathEqualTo("/home")).willReturn(okJson("not json")));

        assertThat(journey.run("v-1").join()).isFalse();
    }

    @Test
    void unreachableEdgeMeansNoStart() {
        edge.stop();

        assertThat(journey.run("v-1").join()).isFalse();
    }

    private static com.github.tomakehurst.wiremock.matching.StringValuePattern equalTo(String value) {
        return com.github.tomakehurst.wiremock.client.WireMock.equalTo(value);
    }
}
