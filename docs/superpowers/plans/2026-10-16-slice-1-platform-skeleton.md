# Slice 1: Platform Skeleton Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One command starts the mini-Netflix platform (8 Quarkus Services, DynamoDB Local, ElasticMQ, Redis) plus viewer-simulator in Docker Compose, and SPS is visible in the viewer-simulator log (Act 1 without dashboard).

**Architecture:** Maven multi-module repository. Each platform Service is a small blocking Quarkus REST app on a bounded worker pool (ADR 0002), talking to other Services through the Quarkus REST Client and to AWS through AWS SDK v2 clients (Quarkiverse Amazon Services) pointed at per-service emulators (ADR 0001). viewer-simulator drives Viewer journeys through edge-gateway and counts Playback Starts. A test-only `test-support` module provides Testcontainers and WireMock test resources.

**Tech Stack:** Java 25, Quarkus 3.40.1, Quarkiverse Amazon Services 3.23.0, AWS SDK v2 (url-connection client), JUnit 5, REST Assured, AssertJ 3.27.7, WireMock 3.13.2, Testcontainers 2.0.5 (managed by Quarkus BOM), Awaitility (managed), Docker Compose.

**Spec:** [`docs/superpowers/specs/2026-10-16-chaos-lecture-demo-design.md`](../specs/2026-10-16-chaos-lecture-demo-design.md), delivery slice 1. Read `CONTEXT.md`, `docs/adr/` and `CONTRIBUTING.md` before starting.

## Global Constraints

- Java 25 (`maven.compiler.release=25`); every command runs with `JAVA_HOME=/usr/lib/jvm/java-25-openjdk` (the machine default is JDK 17; the enforcer rule fails the build on anything below 25).
- Quarkus 3.40.1 for every runnable module (ADR 0003); Maven multi-module with `./mvnw` (ADR 0004).
- Endpoints are blocking (plain return types), never `@RunOnVirtualThread`, never `Uni`/`Multi` in platform Services (ADR 0002). Every platform Service sets `quarkus.thread-pool.max-threads=16` and `quarkus.thread-pool.queue-size=32`.
- AWS access only through AWS SDK v2 against DynamoDB Local (`amazon/dynamodb-local:3.3.2`) and ElasticMQ (`softwaremill/elasticmq-native:1.7.1`); cache is `redis:8.8.3` (ADR 0001). `quarkus.devservices.enabled=false` in every module.
- All published Compose ports bind to `127.0.0.1`.
- Base package `io.github.amadeu01.chaos.<module>` (dashes dropped: `edgegateway`, `ratingsworker`, `viewersimulator`, `testsupport`).
- Names follow `CONTEXT.md`: Service, Dependency, Viewer, Playback Start, SPS. Code, identifiers and log lines in English.
- Commits: gitmoji code subject, one idea per commit, body explaining why (see `CONTRIBUTING.md`), trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`, then `git push`.
- Human docs ship in English and Brazilian Portuguese in the same commit.

## Review Focus

1. **Blank `viewerId` or out-of-range rating stars** sent to api must give `400`, not a `500` from a downstream call. Pinned in Task 8.
2. **api returns an error or is unreachable**: edge-gateway must pass api's status through (`400` stays `400`, `503` stays `503`) and answer `502` when api cannot be reached. Pinned in Task 9.
3. **A malformed (poison) rating message** must be dropped without blocking the queue for valid messages. Pinned in Task 10.
4. **Viewers/s outside 0..500** must be rejected with `400`; `0` is valid and pauses traffic. Pinned in Task 11.
5. **A corrupt cache entry** in Redis must be treated as a cache miss, not a `500`. Pinned in Task 7.

---

## File Structure

```
pom.xml                                   root aggregator: versions, BOMs, plugin management, module list
mvnw, mvnw.cmd, .mvn/wrapper/             Maven wrapper (script-only)
libs/test-support/                        test resources shared by Service tests (not runnable)
  src/main/java/io/github/amadeu01/chaos/testsupport/
    Images.java                           emulator image tags (kept in sync with docker-compose.yml)
    InjectWireMock.java                   field annotation for WireMockResource injection
    WireMockResource.java                 WireMock server, sets REST client URL properties
    DynamoDbLocalResource.java            DynamoDB Local container + tables
    ElasticMqResource.java                ElasticMQ container with the ratings queue
    RedisResource.java                    Redis container
  src/main/resources/elasticmq.conf       ElasticMQ config for tests
platform/profile/                         GET /profiles/{viewerId}
platform/license/                         POST /licenses
platform/playback/                        POST /playbacks -> license
platform/recommendations/                 GET /recommendations/{viewerId}
platform/catalog/                         GET /titles, GET /titles/{id}; Redis cache -> DynamoDB store
platform/api/                             GET /home, POST /play, POST /ratings (SQS)
platform/edge-gateway/                    GET /home, POST /play, POST /ratings -> api
platform/ratings-worker/                  SQS ratings queue -> DynamoDB ratings table
control-plane/viewer-simulator/           Viewer journeys, SPS log, PUT/GET /viewers-per-second
docker/quarkus-jvm.Dockerfile             one JVM image definition shared by every Quarkus module
docker-compose.yml                        the whole stack
infra/dynamodb/create-tables.sh           creates DynamoDB tables (run by store-init container)
infra/elasticmq/elasticmq.conf            ElasticMQ config with the ratings queue
scripts/platform-up.sh, platform-down.sh  start/stop, used by README and IntelliJ run configs
.run/*.run.xml                            IntelliJ run configurations
README.md, README.pt-BR.md, docs/en/, docs/pt-br/
```

Each Service module has the same shape: `pom.xml`, `src/main/resources/application.properties`, `src/main/java/.../*.java` (resource, records, clients), `src/test/java/.../*Test.java`.

---

### Task 1: Maven build skeleton

**Files:**
- Create: `pom.xml`
- Create: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` (generated)
- Modify: `CONTRIBUTING.md` (gitmoji table)

**Interfaces:**
- Produces: parent POM `io.github.amadeu01.chaos:chaos-lecture-demo:0.1.0-SNAPSHOT` with managed versions; property `quarkus.platform.version`; empty `<modules>` list that later tasks append to.

- [ ] **Step 1: Write the root POM**

`pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>io.github.amadeu01.chaos</groupId>
    <artifactId>chaos-lecture-demo</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>Chaos Engineering Lecture Demo</name>

    <modules>
    </modules>

    <properties>
        <maven.compiler.release>25</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
        <quarkus.platform.version>3.40.1</quarkus.platform.version>
        <quarkus-amazon-services.version>3.23.0</quarkus-amazon-services.version>
        <aws-sdk.version>2.56.0</aws-sdk.version>
        <assertj.version>3.27.7</assertj.version>
        <wiremock.version>3.13.2</wiremock.version>
        <compiler-plugin.version>3.16.0</compiler-plugin.version>
        <surefire-plugin.version>3.6.0</surefire-plugin.version>
        <enforcer-plugin.version>3.6.3</enforcer-plugin.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- First import wins: Quarkus decides shared versions, the AWS BOM fills the gaps. -->
            <dependency>
                <groupId>${quarkus.platform.group-id}</groupId>
                <artifactId>quarkus-bom</artifactId>
                <version>${quarkus.platform.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>io.quarkiverse.amazonservices</groupId>
                <artifactId>quarkus-amazon-services-bom</artifactId>
                <version>${quarkus-amazon-services.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>software.amazon.awssdk</groupId>
                <artifactId>bom</artifactId>
                <version>${aws-sdk.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>io.github.amadeu01.chaos</groupId>
                <artifactId>test-support</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>org.assertj</groupId>
                <artifactId>assertj-core</artifactId>
                <version>${assertj.version}</version>
            </dependency>
            <dependency>
                <groupId>org.wiremock</groupId>
                <artifactId>wiremock-standalone</artifactId>
                <version>${wiremock.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>${quarkus.platform.group-id}</groupId>
                    <artifactId>quarkus-maven-plugin</artifactId>
                    <version>${quarkus.platform.version}</version>
                    <extensions>true</extensions>
                    <executions>
                        <execution>
                            <goals>
                                <goal>build</goal>
                                <goal>generate-code</goal>
                                <goal>generate-code-tests</goal>
                            </goals>
                        </execution>
                    </executions>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>${compiler-plugin.version}</version>
                    <configuration>
                        <parameters>true</parameters>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>${surefire-plugin.version}</version>
                    <configuration>
                        <systemPropertyVariables>
                            <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
                        </systemPropertyVariables>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-enforcer-plugin</artifactId>
                <version>${enforcer-plugin.version}</version>
                <executions>
                    <execution>
                        <id>require-java-25</id>
                        <goals>
                            <goal>enforce</goal>
                        </goals>
                        <configuration>
                            <rules>
                                <requireJavaVersion>
                                    <version>[25,)</version>
                                    <message>This project needs JDK 25. In IntelliJ: File > Project Structure > SDK. On the command line: set JAVA_HOME to a JDK 25.</message>
                                </requireJavaVersion>
                            </rules>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Generate the Maven wrapper**

Maven is not installed on the machine, so download it once into the scratchpad and let it generate the wrapper:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
SCRATCH=/tmp/claude-1000/-home-amca-Developer-caos-testing/a1f4d72f-e196-4340-ab69-e68db0c67e01/scratchpad
curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.16/binaries/apache-maven-3.9.16-bin.tar.gz | tar xz -C "$SCRATCH"
"$SCRATCH/apache-maven-3.9.16/bin/mvn" -N wrapper:3.3.4:wrapper -Dmaven=3.9.16 -Dtype=only-script
```

Expected: `mvnw`, `mvnw.cmd` and `.mvn/wrapper/maven-wrapper.properties` exist.

- [ ] **Step 3: Verify the build and the Java guard**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q validate && echo OK`
Expected: `OK`

Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./mvnw -q validate`
Expected: FAIL with `This project needs JDK 25.`

- [ ] **Step 4: Add the gitmoji used for structure to CONTRIBUTING.md**

In the gitmoji table in `CONTRIBUTING.md`, add this row directly below the `:tada:` row:

```markdown
| `:building_construction:` | Project structure: modules, build layout |
```

- [ ] **Step 5: Commit and push**

```bash
git add pom.xml mvnw mvnw.cmd .mvn CONTRIBUTING.md
git commit -F - <<'EOF'
:building_construction: Set up the Maven multi-module build

One root POM pins Java 25, Quarkus 3.40.1 and Quarkiverse Amazon Services
3.23.0 for every module (ADR 0003, ADR 0004). The Maven wrapper means a
student needs only a JDK and IntelliJ; the enforcer rule turns "wrong JDK"
into a clear message instead of an obscure compiler error.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 2: Shared test resources (`libs/test-support`)

**Files:**
- Create: `libs/test-support/pom.xml`
- Create: `libs/test-support/src/main/java/io/github/amadeu01/chaos/testsupport/{Images,InjectWireMock,WireMockResource,DynamoDbLocalResource,ElasticMqResource,RedisResource}.java`
- Create: `libs/test-support/src/main/resources/elasticmq.conf`
- Modify: `pom.xml` (module list)

**Interfaces:**
- Produces (used as `@WithTestResource` in later tasks):
  - `WireMockResource` with init arg `WireMockResource.URL_PROPERTIES` = comma-separated config property names, each set to the WireMock base URL; injects `WireMockServer` into test fields annotated `@InjectWireMock`.
  - `DynamoDbLocalResource`: sets `quarkus.dynamodb.endpoint-override`; creates tables `titles` and `ratings` (hash key `id`, string).
  - `ElasticMqResource`: sets `quarkus.sqs.endpoint-override`; queue `ratings` exists.
  - `RedisResource`: sets `quarkus.redis.hosts`.

This module has no tests of its own: every Service test in Tasks 5 to 11 exercises it.

- [ ] **Step 1: Add the module to the root POM**

In `pom.xml`, inside `<modules>`:

```xml
        <module>libs/test-support</module>
```

- [ ] **Step 2: Write `libs/test-support/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.github.amadeu01.chaos</groupId>
        <artifactId>chaos-lecture-demo</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>test-support</artifactId>
    <name>Test support: emulator and WireMock test resources</name>

    <dependencies>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit5</artifactId>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers</artifactId>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>dynamodb</artifactId>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>url-connection-client</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Write the image constants and the WireMock resource**

`Images.java`:

```java
package io.github.amadeu01.chaos.testsupport;

/** Emulator images used by tests. Keep in sync with docker-compose.yml so tests run against what students run. */
public final class Images {
    public static final String DYNAMODB_LOCAL = "amazon/dynamodb-local:3.3.2";
    public static final String ELASTICMQ = "softwaremill/elasticmq-native:1.7.1";
    public static final String REDIS = "redis:8.8.3";

    private Images() {
    }
}
```

`InjectWireMock.java`:

```java
package io.github.amadeu01.chaos.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a {@code WireMockServer} field in a test to receive the server started by {@link WireMockResource}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface InjectWireMock {
}
```

`WireMockResource.java`:

```java
package io.github.amadeu01.chaos.testsupport;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts one WireMock server that stands in for every downstream Service of the Service under test.
 * Downstream Services are told apart by path, so one server is enough.
 */
