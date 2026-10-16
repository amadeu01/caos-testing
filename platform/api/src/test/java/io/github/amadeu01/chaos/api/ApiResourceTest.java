package io.github.amadeu01.chaos.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.amadeu01.chaos.testsupport.ElasticMqResource;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.awaitility.Awaitility.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@WithTestResource(ElasticMqResource.class)
@WithTestResource(value = WireMockResource.class, initArgs = @ResourceArg(name = WireMockResource.URL_PROPERTIES,
        value = "quarkus.rest-client.profile.url,quarkus.rest-client.catalog.url,"
                + "quarkus.rest-client.recommendations.url,quarkus.rest-client.playback.url,telemetry.url"))
class ApiResourceTest {

    @InjectWireMock
    WireMockServer wireMock;

    @Inject
    SqsClient sqs;

    @BeforeEach
    void stubHealthyDependencies() {
        wireMock.resetAll();
        wireMock.stubFor(get("/profiles/v-1").willReturn(okJson(
                "{\"viewerId\":\"v-1\",\"displayName\":\"Viewer v-1\",\"maturityRating\":\"ADULT\"}")));
        wireMock.stubFor(get("/titles").willReturn(okJson(titlesJson(25))));
        wireMock.stubFor(get("/recommendations/v-1").willReturn(okJson(
                "{\"viewerId\":\"v-1\",\"titleIds\":[\"t03\",\"t09\"]}")));
        wireMock.stubFor(post("/playbacks").willReturn(okJson(
                "{\"streamUrl\":\"https://stream.example/titles/t03?license=lic-1\",\"licenseId\":\"lic-1\"}")));
    }

    @Test
    void homeAggregatesProfileCatalogAndRecommendations() {
        given().when().get("/home?viewerId=v-1")
                .then().statusCode(200)
                .body("profile.viewerId", equalTo("v-1"))
                .body("titles", hasSize(HomeResource.TITLES_ON_HOME))
                .body("recommendedTitleIds", contains("t03", "t09"));
    }

    @Test
    void homeWithoutViewerIsBadRequestAndCallsNothing() {
        given().queryParam("viewerId", " ").when().get("/home").then().statusCode(400);
        given().when().get("/home").then().statusCode(400);
        wireMock.verify(0, anyRequestedFor(urlPathMatching("/(profiles|titles|recommendations|playbacks).*")));
    }

    /** Naive baseline for Act 2: without a Fallback, a Non-critical Dependency breaks the whole home page. */
    @Test
    void homeFailsWhenRecommendationsFails() {
        wireMock.stubFor(get("/recommendations/v-1").willReturn(aResponse().withStatus(503)));
        given().when().get("/home?viewerId=v-1").then().statusCode(greaterThanOrEqualTo(500));
    }

    @Test
    void playStartsPlayback() {
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t03\"}")
                .when().post("/play")
                .then().statusCode(200)
                .body("licenseId", equalTo("lic-1"));
    }

    @Test
    void playWithoutTitleIsBadRequest() {
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\"}")
                .when().post("/play").then().statusCode(400);
    }

    @Test
    void ratingIsPublishedToQueue() {
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t03\",\"stars\":4}")
                .when().post("/ratings")
                .then().statusCode(202);

        String queueUrl = sqs.getQueueUrl(request -> request.queueName("ratings")).queueUrl();
        List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                .maxNumberOfMessages(10).waitTimeSeconds(2)).messages();
        assertThat(messages).extracting(Message::body).anyMatch(body -> body.contains("\"titleId\":\"t03\""));
    }

    @Test
    void ratingWithStarsOutOfRangeIsBadRequest() {
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t03\",\"stars\":6}")
                .when().post("/ratings").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t03\",\"stars\":0}")
                .when().post("/ratings").then().statusCode(400);
    }

    @Test
    void reportsEachDependencyCallToTelemetry() {
        wireMock.stubFor(post("/events").willReturn(aResponse().withStatus(202)));

        given().when().get("/home?viewerId=v-1").then().statusCode(200);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> wireMock.verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$.calls[?(@.callee == 'recommendations' && @.outcome == 'OK')]"))));
    }

    @Test
    void reportsAFailedDependencyAsAnError() {
        wireMock.stubFor(post("/events").willReturn(aResponse().withStatus(202)));
        wireMock.stubFor(get("/recommendations/v-1").willReturn(aResponse().withStatus(503)));

        given().when().get("/home?viewerId=v-1");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> wireMock.verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$.calls[?(@.callee == 'recommendations' && @.outcome == 'ERROR')]"))));
    }

    private static String titlesJson(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(n -> "{\"id\":\"t%02d\",\"name\":\"Title %d\",\"genre\":\"Drama\",\"year\":2000}".formatted(n, n))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
