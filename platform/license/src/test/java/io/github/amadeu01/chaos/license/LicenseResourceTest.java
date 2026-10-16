package io.github.amadeu01.chaos.license;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@WithTestResource(value = WireMockResource.class,
        initArgs = @ResourceArg(name = WireMockResource.URL_PROPERTIES, value = "telemetry.url"))
class LicenseResourceTest {

    @InjectWireMock
    WireMockServer telemetry;

    @Test
    void issuesLicenseForTitle() {
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t07\"}")
                .when().post("/licenses")
                .then().statusCode(200)
                .body("licenseId", notNullValue())
                .body("viewerId", equalTo("v-1"))
                .body("titleId", equalTo("t07"))
                .body("expiresAt", notNullValue());
    }

    @Test
    void rejectsRequestWithoutTitle() {
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\"}")
                .when().post("/licenses")
                .then().statusCode(400);
    }

    @Test
    void sendsHeartbeats() {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> telemetry.verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$[?(@.service == 'license')]"))));
    }
}
