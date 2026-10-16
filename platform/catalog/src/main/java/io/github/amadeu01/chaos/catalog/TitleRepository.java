package io.github.amadeu01.chaos.catalog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Dependency catalog -> store (Critical): titles live in the DynamoDB table "titles". */
@ApplicationScoped
public class TitleRepository {

    static final String TABLE = "titles";

    @Inject
    DynamoDbClient dynamo;

    public void saveAll(List<Title> titles) {
        titles.forEach(title -> dynamo.putItem(request -> request.tableName(TABLE).item(toItem(title))));
    }

    public List<Title> findAll() {
        return dynamo.scan(request -> request.tableName(TABLE)).items().stream()
                .map(TitleRepository::fromItem)
                .sorted(Comparator.comparing(Title::id))
                .toList();
    }

    public Optional<Title> findById(String id) {
        Map<String, AttributeValue> item = dynamo.getItem(request -> request.tableName(TABLE)
                .key(Map.of("id", AttributeValue.fromS(id)))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(fromItem(item));
    }

    private static Map<String, AttributeValue> toItem(Title title) {
        return Map.of(
                "id", AttributeValue.fromS(title.id()),
                "name", AttributeValue.fromS(title.name()),
                "genre", AttributeValue.fromS(title.genre()),
                "year", AttributeValue.fromN(Integer.toString(title.year())));
    }

    private static Title fromItem(Map<String, AttributeValue> item) {
        return new Title(item.get("id").s(), item.get("name").s(), item.get("genre").s(),
                Integer.parseInt(item.get("year").n()));
    }
}