public class WireMockResource implements QuarkusTestResourceLifecycleManager {

    /** Init arg: comma-separated config properties (for example {@code quarkus.rest-client.profile.url}) to point at WireMock. */
    public static final String URL_PROPERTIES = "urlProperties";

    private WireMockServer server;
    private List<String> urlProperties = List.of();

    @Override
    public void init(Map<String, String> initArgs) {
        urlProperties = Arrays.stream(initArgs.getOrDefault(URL_PROPERTIES, "").split(","))
                .map(String::trim)
                .filter(property -> !property.isEmpty())
                .toList();
    }

    @Override
    public Map<String, String> start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        Map<String, String> config = new HashMap<>();
        urlProperties.forEach(property -> config.put(property, server.baseUrl()));
        return config;
    }

    @Override
    public void inject(TestInjector testInjector) {
        testInjector.injectIntoFields(server,
                new TestInjector.AnnotatedAndMatchesType(InjectWireMock.class, WireMockServer.class));
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }
}
```

- [ ] **Step 4: Write the emulator resources**

`DynamoDbLocalResource.java`:

```java
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
```

`ElasticMqResource.java`:

```java
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
```

`RedisResource.java`:

```java
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
```

`libs/test-support/src/main/resources/elasticmq.conf` (same content as `infra/elasticmq/elasticmq.conf` in Task 12):

```hocon
include classpath("application.conf")

# "*" makes queue URLs use the host the client called, so they work both
# inside Compose and from tests on the host.
node-address {
  protocol = http
  host = "*"
  port = 9324
  context-path = ""
}

rest-sqs {
  enabled = true
  bind-port = 9324
  bind-hostname = "0.0.0.0"
}

queues {
  ratings {}
}
```

- [ ] **Step 5: Verify it compiles**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl libs/test-support install && echo OK`
Expected: `OK`. If `org.testcontainers.containers.GenericContainer` does not resolve, check the Testcontainers 2.0 package for `GenericContainer` in the resolved jar (`jar tf`) and adjust the import.

- [ ] **Step 6: Commit and push**

```bash
git add pom.xml libs/test-support
git commit -F - <<'EOF'
:white_check_mark: Add shared test resources for emulators and WireMock

Service tests run against the same DynamoDB Local, ElasticMQ and Redis
images that Docker Compose runs, started by Testcontainers. Downstream
Services are replaced by one WireMock server. Keeping these in one module
means every Service test sets up its world the same way.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 3: profile Service

**Files:**
- Create: `platform/profile/pom.xml`
- Create: `platform/profile/src/main/resources/application.properties`
- Create: `platform/profile/src/main/java/io/github/amadeu01/chaos/profile/{Profile,ProfileResource}.java`
- Test: `platform/profile/src/test/java/io/github/amadeu01/chaos/profile/ProfileResourceTest.java`
- Modify: `pom.xml` (module list)

**Interfaces:**
- Produces: `GET /profiles/{viewerId}` returns `{"viewerId": "...", "displayName": "...", "maturityRating": "ADULT" | "KIDS"}`. The same `viewerId` always gives the same profile.

- [ ] **Step 1: Add the module and its POM**

In `pom.xml` `<modules>`: `<module>platform/profile</module>`.

`platform/profile/pom.xml` (this is the template every Service POM follows; later tasks show their own full POM):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>io.github.amadeu01.chaos</groupId>
        <artifactId>chaos-lecture-demo</artifactId>
        <version>0.1.0-SNAPSHOT</version>
        <relativePath>../../pom.xml</relativePath>
    </parent>

    <artifactId>profile</artifactId>
    <name>Platform: profile</name>

    <dependencies>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-jackson</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-smallrye-health</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-junit5</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.rest-assured</groupId>
            <artifactId>rest-assured</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>${quarkus.platform.group-id}</groupId>
                <artifactId>quarkus-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

`platform/profile/src/main/resources/application.properties`:

```properties
quarkus.application.name=profile
quarkus.http.port=8080
quarkus.http.test-port=0
quarkus.devservices.enabled=false
# ADR 0002: small bounded worker pool so a slow Dependency visibly exhausts it.
quarkus.thread-pool.max-threads=16
quarkus.thread-pool.queue-size=32
```

- [ ] **Step 2: Write the failing test**

`ProfileResourceTest.java`:

```java
package io.github.amadeu01.chaos.profile;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.oneOf;

@QuarkusTest
class ProfileResourceTest {

    @Test
    void returnsProfileForViewer() {
        given().when().get("/profiles/v-42")
                .then().statusCode(200)
                .body("viewerId", equalTo("v-42"))
                .body("displayName", equalTo("Viewer v-42"))
                .body("maturityRating", oneOf("ADULT", "KIDS"));
    }

    @Test
    void sameViewerAlwaysGetsSameProfile() {
        String first = given().get("/profiles/v-7").then().extract().asString();
        String second = given().get("/profiles/v-7").then().extract().asString();
        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
    }
}
```

Add AssertJ to the profile POM test dependencies (and to every later Service POM):

```xml
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl platform/profile test`
Expected: FAIL, `returnsProfileForViewer` gets `404`.

- [ ] **Step 4: Implement**

`Profile.java`:

```java
package io.github.amadeu01.chaos.profile;

public record Profile(String viewerId, String displayName, String maturityRating) {

    /** Profiles are derived from the viewer id, so no storage is needed and answers are repeatable. */
    static Profile of(String viewerId) {
        String maturity = Math.floorMod(viewerId.hashCode(), 2) == 0 ? "ADULT" : "KIDS";
        return new Profile(viewerId, "Viewer " + viewerId, maturity);
    }
}
```

`ProfileResource.java`:

```java
package io.github.amadeu01.chaos.profile;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/profiles")
public class ProfileResource {

