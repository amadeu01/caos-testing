package io.github.amadeu01.chaos.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@WithTestResource(value = WireMockResource.class, initArgs = @ResourceArg(name = WireMockResource.URL_PROPERTIES,
        value = "quarkus.rest-client.telemetry.url,quarkus.rest-client.viewer-simulator.url"))
class PageTest {

    /** Every container in docker-compose.yml that the topology must show. */
    private static final List<String> SERVICES = List.of("viewer-simulator", "edge-gateway", "api", "profile",
            "catalog", "cache", "store", "recommendations", "playback", "license", "queue", "ratings-worker");

    @Test
    void servesThePage() {
        given().when().get("/")
                .then().statusCode(200)
                .body(containsString("<title>Chaos Lecture Demo</title>"))
                .body(containsString("vendor/cytoscape.min.js"));
    }

    @Test
    void servesTheVendoredGraphLibrary() {
        given().when().get("/vendor/cytoscape.min.js").then().statusCode(200);
    }

    @Test
    void servesTheTelemetryGraphsPageWithItsChartLibrary() {
        given().when().get("/telemetry.html")
                .then().statusCode(200)
                .body(containsString("vendor/uPlot.iife.min.js"))
                .body(containsString("href=\"index.html\""));
        given().when().get("/vendor/uPlot.iife.min.js").then().statusCode(200);
        given().when().get("/vendor/uPlot.min.css").then().statusCode(200);
        given().when().get("/vendor/LICENSE-uplot").then().statusCode(200);
        given().when().get("/telemetry.js").then().statusCode(200);
        given().when().get("/telemetry.css").then().statusCode(200);
    }

    @Test
    void staticFilesAreRevalidatedNotCachedAsImmutable() {
        String cacheControl = given().when().get("/app.js").then().statusCode(200).extract().header("Cache-Control");

        assertThat(cacheControl == null ? "" : cacheControl).doesNotContain("immutable");
    }

    @Test
    void dashboardLinksToTheTelemetryGraphs() {
        given().when().get("/").then().statusCode(200).body(containsString("href=\"telemetry.html\""));
    }

    @Test
    void topologyShowsEveryServiceAndOnlyKnownDependencies() throws Exception {
        JsonNode topology = new ObjectMapper().readTree(given().get("/topology.json").then().statusCode(200).extract().asString());

        Set<String> nodes = new HashSet<>();
        topology.path("nodes").forEach(node -> nodes.add(node.path("id").asText()));
        assertThat(nodes).containsExactlyInAnyOrderElementsOf(SERVICES);

        topology.path("edges").forEach(edge -> {
            assertThat(nodes).contains(edge.path("source").asText(), edge.path("target").asText());
            assertThat(edge.path("kind").asText()).isIn("critical", "non-critical", "async");
        });
        assertThat(topology.path("edges")).hasSize(12);
    }
}
