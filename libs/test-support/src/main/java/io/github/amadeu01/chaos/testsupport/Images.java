package io.github.amadeu01.chaos.testsupport;

/** Emulator images used by tests. Keep in sync with docker-compose.yml so tests run against what students run. */
public final class Images {
    public static final String DYNAMODB_LOCAL = "amazon/dynamodb-local:3.3.2";
    public static final String ELASTICMQ = "softwaremill/elasticmq-native:1.7.1";
    public static final String REDIS = "redis:8.8.3";

    private Images() {
    }
}
