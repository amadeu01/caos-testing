package io.github.amadeu01.chaos.telemetry;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class TelemetryResourceTest {

    /** Same literal as lib-telemetry's EventBatchJsonTest: both sides of the wire agree. */
    static final String WIRE_FORMAT = "{\"service\":\"api\",\"dropped\":2,\"calls\":[{\"timestamp\":1000,\"caller\":\"api\","
            + "\"callee\":\"profile\",\"group\":\"none\",\"outcome\":\"OK\",\"latencyMs\":3}],"
            + "\"starts\":[{\"timestamp\":1000,\"group\":\"none\"}]}";

    @Test
    void acceptsTheWireFormat() {
        given().contentType(ContentType.JSON).body(WIRE_FORMAT)
                .when().post("/events")
                .then().statusCode(202);
    }

    @Test
    void recentStartsShowUpInTheSpsSeries() {
        long recent = System.currentTimeMillis() - 1_000;
        long from = recent - 5_000;
        given().contentType(ContentType.JSON)
                .body(WIRE_FORMAT.replace("\"timestamp\":1000", "\"timestamp\":" + recent))
                .when().post("/events").then().statusCode(202);

        given().when().get("/sps?group=total&from=" + from)
                .then().statusCode(200)
                .body("sps", hasItem(1));
    }

    @Test
    void latestWindowListsTheReportingService() {
        given().contentType(ContentType.JSON).body(WIRE_FORMAT).when().post("/events");

        given().when().get("/windows/latest")
                .then().statusCode(200)
                .body("sps.total", notNullValue())
                .body("services.name", hasItem("api"));
    }

    @Test
    void emptyBodyIsBadRequest() {
        given().contentType(ContentType.JSON)
                .when().post("/events")
                .then().statusCode(400);
    }

    @Test
    void unknownGroupIsBadRequest() {
        given().when().get("/sps?group=everyone&from=0").then().statusCode(400);
    }
}
