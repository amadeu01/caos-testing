package io.github.amadeu01.chaos.viewersimulator;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ViewerRateResourceTest {

    @Test
    void changesViewersPerSecond() {
        given().when().put("/viewers-per-second?value=80").then().statusCode(204);
        given().when().get("/viewers-per-second").then().statusCode(200).body("viewersPerSecond", equalTo(80));
    }

    @Test
    void zeroPausesTraffic() {
        given().when().put("/viewers-per-second?value=0").then().statusCode(204);
        given().when().get("/viewers-per-second").then().body("viewersPerSecond", equalTo(0));
    }

    @Test
    void rejectsRatesOutsideBounds() {
        given().when().put("/viewers-per-second?value=-1").then().statusCode(400);
        given().when().put("/viewers-per-second?value=501").then().statusCode(400);
    }
}
