package io.github.amadeu01.chaos.catalog;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import java.time.Duration;
import io.github.amadeu01.chaos.testsupport.DynamoDbLocalResource;
import io.github.amadeu01.chaos.testsupport.RedisResource;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@WithTestResource(DynamoDbLocalResource.class)
@WithTestResource(RedisResource.class)
@WithTestResource(value = WireMockResource.class, initArgs =
        @ResourceArg(name = WireMockResource.URL_PROPERTIES, value = "telemetry.url"))
class TitleResourceTest {

    @InjectWireMock
    WireMockServer telemetry;

    @Inject
    RedisDataSource redis;

    @BeforeEach
    void clearCache() {
        redis.key().del(TitleCache.KEY);
    }

    @Test
    void listsSeededTitles() {
        given().when().get("/titles")
                .then().statusCode(200)
                .body("$", hasSize(50))
                .body("[0].id", equalTo("t01"));
    }

    @Test
    void fillsCacheAfterReadingStore() {
        given().get("/titles").then().statusCode(200);
        assertThat(redis.value(String.class).get(TitleCache.KEY)).contains("\"t01\"");
    }

    @Test
    void corruptCacheEntryIsTreatedAsMiss() {
        redis.value(String.class).set(TitleCache.KEY, "not json");
        given().when().get("/titles")
                .then().statusCode(200)
                .body("$", hasSize(50));
    }

    @Test
    void reportsCacheAndStoreCalls() {
        telemetry.stubFor(post("/events").willReturn(aResponse().withStatus(202)));

        given().get("/titles").then().statusCode(200);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            telemetry.verify(postRequestedFor(urlEqualTo("/events"))
                    .withRequestBody(matchingJsonPath("$.calls[?(@.callee == 'cache')]")));
            telemetry.verify(postRequestedFor(urlEqualTo("/events"))
                    .withRequestBody(matchingJsonPath("$.calls[?(@.callee == 'store')]")));
        });
    }

    @Test
    void returnsSingleTitle() {
        given().when().get("/titles/t07")
                .then().statusCode(200)
                .body("id", equalTo("t07"));
    }

    @Test
    void unknownTitleIsNotFound() {
        given().when().get("/titles/t99").then().statusCode(404);
    }
}
