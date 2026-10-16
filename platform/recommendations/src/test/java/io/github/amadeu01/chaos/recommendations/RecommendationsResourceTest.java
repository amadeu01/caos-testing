package io.github.amadeu01.chaos.recommendations;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@WithTestResource(value = WireMockResource.class,
        initArgs = @ResourceArg(name = WireMockResource.URL_PROPERTIES, value = "telemetry.url"))
class RecommendationsResourceTest {

    @InjectWireMock
    WireMockServer telemetry;

    @Test
    void returnsRecommendationsForViewer() {
        given().when().get("/recommendations/v-3")
                .then().statusCode(200)
                .body("viewerId", equalTo("v-3"))
                .body("titleIds", hasSize(10));
    }

    @Test
    void sendsHeartbeats() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> telemetry.verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$[?(@.service == 'recommendations')]"))));
    }
}
