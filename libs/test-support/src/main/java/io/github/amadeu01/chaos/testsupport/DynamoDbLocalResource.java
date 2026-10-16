package io.github.amadeu01.chaos.testsupport;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** DynamoDB Local in a container, with the same tables that infra/dynamodb/create-tables.sh creates. */
public class DynamoDbLocalResource implements QuarkusTestResourceLifecycleManager {

    /** Keep in sync with infra/dynamodb/create-tables.sh. */
    public static final List<String> TABLES = List.of("titles", "ratings");

    private GenericContainer<?> container;

    @Override
    public Map<String, String> start() {
        container = new GenericContainer<>(DockerImageName.parse(Images.DYNAMODB_LOCAL))
                .withExposedPorts(8000)
                .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");
        container.start();
        String endpoint = "http://" + container.getHost() + ":" + container.getMappedPort(8000);
        createTables(endpoint);
        return Map.of("quarkus.dynamodb.endpoint-override", endpoint);
    }

    private static void createTables(String endpoint) {
        try (DynamoDbClient client = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClient(UrlConnectionHttpClient.create())
                .build()) {
            for (String table : TABLES) {
                client.createTable(request -> request
                        .tableName(table)
                        .billingMode(BillingMode.PAY_PER_REQUEST)
                        .attributeDefinitions(AttributeDefinition.builder()
                                .attributeName("id").attributeType(ScalarAttributeType.S).build())
                        .keySchema(KeySchemaElement.builder()
                                .attributeName("id").keyType(KeyType.HASH).build()));
            }
        }
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }
}
