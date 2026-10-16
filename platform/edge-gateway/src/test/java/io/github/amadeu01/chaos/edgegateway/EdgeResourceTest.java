package io.github.amadeu01.chaos.edgegateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@WithTestResource(value = WireMockResource.class, initArgs =
        @ResourceArg(name = WireMockResource.URL_PROPERTIES, value = "quarkus.rest-client.api.url,telemetry.url"))
class EdgeResourceTest {

    @InjectWireMock
    WireMockServer wireMock;

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void relaysHome() {
        wireMock.stubFor(get(urlPathEqualTo("/home")).willReturn(okJson("{\"recommendedTitleIds\":[\"t03\"]}")));
        given().when().get("/home?viewerId=v-1")
                .then().statusCode(200)
                .body("recommendedTitleIds[0]", equalTo("t03"));
    }

    @Test
    void relaysPlayBody() {
        wireMock.stubFor(post("/play").willReturn(okJson("{\"licenseId\":\"lic-1\"}")));
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t03\"}")
                .when().post("/play")
                .then().statusCode(200).body("licenseId", equalTo("lic-1"));
        wireMock.verify(postRequestedFor(urlEqualTo("/play"))
                .withRequestBody(equalToJson("{\"viewerId\":\"v-1\",\"titleId\":\"t03\"}")));
    }

    @Test
    void keepsClientErrorStatus() {
        wireMock.stubFor(get(urlPathEqualTo("/home")).willReturn(aResponse().withStatus(400)));
        given().when().get("/home").then().statusCode(400);
    }

    @Test
    void keepsServerErrorStatus() {
        wireMock.stubFor(post("/ratings").willReturn(aResponse().withStatus(503)));
        given().contentType(ContentType.JSON).body("{}").when().post("/ratings").then().statusCode(503);
    }

    @Test
    void answersBadGatewayWhenApiIsUnreachable() {
        wireMock.stubFor(get(urlPathEqualTo("/home")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        given().when().get("/home?viewerId=v-1")
                .then().statusCode(502)
                .body("error", equalTo("api unreachable"));
    }

    @Test
    void reportsApiServerErrorsAsErrors() {
        wireMock.stubFor(post("/events").willReturn(aResponse().withStatus(202)));
        wireMock.stubFor(get(urlPathEqualTo("/home")).willReturn(aResponse().withStatus(503)));

        given().when().get("/home?viewerId=v-1").then().statusCode(503);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> wireMock.verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$.calls[?(@.callee == 'api' && @.outcome == 'ERROR')]"))));
    }

    @Test
    void reportsAnUnreachableApiAsAnError() {
        wireMock.stubFor(post("/events").willReturn(aResponse().withStatus(202)));
        wireMock.stubFor(get(urlPathEqualTo("/home")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        given().when().get("/home?viewerId=v-1").then().statusCode(502);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> wireMock.verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$.calls[?(@.callee == 'api' && @.outcome == 'ERROR')]"))));
    }
}
