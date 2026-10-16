package io.github.amadeu01.chaos.testsupport;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.util.Map;

/** ElasticMQ (SQS-compatible) in a container, with the ratings queue from elasticmq.conf. */
public class ElasticMqResource implements QuarkusTestResourceLifecycleManager {

    private GenericContainer<?> container;

    @Override
    public Map<String, String> start() {
        container = new GenericContainer<>(DockerImageName.parse(Images.ELASTICMQ))
                .withExposedPorts(9324)
                .withCopyFileToContainer(MountableFile.forClasspathResource("elasticmq.conf"), "/opt/elasticmq.conf");
        container.start();
        return Map.of("quarkus.sqs.endpoint-override",
                "http://" + container.getHost() + ":" + container.getMappedPort(9324));
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
    }
}
