package io.github.amadeu01.chaos.profile;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.oneOf;

@QuarkusTest
class ProfileResourceTest {

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
}