    @GET
    @Path("/{viewerId}")
    public Profile get(@PathParam("viewerId") String viewerId) {
        return Profile.of(viewerId);
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl platform/profile test`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit and push**

```bash
git add pom.xml platform/profile
git commit -F - <<'EOF'
:sparkles: Add profile Service

profile answers who the Viewer is. It is a Non-critical Dependency of api:
later slices give api an anonymous-profile Fallback, so a broken profile
must not stop a Playback Start. Profiles are derived from the viewer id so
the Service needs no storage and every run is repeatable.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 4: license Service

**Files:**
- Create: `platform/license/pom.xml` (same as profile's POM with `<artifactId>license</artifactId>` and `<name>Platform: license</name>`, including the AssertJ test dependency)
- Create: `platform/license/src/main/resources/application.properties` (same as profile's with `quarkus.application.name=license`)
- Create: `platform/license/src/main/java/io/github/amadeu01/chaos/license/{LicenseRequest,License,LicenseResource}.java`
- Test: `platform/license/src/test/java/io/github/amadeu01/chaos/license/LicenseResourceTest.java`
- Modify: `pom.xml` (module list: `<module>platform/license</module>`)

**Interfaces:**
- Produces: `POST /licenses` with `{"viewerId","titleId"}` returns `200 {"licenseId","viewerId","titleId","expiresAt"}`; missing or blank field returns `400`.

- [ ] **Step 1: Create the POM, properties and module entry** as listed under Files.

- [ ] **Step 2: Write the failing test**

```java
package io.github.amadeu01.chaos.license;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class LicenseResourceTest {

    @Test
    void issuesLicenseForTitle() {
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t07\"}")
                .when().post("/licenses")
                .then().statusCode(200)
                .body("licenseId", notNullValue())
                .body("viewerId", equalTo("v-1"))
                .body("titleId", equalTo("t07"))
                .body("expiresAt", notNullValue());
    }

    @Test
    void rejectsRequestWithoutTitle() {
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\"}")
                .when().post("/licenses")
                .then().statusCode(400);
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl platform/license test`
Expected: FAIL with `404`.

- [ ] **Step 4: Implement**

```java
package io.github.amadeu01.chaos.license;

public record LicenseRequest(String viewerId, String titleId) {
}
```

```java
package io.github.amadeu01.chaos.license;

import java.time.Instant;

public record License(String licenseId, String viewerId, String titleId, Instant expiresAt) {
}
```

```java
package io.github.amadeu01.chaos.license;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Path("/licenses")
public class LicenseResource {

    static final Duration LICENSE_VALIDITY = Duration.ofHours(4);

    @POST
    public License issue(LicenseRequest request) {
        if (request == null || isBlank(request.viewerId()) || isBlank(request.titleId())) {
            throw new BadRequestException("viewerId and titleId are required");
        }
        return new License(UUID.randomUUID().toString(), request.viewerId(), request.titleId(),
                Instant.now().plus(LICENSE_VALIDITY));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl platform/license test`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit and push**

```bash
git add pom.xml platform/license
git commit -F - <<'EOF'
:sparkles: Add license Service

license grants the right to play a title. It is a Critical Dependency:
without a license there is no Playback Start, and no Fallback can hide
that. Act 5 uses this to show that some failures cannot be degraded away.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 5: playback Service

**Files:**
- Create: `platform/playback/pom.xml`
- Create: `platform/playback/src/main/resources/application.properties`
- Create: `platform/playback/src/main/java/io/github/amadeu01/chaos/playback/{PlaybackRequest,Playback,LicenseRequest,License,LicenseClient,PlaybackResource}.java`
- Test: `platform/playback/src/test/java/io/github/amadeu01/chaos/playback/PlaybackResourceTest.java`
- Modify: `pom.xml` (`<module>platform/playback</module>`)

**Interfaces:**
- Consumes: license `POST /licenses` (Task 4).
- Produces: `POST /playbacks` with `{"viewerId","titleId"}` returns `200 {"streamUrl","licenseId"}`; `400` on missing fields; `5xx` when license fails.

- [ ] **Step 1: POM and properties**

`platform/playback/pom.xml`: profile's POM with `<artifactId>playback</artifactId>`, `<name>Platform: playback</name>`, plus:

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-client-jackson</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.amadeu01.chaos</groupId>
            <artifactId>test-support</artifactId>
            <scope>test</scope>
        </dependency>
```

`application.properties`:

```properties
quarkus.application.name=playback
quarkus.http.port=8080
quarkus.http.test-port=0
quarkus.devservices.enabled=false
quarkus.thread-pool.max-threads=16
quarkus.thread-pool.queue-size=32
quarkus.rest-client.license.url=http://license:8080
```

- [ ] **Step 2: Write the failing test**

```java
package io.github.amadeu01.chaos.playback;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@QuarkusTest
@WithTestResource(value = WireMockResource.class, initArgs =
        @ResourceArg(name = WireMockResource.URL_PROPERTIES, value = "quarkus.rest-client.license.url"))
class PlaybackResourceTest {

    @InjectWireMock
    WireMockServer wireMock;

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void startsPlaybackWithLicense() {
        wireMock.stubFor(post("/licenses").willReturn(okJson(
                "{\"licenseId\":\"lic-1\",\"viewerId\":\"v-1\",\"titleId\":\"t07\",\"expiresAt\":\"2030-01-01T00:00:00Z\"}")));

        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t07\"}")
                .when().post("/playbacks")
                .then().statusCode(200)
                .body("licenseId", equalTo("lic-1"))
                .body("streamUrl", equalTo("https://stream.example/titles/t07?license=lic-1"));

        wireMock.verify(1, postRequestedFor(urlEqualTo("/licenses")));
    }

    @Test
    void failsWhenLicenseFails() {
        wireMock.stubFor(post("/licenses").willReturn(aResponse().withStatus(503)));

        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t07\"}")
                .when().post("/playbacks")
                .then().statusCode(greaterThanOrEqualTo(500));
    }

    @Test
    void rejectsRequestWithoutViewer() {
        given().contentType(ContentType.JSON).body("{\"titleId\":\"t07\"}")
                .when().post("/playbacks")
                .then().statusCode(400);
        wireMock.verify(0, postRequestedFor(urlEqualTo("/licenses")));
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl libs/test-support,platform/playback install`
Expected: FAIL, `/playbacks` returns `404`.

- [ ] **Step 4: Implement**

```java
package io.github.amadeu01.chaos.playback;

public record PlaybackRequest(String viewerId, String titleId) {
}
```

```java
package io.github.amadeu01.chaos.playback;

public record Playback(String streamUrl, String licenseId) {
}
```

```java
package io.github.amadeu01.chaos.playback;

public record LicenseRequest(String viewerId, String titleId) {
}
```

```java
package io.github.amadeu01.chaos.playback;

import java.time.Instant;

public record License(String licenseId, String viewerId, String titleId, Instant expiresAt) {
}
```

```java
package io.github.amadeu01.chaos.playback;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Dependency playback -> license (Critical). */
@RegisterRestClient(configKey = "license")
@Path("/licenses")
public interface LicenseClient {

    @POST
    License issue(LicenseRequest request);
}
```

```java
package io.github.amadeu01.chaos.playback;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@Path("/playbacks")
public class PlaybackResource {

    @Inject
    @RestClient
    LicenseClient licenses;

    @POST
    public Playback start(PlaybackRequest request) {
        if (request == null || isBlank(request.viewerId()) || isBlank(request.titleId())) {
            throw new BadRequestException("viewerId and titleId are required");
        }
        License license = licenses.issue(new LicenseRequest(request.viewerId(), request.titleId()));
        return new Playback("https://stream.example/titles/" + request.titleId() + "?license=" + license.licenseId(),
                license.licenseId());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl libs/test-support,platform/playback install`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit and push**

```bash
git add pom.xml platform/playback
git commit -F - <<'EOF'
:sparkles: Add playback Service calling license

playback turns "the Viewer pressed play" into a licensed stream. Its call
to license is the first Dependency in the platform, made with the Quarkus
REST Client on a blocking worker thread (ADR 0002). There is deliberately
no Resilience Policy yet: slice 4 adds it, so students can compare.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 6: recommendations Service

**Files:**
- Create: `platform/recommendations/pom.xml` (profile's POM with `<artifactId>recommendations</artifactId>`, `<name>Platform: recommendations</name>`)
- Create: `platform/recommendations/src/main/resources/application.properties` (profile's with `quarkus.application.name=recommendations`)
- Create: `platform/recommendations/src/main/java/io/github/amadeu01/chaos/recommendations/{RecommendationEngine,Recommendations,RecommendationsResource}.java`
- Test: `platform/recommendations/src/test/java/io/github/amadeu01/chaos/recommendations/{RecommendationEngineTest,RecommendationsResourceTest}.java`
- Modify: `pom.xml` (`<module>platform/recommendations</module>`)

**Interfaces:**
- Produces: `GET /recommendations/{viewerId}` returns `{"viewerId", "titleIds": [10 distinct ids]}`. Title ids use the catalog format `t01`..`t50`.

- [ ] **Step 1: Create the POM, properties and module entry** as listed under Files.

- [ ] **Step 2: Write the failing tests**

```java
package io.github.amadeu01.chaos.recommendations;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationEngineTest {

    private final RecommendationEngine engine = new RecommendationEngine();

    @Test
    void recommendsTenDistinctCatalogTitles() {
        assertThat(engine.recommendFor("v-1"))
                .hasSize(RecommendationEngine.PICKS)
                .doesNotHaveDuplicates()
                .allMatch(id -> id.matches("t(0[1-9]|[1-4][0-9]|50)"));
    }

    @Test
    void sameViewerGetsSameRecommendations() {
        assertThat(engine.recommendFor("v-9")).isEqualTo(engine.recommendFor("v-9"));
    }

    @Test
    void differentViewersUsuallyGetDifferentRecommendations() {
        assertThat(engine.recommendFor("v-1")).isNotEqualTo(engine.recommendFor("v-2"));
    }
}
```

```java
package io.github.amadeu01.chaos.recommendations;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class RecommendationsResourceTest {

    @Test
    void returnsRecommendationsForViewer() {
        given().when().get("/recommendations/v-3")
                .then().statusCode(200)
                .body("viewerId", equalTo("v-3"))
                .body("titleIds", hasSize(10));
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl platform/recommendations test`
Expected: FAIL to compile, `RecommendationEngine` missing.

- [ ] **Step 4: Implement**

```java
package io.github.amadeu01.chaos.recommendations;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Stands in for a trained model: a repeatable pseudo-random pick per Viewer.
 * The id format and catalog size match catalog's seeded titles (t01..t50).
 */
@ApplicationScoped
public class RecommendationEngine {

    static final int CATALOG_SIZE = 50;
    static final int PICKS = 10;

    public List<String> recommendFor(String viewerId) {
        List<String> ids = new ArrayList<>(IntStream.rangeClosed(1, CATALOG_SIZE)
                .mapToObj(n -> "t%02d".formatted(n))
                .toList());
        Collections.shuffle(ids, new Random(viewerId.hashCode()));
        return List.copyOf(ids.subList(0, PICKS));
    }
}
```

```java
package io.github.amadeu01.chaos.recommendations;

import java.util.List;

public record Recommendations(String viewerId, List<String> titleIds) {
}
```

```java
package io.github.amadeu01.chaos.recommendations;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

@Path("/recommendations")
public class RecommendationsResource {

    @Inject
    RecommendationEngine engine;

    @GET
    @Path("/{viewerId}")
    public Recommendations get(@PathParam("viewerId") String viewerId) {
        return new Recommendations(viewerId, engine.recommendFor(viewerId));
    }
}
```

- [ ] **Step 5: Run to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl platform/recommendations test`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit and push**

```bash
git add pom.xml platform/recommendations
git commit -F - <<'EOF'
:sparkles: Add recommendations Service

recommendations personalises the home page. It is the star of Acts 2 and 3:
a Non-critical Dependency whose failure, unprotected, still stops every
Playback Start, because Viewers never get a home page to press play on.
The "model" is a repeatable shuffle per Viewer, enough for a demo.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 7: catalog Service (cache and store)

**Files:**
- Create: `platform/catalog/pom.xml`
- Create: `platform/catalog/src/main/resources/application.properties`
- Create: `platform/catalog/src/main/java/io/github/amadeu01/chaos/catalog/{Title,TitleSeed,TitleRepository,TitleCache,CatalogService,CatalogSeeder,TitleResource}.java`
- Test: `platform/catalog/src/test/java/io/github/amadeu01/chaos/catalog/{TitleSeedTest,TitleResourceTest}.java`
- Modify: `pom.xml` (`<module>platform/catalog</module>`)

**Interfaces:**
- Consumes: DynamoDB table `titles` (hash key `id`), Redis.
- Produces: `GET /titles` returns 50 titles `[{"id","name","genre","year"}]` sorted by id; `GET /titles/{id}` returns one title or `404`. Redis key `catalog:titles` holds the JSON list for 30 s.

- [ ] **Step 1: POM and properties**

`platform/catalog/pom.xml`: profile's POM with `<artifactId>catalog</artifactId>`, `<name>Platform: catalog</name>`, plus:

```xml
        <dependency>
            <groupId>io.quarkiverse.amazonservices</groupId>
            <artifactId>quarkus-amazon-dynamodb</artifactId>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>url-connection-client</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-redis-client</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.amadeu01.chaos</groupId>
            <artifactId>test-support</artifactId>
            <scope>test</scope>
        </dependency>
```

`application.properties`:

```properties
quarkus.application.name=catalog
quarkus.http.port=8080
quarkus.http.test-port=0
quarkus.devservices.enabled=false
quarkus.thread-pool.max-threads=16
quarkus.thread-pool.queue-size=32

# ADR 0001: real AWS SDK against DynamoDB Local.
quarkus.dynamodb.endpoint-override=http://store:8000
quarkus.dynamodb.aws.region=us-east-1
quarkus.dynamodb.aws.credentials.type=static
quarkus.dynamodb.aws.credentials.static-provider.access-key-id=test
quarkus.dynamodb.aws.credentials.static-provider.secret-access-key=test
quarkus.dynamodb.sync-client.type=url

# Redis stands in for ElastiCache.
quarkus.redis.hosts=redis://cache:6379
```

- [ ] **Step 2: Write the failing tests**

```java
package io.github.amadeu01.chaos.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TitleSeedTest {

    @Test
    void seedsFiftyTitlesWithCatalogIds() {
        assertThat(TitleSeed.titles())
                .hasSize(50)
                .extracting(Title::id)
                .doesNotHaveDuplicates()
                .startsWith("t01")
                .endsWith("t50");
    }

    @Test
    void seedIsRepeatable() {
        assertThat(TitleSeed.titles()).isEqualTo(TitleSeed.titles());
    }
}
```

```java
package io.github.amadeu01.chaos.catalog;

import io.github.amadeu01.chaos.testsupport.DynamoDbLocalResource;
import io.github.amadeu01.chaos.testsupport.RedisResource;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@WithTestResource(DynamoDbLocalResource.class)
@WithTestResource(RedisResource.class)
class TitleResourceTest {

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
```

If `@WithTestResource` is not repeatable in Quarkus 3.40, use `@WithTestResource.List({@WithTestResource(DynamoDbLocalResource.class), @WithTestResource(RedisResource.class)})`.

- [ ] **Step 3: Run to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl libs/test-support,platform/catalog install`
Expected: FAIL to compile, `TitleSeed` and `TitleCache` missing.

- [ ] **Step 4: Implement the seed and the store**

```java
package io.github.amadeu01.chaos.catalog;

public record Title(String id, String name, String genre, int year) {
}
```

```java
package io.github.amadeu01.chaos.catalog;

import java.util.List;
import java.util.stream.IntStream;

/** Fifty fictional titles, ids t01..t50 (recommendations relies on this id format). */
final class TitleSeed {

    private static final List<String> ADJECTIVES = List.of(
            "Silent", "Broken", "Hidden", "Last", "Crimson", "Frozen", "Electric", "Lost", "Midnight", "Golden");
    private static final List<String> NOUNS = List.of("Harbor", "Signal", "Empire", "Garden", "Circuit");
    private static final List<String> GENRES = List.of("Drama", "Thriller", "Comedy", "Sci-Fi", "Documentary");

    private TitleSeed() {
    }

    static List<Title> titles() {
        return IntStream.range(0, 50)
                .mapToObj(i -> new Title(
                        "t%02d".formatted(i + 1),
                        "The " + ADJECTIVES.get(i % 10) + " " + NOUNS.get(i / 10),
                        GENRES.get(i % GENRES.size()),
                        1990 + (i * 7) % 35))
                .toList();
    }
}
```

```java
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
```

`TitleRepository.scan` returns at most 1 MB per page; 50 small items fit in one page.

- [ ] **Step 5: Implement the cache, service, seeder and resource**

```java
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
```

```java
package io.github.amadeu01.chaos.catalog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class CatalogService {

    @Inject
    TitleCache cache;

    @Inject
    TitleRepository repository;

    public List<Title> all() {
        return cache.get().orElseGet(() -> {
            List<Title> titles = repository.findAll();
            cache.put(titles);
            return titles;
        });
    }

    public Optional<Title> byId(String id) {
        return repository.findById(id);
    }
}
```

```java
package io.github.amadeu01.chaos.catalog;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/** Writes the seed titles at startup. Idempotent: PutItem overwrites, so restarts are safe. */
@ApplicationScoped
public class CatalogSeeder {

    private static final Logger LOG = Logger.getLogger(CatalogSeeder.class);

    @Inject
    TitleRepository repository;

    void onStart(@Observes StartupEvent event) {
        var titles = TitleSeed.titles();
        repository.saveAll(titles);
        LOG.infof("Seeded %d titles", titles.size());
    }
}
```

```java
package io.github.amadeu01.chaos.catalog;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import java.util.List;

@Path("/titles")
public class TitleResource {

    @Inject
    CatalogService catalog;

    @GET
    public List<Title> all() {
        return catalog.all();
    }

    @GET
    @Path("/{id}")
    public Title byId(@PathParam("id") String id) {
        return catalog.byId(id).orElseThrow(() -> new NotFoundException("No title " + id));
    }
}
```

- [ ] **Step 6: Run to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl libs/test-support,platform/catalog install`
Expected: PASS (7 tests).

- [ ] **Step 7: Commit and push**

```bash
git add pom.xml platform/catalog
git commit -F - <<'EOF'
:sparkles: Add catalog Service backed by cache and store

catalog reads titles through a Redis cache (standing in for ElastiCache)
and falls back to DynamoDB on a miss, the classic cache-aside pattern.
That gives the platform its first Dependency chain inside one Service:
cache is Non-critical, store is Critical. A corrupt cache entry counts as
a miss, because a cache must never be the reason a request fails.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 8: api Service (Viewer journey)

**Files:**
- Create: `platform/api/pom.xml`
- Create: `platform/api/src/main/resources/application.properties`
- Create: `platform/api/src/main/java/io/github/amadeu01/chaos/api/{Profile,Title,Recommendations,PlayRequest,Playback,Rating,Home}.java` (records)
- Create: `platform/api/src/main/java/io/github/amadeu01/chaos/api/{ProfileClient,CatalogClient,RecommendationsClient,PlaybackClient}.java`
- Create: `platform/api/src/main/java/io/github/amadeu01/chaos/api/{Requests,HomeResource,PlayResource,RatingPublisher,RatingResource}.java`
- Test: `platform/api/src/test/java/io/github/amadeu01/chaos/api/ApiResourceTest.java`
- Modify: `pom.xml` (`<module>platform/api</module>`)

**Interfaces:**
- Consumes: profile `GET /profiles/{viewerId}`, catalog `GET /titles`, recommendations `GET /recommendations/{viewerId}`, playback `POST /playbacks`, SQS queue `ratings`.
- Produces:
  - `GET /home?viewerId=` returns `{"profile":{...}, "titles":[first 20 titles], "recommendedTitleIds":[...]}`; `400` when `viewerId` is blank.
  - `POST /play` with `{"viewerId","titleId"}` returns playback's `{"streamUrl","licenseId"}`; `400` on blank fields.
  - `POST /ratings` with `{"viewerId","titleId","stars"}` returns `202` and sends the JSON to queue `ratings`; `400` when a field is blank or `stars` is outside 1..5.

- [ ] **Step 1: POM and properties**

`platform/api/pom.xml`: profile's POM with `<artifactId>api</artifactId>`, `<name>Platform: api</name>`, plus:

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-client-jackson</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkiverse.amazonservices</groupId>
            <artifactId>quarkus-amazon-sqs</artifactId>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>url-connection-client</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.amadeu01.chaos</groupId>
            <artifactId>test-support</artifactId>
            <scope>test</scope>
        </dependency>
```

`application.properties`:

```properties
quarkus.application.name=api
quarkus.http.port=8080
quarkus.http.test-port=0
quarkus.devservices.enabled=false
# ADR 0002: Act 4 exhausts exactly this pool.
quarkus.thread-pool.max-threads=16
quarkus.thread-pool.queue-size=32

quarkus.rest-client.profile.url=http://profile:8080
quarkus.rest-client.catalog.url=http://catalog:8080
quarkus.rest-client.recommendations.url=http://recommendations:8080
quarkus.rest-client.playback.url=http://playback:8080

quarkus.sqs.endpoint-override=http://queue:9324
quarkus.sqs.aws.region=us-east-1
quarkus.sqs.aws.credentials.type=static
quarkus.sqs.aws.credentials.static-provider.access-key-id=test
quarkus.sqs.aws.credentials.static-provider.secret-access-key=test
quarkus.sqs.sync-client.type=url
ratings.queue-name=ratings
```

- [ ] **Step 2: Write the failing test**

```java
package io.github.amadeu01.chaos.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.amadeu01.chaos.testsupport.ElasticMqResource;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
@WithTestResource(ElasticMqResource.class)
@WithTestResource(value = WireMockResource.class, initArgs = @ResourceArg(name = WireMockResource.URL_PROPERTIES,
        value = "quarkus.rest-client.profile.url,quarkus.rest-client.catalog.url,"
                + "quarkus.rest-client.recommendations.url,quarkus.rest-client.playback.url"))
class ApiResourceTest {

    @InjectWireMock
    WireMockServer wireMock;

    @Inject
    SqsClient sqs;

    @BeforeEach
    void stubHealthyDependencies() {
        wireMock.resetAll();
        wireMock.stubFor(get("/profiles/v-1").willReturn(okJson(
                "{\"viewerId\":\"v-1\",\"displayName\":\"Viewer v-1\",\"maturityRating\":\"ADULT\"}")));
        wireMock.stubFor(get("/titles").willReturn(okJson(titlesJson(25))));
        wireMock.stubFor(get("/recommendations/v-1").willReturn(okJson(
                "{\"viewerId\":\"v-1\",\"titleIds\":[\"t03\",\"t09\"]}")));
        wireMock.stubFor(post("/playbacks").willReturn(okJson(
                "{\"streamUrl\":\"https://stream.example/titles/t03?license=lic-1\",\"licenseId\":\"lic-1\"}")));
    }

    @Test
    void homeAggregatesProfileCatalogAndRecommendations() {
        given().when().get("/home?viewerId=v-1")
                .then().statusCode(200)
                .body("profile.viewerId", equalTo("v-1"))
                .body("titles", hasSize(HomeResource.TITLES_ON_HOME))
                .body("recommendedTitleIds", contains("t03", "t09"));
    }

    @Test
    void homeWithoutViewerIsBadRequestAndCallsNothing() {
        given().when().get("/home?viewerId=%20").then().statusCode(400);
        given().when().get("/home").then().statusCode(400);
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    /** Naive baseline for Act 2: without a Fallback, a Non-critical Dependency breaks the whole home page. */
    @Test
    void homeFailsWhenRecommendationsFails() {
        wireMock.stubFor(get("/recommendations/v-1").willReturn(aResponse().withStatus(503)));
        given().when().get("/home?viewerId=v-1").then().statusCode(greaterThanOrEqualTo(500));
    }

    @Test
    void playStartsPlayback() {
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t03\"}")
                .when().post("/play")
                .then().statusCode(200)
                .body("licenseId", equalTo("lic-1"));
    }

    @Test
    void playWithoutTitleIsBadRequest() {
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\"}")
                .when().post("/play").then().statusCode(400);
    }

    @Test
    void ratingIsPublishedToQueue() {
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t03\",\"stars\":4}")
                .when().post("/ratings")
                .then().statusCode(202);

        String queueUrl = sqs.getQueueUrl(request -> request.queueName("ratings")).queueUrl();
        List<Message> messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl)
                .maxNumberOfMessages(10).waitTimeSeconds(2)).messages();
        assertThat(messages).extracting(Message::body).anyMatch(body -> body.contains("\"titleId\":\"t03\""));
    }

    @Test
    void ratingWithStarsOutOfRangeIsBadRequest() {
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t03\",\"stars\":6}")
                .when().post("/ratings").then().statusCode(400);
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t03\",\"stars\":0}")
                .when().post("/ratings").then().statusCode(400);
    }

    private static String titlesJson(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(n -> "{\"id\":\"t%02d\",\"name\":\"Title %d\",\"genre\":\"Drama\",\"year\":2000}".formatted(n, n))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl libs/test-support,platform/api install`
Expected: FAIL to compile, `HomeResource` missing.

- [ ] **Step 4: Implement the records**

```java
package io.github.amadeu01.chaos.api;

public record Profile(String viewerId, String displayName, String maturityRating) {
}
```

```java
package io.github.amadeu01.chaos.api;

public record Title(String id, String name, String genre, int year) {
}
```

```java
package io.github.amadeu01.chaos.api;

import java.util.List;

public record Recommendations(String viewerId, List<String> titleIds) {
}
```

```java
package io.github.amadeu01.chaos.api;

public record PlayRequest(String viewerId, String titleId) {
}
```

```java
package io.github.amadeu01.chaos.api;

public record Playback(String streamUrl, String licenseId) {
}
```

```java
package io.github.amadeu01.chaos.api;

public record Rating(String viewerId, String titleId, int stars) {
}
```

```java
package io.github.amadeu01.chaos.api;

import java.util.List;

public record Home(Profile profile, List<Title> titles, List<String> recommendedTitleIds) {
}
```

- [ ] **Step 5: Implement the REST clients**

```java
package io.github.amadeu01.chaos.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Dependency api -> profile (Non-critical). */
@RegisterRestClient(configKey = "profile")
@Path("/profiles")
public interface ProfileClient {

    @GET
    @Path("/{viewerId}")
    Profile get(@PathParam("viewerId") String viewerId);
}
```

```java
package io.github.amadeu01.chaos.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/** Dependency api -> catalog (Critical). */
@RegisterRestClient(configKey = "catalog")
@Path("/titles")
public interface CatalogClient {

    @GET
    List<Title> all();
}
```

```java
package io.github.amadeu01.chaos.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Dependency api -> recommendations (Non-critical). */
@RegisterRestClient(configKey = "recommendations")
@Path("/recommendations")
public interface RecommendationsClient {

    @GET
    @Path("/{viewerId}")
    Recommendations get(@PathParam("viewerId") String viewerId);
}
```

```java
package io.github.amadeu01.chaos.api;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Dependency api -> playback (Critical). */
@RegisterRestClient(configKey = "playback")
@Path("/playbacks")
public interface PlaybackClient {

    @POST
    Playback start(PlayRequest request);
}
```

- [ ] **Step 6: Implement the resources and the publisher**

```java
package io.github.amadeu01.chaos.api;

import jakarta.ws.rs.BadRequestException;

/** Input checks done before any Dependency is called, so bad input is a 400 and never a downstream error. */
final class Requests {

    private Requests() {
    }

    static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(name + " is required");
        }
    }
}
```

```java
package io.github.amadeu01.chaos.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;

/** First half of the Viewer journey: everything a Viewer needs before pressing play. */
@Path("/home")
public class HomeResource {

