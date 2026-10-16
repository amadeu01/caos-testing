package io.github.amadeu01.chaos.profile;

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
import static org.hamcrest.Matchers.oneOf;

@QuarkusTest
@WithTestResource(value = WireMockResource.class,
        initArgs = @ResourceArg(name = WireMockResource.URL_PROPERTIES, value = "telemetry.url"))
class ProfileResourceTest {

    @InjectWireMock
    WireMockServer telemetry;

    @Test
    void returnsProfileForViewer() {
        given().when().get("/profiles/v-42")
                .then().statusCode(200)
                .body("viewerId", equalTo("v-42"))
                .body("displayName", equalTo("Viewer v-42"))
                .body("maturityRating", oneOf("ADULT", "KIDS"));
    }

    @Test
    void sameViewerAlwaysGetsSameProfile() {
        String first = given().get("/profiles/v-7").then().extract().asString();
        String second = given().get("/profiles/v-7").then().extract().asString();
        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
    }

    @Test
    void sendsHeartbeats() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> telemetry.verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$[?(@.service == 'profile')]"))));
    }
}
