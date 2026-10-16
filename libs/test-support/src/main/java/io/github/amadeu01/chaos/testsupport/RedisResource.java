package io.github.amadeu01.chaos.testsupport;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

/** Redis in a container, standing in for ElastiCache. */
public class RedisResource implements QuarkusTestResourceLifecycleManager {

    private GenericContainer<?> container;

    @Override
    public Map<String, String> start() {
        container = new GenericContainer<>(DockerImageName.parse(Images.REDIS)).withExposedPorts(6379);
        container.start();
        return Map.of("quarkus.redis.hosts",
                "redis://" + container.getHost() + ":" + container.getMappedPort(6379));
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }
}