    static final int TITLES_ON_HOME = 20;

    @Inject
    @RestClient
    ProfileClient profiles;

    @Inject
    @RestClient
    CatalogClient catalog;

    @Inject
    @RestClient
    RecommendationsClient recommendations;

    @GET
    public Home home(@QueryParam("viewerId") String viewerId) {
        Requests.requireNonBlank(viewerId, "viewerId");
        Profile profile = profiles.get(viewerId);
        List<Title> titles = catalog.all().stream().limit(TITLES_ON_HOME).toList();
        List<String> recommended = recommendations.get(viewerId).titleIds();
        return new Home(profile, titles, recommended);
    }
}
```

```java
package io.github.amadeu01.chaos.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/** Second half of the Viewer journey: a successful answer here is a Playback Start. */
@Path("/play")
public class PlayResource {

    @Inject
    @RestClient
    PlaybackClient playback;

    @POST
    public Playback play(PlayRequest request) {
        if (request == null) {
            throw new BadRequestException("body is required");
        }
        Requests.requireNonBlank(request.viewerId(), "viewerId");
        Requests.requireNonBlank(request.titleId(), "titleId");
        return playback.start(request);
    }
}
```

```java
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
```

```java
package io.github.amadeu01.chaos.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/ratings")
public class RatingResource {

    static final int MIN_STARS = 1;
    static final int MAX_STARS = 5;

    @Inject
    RatingPublisher publisher;

    @POST
    public Response rate(Rating rating) {
        if (rating == null) {
            throw new BadRequestException("body is required");
        }
        Requests.requireNonBlank(rating.viewerId(), "viewerId");
        Requests.requireNonBlank(rating.titleId(), "titleId");
        if (rating.stars() < MIN_STARS || rating.stars() > MAX_STARS) {
            throw new BadRequestException("stars must be between " + MIN_STARS + " and " + MAX_STARS);
        }
        publisher.publish(rating);
        return Response.accepted().build();
    }
}
```

- [ ] **Step 7: Run to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl libs/test-support,platform/api install`
Expected: PASS (7 tests).

- [ ] **Step 8: Commit and push**

```bash
git add pom.xml platform/api
git commit -F - <<'EOF'
:sparkles: Add api Service composing the Viewer journey

api is where the Viewer journey comes together: /home calls profile,
catalog and recommendations; /play calls playback; /ratings drops a
message on the SQS queue. It is the Service that suffers most in the
lecture, because every Dependency failure lands on its 16 worker threads.

homeFailsWhenRecommendationsFails documents today's naive behaviour on
purpose: it is the Cascading Failure Act 2 shows, and slice 4 replaces it
with a Fallback.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 9: edge-gateway

**Files:**
- Create: `platform/edge-gateway/pom.xml`
- Create: `platform/edge-gateway/src/main/resources/application.properties`
- Create: `platform/edge-gateway/src/main/java/io/github/amadeu01/chaos/edgegateway/{ApiClient,EdgeResource}.java`
- Test: `platform/edge-gateway/src/test/java/io/github/amadeu01/chaos/edgegateway/EdgeResourceTest.java`
- Modify: `pom.xml` (`<module>platform/edge-gateway</module>`)

**Interfaces:**
- Consumes: api `GET /home`, `POST /play`, `POST /ratings` (Task 8).
- Produces: the same three routes, relaying api's status and body; `502 {"error":"api unreachable"}` when api cannot be reached. Slice 5 adds Control/Experiment Group assignment here.

- [ ] **Step 1: POM and properties**

`platform/edge-gateway/pom.xml`: profile's POM with `<artifactId>edge-gateway</artifactId>`, `<name>Platform: edge-gateway</name>`, plus `quarkus-rest-client-jackson` and `test-support` (test scope) as in Task 5.

`application.properties`:

```properties
quarkus.application.name=edge-gateway
quarkus.http.port=8080
quarkus.http.test-port=0
quarkus.devservices.enabled=false
quarkus.thread-pool.max-threads=16
quarkus.thread-pool.queue-size=32
quarkus.rest-client.api.url=http://api:8080
# A gateway relays api's status codes instead of turning every error into an exception.
quarkus.rest-client.disable-default-mapper=true
```

- [ ] **Step 2: Write the failing test**

```java
package io.github.amadeu01.chaos.edgegateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@WithTestResource(value = WireMockResource.class, initArgs =
        @ResourceArg(name = WireMockResource.URL_PROPERTIES, value = "quarkus.rest-client.api.url"))
class EdgeResourceTest {

    @InjectWireMock
    WireMockServer wireMock;

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void relaysHome() {
        wireMock.stubFor(get(urlPathEqualTo("/home")).willReturn(okJson("{\"recommendedTitleIds\":[\"t03\"]}")));
        given().when().get("/home?viewerId=v-1")
                .then().statusCode(200)
                .body("recommendedTitleIds[0]", equalTo("t03"));
    }

    @Test
    void relaysPlayBody() {
        wireMock.stubFor(post("/play").willReturn(okJson("{\"licenseId\":\"lic-1\"}")));
        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t03\"}")
                .when().post("/play")
                .then().statusCode(200).body("licenseId", equalTo("lic-1"));
        wireMock.verify(postRequestedFor(urlEqualTo("/play"))
                .withRequestBody(equalToJson("{\"viewerId\":\"v-1\",\"titleId\":\"t03\"}")));
    }

    @Test
    void keepsClientErrorStatus() {
        wireMock.stubFor(get(urlPathEqualTo("/home")).willReturn(aResponse().withStatus(400)));
        given().when().get("/home").then().statusCode(400);
    }

