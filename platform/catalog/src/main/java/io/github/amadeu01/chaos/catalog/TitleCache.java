package io.github.amadeu01.chaos.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/** Dependency catalog -> cache (Non-critical): a miss, or a corrupt entry, falls through to the store. */
@ApplicationScoped
public class TitleCache {

    static final String KEY = "catalog:titles";
    static final long TTL_SECONDS = 30;
    private static final Logger LOG = Logger.getLogger(TitleCache.class);
    private static final TypeReference<List<Title>> TITLES = new TypeReference<>() {
    };

    private final ValueCommands<String, String> values;
    private final ObjectMapper mapper;

    TitleCache(RedisDataSource redis, ObjectMapper mapper) {
        this.values = redis.value(String.class);
        this.mapper = mapper;
    }

    public Optional<List<Title>> get() {
        String json = values.get(KEY);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(json, TITLES));
        } catch (JsonProcessingException e) {
            LOG.warnf("Ignoring corrupt cache entry %s", KEY);
            return Optional.empty();
        }
    }

    public void put(List<Title> titles) {
        try {
            values.setex(KEY, TTL_SECONDS, mapper.writeValueAsString(titles));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Titles must always serialise", e);
        }
    }
}
