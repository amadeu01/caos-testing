package io.github.amadeu01.chaos.license;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class LicenseResourceTest {

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
}