    @Test
    void keepsServerErrorStatus() {
        wireMock.stubFor(post("/ratings").willReturn(aResponse().withStatus(503)));
        given().contentType(ContentType.JSON).body("{}").when().post("/ratings").then().statusCode(503);
    }

    @Test
    void answersBadGatewayWhenApiIsUnreachable() {
        wireMock.stubFor(get(urlPathEqualTo("/home")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        given().when().get("/home?viewerId=v-1")
                .then().statusCode(502)
                .body("error", equalTo("api unreachable"));
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl libs/test-support,platform/edge-gateway install`
Expected: FAIL, routes return `404`.

- [ ] **Step 4: Implement**

```java
package io.github.amadeu01.chaos.edgegateway;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** Dependency edge-gateway -> api (Critical). Returns raw Responses so the gateway can relay any status. */
@RegisterRestClient(configKey = "api")
public interface ApiClient {

    @GET
    @Path("/home")
    Response home(@QueryParam("viewerId") String viewerId);

    @POST
    @Path("/play")
    @Consumes(MediaType.APPLICATION_JSON)
    Response play(String body);

    @POST
    @Path("/ratings")
    @Consumes(MediaType.APPLICATION_JSON)
    Response rate(String body);
}
```

```java
package io.github.amadeu01.chaos.edgegateway;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** The front door, like Netflix's Zuul: every Viewer request enters the platform here. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class EdgeResource {

    @Inject
    @RestClient
    ApiClient api;

    @GET
    @Path("home")
    public Response home(@QueryParam("viewerId") String viewerId) {
        return relay(api.home(viewerId));
    }

    @POST
    @Path("play")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response play(String body) {
        return relay(api.play(body));
    }

    @POST
    @Path("ratings")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response rate(String body) {
        return relay(api.rate(body));
    }

    @ServerExceptionMapper
    public Response apiUnreachable(ProcessingException e) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity("{\"error\":\"api unreachable\"}")
                .type(MediaType.APPLICATION_JSON)
                .build();
    }

    private static Response relay(Response upstream) {
        try (upstream) {
            String body = upstream.hasEntity() ? upstream.readEntity(String.class) : null;
            return Response.status(upstream.getStatus()).entity(body).type(MediaType.APPLICATION_JSON).build();
        }
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl libs/test-support,platform/edge-gateway install`
Expected: PASS (5 tests). If `keepsClientErrorStatus` gets `500`, `quarkus.rest-client.disable-default-mapper` was not picked up: check the property name in the Quarkus REST Client reference for 3.40 and fix it there.

- [ ] **Step 6: Commit and push**

```bash
git add pom.xml platform/edge-gateway
git commit -F - <<'EOF'
:sparkles: Add edge-gateway in front of api

Every Viewer request enters through edge-gateway, as every Netflix request
enters through Zuul. Today it only relays; slice 5 makes it assign Viewers
to the Control and Experiment Groups, the same place Netflix's ChAP tags
traffic. A gateway keeps api's status codes and answers 502 when api is
gone, so errors stay honest at the edge.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 10: ratings-worker

**Files:**
- Create: `platform/ratings-worker/pom.xml`
- Create: `platform/ratings-worker/src/main/resources/application.properties`
- Create: `platform/ratings-worker/src/main/java/io/github/amadeu01/chaos/ratingsworker/{Rating,RatingConsumer}.java`
- Test: `platform/ratings-worker/src/test/java/io/github/amadeu01/chaos/ratingsworker/RatingConsumerTest.java`
- Modify: `pom.xml` (`<module>platform/ratings-worker</module>`)

**Interfaces:**
- Consumes: SQS queue `ratings` with bodies `{"viewerId","titleId","stars"}` (Task 8); DynamoDB table `ratings`.
- Produces: one item per message in table `ratings`: `id` (message id), `viewerId`, `titleId`, `stars` (number). Malformed messages are logged and deleted.

- [ ] **Step 1: POM and properties**

`platform/ratings-worker/pom.xml`: profile's POM with `<artifactId>ratings-worker</artifactId>`, `<name>Platform: ratings-worker</name>`, plus:

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-scheduler</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkiverse.amazonservices</groupId>
            <artifactId>quarkus-amazon-sqs</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkiverse.amazonservices</groupId>
            <artifactId>quarkus-amazon-dynamodb</artifactId>
        </dependency>
        <dependency>
            <groupId>software.amazon.awssdk</groupId>
            <artifactId>url-connection-client</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.amadeu01.chaos</groupId>
            <artifactId>test-support</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <scope>test</scope>
        </dependency>
```

`application.properties`:

```properties
quarkus.application.name=ratings-worker
quarkus.http.port=8080
quarkus.http.test-port=0
quarkus.devservices.enabled=false
quarkus.thread-pool.max-threads=16
quarkus.thread-pool.queue-size=32

quarkus.sqs.endpoint-override=http://queue:9324
quarkus.sqs.aws.region=us-east-1
quarkus.sqs.aws.credentials.type=static
quarkus.sqs.aws.credentials.static-provider.access-key-id=test
quarkus.sqs.aws.credentials.static-provider.secret-access-key=test
quarkus.sqs.sync-client.type=url

quarkus.dynamodb.endpoint-override=http://store:8000
quarkus.dynamodb.aws.region=us-east-1
quarkus.dynamodb.aws.credentials.type=static
quarkus.dynamodb.aws.credentials.static-provider.access-key-id=test
quarkus.dynamodb.aws.credentials.static-provider.secret-access-key=test
quarkus.dynamodb.sync-client.type=url

ratings.queue-name=ratings
```

- [ ] **Step 2: Write the failing test**

```java
package io.github.amadeu01.chaos.ratingsworker;

import io.github.amadeu01.chaos.testsupport.DynamoDbLocalResource;
import io.github.amadeu01.chaos.testsupport.ElasticMqResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
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
    DynamoDbClient dynamo;

    @Test
    void storesRatingFromQueue() {
        send("{\"viewerId\":\"v-1\",\"titleId\":\"t11\",\"stars\":5}");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(storedRatings()).anySatisfy(item -> {
                    assertThat(item.get("titleId").s()).isEqualTo("t11");
                    assertThat(item.get("stars").n()).isEqualTo("5");
                }));
    }

    @Test
    void malformedMessageIsDroppedAndDoesNotBlockTheQueue() {
        send("not json");
        send("{\"viewerId\":\"v-2\",\"titleId\":\"t12\",\"stars\":3}");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(storedRatings()).anySatisfy(item -> assertThat(item.get("titleId").s()).isEqualTo("t12"));
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
```

- [ ] **Step 3: Run to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl libs/test-support,platform/ratings-worker install`
Expected: FAIL, both tests time out after 10 s because nothing consumes the queue.

- [ ] **Step 4: Implement**

```java
package io.github.amadeu01.chaos.ratingsworker;

public record Rating(String viewerId, String titleId, int stars) {
}
```

```java
package io.github.amadeu01.chaos.ratingsworker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

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
    private static final Logger LOG = Logger.getLogger(RatingConsumer.class);

    @Inject
    SqsClient sqs;

    @Inject
    DynamoDbClient dynamo;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "ratings.queue-name")
    String queueName;

    private volatile String queueUrl;

    @Scheduled(every = "1s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        for (Message message : sqs.receiveMessage(request -> request.queueUrl(queueUrl())
                .maxNumberOfMessages(BATCH_SIZE)).messages()) {
            handle(message);
            sqs.deleteMessage(request -> request.queueUrl(queueUrl()).receiptHandle(message.receiptHandle()));
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
        dynamo.putItem(request -> request.tableName(TABLE).item(Map.of(
                "id", AttributeValue.fromS(message.messageId()),
                "viewerId", AttributeValue.fromS(rating.viewerId()),
                "titleId", AttributeValue.fromS(rating.titleId()),
                "stars", AttributeValue.fromN(Integer.toString(rating.stars())))));
    }

    private String queueUrl() {
        if (queueUrl == null) {
            queueUrl = sqs.getQueueUrl(request -> request.queueName(queueName)).queueUrl();
        }
        return queueUrl;
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl libs/test-support,platform/ratings-worker install`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit and push**

```bash
git add pom.xml platform/ratings-worker
git commit -F - <<'EOF'
:sparkles: Add ratings-worker consuming the ratings queue

Ratings travel through SQS instead of a direct call, so the worker can be
down without any Viewer noticing: messages wait, SPS holds. That is the
point of Experiment 4. A message is deleted only after it is stored, and a
malformed "poison" message is dropped so it cannot block the queue forever.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 11: viewer-simulator and the SPS log

**Files:**
- Create: `control-plane/viewer-simulator/pom.xml`
- Create: `control-plane/viewer-simulator/src/main/resources/application.properties`
- Create: `control-plane/viewer-simulator/src/main/java/io/github/amadeu01/chaos/viewersimulator/{SpsCounter,ViewerJourney,ViewerSimulator,ViewerRateResource}.java`
- Test: `control-plane/viewer-simulator/src/test/java/io/github/amadeu01/chaos/viewersimulator/{SpsCounterTest,ViewerJourneyTest,ViewerRateResourceTest}.java`
- Modify: `pom.xml` (`<module>control-plane/viewer-simulator</module>`)

**Interfaces:**
- Consumes: edge-gateway `GET /home?viewerId=`, `POST /play`, `POST /ratings` (Task 9).
- Produces:
  - `SpsCounter.recordStart(long epochMillis)`, `SpsCounter.startsIn(long epochSecond)` (slice 2 moves SPS to telemetry; this counter stays for the log).
  - `ViewerJourney.run(String viewerId)` returns `CompletableFuture<Boolean>` (true = Playback Start).
  - `GET /viewers-per-second` returns `{"viewersPerSecond": n}`; `PUT /viewers-per-second?value=n` with `0 <= n <= 500`, else `400`.
  - Log line every second: `SPS total=<n> viewersPerSecond=<rate>`.

viewer-simulator is control plane, not a platform Service, so ADR 0002 does not apply: it uses the JDK `HttpClient` asynchronously so it never becomes the bottleneck it is measuring.

- [ ] **Step 1: POM and properties**

`control-plane/viewer-simulator/pom.xml`: profile's POM (with `<relativePath>../../pom.xml</relativePath>`) with `<artifactId>viewer-simulator</artifactId>`, `<name>Control plane: viewer-simulator</name>`, plus:

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-scheduler</artifactId>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <scope>test</scope>
        </dependency>
```

`application.properties`:

```properties
quarkus.application.name=viewer-simulator
quarkus.http.port=8080
quarkus.http.test-port=0
quarkus.devservices.enabled=false
simulator.target-url=http://edge-gateway:8080
simulator.viewers-per-second=50
simulator.enabled=true
%test.simulator.enabled=false
```

- [ ] **Step 2: Write the failing tests**

```java
package io.github.amadeu01.chaos.viewersimulator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpsCounterTest {

    private final SpsCounter counter = new SpsCounter();

    @Test
    void countsStartsWithinTheSameSecond() {
        counter.recordStart(10_000);
        counter.recordStart(10_999);
        assertThat(counter.startsIn(10)).isEqualTo(2);
    }

    @Test
    void keepsSecondsApart() {
        counter.recordStart(10_500);
        counter.recordStart(11_500);
        assertThat(counter.startsIn(10)).isEqualTo(1);
        assertThat(counter.startsIn(11)).isEqualTo(1);
    }

    @Test
    void secondWithoutStartsIsZero() {
        assertThat(counter.startsIn(42)).isZero();
    }

    @Test
    void reusedSlotForgetsOlderSecond() {
        counter.recordStart(10_000);
        counter.recordStart((10 + SpsCounter.SECONDS_KEPT) * 1000L);
        assertThat(counter.startsIn(10)).isZero();
        assertThat(counter.startsIn(10 + SpsCounter.SECONDS_KEPT)).isEqualTo(1);
    }
}
```

```java
package io.github.amadeu01.chaos.viewersimulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.accepted;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class ViewerJourneyTest {

    private WireMockServer edge;
    private ViewerJourney journey;

    @BeforeEach
    void start() {
        edge = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        edge.start();
        journey = new ViewerJourney(HttpClient.newHttpClient(), new ObjectMapper(), URI.create(edge.baseUrl()));
        edge.stubFor(post("/play").willReturn(okJson("{\"licenseId\":\"lic-1\"}")));
        edge.stubFor(post("/ratings").willReturn(accepted()));
    }

    @AfterEach
    void stop() {
        edge.stop();
    }

    @Test
    void completedJourneyIsAPlaybackStart() {
        edge.stubFor(get(urlPathEqualTo("/home")).willReturn(okJson(
                "{\"titles\":[{\"id\":\"t01\"}],\"recommendedTitleIds\":[\"t09\"]}")));

        assertThat(journey.run("v-1").join()).isTrue();
        edge.verify(postRequestedFor(urlEqualTo("/play")).withRequestBody(matchingJsonPath("$.titleId", equalTo("t09"))));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> edge.verify(postRequestedFor(urlEqualTo("/ratings"))));
    }

    @Test
    void picksFirstTitleWhenThereAreNoRecommendations() {
        edge.stubFor(get(urlPathEqualTo("/home")).willReturn(okJson(
                "{\"titles\":[{\"id\":\"t01\"}],\"recommendedTitleIds\":[]}")));

        assertThat(journey.run("v-1").join()).isTrue();
        edge.verify(postRequestedFor(urlEqualTo("/play")).withRequestBody(matchingJsonPath("$.titleId", equalTo("t01"))));
    }

    @Test
    void failedHomeMeansNoStartAndNoPlay() {
        edge.stubFor(get(urlPathEqualTo("/home")).willReturn(aResponse().withStatus(500)));

        assertThat(journey.run("v-1").join()).isFalse();
        edge.verify(0, postRequestedFor(urlEqualTo("/play")));
    }

    @Test
    void failedPlayMeansNoStart() {
        edge.stubFor(get(urlPathEqualTo("/home")).willReturn(okJson(
                "{\"titles\":[{\"id\":\"t01\"}],\"recommendedTitleIds\":[\"t09\"]}")));
        edge.stubFor(post("/play").willReturn(aResponse().withStatus(503)));

        assertThat(journey.run("v-1").join()).isFalse();
    }

    @Test
    void malformedHomeMeansNoStart() {
        edge.stubFor(get(urlPathEqualTo("/home")).willReturn(okJson("not json")));

        assertThat(journey.run("v-1").join()).isFalse();
    }

    @Test
    void unreachableEdgeMeansNoStart() {
        edge.stop();

        assertThat(journey.run("v-1").join()).isFalse();
    }

    private static com.github.tomakehurst.wiremock.matching.StringValuePattern equalTo(String value) {
        return com.github.tomakehurst.wiremock.client.WireMock.equalTo(value);
    }
}
```

```java
package io.github.amadeu01.chaos.viewersimulator;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ViewerRateResourceTest {

    @Test
    void changesViewersPerSecond() {
        given().when().put("/viewers-per-second?value=80").then().statusCode(204);
        given().when().get("/viewers-per-second").then().statusCode(200).body("viewersPerSecond", equalTo(80));
    }

    @Test
    void zeroPausesTraffic() {
        given().when().put("/viewers-per-second?value=0").then().statusCode(204);
        given().when().get("/viewers-per-second").then().body("viewersPerSecond", equalTo(0));
    }

    @Test
    void rejectsRatesOutsideBounds() {
        given().when().put("/viewers-per-second?value=-1").then().statusCode(400);
        given().when().put("/viewers-per-second?value=501").then().statusCode(400);
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl control-plane/viewer-simulator test`
Expected: FAIL to compile, `SpsCounter` missing.

- [ ] **Step 4: Implement the counter and the journey**

```java
package io.github.amadeu01.chaos.viewersimulator;

/** Playback Starts per wall-clock second, for the last {@value #SECONDS_KEPT} seconds. */
public final class SpsCounter {

    static final int SECONDS_KEPT = 60;

    private final long[] seconds = new long[SECONDS_KEPT];
    private final int[] counts = new int[SECONDS_KEPT];

    public SpsCounter() {
        java.util.Arrays.fill(seconds, -1);
    }

    public synchronized void recordStart(long epochMillis) {
        long second = epochMillis / 1000;
        int slot = (int) (second % SECONDS_KEPT);
        if (seconds[slot] != second) {
            seconds[slot] = second;
            counts[slot] = 0;
        }
        counts[slot]++;
    }

    public synchronized int startsIn(long epochSecond) {
        int slot = (int) (epochSecond % SECONDS_KEPT);
        return seconds[slot] == epochSecond ? counts[slot] : 0;
    }
}
```

```java
package io.github.amadeu01.chaos.viewersimulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One Viewer: load the home page, pick a title, press play, then rate it.
 * The journey is a Playback Start only if both the home page and play succeed.
 */
public class ViewerJourney {

    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI edge;

    public ViewerJourney(HttpClient http, ObjectMapper mapper, URI edge) {
        this.http = http;
        this.mapper = mapper;
        this.edge = edge;
    }

    public CompletableFuture<Boolean> run(String viewerId) {
        return send(HttpRequest.newBuilder(edge.resolve("/home?viewerId=" + viewerId)).GET())
                .thenCompose(home -> {
                    Optional<String> title = isSuccess(home) ? pickTitle(home.body()) : Optional.empty();
                    if (title.isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return send(postJson("/play", "{\"viewerId\":\"%s\",\"titleId\":\"%s\"}".formatted(viewerId, title.get())))
                            .thenApply(play -> {
                                if (!isSuccess(play)) {
                                    return false;
                                }
                                int stars = ThreadLocalRandom.current().nextInt(1, 6);
                                send(postJson("/ratings", "{\"viewerId\":\"%s\",\"titleId\":\"%s\",\"stars\":%d}"
                                        .formatted(viewerId, title.get(), stars)));
                                return true;
                            });
                })
                .exceptionally(failure -> false);
    }

    Optional<String> pickTitle(String homeJson) {
        try {
            JsonNode home = mapper.readTree(homeJson);
            JsonNode recommended = home.path("recommendedTitleIds");
            if (recommended.isArray() && !recommended.isEmpty()) {
                return Optional.of(recommended.get(0).asText());
            }
            JsonNode titles = home.path("titles");
            if (titles.isArray() && !titles.isEmpty()) {
                return Optional.of(titles.get(0).path("id").asText());
            }
            return Optional.empty();
        } catch (Exception malformed) {
            return Optional.empty();
        }
    }

    private CompletableFuture<HttpResponse<String>> send(HttpRequest.Builder request) {
        return http.sendAsync(request.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder postJson(String path, String json) {
        return HttpRequest.newBuilder(edge.resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
    }

    private static boolean isSuccess(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }
}
```

- [ ] **Step 5: Implement the simulator and the rate resource**

```java
package io.github.amadeu01.chaos.viewersimulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Launches Viewers at a steady rate and logs SPS once per second. */
@ApplicationScoped
public class ViewerSimulator {

    static final int MAX_VIEWERS_PER_SECOND = 500;
    static final int TICKS_PER_SECOND = 10;
    private static final Logger LOG = Logger.getLogger(ViewerSimulator.class);

    @ConfigProperty(name = "simulator.target-url")
    URI targetUrl;

    @ConfigProperty(name = "simulator.enabled")
    boolean enabled;

    @Inject
    ObjectMapper mapper;

    private final SpsCounter sps = new SpsCounter();
    private ScheduledExecutorService ticker;
    private ViewerJourney journey;
    private volatile int viewersPerSecond;
    private double viewerBudget;

    @Inject
    ViewerSimulator(@ConfigProperty(name = "simulator.viewers-per-second") int initialViewersPerSecond) {
        this.viewersPerSecond = initialViewersPerSecond;
    }

    void onStart(@Observes StartupEvent event) {
        if (!enabled) {
            return;
        }
        journey = new ViewerJourney(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), mapper, targetUrl);
        ticker = Executors.newSingleThreadScheduledExecutor();
        ticker.scheduleAtFixedRate(this::launchViewers, 0, 1000 / TICKS_PER_SECOND, TimeUnit.MILLISECONDS);
        LOG.infof("Simulating %d Viewers/s against %s", viewersPerSecond, targetUrl);
    }

    void onStop(@Observes ShutdownEvent event) {
        if (ticker != null) {
            ticker.shutdownNow();
        }
    }

    private void launchViewers() {
        viewerBudget += viewersPerSecond / (double) TICKS_PER_SECOND;
        int launches = (int) viewerBudget;
        viewerBudget -= launches;
        for (int i = 0; i < launches; i++) {
            String viewerId = "v-" + ThreadLocalRandom.current().nextInt(10_000);
            journey.run(viewerId).thenAccept(started -> {
                if (started) {
                    sps.recordStart(System.currentTimeMillis());
                }
            });
        }
    }

    @Scheduled(every = "1s")
    void reportSps() {
        if (enabled) {
            long lastSecond = System.currentTimeMillis() / 1000 - 1;
            LOG.infof("SPS total=%d viewersPerSecond=%d", sps.startsIn(lastSecond), viewersPerSecond);
        }
    }

    public int viewersPerSecond() {
        return viewersPerSecond;
    }

    public void setViewersPerSecond(int value) {
        if (value < 0 || value > MAX_VIEWERS_PER_SECOND) {
            throw new IllegalArgumentException("Viewers/s must be between 0 and " + MAX_VIEWERS_PER_SECOND);
        }
        viewersPerSecond = value;
    }
}
```

```java
package io.github.amadeu01.chaos.viewersimulator;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import java.util.Map;

@Path("/viewers-per-second")
public class ViewerRateResource {

    @Inject
    ViewerSimulator simulator;

    @GET
    public Map<String, Integer> get() {
        return Map.of("viewersPerSecond", simulator.viewersPerSecond());
    }

    @PUT
    public void set(@QueryParam("value") int value) {
        try {
            simulator.setViewersPerSecond(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}
```

- [ ] **Step 6: Run to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl control-plane/viewer-simulator test`
Expected: PASS (13 tests).

- [ ] **Step 7: Run the whole build**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q verify && echo ALL GREEN`
Expected: `ALL GREEN`.

- [ ] **Step 8: Commit and push**

```bash
git add pom.xml control-plane/viewer-simulator
git commit -F - <<'EOF'
:sparkles: Add viewer-simulator and log SPS

SPS, Starts Per Second, is the one number that defines Steady State, the
same business metric Netflix watches. viewer-simulator plays Viewers at a
steady rate and counts a Playback Start only when a whole journey
succeeds, so a broken home page lowers SPS even though /play never ran.

It is control plane, not a Fault target, and uses an asynchronous HTTP
client on purpose: the thing doing the measuring must not become the
bottleneck it measures.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 12: Docker Compose, scripts and IntelliJ run configurations

**Files:**
- Create: `docker/quarkus-jvm.Dockerfile`
- Create: `docker-compose.yml`
- Create: `infra/dynamodb/create-tables.sh`
- Create: `infra/elasticmq/elasticmq.conf`
- Create: `scripts/platform-up.sh`, `scripts/platform-down.sh`
- Create: `.run/Platform up.run.xml`, `.run/Platform down.run.xml`, `.run/SPS log.run.xml`

**Interfaces:**
- Consumes: every module's `target/quarkus-app/` from `./mvnw package`.
- Produces: `scripts/platform-up.sh` (build, start, wait until ready); published ports `127.0.0.1:8000` (edge-gateway), `127.0.0.1:8090` (viewer-simulator), `127.0.0.1:9325` (ElasticMQ UI). Compose service names equal module names, plus `store`, `store-init`, `queue`, `cache`.

One Dockerfile is shared by every Quarkus module instead of one generated `Dockerfile.jvm` per module (a small deviation from spec 2.4): the files would be identical, and one copy stays in sync.

- [ ] **Step 1: Write the Dockerfile and infrastructure files**

`docker/quarkus-jvm.Dockerfile`:

```dockerfile
# Shared by every Quarkus module; the build context is the module directory.
FROM eclipse-temurin:25.0.4.1_1-jre-noble
WORKDIR /deployments
COPY target/quarkus-app/lib/ lib/
COPY target/quarkus-app/*.jar ./
COPY target/quarkus-app/app/ app/
COPY target/quarkus-app/quarkus/ quarkus/
EXPOSE 8080
USER 1001
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Djava.util.logging.manager=org.jboss.logmanager.LogManager -jar quarkus-run.jar"]
```

`infra/dynamodb/create-tables.sh`:

```sh
#!/bin/sh
# Provisions DynamoDB tables before any Service starts, the way CloudFormation or Terraform would in AWS.
# Keep the table list in sync with DynamoDbLocalResource.TABLES in libs/test-support.
set -eu
ENDPOINT=http://store:8000
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test AWS_DEFAULT_REGION=us-east-1

until aws dynamodb list-tables --endpoint-url "$ENDPOINT" >/dev/null 2>&1; do
  echo "waiting for store"
  sleep 1
done

for table in titles ratings; do
  if aws dynamodb describe-table --endpoint-url "$ENDPOINT" --table-name "$table" >/dev/null 2>&1; then
    echo "table $table already exists"
  else
    aws dynamodb create-table --endpoint-url "$ENDPOINT" --table-name "$table" \
      --attribute-definitions AttributeName=id,AttributeType=S \
      --key-schema AttributeName=id,KeyType=HASH \
      --billing-mode PAY_PER_REQUEST >/dev/null
    echo "created table $table"
  fi
done
```

Run: `chmod +x infra/dynamodb/create-tables.sh`

`infra/elasticmq/elasticmq.conf`: same content as `libs/test-support/src/main/resources/elasticmq.conf` (Task 2, Step 4).

- [ ] **Step 2: Write `docker-compose.yml`**

```yaml
name: chaos-lecture-demo

# Shared settings for every Quarkus container.
x-quarkus: &quarkus
  restart: unless-stopped
  mem_limit: 256m
  environment:
    JAVA_OPTS: "-XX:MaxRAMPercentage=70"
  healthcheck:
    # The JRE image has bash but no curl, so the probe speaks HTTP over /dev/tcp.
    test: ["CMD", "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080; printf 'GET /q/health/ready HTTP/1.0\\r\\n\\r\\n' >&3; grep -q UP <&3"]
    interval: 5s
    timeout: 3s
    retries: 30
    start_period: 10s

services:
  # --- AWS emulators (ADR 0001): one container per AWS service -------------
  store:
    image: amazon/dynamodb-local:3.3.2
    command: ["-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb"]
    restart: unless-stopped

  store-init:
    image: amazon/aws-cli:2.38.1
    entrypoint: ["/bin/sh", "/init/create-tables.sh"]
    volumes:
      - ./infra/dynamodb:/init:ro
    depends_on:
      - store

  queue:
    image: softwaremill/elasticmq-native:1.7.1
    volumes:
      - ./infra/elasticmq/elasticmq.conf:/opt/elasticmq.conf:ro
    ports:
      - "127.0.0.1:9325:9325"   # ElasticMQ web UI: watch the ratings queue
    restart: unless-stopped

  cache:
    image: redis:8.8.3
    command: ["redis-server", "--save", "", "--appendonly", "no"]
    restart: unless-stopped

  # --- Platform Services ---------------------------------------------------
  profile:
    <<: *quarkus
    build: { context: ./platform/profile, dockerfile: ../../docker/quarkus-jvm.Dockerfile }

  license:
    <<: *quarkus
    build: { context: ./platform/license, dockerfile: ../../docker/quarkus-jvm.Dockerfile }

  playback:
    <<: *quarkus
    build: { context: ./platform/playback, dockerfile: ../../docker/quarkus-jvm.Dockerfile }
    depends_on: [license]

  recommendations:
    <<: *quarkus
    build: { context: ./platform/recommendations, dockerfile: ../../docker/quarkus-jvm.Dockerfile }

  catalog:
    <<: *quarkus
    build: { context: ./platform/catalog, dockerfile: ../../docker/quarkus-jvm.Dockerfile }
    depends_on:
      store-init: { condition: service_completed_successfully }
      cache: { condition: service_started }

  api:
    <<: *quarkus
    build: { context: ./platform/api, dockerfile: ../../docker/quarkus-jvm.Dockerfile }
    depends_on: [profile, catalog, recommendations, playback, queue]

  edge-gateway:
    <<: *quarkus
    build: { context: ./platform/edge-gateway, dockerfile: ../../docker/quarkus-jvm.Dockerfile }
    ports:
      - "127.0.0.1:8000:8080"
    depends_on: [api]

  ratings-worker:
    <<: *quarkus
    build: { context: ./platform/ratings-worker, dockerfile: ../../docker/quarkus-jvm.Dockerfile }
    depends_on:
      store-init: { condition: service_completed_successfully }
      queue: { condition: service_started }

  # --- Control plane -------------------------------------------------------
  viewer-simulator:
    <<: *quarkus
    build: { context: ./control-plane/viewer-simulator, dockerfile: ../../docker/quarkus-jvm.Dockerfile }
    ports:
      - "127.0.0.1:8090:8080"
    depends_on:
      edge-gateway: { condition: service_healthy }
```

- [ ] **Step 3: Write the scripts**

`scripts/platform-up.sh`:

```bash
#!/usr/bin/env bash
# Builds every module and starts the whole platform in Docker Compose, then waits until it is ready.
set -euo pipefail
cd "$(dirname "$0")/.."

./mvnw -q package -DskipTests
docker compose up -d --build

echo "Waiting for edge-gateway and viewer-simulator..."
for url in http://127.0.0.1:8000/q/health/ready http://127.0.0.1:8090/q/health/ready; do
  for _ in $(seq 1 120); do
    curl -fs "$url" >/dev/null 2>&1 && break
    sleep 1
  done
  if ! curl -fs "$url" >/dev/null 2>&1; then
    echo "Timed out waiting for $url" >&2
    docker compose ps
    exit 1
  fi
done

echo "Platform is up. Watch SPS with: docker compose logs -f viewer-simulator"
```

`scripts/platform-down.sh`:

```bash
#!/usr/bin/env bash
# Stops and removes every container of the platform.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down --remove-orphans
```

Run: `chmod +x scripts/*.sh`

- [ ] **Step 4: Start the platform and check Steady State**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk scripts/platform-up.sh`
Expected: ends with `Platform is up.` within about 2 minutes.

Run: `docker compose logs store-init catalog | grep -E "created table|Seeded"`
Expected: `created table titles`, `created table ratings`, `Seeded 50 titles`.

Run: `sleep 15; docker compose logs --since 10s viewer-simulator | grep SPS`
Expected: lines like `SPS total=50 viewersPerSecond=50`, total between 45 and 50.

Run: `curl -s 'http://127.0.0.1:8000/home?viewerId=v-1' | head -c 200`
Expected: JSON starting with `{"profile":{"viewerId":"v-1"`.

Run: `curl -s -X PUT 'http://127.0.0.1:8090/viewers-per-second?value=0'; sleep 3; docker compose logs --since 2s viewer-simulator | grep SPS`
Expected: `SPS total=0 viewersPerSecond=0`. Then restore with `value=50`.

Run: `docker stats --no-stream --format '{{.Name}} {{.MemUsage}}'`
Expected: every Quarkus container under its 256 MiB limit; total under 3 GB.

Open `http://127.0.0.1:9325` and check that the `ratings` queue stays near empty (ratings-worker keeps up).

If SPS stays below 45 at 50 Viewers/s, look at `docker compose logs api` for rejected tasks and raise `quarkus.thread-pool.max-threads` in `platform/api` only as far as needed; record the final number in the commit body.

- [ ] **Step 5: Stop the platform**

Run: `scripts/platform-down.sh`
Expected: all containers removed.

- [ ] **Step 6: Commit and push the Compose setup**

```bash
git add docker docker-compose.yml infra scripts
git commit -F - <<'EOF'
:whale: Run the platform with Docker Compose

One script builds every module and starts 13 containers: 8 platform
Services, viewer-simulator, and one emulator container per AWS service
(DynamoDB Local, ElasticMQ, Redis), so each can fail on its own (ADR 0001).
Tables are created by an init container before any Service starts, the
way infrastructure is provisioned outside the application in AWS.

Every published port binds to 127.0.0.1. Steady State check: about
50 SPS at 50 Viewers/s.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

- [ ] **Step 7: Add IntelliJ run configurations**

`.run/Platform up.run.xml`:

```xml
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="Platform up" type="ShConfigurationType">
    <option name="SCRIPT_TEXT" value="" />
    <option name="INDEPENDENT_SCRIPT_PATH" value="true" />
    <option name="SCRIPT_PATH" value="$PROJECT_DIR$/scripts/platform-up.sh" />
    <option name="SCRIPT_OPTIONS" value="" />
    <option name="INDEPENDENT_SCRIPT_WORKING_DIRECTORY" value="true" />
    <option name="SCRIPT_WORKING_DIRECTORY" value="$PROJECT_DIR$" />
    <option name="INDEPENDENT_INTERPRETER_PATH" value="true" />
    <option name="INTERPRETER_PATH" value="/bin/bash" />
    <option name="INTERPRETER_OPTIONS" value="" />
    <option name="EXECUTE_IN_TERMINAL" value="true" />
    <option name="EXECUTE_SCRIPT_FILE" value="true" />
    <envs />
    <method v="2" />
  </configuration>
</component>
```

`.run/Platform down.run.xml`: the same with `name="Platform down"` and `SCRIPT_PATH` `$PROJECT_DIR$/scripts/platform-down.sh`.

`.run/SPS log.run.xml`:

```xml
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="SPS log" type="ShConfigurationType">
    <option name="SCRIPT_TEXT" value="docker compose logs -f viewer-simulator" />
    <option name="INDEPENDENT_SCRIPT_PATH" value="true" />
    <option name="SCRIPT_PATH" value="" />
    <option name="SCRIPT_OPTIONS" value="" />
    <option name="INDEPENDENT_SCRIPT_WORKING_DIRECTORY" value="true" />
    <option name="SCRIPT_WORKING_DIRECTORY" value="$PROJECT_DIR$" />
    <option name="INDEPENDENT_INTERPRETER_PATH" value="true" />
    <option name="INTERPRETER_PATH" value="/bin/bash" />
    <option name="INTERPRETER_OPTIONS" value="" />
    <option name="EXECUTE_IN_TERMINAL" value="true" />
    <option name="EXECUTE_SCRIPT_FILE" value="false" />
    <envs />
    <method v="2" />
  </configuration>
</component>
```

Check: open the project in IntelliJ; the three configurations appear in the run configuration dropdown. If IntelliJ is not available to the implementer, check that each file is well-formed XML with `python3 -c "import xml.dom.minidom,sys; [xml.dom.minidom.parse(f) for f in sys.argv[1:]]" .run/*.xml`.

- [ ] **Step 8: Commit and push**

```bash
git add .run
git commit -F - <<'EOF'
:wrench: Add IntelliJ run configurations for the platform

Students start, stop and watch the platform from IntelliJ's run menu
instead of memorising commands. The configurations call the same scripts
as the README, so there is one way to start the platform.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 13: README and human docs (English and Brazilian Portuguese)

**Files:**
- Create: `README.md`, `README.pt-BR.md`
- Create: `docs/en/getting-started.md`, `docs/en/architecture.md`, `docs/en/glossary.md`
- Create: `docs/pt-br/getting-started.md`, `docs/pt-br/architecture.md`, `docs/pt-br/glossary.md`
- Modify: `CONTRIBUTING.md` (bilingual rule)

**Interfaces:**
- Produces: the docs skeleton that later slices extend (telemetry, faults-and-experiments, writing-experiments, dashboard, lecture-guide pages arrive with their slices).

- [ ] **Step 1: Write `README.md`**

````markdown
# Chaos Engineering Lecture Demo

**English** | [Português (Brasil)](README.pt-BR.md)

A mini-Netflix you can break on purpose. It is a teaching project for a lecture on chaos engineering, following the practices Netflix published: Chaos Monkey, FIT, ChAP and the *Principles of Chaos Engineering*.

You start a small streaming platform on your laptop, watch its Steady State (SPS, Playback Starts per second), inject Faults, and see whether the failure spreads or is contained.

> **Status:** slice 1 of 6. The platform runs and SPS is printed to the log. The dashboard, Faults, Resilience Policies and Experiments arrive in the next slices.

## Prerequisites

- JDK 25 (IntelliJ can download one: *File > Project Structure > SDK*)
- Docker with Docker Compose
- IntelliJ IDEA (any edition) with the free *Quarkus Tools* plugin, recommended

## Quick start

```bash
scripts/platform-up.sh                    # build, start, wait until ready
docker compose logs -f viewer-simulator   # watch SPS
scripts/platform-down.sh                  # stop everything
```

In IntelliJ, use the run configurations **Platform up**, **SPS log** and **Platform down**.

## Learn more

- [Getting started](docs/en/getting-started.md)
- [Architecture](docs/en/architecture.md)
- [Glossary](docs/en/glossary.md)
- Design decisions: [`docs/adr/`](docs/adr/)

## Security note

Every port is bound to `127.0.0.1`. Later slices add a chaos-controller that can stop containers through the Docker socket, which is equivalent to root access on your machine. Never expose this stack on a shared network.
````

- [ ] **Step 2: Write `README.pt-BR.md`**

````markdown
# Demonstração de Engenharia do Caos

[English](README.md) | **Português (Brasil)**

Uma mini-Netflix que você quebra de propósito. É um projeto didático para uma aula de engenharia do caos, seguindo as práticas que a Netflix publicou: Chaos Monkey, FIT, ChAP e os *Principles of Chaos Engineering*.

Você sobe uma pequena plataforma de streaming no seu notebook, observa o **Steady State** (estado estável) medido por **SPS** (Playback Starts por segundo), injeta **Faults** (falhas) e vê se a falha se espalha ou é contida.

> **Status:** fatia 1 de 6. A plataforma roda e o SPS aparece no log. O dashboard, as Faults, as **Resilience Policies** (políticas de resiliência) e os **Experiments** (experimentos) chegam nas próximas fatias.

## Pré-requisitos

- JDK 25 (o IntelliJ baixa um para você: *File > Project Structure > SDK*)
- Docker com Docker Compose
- IntelliJ IDEA (qualquer edição) com o plugin gratuito *Quarkus Tools*, recomendado

## Início rápido

```bash
scripts/platform-up.sh                    # compila, sobe e espera ficar pronto
docker compose logs -f viewer-simulator   # acompanhe o SPS
scripts/platform-down.sh                  # para tudo
```

No IntelliJ, use as configurações de execução **Platform up**, **SPS log** e **Platform down**.

## Saiba mais

- [Primeiros passos](docs/pt-br/getting-started.md)
- [Arquitetura](docs/pt-br/architecture.md)
- [Glossário](docs/pt-br/glossary.md)
- Decisões de design: [`docs/adr/`](docs/adr/) (em inglês)

## Aviso de segurança

Todas as portas escutam apenas em `127.0.0.1`. As próximas fatias adicionam um chaos-controller que para contêineres pelo socket do Docker, o que equivale a acesso root na sua máquina. Nunca exponha esta stack em uma rede compartilhada.
````

- [ ] **Step 3: Write `docs/en/getting-started.md` and `docs/pt-br/getting-started.md`**

`docs/en/getting-started.md`:

````markdown
# Getting started

## 1. Install

1. Install Docker with Docker Compose.
2. Install IntelliJ IDEA and the *Quarkus Tools* plugin (*Settings > Plugins*).
3. Open the repository folder in IntelliJ. It imports the Maven project on its own.
4. Set the project SDK to JDK 25 (*File > Project Structure > SDK*; *Download JDK* if you have none).

On the command line, point `JAVA_HOME` at a JDK 25. The build stops with a clear message on any older JDK.

## 2. Build and test

```bash
./mvnw verify
```

Service tests start real DynamoDB Local, ElasticMQ and Redis containers through Testcontainers, so Docker must be running.

## 3. Start the platform

```bash
scripts/platform-up.sh
```

The first run downloads images and takes a few minutes; later runs take about a minute. The script returns when edge-gateway and viewer-simulator are ready.

## 4. Watch Steady State

```bash
docker compose logs -f viewer-simulator
```

Every second you see a line like:

```
SPS total=50 viewersPerSecond=50
```

SPS counts Playback Starts: Viewer journeys that loaded the home page and started playback. At 50 Viewers/s a healthy platform shows about 50 SPS.

Change the traffic:

```bash
curl -X PUT 'http://127.0.0.1:8090/viewers-per-second?value=20'
```

Other things to try:

- `curl 'http://127.0.0.1:8000/home?viewerId=v-1'` shows what a Viewer's home page contains.
- `http://127.0.0.1:9325` is the ElasticMQ web UI, where you can watch the `ratings` queue.

## 5. Stop

```bash
scripts/platform-down.sh
```
````

`docs/pt-br/getting-started.md`:

````markdown
# Primeiros passos

## 1. Instalação

1. Instale o Docker com Docker Compose.
2. Instale o IntelliJ IDEA e o plugin *Quarkus Tools* (*Settings > Plugins*).
3. Abra a pasta do repositório no IntelliJ. Ele importa o projeto Maven sozinho.
4. Defina o SDK do projeto como JDK 25 (*File > Project Structure > SDK*; use *Download JDK* se não tiver um).

Na linha de comando, aponte `JAVA_HOME` para um JDK 25. O build para com uma mensagem clara em qualquer JDK mais antigo.

## 2. Compilar e testar

```bash
./mvnw verify
```

Os testes dos **Services** (serviços) sobem contêineres reais de DynamoDB Local, ElasticMQ e Redis via Testcontainers, então o Docker precisa estar rodando.

## 3. Subir a plataforma

```bash
scripts/platform-up.sh
```

A primeira execução baixa as imagens e leva alguns minutos; as seguintes levam cerca de um minuto. O script termina quando o edge-gateway e o viewer-simulator estão prontos.

## 4. Observar o Steady State

```bash
docker compose logs -f viewer-simulator
```

A cada segundo aparece uma linha como:

```
SPS total=50 viewersPerSecond=50
```

O SPS conta **Playback Starts** (inícios de reprodução): jornadas de **Viewers** (espectadores simulados) que carregaram a página inicial e começaram a assistir. Com 50 Viewers/s, uma plataforma saudável mostra cerca de 50 SPS.

Mude o tráfego:

```bash
curl -X PUT 'http://127.0.0.1:8090/viewers-per-second?value=20'
```

Outras coisas para experimentar:

- `curl 'http://127.0.0.1:8000/home?viewerId=v-1'` mostra o conteúdo da página inicial de um Viewer.
- `http://127.0.0.1:9325` é a interface web do ElasticMQ, onde você acompanha a fila `ratings`.

## 5. Parar

```bash
scripts/platform-down.sh
```
````

- [ ] **Step 4: Write `docs/en/architecture.md` and `docs/pt-br/architecture.md`**

`docs/en/architecture.md`:

````markdown
# Architecture

The platform imitates a tiny Netflix. Every box below is its own container, so each one can fail on its own.

```
viewer-simulator ─▶ edge-gateway ─▶ api ─┬─▶ profile
                                         ├─▶ catalog ─┬─▶ cache (Redis)
                                         │            └─▶ store (DynamoDB Local)
                                         ├─▶ recommendations
                                         ├─▶ playback ─▶ license
                                         └─▶ queue (ElasticMQ) ─▶ ratings-worker ─▶ store
```

## The Viewer journey

1. `GET /home`: api asks profile, catalog and recommendations for the home page.
2. The Viewer picks the first recommended title.
3. `POST /play`: api asks playback, which asks license. Success here is a **Playback Start**.
4. `POST /ratings`: api puts a rating on the queue; ratings-worker stores it later.

## Dependencies

| Dependency | Kind | What its loss means |
|---|---|---|
| edge-gateway → api | Critical | no Viewer gets in |
| api → profile | Non-critical | could show an anonymous profile |
| api → catalog | Critical | no titles to show |
| catalog → cache | Non-critical | catalog can read the store directly |
| catalog → store | Critical | no titles at all |
| api → recommendations | Non-critical | could show popular titles |
| api → playback, playback → license | Critical | no Playback Start |
| api → queue | Non-critical | the rating can be dropped |

"Could" is deliberate: in this slice no Dependency has a Fallback yet, so every failure spreads. Later slices add Resilience Policies and let you switch them on and off.

## AWS without an AWS account

Services use the real AWS SDK v2, pointed at local emulators: DynamoDB Local for DynamoDB, ElasticMQ for SQS, and Redis standing in for ElastiCache. Each emulator is its own container. [ADR 0001](../adr/0001-aws-sdk-against-per-service-emulators.md) explains why this is not LocalStack.

## Threads, on purpose

Every platform Service handles requests on a small, bounded pool of 16 worker threads. That is deliberate: it lets a slow Dependency visibly exhaust a Service in Act 4. See [ADR 0002](../adr/0002-fixed-platform-thread-pools.md).

## Repository layout

| Path | Contents |
|---|---|
| `platform/` | the 8 platform Services (Fault targets) |
| `control-plane/` | viewer-simulator, later chaos-controller, telemetry and dashboard |
| `libs/` | shared code; today only test resources |
| `infra/` | emulator configuration and table creation |
| `scripts/`, `.run/` | start and stop, from the shell or IntelliJ |
````

`docs/pt-br/architecture.md`:

````markdown
# Arquitetura

A plataforma imita uma Netflix bem pequena. Cada caixa abaixo é um contêiner próprio, então cada uma pode falhar sozinha.

```
viewer-simulator ─▶ edge-gateway ─▶ api ─┬─▶ profile
                                         ├─▶ catalog ─┬─▶ cache (Redis)
                                         │            └─▶ store (DynamoDB Local)
                                         ├─▶ recommendations
                                         ├─▶ playback ─▶ license
                                         └─▶ queue (ElasticMQ) ─▶ ratings-worker ─▶ store
```

## A jornada do Viewer

1. `GET /home`: o api pede a página inicial para profile, catalog e recommendations.
2. O **Viewer** (espectador simulado) escolhe o primeiro título recomendado.
3. `POST /play`: o api chama o playback, que chama o license. Sucesso aqui é um **Playback Start** (início de reprodução).
4. `POST /ratings`: o api coloca uma avaliação na fila; o ratings-worker grava depois.

## Dependencies

Uma **Dependency** (dependência) é uma chamada de um Service para outro.

| Dependency | Tipo | O que a perda significa |
|---|---|---|
| edge-gateway → api | **Critical** (crítica) | nenhum Viewer entra |
| api → profile | **Non-critical** (não crítica) | poderia mostrar um perfil anônimo |
| api → catalog | Critical | não há títulos para mostrar |
| catalog → cache | Non-critical | o catalog pode ler direto do store |
| catalog → store | Critical | nenhum título |
| api → recommendations | Non-critical | poderia mostrar títulos populares |
| api → playback, playback → license | Critical | nenhum Playback Start |
| api → queue | Non-critical | a avaliação pode ser descartada |

O "poderia" é proposital: nesta fatia nenhuma Dependency tem **Fallback** (resposta degradada) ainda, então toda falha se espalha. As próximas fatias adicionam **Resilience Policies** (políticas de resiliência) que você liga e desliga.

## AWS sem conta na AWS

Os Services usam o AWS SDK v2 de verdade, apontado para emuladores locais: DynamoDB Local no lugar do DynamoDB, ElasticMQ no lugar do SQS e Redis no lugar do ElastiCache. Cada emulador é um contêiner próprio. O [ADR 0001](../adr/0001-aws-sdk-against-per-service-emulators.md) (em inglês) explica por que não usamos LocalStack.

## Threads, de propósito

Cada Service da plataforma atende requisições em um pool pequeno e limitado de 16 threads. É proposital: assim uma Dependency lenta esgota visivelmente um Service no Ato 4. Veja o [ADR 0002](../adr/0002-fixed-platform-thread-pools.md).

## Organização do repositório

| Caminho | Conteúdo |
|---|---|
| `platform/` | os 8 Services da plataforma (alvos de Faults) |
| `control-plane/` | viewer-simulator; depois chaos-controller, telemetry e dashboard |
| `libs/` | código compartilhado; hoje só recursos de teste |
| `infra/` | configuração dos emuladores e criação de tabelas |
| `scripts/`, `.run/` | subir e parar, pelo terminal ou pelo IntelliJ |
````

- [ ] **Step 5: Write `docs/en/glossary.md` and `docs/pt-br/glossary.md`**

`docs/en/glossary.md`:

```markdown
# Glossary

The definitions live in one place, [`CONTEXT.md`](../../CONTEXT.md), shared by humans and coding agents. Code, dashboard labels and docs all use those words, so a term in a class name means exactly what it means in the lecture.

The terms you meet first: **Viewer**, **Playback Start**, **SPS**, **Steady State**, **Service**, **Dependency** (Critical or Non-critical), **Fallback**, **Fault**, **Experiment**, **Blast Radius**.
```

`docs/pt-br/glossary.md`:

```markdown
# Glossário

As definições ficam em um só lugar, [`CONTEXT.md`](../../CONTEXT.md) (em inglês), compartilhado por pessoas e agentes de código. Código, rótulos do dashboard e documentação usam as mesmas palavras, em inglês, para que um termo no nome de uma classe signifique exatamente o mesmo que na aula.

| Termo | Em português |
|---|---|
| Viewer | espectador simulado |
| Playback Start | início de reprodução: jornada completa até o stream licenciado |
| SPS | Playback Starts por segundo |
| Steady State | estado estável, medido por SPS |
| Service | serviço da plataforma |
| Dependency | dependência: chamada de um Service para outro |
| Critical / Non-critical Dependency | dependência crítica / não crítica |
| Fallback | resposta degradada, mas aceitável |
| Resilience Policy | política de resiliência: timeout, retry, circuit breaker, bulkhead e Fallback |
| Cascading Failure | falha em cascata |
| Fault | falha introduzida de propósito |
| Ad-hoc Fault | falha introduzida à mão, fora de um Experiment |
| Experiment | experimento: teste escrito com Hypothesis, Blast Radius e Verdict |
| Hypothesis | hipótese de que o Steady State se mantém |
| Blast Radius | raio de impacto: parte do tráfego que o Experiment pode afetar |
| Control Group / Experiment Group | grupo de controle / grupo do experimento |
| Abort Condition | condição de aborto |
| Verdict | veredito: hipótese mantida, refutada ou experimento abortado |
| Act | ato: um passo roteirizado da aula |
```

- [ ] **Step 6: Add the bilingual rule to `CONTRIBUTING.md`**

Append to `CONTRIBUTING.md`:

```markdown
## Documentation in two languages

Human documentation exists in English (`README.md`, `docs/en/`) and Brazilian Portuguese (`README.pt-BR.md`, `docs/pt-br/`), with the same pages in both. A change to one language updates the other in the same commit. Domain terms from `CONTEXT.md` stay in English in both; Portuguese text puts the term in bold with a gloss on first use, for example "**Blast Radius** (raio de impacto)".
```

- [ ] **Step 7: Check every relative link resolves**

Run:

```bash
python3 - <<'EOF'
import pathlib, re, sys
bad = []
for md in [pathlib.Path('README.md'), pathlib.Path('README.pt-BR.md'), *pathlib.Path('docs/en').glob('*.md'), *pathlib.Path('docs/pt-br').glob('*.md')]:
    for target in re.findall(r'\]\(([^)#]+)\)', md.read_text()):
        if not target.startswith('http') and not (md.parent / target).exists():
            bad.append(f'{md}: {target}')
print('\n'.join(bad) or 'all links resolve')
sys.exit(1 if bad else 0)
EOF
```

Expected: `all links resolve`.

- [ ] **Step 8: Commit and push**

```bash
git add README.md README.pt-BR.md docs/en docs/pt-br CONTRIBUTING.md
git commit -F - <<'EOF'
:memo: Add README and getting-started docs in English and Portuguese

Students can now clone, start the platform and read SPS without help, in
English or Brazilian Portuguese. Domain terms stay in English in both
languages, glossed in Portuguese on first use, so the words in the docs
match the words in the code and on the dashboard.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```
