package io.github.amadeu01.chaos.dashboard;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.http.Fault;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@WithTestResource(value = WireMockResource.class, initArgs = @ResourceArg(name = WireMockResource.URL_PROPERTIES,
        value = "quarkus.rest-client.telemetry.url,quarkus.rest-client.viewer-simulator.url"))
class ViewerRateResourceTest {

    @InjectWireMock
    WireMockServer wireMock;

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void readsTheCurrentRate() {
        wireMock.stubFor(get("/viewers-per-second").willReturn(okJson("{\"viewersPerSecond\":50}")));

        given().when().get("/api/viewers-per-second")
                .then().statusCode(200).body("viewersPerSecond", equalTo(50));
    }

    @Test
    void forwardsANewRate() {
        wireMock.stubFor(put(urlPathEqualTo("/viewers-per-second")).willReturn(aResponse().withStatus(204)));

        given().when().put("/api/viewers-per-second?value=80").then().statusCode(204);
        wireMock.verify(putRequestedFor(urlEqualTo("/viewers-per-second?value=80")));
    }

    @Test
    void relaysARejectedRate() {
        wireMock.stubFor(put(urlPathEqualTo("/viewers-per-second")).willReturn(aResponse().withStatus(400)));

        given().when().put("/api/viewers-per-second?value=999").then().statusCode(400);
    }

    @Test
    void answersBadGatewayWhenSimulatorIsUnreachable() {
        wireMock.stubFor(get("/viewers-per-second").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        given().when().get("/api/viewers-per-second").then().statusCode(502);
    }
}
