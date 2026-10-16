package io.github.amadeu01.chaos.ratingsworker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.amadeu01.chaos.libtelemetry.DependencyCalls;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;
import java.util.Map;

/**
 * Asynchronous path, off the Viewer journey: when this worker is down, ratings wait in the queue and
 * SPS does not move. A message is deleted only after it was stored (or found malformed), so a store
 * outage means redelivery, not loss.
 */
@ApplicationScoped
public class RatingConsumer {

    static final String TABLE = "ratings";
    static final int BATCH_SIZE = 10;
    static final int MAX_BATCHES_PER_POLL = 50;
    private static final Logger LOG = Logger.getLogger(RatingConsumer.class);

    @Inject
    SqsClient sqs;

    @Inject
    DynamoDbClient dynamo;

    @Inject
    ObjectMapper mapper;

    @Inject
    DependencyCalls calls;

    @ConfigProperty(name = "ratings.queue-name")
    String queueName;

    private volatile String queueUrl;

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        for (int batch = 0; batch < MAX_BATCHES_PER_POLL; batch++) {
            List<Message> messages = calls.call("queue", () -> sqs.receiveMessage(request -> request.queueUrl(queueUrl())
                    .maxNumberOfMessages(BATCH_SIZE)).messages());
            if (messages.isEmpty()) {
                return;
            }
            for (Message message : messages) {
                handle(message);
                calls.run("queue", () -> sqs.deleteMessage(request -> request.queueUrl(queueUrl()).receiptHandle(message.receiptHandle())));
            }
        }
    }

    private void handle(Message message) {
        Rating rating;
        try {
            rating = mapper.readValue(message.body(), Rating.class);
        } catch (JsonProcessingException e) {
            LOG.warnf("Dropping malformed rating message %s", message.messageId());
            return;
        }
        if (!isValid(rating)) {
            LOG.warnf("Dropping malformed rating message %s", message.messageId());
            return;
        }
        calls.run("store", () -> dynamo.putItem(request -> request.tableName(TABLE).item(Map.of(
                "id", AttributeValue.fromS(message.messageId()),
                "viewerId", AttributeValue.fromS(rating.viewerId()),
                "titleId", AttributeValue.fromS(rating.titleId()),
                "stars", AttributeValue.fromN(Integer.toString(rating.stars()))))));
    }

    private static boolean isValid(Rating rating) {
        return rating != null
                && rating.viewerId() != null && !rating.viewerId().isBlank()
                && rating.titleId() != null && !rating.titleId().isBlank()
                && rating.stars() >= 1 && rating.stars() <= 5;
    }

    private String queueUrl() {
        if (queueUrl == null) {
            queueUrl = sqs.getQueueUrl(request -> request.queueName(queueName)).queueUrl();
        }
        return queueUrl;
    }
}
