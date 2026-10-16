package io.github.amadeu01.chaos.playback;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@QuarkusTest
@WithTestResource(value = WireMockResource.class, initArgs =
        @ResourceArg(name = WireMockResource.URL_PROPERTIES, value = "quarkus.rest-client.license.url"))
class PlaybackResourceTest {

    @InjectWireMock
    WireMockServer wireMock;

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void startsPlaybackWithLicense() {
        wireMock.stubFor(post("/licenses").willReturn(okJson(
                "{\"licenseId\":\"lic-1\",\"viewerId\":\"v-1\",\"titleId\":\"t07\",\"expiresAt\":\"2030-01-01T00:00:00Z\"}")));

        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t07\"}")
                .when().post("/playbacks")
                .then().statusCode(200)
                .body("licenseId", equalTo("lic-1"))
                .body("streamUrl", equalTo("https://stream.example/titles/t07?license=lic-1"));

        wireMock.verify(1, postRequestedFor(urlEqualTo("/licenses")));
    }

    @Test
    void failsWhenLicenseFails() {
        wireMock.stubFor(post("/licenses").willReturn(aResponse().withStatus(503)));

        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t07\"}")
                .when().post("/playbacks")
                .then().statusCode(greaterThanOrEqualTo(500));
    }

    @Test
    void rejectsRequestWithoutViewer() {
        given().contentType(ContentType.JSON).body("{\"titleId\":\"t07\"}")
                .when().post("/playbacks")
                .then().statusCode(400);
        wireMock.verify(0, postRequestedFor(urlEqualTo("/licenses")));
    }
}
