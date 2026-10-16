package io.github.amadeu01.chaos.dashboard;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@WithTestResource(value = WireMockResource.class, initArgs = @ResourceArg(name = WireMockResource.URL_PROPERTIES,
        value = "quarkus.rest-client.telemetry.url,quarkus.rest-client.viewer-simulator.url"))
class TelemetryWindowsProxyTest {

    @InjectWireMock
    WireMockServer wireMock;

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void relaysTheHistoryBody() {
        wireMock.stubFor(get(urlPathEqualTo("/windows"))
                .willReturn(okJson("[{\"timestamp\":1000,\"sps\":{\"total\":3,\"control\":0,\"experiment\":0}}]")));

        given().when().get("/api/telemetry/windows?from=1000")
                .then().statusCode(200).body("[0].sps.total", equalTo(3));
        wireMock.verify(getRequestedFor(urlEqualTo("/windows?from=1000")));
    }

    @Test
    void relaysTelemetrysStatus() {
        wireMock.stubFor(get(urlPathEqualTo("/windows")).willReturn(aResponse().withStatus(503)));

        given().when().get("/api/telemetry/windows?from=1000").then().statusCode(503);
    }

    @Test
    void answersBadGatewayWhenTelemetryIsUnreachable() {
        wireMock.stubFor(get(urlPathEqualTo("/windows")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        given().when().get("/api/telemetry/windows?from=1000").then().statusCode(502);
    }
}
