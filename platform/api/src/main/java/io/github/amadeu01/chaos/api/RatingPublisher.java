package io.github.amadeu01.chaos.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Dependency api -> queue (Non-critical): ratings are processed asynchronously by ratings-worker. */
@ApplicationScoped
public class RatingPublisher {

    @Inject
    SqsClient sqs;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "ratings.queue-name")
    String queueName;

    private volatile String queueUrl;

    public void publish(Rating rating) {
        String body;
        try {
            body = mapper.writeValueAsString(rating);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Ratings must always serialise", e);
        }
        sqs.sendMessage(request -> request.queueUrl(queueUrl()).messageBody(body));
    }

    private String queueUrl() {
        if (queueUrl == null) {
            queueUrl = sqs.getQueueUrl(request -> request.queueName(queueName)).queueUrl();
        }
        return queueUrl;
    }
}
