package io.github.amadeu01.chaos.recommendations;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class RecommendationsResourceTest {

    @Test
    void returnsRecommendationsForViewer() {
        given().when().get("/recommendations/v-3")
                .then().statusCode(200)
                .body("viewerId", equalTo("v-3"))
                .body("titleIds", hasSize(10));
    }
}
