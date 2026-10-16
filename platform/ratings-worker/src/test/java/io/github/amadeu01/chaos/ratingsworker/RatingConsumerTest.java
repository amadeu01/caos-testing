package io.github.amadeu01.chaos.ratingsworker;

import io.github.amadeu01.chaos.testsupport.DynamoDbLocalResource;
import io.github.amadeu01.chaos.testsupport.ElasticMqResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.util.function.Consumer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@WithTestResource(DynamoDbLocalResource.class)
@WithTestResource(ElasticMqResource.class)
class RatingConsumerTest {

    @Inject
    SqsClient sqs;

    @Inject
    @InjectSpy
    DynamoDbClient dynamo;

    @Inject
    RatingConsumer consumer;

    @Test
    void storesRatingFromQueue() {
        send("{\"viewerId\":\"v-1\",\"titleId\":\"t11\",\"stars\":5}");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            consumer.poll();
            assertThat(storedRatings()).anySatisfy(item -> {
                    assertThat(item.get("titleId").s()).isEqualTo("t11");
                    assertThat(item.get("stars").n()).isEqualTo("5");
                });
        });
    }

    @Test
    void malformedMessageIsDroppedAndDoesNotBlockTheQueue() {
        send("not json");
        send("{\"viewerId\":\"v-2\",\"titleId\":\"t12\",\"stars\":3}");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            consumer.poll();
            assertThat(storedRatings()).anySatisfy(item -> assertThat(item.get("titleId").s()).isEqualTo("t12"));
            assertThat(messagesLeftInQueue()).isZero();
        });
    }

    @Test
    void semanticallyInvalidMessageIsDroppedAndDoesNotBlockTheQueue() {
        send("{}");
        send("{\"viewerId\":\"v-3\",\"titleId\":\"t13\",\"stars\":9}");
        send("{\"viewerId\":\"v-3\",\"titleId\":\"t14\",\"stars\":2}");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            consumer.poll();
            assertThat(storedRatings()).anySatisfy(item -> assertThat(item.get("titleId").s()).isEqualTo("t14"));
            assertThat(storedRatings()).noneSatisfy(item -> assertThat(item.get("titleId").s()).isEqualTo("t13"));
            assertThat(messagesLeftInQueue()).isZero();
        });
    }

    @Test
    void drainsMoreThanOneBatchPerPoll() {
        int total = 3 * RatingConsumer.BATCH_SIZE + 1;
        for (int i = 0; i < total; i++) {
            send("{\"viewerId\":\"drain\",\"titleId\":\"t-drain-" + i + "\",\"stars\":4}");
        }

        consumer.poll();

        assertThat(storedRatings().stream().filter(item -> item.get("viewerId").s().equals("drain")))
                .hasSize(total);
        assertThat(messagesLeftInQueue()).isZero();
    }

    @Test
    void storeFailureLeavesMessageForRedelivery() {
        sqs.setQueueAttributes(request -> request.queueUrl(queueUrl())
                .attributes(Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "1")));
        Mockito.doThrow(AwsServiceException.builder().message("store down").build())
                .doCallRealMethod()
                .when(dynamo).putItem(Mockito.<Consumer<PutItemRequest.Builder>>any());
        send("{\"viewerId\":\"v-fail\",\"titleId\":\"t-fail\",\"stars\":1}");

        try {
            consumer.poll();
        } catch (AwsServiceException expected) {
            // the store was down: the message must not have been deleted
        }
        assertThat(messagesLeftInQueue()).isEqualTo(1);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            consumer.poll();
            assertThat(storedRatings()).anySatisfy(item -> assertThat(item.get("titleId").s()).isEqualTo("t-fail"));
            assertThat(messagesLeftInQueue()).isZero();
        });
    }

    private void send(String body) {
        sqs.sendMessage(request -> request.queueUrl(queueUrl()).messageBody(body));
    }

    private String queueUrl() {
        return sqs.getQueueUrl(request -> request.queueName("ratings")).queueUrl();
    }

    private List<Map<String, AttributeValue>> storedRatings() {
        return dynamo.scan(request -> request.tableName("ratings")).items();
    }

    private int messagesLeftInQueue() {
        Map<QueueAttributeName, String> attributes = sqs.getQueueAttributes(request -> request.queueUrl(queueUrl())
                .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).attributes();
        return Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                + Integer.parseInt(attributes.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE));
    }
}
