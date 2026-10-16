# Slice 2: Telemetry and Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every Dependency call in the platform is measured, a `telemetry` Service aggregates it into one-second windows, and a dashboard at `http://127.0.0.1:8080` shows the live topology, Service health and SPS (Act 1 with a dashboard).

**Architecture:** A small library, `lib-telemetry`, gives every platform Service one seam for Dependency calls, `DependencyCalls`. It times each call, classifies its outcome and buffers a call event. The buffer is flushed to `telemetry` every 250 ms as a JSON batch, and the flush doubles as a heartbeat. When telemetry is slow or down, events are dropped and counted; a Service request never waits on observability. `telemetry` keeps 600 one-second windows in memory and computes SPS, per-Dependency figures and health. The `dashboard` Service reads the latest window every 500 ms and pushes it over WebSockets Next to one static HTML page that draws the topology with vendored cytoscape.js.

**Tech Stack:** Java 25, Quarkus 3.40.1 (REST, REST Client, Scheduler, Jackson, WebSockets Next), JDK `HttpClient`, cytoscape.js 3.34.3 (vendored), JUnit 5, AssertJ, WireMock, REST Assured, Awaitility.

**Spec:** [`docs/superpowers/specs/2026-10-16-chaos-lecture-demo-design.md`](../specs/2026-10-16-chaos-lecture-demo-design.md), sections 2.3 (lib-telemetry), 3 (telemetry), 5 (dashboard), delivery slice 2. Slice 1 plan: [`2026-10-16-slice-1-platform-skeleton.md`](2026-10-16-slice-1-platform-skeleton.md). Read `CONTEXT.md`, `docs/adr/` and `CONTRIBUTING.md` before starting.

## Global Constraints

- Everything from slice 1's Global Constraints still holds: Java 25 (`JAVA_HOME=/usr/lib/jvm/java-25-openjdk`), Quarkus 3.40.1, Maven multi-module with `./mvnw`, blocking endpoints on `max-threads=16`/`queue-size=32` in platform Services (ADR 0002), Dev Services off, ports bound to `127.0.0.1`, base package `io.github.amadeu01.chaos.<module>` with dashes dropped (`libtelemetry`, `telemetry`, `dashboard`), names from `CONTEXT.md`, gitmoji contextual commits, docs in English and Brazilian Portuguese in the same commit.
- **No resilience yet.** Do not add timeouts, retries, circuit breakers, bulkheads or Fallbacks to any platform Dependency. The naive behaviour is the lesson until slice 4. (Timeouts on control-plane calls to telemetry are allowed: observability must not become a Critical Dependency.)
- Emitting telemetry never blocks or fails a Service request: bounded buffer, asynchronous send, failures counted as dropped.
- Wire format of an event batch (both sides pin it with this exact literal in their tests):
  `{"service":"api","dropped":2,"calls":[{"timestamp":1000,"caller":"api","callee":"profile","group":"none","outcome":"OK","latencyMs":3}],"starts":[{"timestamp":1000,"group":"none"}]}`
- Outcomes: `OK`, `ERROR`, `TIMEOUT`, `REJECTED`, `SHORT_CIRCUITED`, `FALLBACK` (spec 3.1). Slice 2 emits only `OK` and `ERROR`. Group is always `none` until slice 5.
- Callee names are Service names exactly as in Compose: `edge-gateway`, `api`, `profile`, `catalog`, `cache`, `store`, `recommendations`, `playback`, `license`, `queue`, `ratings-worker`.
- Health rule (spec 3.4), first match wins: red when a reporting Service has sent no batch for more than 3 s, or when incoming error percentage is at least 50 %; amber when incoming calls include Fallbacks, or error percentage is at least 5 %, or p99 latency is above 1000 ms; green otherwise. Grey (stopped container) arrives with chaos-controller in slice 3.
- cytoscape.js 3.34.3 is vendored as a file; the dashboard page loads nothing from the network.
- Published ports: dashboard `127.0.0.1:8080`, telemetry `127.0.0.1:8082`, plus slice 1's 8000, 8090, 9325.

## Review Focus

1. **telemetry down or slow**: Service requests must not slow down or fail; events are dropped and counted. Pinned in Task 1 (failing and hanging senders).
2. **Event buffer full under load**: recording must drop, never block. Pinned in Task 1.
3. **Dashboard open while telemetry is down**: the page must say "telemetry unavailable", not freeze or crash. Pinned in Task 3.
4. **Events outside the 600-second window** (too old, or from the future): ignored, never written into another second's window. Pinned in Task 2.
5. **A live Service with no incoming calls** (license while traffic is paused) must be green, not red; a Service that stopped reporting must turn red within about 3 s. Pinned in Task 2.

---

## File Structure

```
docs/adr/0005-own-telemetry-with-explicit-dependency-calls.md
libs/lib-telemetry/                         Dependency-call seam, event buffer, batch sender (not runnable)
  src/main/java/io/github/amadeu01/chaos/libtelemetry/
    Outcome.java, CallEvent.java, StartEvent.java, EventBatch.java   wire model
    CallRecorder.java                       "record one call" interface
    BatchSender.java, HttpBatchSender.java  async POST of a batch
    TelemetryBuffer.java                    bounded buffer, drain, flush, drop counting
    Telemetry.java                          CDI bean: config, 250 ms flush
    DependencyCalls.java                    the seam Services call Dependencies through
  src/main/resources/META-INF/beans.xml
control-plane/telemetry/                    aggregation Service
  src/main/java/io/github/amadeu01/chaos/telemetry/
    CallEvent.java, StartEvent.java, EventBatch.java                 wire model (receiving side)
    Health.java, HealthRule.java            the health rule
    Percentiles.java, DependencyStats.java, Window.java              one-second window
    Sps.java, DependencyView.java, ServiceHealth.java, Snapshot.java, SpsPoint.java   read model
    Aggregator.java                         600-window ring, heartbeat map
    EventsResource.java, WindowsResource.java, SpsResource.java
control-plane/dashboard/                    dashboard Service
  src/main/java/io/github/amadeu01/chaos/dashboard/
    TelemetryClient.java, ViewerSimulatorClient.java
    LiveSocket.java, LivePublisher.java     WebSocket push every 500 ms
    ViewerRateResource.java                 proxy for the Viewers/s slider
  src/main/resources/META-INF/resources/
    index.html, styles.css, app.js, topology.json, vendor/cytoscape.min.js, vendor/LICENSE-cytoscape
platform/*                                  call sites wrapped in DependencyCalls
control-plane/viewer-simulator              records calls to edge-gateway and Playback Starts
docker-compose.yml, scripts/platform-up.sh  two new containers
docs/en|pt-br/telemetry.md, dashboard.md   new pages; README, getting-started, architecture updated
```

Root `pom.xml` `<modules>` final order: `libs/test-support`, `libs/lib-telemetry`, the eight `platform/*` modules (unchanged order), `control-plane/viewer-simulator`, `control-plane/telemetry`, `control-plane/dashboard`.

---

### Task 1: ADR 0005 and lib-telemetry

**Files:**
- Create: `docs/adr/0005-own-telemetry-with-explicit-dependency-calls.md`
- Create: `libs/lib-telemetry/pom.xml`, `libs/lib-telemetry/src/main/resources/META-INF/beans.xml`
- Create: `libs/lib-telemetry/src/main/java/io/github/amadeu01/chaos/libtelemetry/{Outcome,CallEvent,StartEvent,EventBatch,CallRecorder,BatchSender,HttpBatchSender,TelemetryBuffer,Telemetry,DependencyCalls}.java`
- Test: `libs/lib-telemetry/src/test/java/io/github/amadeu01/chaos/libtelemetry/{EventBatchJsonTest,TelemetryBufferTest,HttpBatchSenderTest,DependencyCallsTest}.java`
- Modify: `pom.xml` (module after `libs/test-support`; `lib-telemetry` in `dependencyManagement`)

**Interfaces:**
- Produces:
  - `DependencyCalls` (CDI bean): `<T> T call(String callee, Supplier<T> call)`, `void run(String callee, Runnable call)`, `Response callHttp(String callee, Supplier<Response> call)`. Exceptions are recorded as `ERROR` and rethrown unchanged; `callHttp` records `ERROR` for status ≥ 500.
  - `Telemetry` (CDI bean, implements `CallRecorder`): `recordCall(String callee, Outcome outcome, long latencyMs)`, `recordStart()`. Config: `quarkus.application.name` (the caller), `telemetry.url` (default `http://telemetry:8080`), `telemetry.enabled` (default `true`).
  - `CallRecorder`: `void recordCall(String callee, Outcome outcome, long latencyMs)`.
  - Wire model records exactly as in Global Constraints.

- [ ] **Step 1: Write ADR 0005**

`docs/adr/0005-own-telemetry-with-explicit-dependency-calls.md`:

```markdown
# Own telemetry with an explicit DependencyCalls seam

Every platform Service sends Dependency call events to our own `telemetry` Service, and every Dependency call goes through one visible seam, `DependencyCalls.call("profile", () -> ...)`, instead of OpenTelemetry auto-instrumentation or Micrometer metrics scraped by Prometheus. The lecture needs one-second windows on stage (a 5 to 15 s scrape interval feels broken when you just stopped a Service), offline startup without extra containers, and code students can read end to end. The explicit seam is also where slice 3 injects Faults and slice 4 adds Resilience Policies, so each lesson changes one place.

## Considered Options

- **OpenTelemetry with an OTLP collector and a tracing UI.** Industry standard and worth a slide, but adds containers, hides the measuring inside agents and filters, and its UIs are not a live topology.
- **Micrometer and Prometheus.** Quarkus supports it out of the box, but scraping is too coarse for live chaos on a projector, and Grafana would be a second UI.
- **JAX-RS client filters instead of an explicit seam.** Automatic, but filters never see connection failures, and AWS SDK and Redis calls would need a second mechanism.
```

- [ ] **Step 2: POM, module entries and beans.xml**

In root `pom.xml` `<modules>`, directly after `<module>libs/test-support</module>`: `<module>libs/lib-telemetry</module>`. In `<dependencyManagement>`, next to `test-support`:

```xml
            <dependency>
                <groupId>io.github.amadeu01.chaos</groupId>
                <artifactId>lib-telemetry</artifactId>
                <version>${project.version}</version>
            </dependency>
```

`libs/lib-telemetry/pom.xml`:

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

    <artifactId>lib-telemetry</artifactId>
    <name>Library: telemetry (DependencyCalls seam)</name>

    <dependencies>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-arc</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-scheduler</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-jackson</artifactId>
        </dependency>
        <dependency>
            <groupId>jakarta.ws.rs</groupId>
            <artifactId>jakarta.ws.rs-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

`libs/lib-telemetry/src/main/resources/META-INF/beans.xml` (makes Quarkus index the library's beans and `@Scheduled` method):

```xml
<beans xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.0" bean-discovery-mode="annotated"/>
```

- [ ] **Step 3: Write the failing tests**

`EventBatchJsonTest.java`:

```java
package io.github.amadeu01.chaos.libtelemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pins the wire format; control-plane/telemetry pins the same literal on the receiving side. */
class EventBatchJsonTest {

    static final String WIRE_FORMAT = "{\"service\":\"api\",\"dropped\":2,\"calls\":[{\"timestamp\":1000,\"caller\":\"api\","
            + "\"callee\":\"profile\",\"group\":\"none\",\"outcome\":\"OK\",\"latencyMs\":3}],"
            + "\"starts\":[{\"timestamp\":1000,\"group\":\"none\"}]}";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serialisesToTheWireFormat() throws Exception {
        EventBatch batch = new EventBatch("api", 2,
                List.of(new CallEvent(1000, "api", "profile", "none", Outcome.OK, 3)),
                List.of(new StartEvent(1000, "none")));

        assertThat(mapper.readTree(mapper.writeValueAsString(batch))).isEqualTo(mapper.readTree(WIRE_FORMAT));
    }
}
```

`TelemetryBufferTest.java`:

```java
package io.github.amadeu01.chaos.libtelemetry;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryBufferTest {

    private final TelemetryBuffer buffer = new TelemetryBuffer("api", 2, () -> 1000L);

    @Test
    void recordsCallsAsTheConfiguredCaller() {
        buffer.recordCall("profile", Outcome.OK, 3);

        EventBatch batch = buffer.drain();
        assertThat(batch.service()).isEqualTo("api");
        assertThat(batch.calls()).containsExactly(new CallEvent(1000, "api", "profile", "none", Outcome.OK, 3));
    }

    @Test
    void fullBufferDropsInsteadOfBlocking() {
        buffer.recordCall("profile", Outcome.OK, 1);
        buffer.recordCall("profile", Outcome.OK, 1);
        buffer.recordCall("profile", Outcome.OK, 1);

        EventBatch batch = buffer.drain();
        assertThat(batch.calls()).hasSize(2);
        assertThat(batch.dropped()).isEqualTo(1);
    }

    @Test
    void drainResetsTheDropCount() {
        buffer.recordStart();
        buffer.recordStart();
        buffer.recordStart();
        buffer.drain();

        assertThat(buffer.drain().dropped()).isZero();
    }

    @Test
    void emptyBufferStillProducesABatchAsAHeartbeat() {
        AtomicReference<EventBatch> sent = new AtomicReference<>();
        buffer.flushTo(batch -> {
            sent.set(batch);
            return CompletableFuture.completedFuture(null);
        });

        assertThat(sent.get()).isNotNull();
        assertThat(sent.get().calls()).isEmpty();
    }

    @Test
    void failedSendCountsTheBatchAsDropped() {
        buffer.recordCall("profile", Outcome.OK, 1);
        buffer.recordStart();
        buffer.flushTo(batch -> CompletableFuture.failedFuture(new IllegalStateException("telemetry down")));

        assertThat(buffer.drain().dropped()).isEqualTo(2);
    }

    @Test
    void senderThatThrowsIsTreatedAsAFailedSend() {
        buffer.recordCall("profile", Outcome.OK, 1);
        buffer.flushTo(batch -> {
            throw new IllegalStateException("cannot even start");
        });

        assertThat(buffer.drain().dropped()).isEqualTo(1);
    }

    @Test
    void dropsCarriedByAFailedBatchAreNotLost() {
        buffer.recordStart();
        buffer.recordStart();
        buffer.recordStart();
        buffer.flushTo(batch -> CompletableFuture.failedFuture(new IllegalStateException("down")));

        assertThat(buffer.drain().dropped()).isEqualTo(3);
    }
}
```

`HttpBatchSenderTest.java`:

```java
package io.github.amadeu01.chaos.libtelemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpBatchSenderTest {

    private WireMockServer telemetry;
    private HttpBatchSender sender;

    @BeforeEach
    void start() {
        telemetry = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        telemetry.start();
        sender = new HttpBatchSender(HttpClient.newHttpClient(), new ObjectMapper(),
                URI.create(telemetry.baseUrl() + "/events"), Duration.ofMillis(300));
    }

    @AfterEach
    void stop() {
        telemetry.stop();
    }

    @Test
    void postsTheBatchAsJson() {
        telemetry.stubFor(post("/events").willReturn(aResponse().withStatus(202)));

        sender.send(new EventBatch("api", 2,
                List.of(new CallEvent(1000, "api", "profile", "none", Outcome.OK, 3)),
                List.of(new StartEvent(1000, "none")))).join();

        telemetry.verify(postRequestedFor(urlEqualTo("/events"))
                .withHeader("Content-Type", com.github.tomakehurst.wiremock.client.WireMock.equalTo("application/json"))
                .withRequestBody(equalToJson(EventBatchJsonTest.WIRE_FORMAT)));
    }

    @Test
    void errorStatusFailsTheSend() {
        telemetry.stubFor(post("/events").willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> sender.send(new EventBatch("api", 0, List.of(), List.of())).join())
                .isInstanceOf(CompletionException.class);
    }

    @Test
    void slowTelemetryTimesOutInsteadOfHanging() {
        telemetry.stubFor(post("/events").willReturn(aResponse().withStatus(202).withFixedDelay(2000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> sender.send(new EventBatch("api", 0, List.of(), List.of())).join())
                .isInstanceOf(CompletionException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
    }
}
```

`DependencyCallsTest.java`:

```java
package io.github.amadeu01.chaos.libtelemetry;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DependencyCallsTest {

    record Recorded(String callee, Outcome outcome, long latencyMs) {
    }

    private final List<Recorded> recorded = new ArrayList<>();
    private final DependencyCalls calls = new DependencyCalls((callee, outcome, latencyMs) ->
            recorded.add(new Recorded(callee, outcome, latencyMs)));

    @Test
    void successfulCallIsRecordedAsOk() {
        String result = calls.call("profile", () -> "Viewer v-1");

        assertThat(result).isEqualTo("Viewer v-1");
        assertThat(recorded).singleElement().satisfies(r -> {
            assertThat(r.callee()).isEqualTo("profile");
            assertThat(r.outcome()).isEqualTo(Outcome.OK);
            assertThat(r.latencyMs()).isNotNegative();
        });
    }

    @Test
    void failedCallIsRecordedAsErrorAndRethrownUnchanged() {
        IllegalStateException failure = new IllegalStateException("recommendations is down");

        assertThatThrownBy(() -> calls.call("recommendations", () -> {
            throw failure;
        })).isSameAs(failure);
        assertThat(recorded).singleElement().extracting(Recorded::outcome).isEqualTo(Outcome.ERROR);
    }

    @Test
    void runRecordsLikeCall() {
        calls.run("queue", () -> {
        });

        assertThat(recorded).singleElement().extracting(Recorded::callee).isEqualTo("queue");
    }

    @Test
    void httpServerErrorStatusIsAnError() {
        calls.callHttp("api", () -> Response.status(503).build());

        assertThat(recorded).singleElement().extracting(Recorded::outcome).isEqualTo(Outcome.ERROR);
    }

    @Test
    void httpClientErrorStatusIsNotTheCalleesFault() {
        calls.callHttp("api", () -> Response.status(400).build());

        assertThat(recorded).singleElement().extracting(Recorded::outcome).isEqualTo(Outcome.OK);
    }
}
```

`jakarta.ws.rs.core.Response.status(...).build()` needs a JAX-RS implementation at test time. If the test fails with "RuntimeDelegate not found", add test-scoped `org.jboss.resteasy.reactive:resteasy-reactive-common` (managed by the Quarkus BOM) to the POM and say so in the report.

- [ ] **Step 4: Run to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl libs/lib-telemetry -am verify`
Expected: FAIL to compile, `EventBatch` missing.

- [ ] **Step 5: Implement the wire model and interfaces**

```java
package io.github.amadeu01.chaos.libtelemetry;

/** How one Dependency call ended, from the caller's point of view (spec 3.1). */
public enum Outcome {
    OK, ERROR, TIMEOUT, REJECTED, SHORT_CIRCUITED, FALLBACK
}
```

```java
package io.github.amadeu01.chaos.libtelemetry;

/** One Dependency call, written by the caller. {@code group} is "none" until slice 5 adds Experiments. */
public record CallEvent(long timestamp, String caller, String callee, String group, Outcome outcome, long latencyMs) {

    public static final String NO_GROUP = "none";
}
```

```java
package io.github.amadeu01.chaos.libtelemetry;

/** One Playback Start, written by viewer-simulator. */
public record StartEvent(long timestamp, String group) {
}
```

```java
package io.github.amadeu01.chaos.libtelemetry;

import java.util.List;

/** What a Service sends to telemetry every 250 ms; an empty batch is a heartbeat. */
public record EventBatch(String service, long dropped, List<CallEvent> calls, List<StartEvent> starts) {

    int eventCount() {
        return calls.size() + starts.size();
    }
}
```

```java
package io.github.amadeu01.chaos.libtelemetry;

/** Records one Dependency call made by this Service. */
public interface CallRecorder {

    void recordCall(String callee, Outcome outcome, long latencyMs);
}
```

```java
package io.github.amadeu01.chaos.libtelemetry;

import java.util.concurrent.CompletableFuture;

/** Sends a batch without blocking the caller; a failed future means the batch was not delivered. */
public interface BatchSender {

    CompletableFuture<Void> send(EventBatch batch);
}
```

- [ ] **Step 6: Implement the sender, buffer, bean and seam**

```java
package io.github.amadeu01.chaos.libtelemetry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** POSTs a batch to telemetry asynchronously, with a short timeout so a slow telemetry never piles up requests. */
final class HttpBatchSender implements BatchSender {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;

    HttpBatchSender(HttpClient http, ObjectMapper mapper, URI endpoint, Duration timeout) {
        this.http = http;
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    @Override
    public CompletableFuture<Void> send(EventBatch batch) {
        String json;
        try {
            json = mapper.writeValueAsString(batch);
        } catch (JsonProcessingException e) {
            return CompletableFuture.failedFuture(e);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenAccept(response -> {
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("telemetry answered " + response.statusCode());
            }
        });
    }
}
```

```java
package io.github.amadeu01.chaos.libtelemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Bounded buffer between a Service and telemetry. Recording never blocks: a full buffer drops the event
 * and counts it, so observability can never become a Critical Dependency.
 */
public final class TelemetryBuffer {

    private final String service;
    private final LongSupplier clock;
    private final BlockingQueue<CallEvent> calls;
    private final BlockingQueue<StartEvent> starts;
    private final AtomicLong dropped = new AtomicLong();

    public TelemetryBuffer(String service, int capacity) {
        this(service, capacity, System::currentTimeMillis);
    }

    TelemetryBuffer(String service, int capacity, LongSupplier clock) {
        this.service = service;
        this.clock = clock;
        this.calls = new ArrayBlockingQueue<>(capacity);
        this.starts = new ArrayBlockingQueue<>(capacity);
    }

    public void recordCall(String callee, Outcome outcome, long latencyMs) {
        if (!calls.offer(new CallEvent(clock.getAsLong(), service, callee, CallEvent.NO_GROUP, outcome, latencyMs))) {
            dropped.incrementAndGet();
        }
    }

    public void recordStart() {
        if (!starts.offer(new StartEvent(clock.getAsLong(), CallEvent.NO_GROUP))) {
            dropped.incrementAndGet();
        }
    }

    EventBatch drain() {
        List<CallEvent> drainedCalls = new ArrayList<>();
        List<StartEvent> drainedStarts = new ArrayList<>();
        calls.drainTo(drainedCalls);
        starts.drainTo(drainedStarts);
        return new EventBatch(service, dropped.getAndSet(0), drainedCalls, drainedStarts);
    }

    /** Sends everything recorded so far; an undelivered batch is added to the next batch's drop count. */
    public void flushTo(BatchSender sender) {
        EventBatch batch = drain();
        CompletableFuture<Void> sent;
        try {
            sent = sender.send(batch);
        } catch (RuntimeException e) {
            sent = CompletableFuture.failedFuture(e);
        }
        sent.whenComplete((ignored, failure) -> {
            if (failure != null) {
                dropped.addAndGet(batch.eventCount() + batch.dropped());
            }
        });
    }
}
```

```java
package io.github.amadeu01.chaos.libtelemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** This Service's connection to telemetry: buffers events and flushes them every 250 ms. */
@ApplicationScoped
public class Telemetry implements CallRecorder {

    static final int BUFFER_CAPACITY = 10_000;
    static final Duration SEND_TIMEOUT = Duration.ofSeconds(1);

    @ConfigProperty(name = "quarkus.application.name")
    String service;

    @ConfigProperty(name = "telemetry.url", defaultValue = "http://telemetry:8080")
    URI url;

    @ConfigProperty(name = "telemetry.enabled", defaultValue = "true")
    boolean enabled;

    @Inject
    ObjectMapper mapper;

    private TelemetryBuffer buffer;
    private BatchSender sender;

    @PostConstruct
    void init() {
        buffer = new TelemetryBuffer(service, BUFFER_CAPACITY);
        sender = new HttpBatchSender(HttpClient.newBuilder().connectTimeout(SEND_TIMEOUT).build(),
                mapper, url.resolve("/events"), SEND_TIMEOUT);
    }

    @Override
    public void recordCall(String callee, Outcome outcome, long latencyMs) {
        buffer.recordCall(callee, outcome, latencyMs);
    }

    public void recordStart() {
        buffer.recordStart();
    }

    /** Sends buffered events now; the scheduler calls it every 250 ms, tests without a scheduler call it directly. */
    @Scheduled(every = "0.25s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void flush() {
        if (enabled) {
            buffer.flushTo(sender);
        }
    }
}
```

If Quarkus rejects `every = "0.25s"`, use `every = "PT0.25S"` and say so in the report.

```java
package io.github.amadeu01.chaos.libtelemetry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.function.Supplier;

/**
 * The one seam every Dependency call goes through: {@code calls.call("profile", () -> profiles.get(id))}.
 * Today it only measures. Slice 3 injects Faults here and slice 4 adds Resilience Policies here (ADR 0005).
 */
@ApplicationScoped
public class DependencyCalls {

    private final CallRecorder recorder;

    @Inject
    public DependencyCalls(CallRecorder recorder) {
        this.recorder = recorder;
    }

    public <T> T call(String callee, Supplier<T> call) {
        long start = System.nanoTime();
        try {
            T result = call.get();
            recorder.recordCall(callee, Outcome.OK, elapsedMs(start));
            return result;
        } catch (RuntimeException e) {
            recorder.recordCall(callee, Outcome.ERROR, elapsedMs(start));
            throw e;
        }
    }

    public void run(String callee, Runnable call) {
        call(callee, () -> {
            call.run();
            return null;
        });
    }

    /** For callers that receive raw HTTP responses (edge-gateway): a 5xx is the callee's failure, a 4xx is not. */
    public Response callHttp(String callee, Supplier<Response> call) {
        long start = System.nanoTime();
        try {
            Response response = call.get();
            recorder.recordCall(callee, response.getStatus() >= 500 ? Outcome.ERROR : Outcome.OK, elapsedMs(start));
            return response;
        } catch (RuntimeException e) {
            recorder.recordCall(callee, Outcome.ERROR, elapsedMs(start));
            throw e;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
```

- [ ] **Step 7: Run to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl libs/lib-telemetry -am verify`
Expected: PASS (16 tests).

- [ ] **Step 8: Commit and push**

```bash
git add docs/adr/0005-own-telemetry-with-explicit-dependency-calls.md
git commit -F - <<'EOF'
:memo: ADR 0005: own telemetry behind an explicit DependencyCalls seam

One-second windows on a projector, offline, readable end to end: that is
why the demo measures Dependency calls itself instead of using
OpenTelemetry or Prometheus. The explicit seam is also where Faults
(slice 3) and Resilience Policies (slice 4) will plug in.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git add pom.xml libs/lib-telemetry
git commit -F - <<'EOF'
:chart_with_upwards_trend: Add lib-telemetry with the DependencyCalls seam

Every Dependency call can now be timed and classified in one place,
DependencyCalls.call("profile", () -> ...). Events go into a bounded
buffer flushed to telemetry every 250 ms; the flush doubles as a
heartbeat. A full buffer or an unreachable telemetry drops and counts
events instead of slowing a request down: observability must never
become a Critical Dependency.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 2: telemetry Service

**Files:**
- Create: `control-plane/telemetry/pom.xml`, `control-plane/telemetry/src/main/resources/application.properties`
- Create: `control-plane/telemetry/src/main/java/io/github/amadeu01/chaos/telemetry/{CallEvent,StartEvent,EventBatch,Health,HealthRule,Percentiles,DependencyStats,Window,Sps,DependencyView,ServiceHealth,Snapshot,SpsPoint,Aggregator,EventsResource,WindowsResource,SpsResource}.java`
- Test: `control-plane/telemetry/src/test/java/io/github/amadeu01/chaos/telemetry/{HealthRuleTest,PercentilesTest,AggregatorTest,TelemetryResourceTest}.java`
- Modify: `pom.xml` (module after `control-plane/viewer-simulator`)

**Interfaces:**
- Consumes: event batches in the wire format (Global Constraints).
- Produces:
  - `POST /events` (batch) → `202`.
  - `GET /windows/latest` → `{"timestamp": <ms of the last complete second>, "sps": {"total","control","experiment"}, "dependencies": [{"caller","callee","callsPerSecond","errorPercent","p50Ms","p99Ms","outcomes":{...},"health":"GREEN|AMBER|RED"}], "services": [{"name","health"}]}`. Dependencies sorted by `caller->callee`, services by name.
  - `GET /sps?group=total|control|experiment&from=<epochMs>` → `[{"timestamp","sps"}]`, one point per second from `from` (at most 600 s back) up to the last complete second; unknown group → `400`.

- [ ] **Step 1: POM, properties, module entry**

Root `pom.xml` `<modules>`: `<module>control-plane/telemetry</module>` directly after `<module>control-plane/viewer-simulator</module>`.

`control-plane/telemetry/pom.xml`: same shape as `platform/license/pom.xml` with `<artifactId>telemetry</artifactId>` and `<name>Control plane: telemetry</name>` (dependencies `quarkus-rest-jackson`, `quarkus-smallrye-health`; test `quarkus-junit5`, `rest-assured`, `assertj-core`; `quarkus-maven-plugin`).

`application.properties`:

```properties
quarkus.application.name=telemetry
quarkus.http.port=8080
quarkus.http.test-port=0
quarkus.devservices.enabled=false
```

(telemetry is control plane: ADR 0002's small pool does not apply.)

- [ ] **Step 2: Write the failing tests**

`HealthRuleTest.java`:

```java
package io.github.amadeu01.chaos.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HealthRuleTest {

    private static final long NOW = 100_000;

    private static DependencyView incoming(int ok, int errors, int fallbacks, long p99Ms) {
        int calls = ok + errors + fallbacks;
        return new DependencyView("api", "profile", calls, calls == 0 ? 0 : errors * 100.0 / calls, 1, p99Ms,
                Map.of("OK", ok, "ERROR", errors, "FALLBACK", fallbacks), Health.GREEN);
    }

    @Test
    void reportingServiceThatWentSilentIsRed() {
        assertThat(HealthRule.forService(List.of(), NOW - 3_001, NOW)).isEqualTo(Health.RED);
    }

    @Test
    void reportingServiceWithoutIncomingCallsIsGreen() {
        assertThat(HealthRule.forService(List.of(), NOW - 500, NOW)).isEqualTo(Health.GREEN);
    }

    @Test
    void halfOfCallsFailingIsRed() {
        assertThat(HealthRule.forService(List.of(incoming(5, 5, 0, 10)), null, NOW)).isEqualTo(Health.RED);
    }

    @Test
    void fivePercentErrorsIsAmber() {
        assertThat(HealthRule.forService(List.of(incoming(95, 5, 0, 10)), null, NOW)).isEqualTo(Health.AMBER);
    }

    @Test
    void servingFallbacksIsAmber() {
        assertThat(HealthRule.forService(List.of(incoming(9, 0, 1, 10)), null, NOW)).isEqualTo(Health.AMBER);
    }

    @Test
    void slowP99IsAmber() {
        assertThat(HealthRule.forService(List.of(incoming(10, 0, 0, 1_001)), null, NOW)).isEqualTo(Health.AMBER);
    }

    @Test
    void healthyTrafficIsGreen() {
        assertThat(HealthRule.forService(List.of(incoming(99, 1, 0, 50)), null, NOW)).isEqualTo(Health.GREEN);
    }

    @Test
    void dependencyHealthUsesTheSameThresholds() {
        assertThat(HealthRule.forDependency(5, 5, 0, 10)).isEqualTo(Health.RED);
        assertThat(HealthRule.forDependency(95, 5, 0, 10)).isEqualTo(Health.AMBER);
        assertThat(HealthRule.forDependency(100, 0, 0, 10)).isEqualTo(Health.GREEN);
    }
}
```

`PercentilesTest.java`:

```java
package io.github.amadeu01.chaos.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class PercentilesTest {

    @Test
    void nearestRankOnSortedValues() {
        List<Long> oneToHundred = LongStream.rangeClosed(1, 100).boxed().toList();
        assertThat(Percentiles.of(oneToHundred, 0.50)).isEqualTo(50);
        assertThat(Percentiles.of(oneToHundred, 0.99)).isEqualTo(99);
    }

    @Test
    void emptyIsZero() {
        assertThat(Percentiles.of(List.of(), 0.99)).isZero();
    }

    @Test
    void singleValue() {
        assertThat(Percentiles.of(List.of(7L), 0.50)).isEqualTo(7);
    }
}
```

`AggregatorTest.java`:

```java
package io.github.amadeu01.chaos.telemetry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class AggregatorTest {

    private final AtomicLong now = new AtomicLong(1_000_500_000L);
    private final Aggregator aggregator = new Aggregator(now::get);

    private long lastCompleteSecondMs() {
        return (now.get() / 1000 - 1) * 1000;
    }

    private static CallEvent call(long timestamp, String caller, String callee, String outcome, long latencyMs) {
        return new CallEvent(timestamp, caller, callee, "none", outcome, latencyMs);
    }

    @Test
    void latestWindowSummarisesTheLastCompleteSecond() {
        long t = lastCompleteSecondMs() + 100;
        aggregator.ingest(new EventBatch("api", 0, List.of(
                call(t, "api", "profile", "OK", 10),
                call(t, "api", "profile", "OK", 20),
                call(t, "api", "profile", "ERROR", 30)), List.of()));

        Snapshot snapshot = aggregator.latest();

        assertThat(snapshot.timestamp()).isEqualTo(lastCompleteSecondMs());
        assertThat(snapshot.dependencies()).singleElement().satisfies(d -> {
            assertThat(d.caller()).isEqualTo("api");
            assertThat(d.callee()).isEqualTo("profile");
            assertThat(d.callsPerSecond()).isEqualTo(3);
            assertThat(d.errorPercent()).isEqualTo(100.0 / 3);
            assertThat(d.p50Ms()).isEqualTo(20);
            assertThat(d.p99Ms()).isEqualTo(30);
            assertThat(d.outcomes()).containsEntry("OK", 2).containsEntry("ERROR", 1);
            assertThat(d.health()).isEqualTo(Health.AMBER);
        });
    }

    @Test
    void countsPlaybackStartsPerGroup() {
        long t = lastCompleteSecondMs() + 100;
        aggregator.ingest(new EventBatch("viewer-simulator", 0, List.of(), List.of(
                new StartEvent(t, "none"), new StartEvent(t, "control"), new StartEvent(t, "experiment"),
                new StartEvent(t, "experiment"))));

        assertThat(aggregator.latest().sps()).isEqualTo(new Sps(4, 1, 2));
    }

    @Test
    void eventsOlderThanTheRingAreIgnored() {
        long t = lastCompleteSecondMs() + 100;
        aggregator.ingest(new EventBatch("api", 0, List.of(call(t, "api", "profile", "OK", 1)), List.of()));
        long sameSlotTenMinutesEarlier = t - Aggregator.WINDOWS_KEPT * 1000L;
        aggregator.ingest(new EventBatch("api", 0, List.of(call(sameSlotTenMinutesEarlier, "api", "profile", "OK", 1)), List.of()));

        assertThat(aggregator.latest().dependencies()).singleElement()
                .extracting(DependencyView::callsPerSecond).isEqualTo(1);
    }

    @Test
    void eventsFromTheFutureAreIgnored() {
        aggregator.ingest(new EventBatch("api", 0, List.of(call(now.get() + 60_000, "api", "profile", "OK", 1)), List.of()));
        now.addAndGet(61_000);

        assertThat(aggregator.latest().dependencies()).isEmpty();
    }

    @Test
    void servicesIncludeReportersCallersAndCallees() {
        long t = lastCompleteSecondMs() + 100;
        aggregator.ingest(new EventBatch("catalog", 0, List.of(call(t, "catalog", "store", "OK", 1)), List.of()));
        aggregator.ingest(new EventBatch("license", 0, List.of(), List.of()));

        assertThat(aggregator.latest().services()).extracting(ServiceHealth::name)
                .containsExactly("catalog", "license", "store");
    }

    @Test
    void serviceThatStopsReportingTurnsRed() {
        aggregator.ingest(new EventBatch("license", 0, List.of(), List.of()));
        now.addAndGet(3_500);

        assertThat(aggregator.latest().services()).singleElement()
                .extracting(ServiceHealth::health).isEqualTo(Health.RED);
    }

    @Test
    void spsSeriesHasOnePointPerSecond() {
        long t = lastCompleteSecondMs() + 100;
        aggregator.ingest(new EventBatch("viewer-simulator", 0, List.of(), List.of(new StartEvent(t, "none"))));

        List<SpsPoint> series = aggregator.sps("total", lastCompleteSecondMs() - 2_000);

        assertThat(series).extracting(SpsPoint::sps).containsExactly(0, 0, 1);
        assertThat(series).extracting(SpsPoint::timestamp).last().isEqualTo(lastCompleteSecondMs());
    }
}
```

`TelemetryResourceTest.java`:

```java
package io.github.amadeu01.chaos.telemetry;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class TelemetryResourceTest {

    /** Same literal as lib-telemetry's EventBatchJsonTest: both sides of the wire agree. */
    static final String WIRE_FORMAT = "{\"service\":\"api\",\"dropped\":2,\"calls\":[{\"timestamp\":1000,\"caller\":\"api\","
            + "\"callee\":\"profile\",\"group\":\"none\",\"outcome\":\"OK\",\"latencyMs\":3}],"
            + "\"starts\":[{\"timestamp\":1000,\"group\":\"none\"}]}";

    @Test
    void acceptsTheWireFormat() {
        given().contentType(ContentType.JSON).body(WIRE_FORMAT)
                .when().post("/events")
                .then().statusCode(202);
    }

    @Test
    void recentStartsShowUpInTheSpsSeries() {
        long recent = System.currentTimeMillis() - 1_000;
        long from = recent - 5_000;
        given().contentType(ContentType.JSON)
                .body(WIRE_FORMAT.replace("\"timestamp\":1000", "\"timestamp\":" + recent))
                .when().post("/events").then().statusCode(202);

        given().when().get("/sps?group=total&from=" + from)
                .then().statusCode(200)
                .body("sps", hasItem(1));
    }

    @Test
    void latestWindowListsTheReportingService() {
        given().contentType(ContentType.JSON).body(WIRE_FORMAT).when().post("/events");

        given().when().get("/windows/latest")
                .then().statusCode(200)
                .body("sps.total", notNullValue())
                .body("services.name", hasItem("api"));
    }

    @Test
    void unknownGroupIsBadRequest() {
        given().when().get("/sps?group=everyone&from=0").then().statusCode(400);
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl control-plane/telemetry -am verify`
Expected: FAIL to compile.

- [ ] **Step 4: Implement the model and the rule**

```java
package io.github.amadeu01.chaos.telemetry;

import java.util.List;

public record EventBatch(String service, long dropped, List<CallEvent> calls, List<StartEvent> starts) {
}
```

```java
package io.github.amadeu01.chaos.telemetry;

public record CallEvent(long timestamp, String caller, String callee, String group, String outcome, long latencyMs) {
}
```

```java
package io.github.amadeu01.chaos.telemetry;

public record StartEvent(long timestamp, String group) {
}
```

```java
package io.github.amadeu01.chaos.telemetry;

public enum Health {
    GREEN, AMBER, RED
}
```

```java
package io.github.amadeu01.chaos.telemetry;

import java.util.List;

/** Spec 3.4, the one rule both the dashboard and the lecture guide use. Grey (stopped) arrives in slice 3. */
final class HealthRule {

    static final long HEARTBEAT_TIMEOUT_MS = 3_000;
    static final double RED_ERROR_PERCENT = 50;
    static final double AMBER_ERROR_PERCENT = 5;
    static final long AMBER_P99_MS = 1_000;

    private HealthRule() {
    }

    /**
     * @param incoming   calls this Service received in the window
     * @param lastSeenMs when it last sent a batch, or null if it never reports (the emulators)
     */
    static Health forService(List<DependencyView> incoming, Long lastSeenMs, long nowMs) {
        if (lastSeenMs != null && nowMs - lastSeenMs > HEARTBEAT_TIMEOUT_MS) {
            return Health.RED;
        }
        int ok = 0;
        int fallbacks = 0;
        int calls = 0;
        long p99 = 0;
        for (DependencyView view : incoming) {
            calls += view.callsPerSecond();
            ok += view.outcomes().getOrDefault("OK", 0);
            fallbacks += view.outcomes().getOrDefault("FALLBACK", 0);
            p99 = Math.max(p99, view.p99Ms());
        }
        return forDependency(ok, calls - ok - fallbacks, fallbacks, p99);
    }

    static Health forDependency(int ok, int errors, int fallbacks, long p99Ms) {
        int calls = ok + errors + fallbacks;
        if (calls == 0) {
            return Health.GREEN;
        }
        double errorPercent = errors * 100.0 / calls;
        if (errorPercent >= RED_ERROR_PERCENT) {
            return Health.RED;
        }
        if (fallbacks > 0 || errorPercent >= AMBER_ERROR_PERCENT || p99Ms > AMBER_P99_MS) {
            return Health.AMBER;
        }
        return Health.GREEN;
    }
}
```

```java
package io.github.amadeu01.chaos.telemetry;

import java.util.List;

final class Percentiles {

    private Percentiles() {
    }

    /** Nearest-rank percentile of already sorted values. */
    static long of(List<Long> sorted, double quantile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(quantile * sorted.size());
        return sorted.get(Math.max(rank, 1) - 1);
    }
}
```

```java
package io.github.amadeu01.chaos.telemetry;

public record Sps(int total, int control, int experiment) {
}
```

```java
package io.github.amadeu01.chaos.telemetry;

import java.util.Map;

public record DependencyView(String caller, String callee, int callsPerSecond, double errorPercent,
                             long p50Ms, long p99Ms, Map<String, Integer> outcomes, Health health) {
}
```

```java
package io.github.amadeu01.chaos.telemetry;

public record ServiceHealth(String name, Health health) {
}
```

```java
package io.github.amadeu01.chaos.telemetry;

import java.util.List;

public record Snapshot(long timestamp, Sps sps, List<DependencyView> dependencies, List<ServiceHealth> services) {
}
```

```java
package io.github.amadeu01.chaos.telemetry;

public record SpsPoint(long timestamp, int sps) {
}
```

- [ ] **Step 5: Implement the window and the aggregator**

```java
package io.github.amadeu01.chaos.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Every call from one caller to one callee within one second. */
final class DependencyStats {

    private final String caller;
    private final String callee;
    private final Map<String, Integer> outcomes = new TreeMap<>();
    private final List<Long> latencies = new ArrayList<>();

    DependencyStats(String caller, String callee) {
        this.caller = caller;
        this.callee = callee;
    }

    void record(String outcome, long latencyMs) {
        outcomes.merge(outcome, 1, Integer::sum);
        latencies.add(latencyMs);
    }

    DependencyView view() {
        int calls = latencies.size();
        int ok = outcomes.getOrDefault("OK", 0);
        int fallbacks = outcomes.getOrDefault("FALLBACK", 0);
        int errors = calls - ok - fallbacks;
        List<Long> sorted = latencies.stream().sorted().toList();
        long p99 = Percentiles.of(sorted, 0.99);
        return new DependencyView(caller, callee, calls, calls == 0 ? 0 : errors * 100.0 / calls,
                Percentiles.of(sorted, 0.50), p99, Map.copyOf(outcomes), HealthRule.forDependency(ok, errors, fallbacks, p99));
    }
}
```

```java
package io.github.amadeu01.chaos.telemetry;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Everything telemetry knows about one wall-clock second. */
final class Window {

    final long second;
    private final Map<String, DependencyStats> dependencies = new TreeMap<>();
    private int startsTotal;
    private int startsControl;
    private int startsExperiment;

    Window(long second) {
        this.second = second;
    }

    void recordCall(CallEvent event) {
        dependencies.computeIfAbsent(event.caller() + "->" + event.callee(),
                key -> new DependencyStats(event.caller(), event.callee())).record(event.outcome(), event.latencyMs());
    }

    void recordStart(String group) {
        startsTotal++;
        if ("control".equals(group)) {
            startsControl++;
        } else if ("experiment".equals(group)) {
            startsExperiment++;
        }
    }

    Sps sps() {
        return new Sps(startsTotal, startsControl, startsExperiment);
    }

    List<DependencyView> dependencyViews() {
        return dependencies.values().stream().map(DependencyStats::view).toList();
    }
}
```

```java
package io.github.amadeu01.chaos.telemetry;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongSupplier;

/** Ring of one-second windows (10 minutes) plus when each Service last reported. In memory only. */
@ApplicationScoped
public class Aggregator {

    static final int WINDOWS_KEPT = 600;

    private final LongSupplier clock;
    private final Window[] windows = new Window[WINDOWS_KEPT];
    private final Map<String, Long> lastSeen = new HashMap<>();

    public Aggregator() {
        this(System::currentTimeMillis);
    }

    Aggregator(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized void ingest(EventBatch batch) {
        long now = clock.getAsLong();
        lastSeen.put(batch.service(), now);
        if (batch.calls() != null) {
            for (CallEvent call : batch.calls()) {
                Window window = windowFor(call.timestamp(), now);
                if (window != null) {
                    window.recordCall(call);
                }
            }
        }
        if (batch.starts() != null) {
            for (StartEvent start : batch.starts()) {
                Window window = windowFor(start.timestamp(), now);
                if (window != null) {
                    window.recordStart(start.group());
                }
            }
        }
    }

    public synchronized Snapshot latest() {
        long now = clock.getAsLong();
        long second = now / 1000 - 1;
        Window window = existing(second);
        List<DependencyView> dependencies = window == null ? List.of() : window.dependencyViews();
        TreeSet<String> names = new TreeSet<>(lastSeen.keySet());
        dependencies.forEach(d -> {
            names.add(d.caller());
            names.add(d.callee());
        });
        List<ServiceHealth> services = new ArrayList<>();
        for (String name : names) {
            List<DependencyView> incoming = dependencies.stream().filter(d -> d.callee().equals(name)).toList();
            services.add(new ServiceHealth(name, HealthRule.forService(incoming, lastSeen.get(name), now)));
        }
        return new Snapshot(second * 1000, window == null ? new Sps(0, 0, 0) : window.sps(), dependencies, services);
    }

    /** @param group total, control or experiment */
    public synchronized List<SpsPoint> sps(String group, long fromMs) {
        if (!List.of("total", "control", "experiment").contains(group)) {
            throw new IllegalArgumentException("group must be total, control or experiment");
        }
        long last = clock.getAsLong() / 1000 - 1;
        long first = Math.max(fromMs / 1000, last - WINDOWS_KEPT + 1);
        List<SpsPoint> series = new ArrayList<>();
        for (long second = first; second <= last; second++) {
            Window window = existing(second);
            Sps sps = window == null ? new Sps(0, 0, 0) : window.sps();
            int value = switch (group) {
                case "control" -> sps.control();
                case "experiment" -> sps.experiment();
                default -> sps.total();
            };
            series.add(new SpsPoint(second * 1000, value));
        }
        return series;
    }

    /** The window for an event's second, or null when the event is outside the ring or in the future. */
    private Window windowFor(long timestampMs, long nowMs) {
        long second = timestampMs / 1000;
        long current = nowMs / 1000;
        if (second > current || second <= current - WINDOWS_KEPT) {
            return null;
        }
        int slot = (int) (second % WINDOWS_KEPT);
        if (windows[slot] == null || windows[slot].second != second) {
            windows[slot] = new Window(second);
        }
        return windows[slot];
    }

    private Window existing(long second) {
        Window window = windows[(int) (second % WINDOWS_KEPT)];
        return window != null && window.second == second ? window : null;
    }
}
```

- [ ] **Step 6: Implement the resources**

```java
package io.github.amadeu01.chaos.telemetry;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/events")
public class EventsResource {

    @Inject
    Aggregator aggregator;

    @POST
    public Response ingest(EventBatch batch) {
        aggregator.ingest(batch);
        return Response.accepted().build();
    }
}
```

```java
package io.github.amadeu01.chaos.telemetry;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

@Path("/windows")
public class WindowsResource {

    @Inject
    Aggregator aggregator;

    @GET
    @Path("/latest")
    public Snapshot latest() {
        return aggregator.latest();
    }
}
```

```java
package io.github.amadeu01.chaos.telemetry;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import java.util.List;

/** SPS history; Experiments (slice 5) read it to judge a Hypothesis. */
@Path("/sps")
public class SpsResource {

    @Inject
    Aggregator aggregator;

    @GET
    public List<SpsPoint> sps(@QueryParam("group") @DefaultValue("total") String group,
                              @QueryParam("from") @DefaultValue("0") long fromMs) {
        try {
            return aggregator.sps(group, fromMs);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
    }
}
```

- [ ] **Step 7: Run to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl control-plane/telemetry -am verify`
Expected: PASS (22 tests).

- [ ] **Step 8: Commit and push**

```bash
git add pom.xml control-plane/telemetry
git commit -F - <<'EOF'
:chart_with_upwards_trend: Add telemetry, the one-second view of the platform

telemetry turns call events into what the lecture talks about: SPS per
second, calls per second, error percentage and p50/p99 per Dependency,
and one health colour per Service from the rule in spec 3.4. Ten minutes
of windows stay in memory; nothing is persisted.

A Service that stops sending its 250 ms heartbeat turns red within three
seconds, so a stopped container is visible even when nobody calls it.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 3: dashboard Service (backend)

**Files:**
- Create: `control-plane/dashboard/pom.xml`, `control-plane/dashboard/src/main/resources/application.properties`
- Create: `control-plane/dashboard/src/main/java/io/github/amadeu01/chaos/dashboard/{TelemetryClient,ViewerSimulatorClient,LiveSocket,LivePublisher,ViewerRateResource}.java`
- Test: `control-plane/dashboard/src/test/java/io/github/amadeu01/chaos/dashboard/{LiveSocketTest,ViewerRateResourceTest}.java`
- Modify: `pom.xml` (module after `control-plane/telemetry`)

**Interfaces:**
- Consumes: telemetry `GET /windows/latest` (Task 2), viewer-simulator `GET/PUT /viewers-per-second` (slice 1).
- Produces:
  - WebSocket `/live`: every 500 ms while at least one browser is connected, either `{"type":"window","window":<telemetry snapshot as-is>}` or `{"type":"telemetry-unavailable"}`.
  - `GET /api/viewers-per-second` and `PUT /api/viewers-per-second?value=n`, relaying viewer-simulator's status and body; `502` when it is unreachable.

- [ ] **Step 1: POM, properties, module entry**

Root `pom.xml` `<modules>`: `<module>control-plane/dashboard</module>` directly after `<module>control-plane/telemetry</module>`.

`control-plane/dashboard/pom.xml`: same shape as `platform/license/pom.xml` with `<artifactId>dashboard</artifactId>`, `<name>Control plane: dashboard</name>`, plus:

```xml
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-rest-client-jackson</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-websockets-next</artifactId>
        </dependency>
        <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-scheduler</artifactId>
        </dependency>
        <dependency>
            <groupId>io.github.amadeu01.chaos</groupId>
            <artifactId>test-support</artifactId>
            <scope>test</scope>
        </dependency>
```

`application.properties`:

```properties
quarkus.application.name=dashboard
quarkus.http.port=8080
quarkus.http.test-port=0
quarkus.devservices.enabled=false

quarkus.rest-client.telemetry.url=http://telemetry:8080
# Control plane: a slow telemetry must not stall the push loop.
quarkus.rest-client.telemetry.connect-timeout=500
quarkus.rest-client.telemetry.read-timeout=1000

quarkus.rest-client.viewer-simulator.url=http://viewer-simulator:8080
quarkus.rest-client.viewer-simulator.disable-default-mapper=true
quarkus.rest-client.viewer-simulator.connect-timeout=500
quarkus.rest-client.viewer-simulator.read-timeout=2000
```

- [ ] **Step 2: Write the failing tests**

`LiveSocketTest.java`:

```java
package io.github.amadeu01.chaos.dashboard;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@WithTestResource(value = WireMockResource.class, initArgs = @ResourceArg(name = WireMockResource.URL_PROPERTIES,
        value = "quarkus.rest-client.telemetry.url,quarkus.rest-client.viewer-simulator.url"))
class LiveSocketTest {

    private static final String SNAPSHOT =
            "{\"timestamp\":1000,\"sps\":{\"total\":42,\"control\":0,\"experiment\":0},\"dependencies\":[],\"services\":[]}";

    @InjectWireMock
    WireMockServer wireMock;

    @TestHTTPResource("/live")
    URI live;

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void pushesTheLatestTelemetryWindow() throws Exception {
        wireMock.stubFor(get("/windows/latest").willReturn(okJson(SNAPSHOT)));

        String message = firstMessageMatching(m -> m.contains("\"type\":\"window\""));

        assertThat(message).contains("\"total\":42");
    }

    @Test
    void saysSoWhenTelemetryIsUnavailable() throws Exception {
        wireMock.stubFor(get("/windows/latest").willReturn(aResponse().withStatus(503)));

        assertThat(firstMessageMatching(m -> m.contains("telemetry-unavailable")))
                .isEqualTo(LivePublisher.UNAVAILABLE);
    }

    private String firstMessageMatching(Predicate<String> wanted) throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        URI ws = URI.create(live.toString().replaceFirst("^http", "ws"));
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(ws, new WebSocket.Listener() {
                    private final StringBuilder partial = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            messages.add(partial.toString());
                            partial.setLength(0);
                        }
                        webSocket.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (System.nanoTime() < deadline) {
                String message = messages.poll(1, TimeUnit.SECONDS);
                if (message != null && wanted.test(message)) {
                    return message;
                }
            }
            throw new AssertionError("No matching message within 5 s");
        } finally {
            socket.abort();
        }
    }
}
```

`ViewerRateResourceTest.java`:

```java
package io.github.amadeu01.chaos.dashboard;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.amadeu01.chaos.testsupport.InjectWireMock;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@WithTestResource(value = WireMockResource.class, initArgs = @ResourceArg(name = WireMockResource.URL_PROPERTIES,
        value = "quarkus.rest-client.telemetry.url,quarkus.rest-client.viewer-simulator.url"))
class ViewerRateResourceTest {

    @InjectWireMock
    WireMockServer wireMock;

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    @Test
    void readsTheCurrentRate() {
        wireMock.stubFor(get("/viewers-per-second").willReturn(okJson("{\"viewersPerSecond\":50}")));

        given().when().get("/api/viewers-per-second")
                .then().statusCode(200).body("viewersPerSecond", equalTo(50));
    }

    @Test
    void forwardsANewRate() {
        wireMock.stubFor(put(urlPathEqualTo("/viewers-per-second")).willReturn(aResponse().withStatus(204)));

        given().when().put("/api/viewers-per-second?value=80").then().statusCode(204);
        wireMock.verify(putRequestedFor(urlEqualTo("/viewers-per-second?value=80")));
    }

    @Test
    void relaysARejectedRate() {
        wireMock.stubFor(put(urlPathEqualTo("/viewers-per-second")).willReturn(aResponse().withStatus(400)));

        given().when().put("/api/viewers-per-second?value=999").then().statusCode(400);
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl control-plane/dashboard -am verify`
Expected: FAIL to compile, `LivePublisher` missing.

- [ ] **Step 4: Implement**

```java
package io.github.amadeu01.chaos.dashboard;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** The dashboard relays telemetry's snapshot as-is, so its shape lives in one place (telemetry). */
@RegisterRestClient(configKey = "telemetry")
public interface TelemetryClient {

    @GET
    @Path("/windows/latest")
    @Produces(MediaType.APPLICATION_JSON)
    String latest();
}
```

```java
package io.github.amadeu01.chaos.dashboard;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@RegisterRestClient(configKey = "viewer-simulator")
@Path("/viewers-per-second")
public interface ViewerSimulatorClient {

    @GET
    Response get();

    @PUT
    Response set(@QueryParam("value") String value);
}
```

```java
package io.github.amadeu01.chaos.dashboard;

import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.WebSocket;

/** Browsers connect here; LivePublisher pushes to every open connection. */
@WebSocket(path = "/live")
public class LiveSocket {

    @OnOpen
    void open() {
    }
}
```

```java
package io.github.amadeu01.chaos.dashboard;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** Every 500 ms, sends the latest telemetry window to every open dashboard. */
@ApplicationScoped
public class LivePublisher {

    static final String UNAVAILABLE = "{\"type\":\"telemetry-unavailable\"}";
    private static final Logger LOG = Logger.getLogger(LivePublisher.class);

    @Inject
    @RestClient
    TelemetryClient telemetry;

    @Inject
    OpenConnections connections;

    @Scheduled(every = "0.5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void publish() {
        if (connections.listAll().isEmpty()) {
            return;
        }
        String message = message();
        for (WebSocketConnection connection : connections.listAll()) {
            try {
                connection.sendTextAndAwait(message);
            } catch (RuntimeException e) {
                LOG.debugf("Could not push to dashboard connection %s: %s", connection.id(), e.getMessage());
            }
        }
    }

    String message() {
        try {
            return "{\"type\":\"window\",\"window\":" + telemetry.latest() + "}";
        } catch (RuntimeException e) {
            LOG.debugf("telemetry unavailable: %s", e.getMessage());
            return UNAVAILABLE;
        }
    }
}
```

If Quarkus rejects `every = "0.5s"`, use `every = "PT0.5S"` and say so in the report.

```java
package io.github.amadeu01.chaos.dashboard;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Proxy for the Viewers/s slider, so the page only ever talks to the dashboard. */
@Path("/api/viewers-per-second")
@Produces(MediaType.APPLICATION_JSON)
public class ViewerRateResource {

    @Inject
    @RestClient
    ViewerSimulatorClient simulator;

    @GET
    public Response get() {
        return relay(simulator.get());
    }

    @PUT
    public Response set(@QueryParam("value") String value) {
        return relay(simulator.set(value));
    }

    @ServerExceptionMapper
    public Response simulatorUnreachable(ProcessingException e) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity("{\"error\":\"viewer-simulator unreachable\"}")
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

- [ ] **Step 5: Run to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl control-plane/dashboard -am verify`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit and push**

```bash
git add pom.xml control-plane/dashboard
git commit -F - <<'EOF'
:chart_with_upwards_trend: Add dashboard backend pushing live windows

Every 500 ms the dashboard reads telemetry's latest window and pushes it
over a WebSocket to every open page. When telemetry is down the page is
told so explicitly, instead of silently freezing on old numbers that a
lecturer might mistake for Steady State.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 4: dashboard page (layout A)

**Files:**
- Create: `control-plane/dashboard/src/main/resources/META-INF/resources/{index.html,styles.css,app.js,topology.json}`
- Create: `control-plane/dashboard/src/main/resources/META-INF/resources/vendor/{cytoscape.min.js,LICENSE-cytoscape}`
- Test: `control-plane/dashboard/src/test/java/io/github/amadeu01/chaos/dashboard/PageTest.java`

**Interfaces:**
- Consumes: WebSocket `/live` and `/api/viewers-per-second` (Task 3); snapshot fields from Task 2 (`sps.total`, `dependencies[].caller/callee/callsPerSecond/errorPercent/p50Ms/p99Ms/outcomes/health`, `services[].name/health`).
- Produces: the page at `/`. Edge ids are `caller->callee`, matching telemetry's Dependency keys.

- [ ] **Step 1: Vendor cytoscape.js 3.34.3**

```bash
D=control-plane/dashboard/src/main/resources/META-INF/resources/vendor
mkdir -p $D
curl -fsSL https://cdn.jsdelivr.net/npm/cytoscape@3.34.3/dist/cytoscape.min.js -o $D/cytoscape.min.js
curl -fsSL https://cdn.jsdelivr.net/npm/cytoscape@3.34.3/LICENSE -o $D/LICENSE-cytoscape
head -c 200 $D/cytoscape.min.js; echo; head -3 $D/LICENSE-cytoscape
```

Expected: the file starts with a cytoscape banner or minified code; the license is MIT.

- [ ] **Step 2: Write the failing test**

```java
package io.github.amadeu01.chaos.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.amadeu01.chaos.testsupport.WireMockResource;
import io.quarkus.test.common.ResourceArg;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@WithTestResource(value = WireMockResource.class, initArgs = @ResourceArg(name = WireMockResource.URL_PROPERTIES,
        value = "quarkus.rest-client.telemetry.url,quarkus.rest-client.viewer-simulator.url"))
class PageTest {

    /** Every container in docker-compose.yml that the topology must show. */
    private static final List<String> SERVICES = List.of("viewer-simulator", "edge-gateway", "api", "profile",
            "catalog", "cache", "store", "recommendations", "playback", "license", "queue", "ratings-worker");

    @Test
    void servesThePage() {
        given().when().get("/")
                .then().statusCode(200)
                .body(containsString("<title>Chaos Lecture Demo</title>"))
                .body(containsString("vendor/cytoscape.min.js"));
    }

    @Test
    void servesTheVendoredGraphLibrary() {
        given().when().get("/vendor/cytoscape.min.js").then().statusCode(200);
    }

    @Test
    void topologyShowsEveryServiceAndOnlyKnownDependencies() throws Exception {
        JsonNode topology = new ObjectMapper().readTree(given().get("/topology.json").then().statusCode(200).extract().asString());

        Set<String> nodes = new HashSet<>();
        topology.path("nodes").forEach(node -> nodes.add(node.path("id").asText()));
        assertThat(nodes).containsExactlyInAnyOrderElementsOf(SERVICES);

        topology.path("edges").forEach(edge -> {
            assertThat(nodes).contains(edge.path("source").asText(), edge.path("target").asText());
            assertThat(edge.path("kind").asText()).isIn("critical", "non-critical", "async");
        });
        assertThat(topology.path("edges")).hasSize(12);
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl control-plane/dashboard -am verify`
Expected: FAIL, `/` and `/topology.json` return `404`.

- [ ] **Step 4: Write `topology.json`**

```json
{
  "nodes": [
    { "id": "viewer-simulator", "label": "viewers", "kind": "control", "x": 0, "y": 280 },
    { "id": "edge-gateway", "label": "edge-gateway", "kind": "service", "x": 170, "y": 280 },
    { "id": "api", "label": "api", "kind": "service", "x": 360, "y": 280 },
    { "id": "profile", "label": "profile", "kind": "service", "x": 580, "y": 40 },
    { "id": "catalog", "label": "catalog", "kind": "service", "x": 580, "y": 160 },
    { "id": "recommendations", "label": "recommendations", "kind": "service", "x": 580, "y": 280 },
    { "id": "playback", "label": "playback", "kind": "service", "x": 580, "y": 400 },
    { "id": "queue", "label": "queue (SQS)", "kind": "emulator", "x": 580, "y": 520 },
    { "id": "cache", "label": "cache (Redis)", "kind": "emulator", "x": 800, "y": 110 },
    { "id": "store", "label": "store (DynamoDB)", "kind": "emulator", "x": 1000, "y": 300 },
    { "id": "license", "label": "license", "kind": "service", "x": 800, "y": 400 },
    { "id": "ratings-worker", "label": "ratings-worker", "kind": "service", "x": 800, "y": 520 }
  ],
  "edges": [
    { "source": "viewer-simulator", "target": "edge-gateway", "kind": "critical" },
    { "source": "edge-gateway", "target": "api", "kind": "critical" },
    { "source": "api", "target": "profile", "kind": "non-critical" },
    { "source": "api", "target": "catalog", "kind": "critical" },
    { "source": "catalog", "target": "cache", "kind": "non-critical" },
    { "source": "catalog", "target": "store", "kind": "critical" },
    { "source": "api", "target": "recommendations", "kind": "non-critical" },
    { "source": "api", "target": "playback", "kind": "critical" },
    { "source": "playback", "target": "license", "kind": "critical" },
    { "source": "api", "target": "queue", "kind": "non-critical" },
    { "source": "ratings-worker", "target": "queue", "kind": "async" },
    { "source": "ratings-worker", "target": "store", "kind": "async" }
  ]
}
```

- [ ] **Step 5: Write `index.html`**

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Chaos Lecture Demo</title>
  <link rel="stylesheet" href="styles.css">
  <script src="vendor/cytoscape.min.js"></script>
</head>
<body>
  <header class="top-bar">
    <h1>Chaos Lecture Demo</h1>
    <label class="rate">Viewers/s
      <input id="rate" type="range" min="0" max="200" step="10" value="50">
      <output id="rate-value">50</output>
    </label>
    <span id="connection" class="connection">connecting…</span>
  </header>

  <section class="sps-strip" aria-label="Steady State">
    <div class="sps-now"><span class="label">SPS</span><span id="sps-value">–</span></div>
    <canvas id="sps-chart" aria-label="SPS over the last 60 seconds"></canvas>
  </section>

  <main class="main">
    <div id="topology" class="topology" aria-label="Platform topology"></div>
    <aside id="drawer" class="drawer"><p class="hint">Click a Service or a Dependency.</p></aside>
  </main>

  <footer class="experiment-panel">
    <span class="label">Experiment</span>
    <span id="experiment">No Experiment running.</span>
  </footer>

  <script src="app.js"></script>
</body>
</html>
```

- [ ] **Step 6: Write `styles.css`**

```css
/* Dark by default: it reads best on a projector. */
:root {
  --bg: #16181d;
  --panel: #20232a;
  --text: #e8eaed;
  --muted: #9aa0a6;
  --line: #3c4043;
  --green: #2e9e5b;
  --amber: #e0a526;
  --red: #d64545;
  --accent: #8ab4f8;
  color-scheme: dark;
}

* { box-sizing: border-box; }

body {
  margin: 0;
  height: 100vh;
  display: grid;
  grid-template-rows: auto auto 1fr auto;
  background: var(--bg);
  color: var(--text);
  font: 15px/1.4 system-ui, sans-serif;
}

.label { color: var(--muted); font-size: 12px; text-transform: uppercase; letter-spacing: .08em; }

.top-bar {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 10px 16px;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
}
.top-bar h1 { margin: 0; font-size: 18px; }
.rate { display: flex; align-items: center; gap: 8px; }
.connection { margin-left: auto; color: var(--muted); }
.connection.live { color: var(--green); }
.connection.down { color: var(--red); }

.sps-strip {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--line);
}
.sps-now { display: flex; flex-direction: column; min-width: 90px; }
#sps-value { font-size: 36px; font-weight: 700; }
#sps-chart { flex: 1; height: 70px; }

.main { display: flex; min-height: 0; }
.topology { flex: 1; min-width: 0; }
.drawer {
  width: 300px;
  padding: 12px 16px;
  background: var(--panel);
  border-left: 1px solid var(--line);
  overflow-y: auto;
}
.drawer h2 { margin: 0 0 8px; font-size: 16px; }
.drawer dl { display: grid; grid-template-columns: auto 1fr; gap: 4px 12px; margin: 0; }
.drawer dt { color: var(--muted); }
.drawer dd { margin: 0; }
.hint { color: var(--muted); }

.experiment-panel {
  display: flex;
  gap: 12px;
  align-items: baseline;
  padding: 8px 16px;
  background: var(--panel);
  border-top: 1px solid var(--line);
}

@media (max-width: 800px) {
  .main { flex-direction: column; }
  .drawer { width: auto; border-left: 0; border-top: 1px solid var(--line); }
  .topology { min-height: 60vh; }
}
```

- [ ] **Step 7: Write `app.js`**

```js
'use strict';

const COLORS = { green: '#2e9e5b', amber: '#e0a526', red: '#d64545', unknown: '#7a7f87', idle: '#5f6368' };
const SPS_HISTORY_SECONDS = 60;
const state = { snapshot: null, spsHistory: [], lastTimestamp: null };

main();

async function main() {
  const topology = await (await fetch('topology.json')).json();
  const cy = createGraph(topology);
  cy.on('select unselect', () => renderDrawer(cy));
  connectLive(cy);
  setupRateSlider();
  animateEdges(cy);
}

function createGraph(topology) {
  return cytoscape({
    container: document.getElementById('topology'),
    layout: { name: 'preset', padding: 40 },
    autoungrabify: true,
    elements: [
      ...topology.nodes.map(n => ({
        data: { id: n.id, label: n.label }, position: { x: n.x, y: n.y }, classes: n.kind,
      })),
      ...topology.edges.map(e => ({
        data: { id: `${e.source}->${e.target}`, source: e.source, target: e.target, kind: e.kind, label: '', rate: 0 },
        classes: e.kind,
      })),
    ],
    style: [
      { selector: 'node', style: {
        'background-color': COLORS.unknown, label: 'data(label)', color: '#e8eaed', 'font-size': 14,
        'text-valign': 'bottom', 'text-margin-y': 6, width: 36, height: 36 } },
      { selector: 'node.emulator', style: { shape: 'round-rectangle' } },
      { selector: 'node.control', style: { shape: 'diamond' } },
      ...['green', 'amber', 'red'].map(h => ({ selector: `node.health-${h}`, style: { 'background-color': COLORS[h] } })),
      { selector: 'edge', style: {
        width: 2, 'line-color': COLORS.idle, 'target-arrow-color': COLORS.idle, 'target-arrow-shape': 'triangle',
        'curve-style': 'bezier', 'line-style': 'dashed', 'line-dash-pattern': [8, 6],
        label: 'data(label)', color: '#bdc1c6', 'font-size': 11,
        'text-background-color': '#16181d', 'text-background-opacity': 0.85, 'text-background-padding': 2 } },
      { selector: 'edge.critical', style: { width: 3 } },
      { selector: 'edge.async', style: { 'line-dash-pattern': [2, 6] } },
      ...['green', 'amber', 'red'].map(h => ({
        selector: `edge.health-${h}`, style: { 'line-color': COLORS[h], 'target-arrow-color': COLORS[h] } })),
      { selector: ':selected', style: { 'border-width': 3, 'border-color': '#8ab4f8', 'overlay-opacity': 0 } },
    ],
  });
}

function connectLive(cy) {
  const socket = new WebSocket(`${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/live`);
  socket.onmessage = event => {
    const message = JSON.parse(event.data);
    if (message.type === 'window') {
      setConnection('live', 'live');
      render(cy, message.window);
    } else {
      setConnection('telemetry unavailable', 'down');
    }
  };
  socket.onclose = () => {
    setConnection('reconnecting…', 'down');
    setTimeout(() => connectLive(cy), 2000);
  };
}

function setConnection(text, css) {
  const status = document.getElementById('connection');
  status.textContent = text;
  status.className = `connection ${css}`;
}

function render(cy, snapshot) {
  state.snapshot = snapshot;
  const health = new Map(snapshot.services.map(s => [s.name, s.health.toLowerCase()]));
  cy.nodes().forEach(node => {
    node.removeClass('health-green health-amber health-red');
    if (health.has(node.id())) node.addClass(`health-${health.get(node.id())}`);
  });

  const dependencies = new Map(snapshot.dependencies.map(d => [`${d.caller}->${d.callee}`, d]));
  cy.edges().forEach(edge => {
    const d = dependencies.get(edge.id());
    edge.removeClass('health-green health-amber health-red');
    edge.data('rate', d ? d.callsPerSecond : 0);
    edge.data('label', d ? edgeLabel(d) : '');
    if (d) edge.addClass(`health-${d.health.toLowerCase()}`);
  });

  if (snapshot.timestamp !== state.lastTimestamp) {
    state.lastTimestamp = snapshot.timestamp;
    pushSps(snapshot.sps.total);
  }
  renderDrawer(cy);
}

function edgeLabel(d) {
  return d.errorPercent > 0 ? `${d.callsPerSecond}/s · ${Math.round(d.errorPercent)}% err` : `${d.callsPerSecond}/s`;
}

function pushSps(total) {
  state.spsHistory.push(total);
  if (state.spsHistory.length > SPS_HISTORY_SECONDS) state.spsHistory.shift();
  document.getElementById('sps-value').textContent = total;
  drawSpsChart();
}

function drawSpsChart() {
  const canvas = document.getElementById('sps-chart');
  const ratio = window.devicePixelRatio || 1;
  canvas.width = canvas.clientWidth * ratio;
  canvas.height = canvas.clientHeight * ratio;
  const ctx = canvas.getContext('2d');
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  const values = state.spsHistory;
  if (values.length < 2) return;
  const max = Math.max(10, ...values) * 1.1;
  const step = canvas.width / (SPS_HISTORY_SECONDS - 1);
  const offset = (SPS_HISTORY_SECONDS - values.length) * step;
  ctx.strokeStyle = COLORS.green;
  ctx.lineWidth = 2 * ratio;
  ctx.beginPath();
  values.forEach((v, i) => {
    const x = offset + i * step;
    const y = canvas.height - (v / max) * canvas.height;
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
  });
  ctx.stroke();
}

/** Dashes march along each Dependency at a speed that follows its call rate. */
function animateEdges(cy) {
  const frame = () => {
    cy.edges().forEach(edge => {
      const rate = edge.data('rate') || 0;
      const offset = (edge.scratch('_dashOffset') || 0) - Math.min(4, rate / 15);
      edge.scratch('_dashOffset', offset);
      edge.style('line-dash-offset', offset);
    });
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}

function renderDrawer(cy) {
  const drawer = document.getElementById('drawer');
  const selected = cy.$(':selected');
  drawer.replaceChildren();
  if (selected.empty() || !state.snapshot) {
    drawer.append(element('p', 'Click a Service or a Dependency.', 'hint'));
    return;
  }
  const item = selected[0];
  if (item.isNode()) {
    const service = state.snapshot.services.find(s => s.name === item.id());
    drawer.append(element('h2', item.data('label')));
    drawer.append(details([['Health', service ? service.health.toLowerCase() : 'no data']]));
    return;
  }
  const d = state.snapshot.dependencies.find(dep => `${dep.caller}->${dep.callee}` === item.id());
  drawer.append(element('h2', `${item.data('source')} → ${item.data('target')}`));
  const rows = [['Kind', item.data('kind')]];
  if (d) {
    rows.push(['Calls/s', d.callsPerSecond], ['Errors', `${d.errorPercent.toFixed(1)} %`],
      ['p50', `${d.p50Ms} ms`], ['p99', `${d.p99Ms} ms`], ['Health', d.health.toLowerCase()],
      ...Object.entries(d.outcomes).map(([outcome, count]) => [outcome, count]));
  } else {
    rows.push(['Calls/s', 0]);
  }
  drawer.append(details(rows));
}

function element(tag, text, css) {
  const el = document.createElement(tag);
  el.textContent = text;
  if (css) el.className = css;
  return el;
}

function details(rows) {
  const list = document.createElement('dl');
  rows.forEach(([term, value]) => list.append(element('dt', term), element('dd', String(value))));
  return list;
}

async function setupRateSlider() {
  const slider = document.getElementById('rate');
  const output = document.getElementById('rate-value');
  try {
    const response = await fetch('api/viewers-per-second');
    if (response.ok) {
      const { viewersPerSecond } = await response.json();
      slider.value = viewersPerSecond;
      output.value = viewersPerSecond;
    }
  } catch (unreachable) {
    output.value = '?';
  }
  slider.addEventListener('input', () => { output.value = slider.value; });
  slider.addEventListener('change', () => fetch(`api/viewers-per-second?value=${slider.value}`, { method: 'PUT' }));
}
```

- [ ] **Step 8: Run to verify it passes**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl control-plane/dashboard -am verify`
Expected: PASS (8 tests in the module).

Run: `node --check control-plane/dashboard/src/main/resources/META-INF/resources/app.js && echo JS OK`
Expected: `JS OK`.

- [ ] **Step 9: Commit and push**

```bash
git add control-plane/dashboard/src/main/resources/META-INF/resources control-plane/dashboard/src/test
git commit -F - <<'EOF'
:lipstick: Draw the live topology on the dashboard (layout A)

One page, built for a projector: SPS on top, the platform in the middle,
details of the selected Service or Dependency on the right. Nodes take
their health colour from telemetry, Dependencies show calls per second
and errors, and their dashes march faster as traffic grows.

cytoscape.js 3.34.3 is vendored so the dashboard works offline.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 5: Measure api's Dependency calls

**Files:**
- Modify: `platform/api/pom.xml` (add `lib-telemetry`)
- Modify: `platform/api/src/main/java/io/github/amadeu01/chaos/api/{HomeResource,PlayResource,RatingPublisher}.java`
- Modify: `platform/api/src/test/java/io/github/amadeu01/chaos/api/ApiResourceTest.java`

**Interfaces:**
- Consumes: `DependencyCalls.call/run` (Task 1).
- Produces: api reports Dependencies `api->profile`, `api->catalog`, `api->recommendations`, `api->playback`, `api->queue`.

- [ ] **Step 1: Add the dependency**

In `platform/api/pom.xml` `<dependencies>`:

```xml
        <dependency>
            <groupId>io.github.amadeu01.chaos</groupId>
            <artifactId>lib-telemetry</artifactId>
        </dependency>
```

- [ ] **Step 2: Write the failing test and fix the existing one**

In `ApiResourceTest`:

1. Add `quarkus.rest-client.profile.url` style property `telemetry.url` to the WireMock resource's URL list, so the value becomes:
   `"quarkus.rest-client.profile.url,quarkus.rest-client.catalog.url,quarkus.rest-client.recommendations.url,quarkus.rest-client.playback.url,telemetry.url"`.
2. `homeWithoutViewerIsBadRequestAndCallsNothing`: telemetry heartbeats now reach WireMock too, so replace `wireMock.verify(0, anyRequestedFor(anyUrl()));` with
   `wireMock.verify(0, anyRequestedFor(urlPathMatching("/(profiles|titles|recommendations|playbacks).*")));`
   (import `com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching`; drop the `anyUrl` import if unused).
3. Add:

```java
    @Test
    void reportsEachDependencyCallToTelemetry() {
        wireMock.stubFor(post("/events").willReturn(aResponse().withStatus(202)));

        given().when().get("/home?viewerId=v-1").then().statusCode(200);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> wireMock.verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$.calls[?(@.callee == 'recommendations' && @.outcome == 'OK')]"))));
    }

    @Test
    void reportsAFailedDependencyAsAnError() {
        wireMock.stubFor(post("/events").willReturn(aResponse().withStatus(202)));
        wireMock.stubFor(get("/recommendations/v-1").willReturn(aResponse().withStatus(503)));

        given().when().get("/home?viewerId=v-1");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> wireMock.verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$.calls[?(@.callee == 'recommendations' && @.outcome == 'ERROR')]"))));
    }
```

Imports to add: `com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor`, `...urlEqualTo`, `...matchingJsonPath`, `org.awaitility.Awaitility.await`, `java.time.Duration`. Add `org.awaitility:awaitility` (test scope) to the api POM.

- [ ] **Step 3: Run to verify the new tests fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl platform/api -am verify`
Expected: the two new tests FAIL (heartbeats arrive with empty `calls`); the others PASS.

- [ ] **Step 4: Route the calls through the seam**

`HomeResource`: add

```java
    @Inject
    DependencyCalls calls;
```

(import `io.github.amadeu01.chaos.libtelemetry.DependencyCalls`) and make `home` read:

```java
    @GET
    public Home home(@QueryParam("viewerId") String viewerId) {
        Requests.requireNonBlank(viewerId, "viewerId");
        Profile profile = calls.call("profile", () -> profiles.get(viewerId));
        List<Title> titles = calls.call("catalog", () -> catalog.all()).stream().limit(TITLES_ON_HOME).toList();
        List<String> recommended = calls.call("recommendations", () -> recommendations.get(viewerId)).titleIds();
        return new Home(profile, titles, recommended);
    }
```

`PlayResource`: inject `DependencyCalls calls` the same way and replace `return playback.start(request);` with `return calls.call("playback", () -> playback.start(request));`.

`RatingPublisher`: inject `DependencyCalls calls` and replace the send line with:

```java
        calls.run("queue", () -> sqs.sendMessage(request -> request.queueUrl(queueUrl()).messageBody(body)));
```

- [ ] **Step 5: Run to verify all pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl platform/api -am verify`
Expected: PASS (9 tests).

- [ ] **Step 6: Commit and push**

```bash
git add platform/api
git commit -F - <<'EOF'
:chart_with_upwards_trend: Measure every Dependency call api makes

api's five Dependencies now go through DependencyCalls, so the dashboard
can show each one's rate, errors and latency. Note what did not change:
a failing recommendations still fails the whole home page. Measuring a
Cascading Failure is the first step to containing it.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 6: Measure playback and edge-gateway

**Files:**
- Modify: `platform/playback/pom.xml`, `platform/playback/src/main/java/io/github/amadeu01/chaos/playback/PlaybackResource.java`
- Modify: `platform/edge-gateway/pom.xml`, `platform/edge-gateway/src/main/java/io/github/amadeu01/chaos/edgegateway/EdgeResource.java`
- Modify: `platform/playback/src/test/java/io/github/amadeu01/chaos/playback/PlaybackResourceTest.java`, `platform/edge-gateway/src/test/java/io/github/amadeu01/chaos/edgegateway/EdgeResourceTest.java`

**Interfaces:**
- Consumes: `DependencyCalls.call` and `callHttp` (Task 1).
- Produces: Dependencies `playback->license` and `edge-gateway->api`.

- [ ] **Step 1: Add `lib-telemetry` (and test-scoped `org.awaitility:awaitility`) to both POMs**, as in Task 5 Step 1.

- [ ] **Step 2: Write the failing tests**

In both tests, add `telemetry.url` to the WireMock URL properties (`"quarkus.rest-client.license.url,telemetry.url"` and `"quarkus.rest-client.api.url,telemetry.url"`).

`PlaybackResourceTest`, add:

```java
    @Test
    void reportsTheCallToLicense() {
        wireMock.stubFor(post("/events").willReturn(aResponse().withStatus(202)));
        wireMock.stubFor(post("/licenses").willReturn(okJson(
                "{\"licenseId\":\"lic-1\",\"viewerId\":\"v-1\",\"titleId\":\"t07\",\"expiresAt\":\"2030-01-01T00:00:00Z\"}")));

        given().contentType(ContentType.JSON).body("{\"viewerId\":\"v-1\",\"titleId\":\"t07\"}").post("/playbacks");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> wireMock.verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$.calls[?(@.caller == 'playback' && @.callee == 'license')]"))));
    }
```

`EdgeResourceTest`, add:

```java
    @Test
    void reportsApiServerErrorsAsErrors() {
        wireMock.stubFor(post("/events").willReturn(aResponse().withStatus(202)));
        wireMock.stubFor(get(urlPathEqualTo("/home")).willReturn(aResponse().withStatus(503)));

        given().when().get("/home?viewerId=v-1").then().statusCode(503);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> wireMock.verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$.calls[?(@.callee == 'api' && @.outcome == 'ERROR')]"))));
    }

    @Test
    void reportsAnUnreachableApiAsAnError() {
        wireMock.stubFor(post("/events").willReturn(aResponse().withStatus(202)));
        wireMock.stubFor(get(urlPathEqualTo("/home")).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        given().when().get("/home?viewerId=v-1").then().statusCode(502);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> wireMock.verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$.calls[?(@.callee == 'api' && @.outcome == 'ERROR')]"))));
    }
```

Add the matching imports (`postRequestedFor`, `urlEqualTo`, `matchingJsonPath`, `await`, `Duration`).

- [ ] **Step 3: Run to verify the new tests fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl platform/playback,platform/edge-gateway -am verify`
Expected: the three new tests FAIL; the others PASS.

- [ ] **Step 4: Route the calls through the seam**

`PlaybackResource`: inject `DependencyCalls calls` and replace the license line with:

```java
        License license = calls.call("license", () -> licenses.issue(new LicenseRequest(request.viewerId(), request.titleId())));
```

`EdgeResource`: inject `DependencyCalls calls` and make the three routes:

```java
    public Response home(@QueryParam("viewerId") String viewerId) {
        return relay(calls.callHttp("api", () -> api.home(viewerId)));
    }

    public Response play(String body) {
        return relay(calls.callHttp("api", () -> api.play(body)));
    }

    public Response rate(String body) {
        return relay(calls.callHttp("api", () -> api.rate(body)));
    }
```

(keep the existing annotations on each method).

- [ ] **Step 5: Run to verify all pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl platform/playback,platform/edge-gateway -am verify`
Expected: PASS (playback 4, edge-gateway 7).

- [ ] **Step 6: Commit and push**

```bash
git add platform/playback platform/edge-gateway
git commit -F - <<'EOF'
:chart_with_upwards_trend: Measure playback and edge-gateway

edge-gateway relays raw responses instead of throwing on errors, so it
uses DependencyCalls.callHttp: a 5xx from api is api's failure, a 4xx is
the Viewer's. Without that distinction the dashboard would paint api
green while every Viewer gets an error page.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 7: Measure catalog and ratings-worker

**Files:**
- Modify: `platform/catalog/pom.xml`, `platform/catalog/src/main/java/io/github/amadeu01/chaos/catalog/{TitleCache,TitleRepository}.java`
- Modify: `platform/ratings-worker/pom.xml`, `platform/ratings-worker/src/main/java/io/github/amadeu01/chaos/ratingsworker/RatingConsumer.java`
- Test: `platform/catalog/src/test/java/io/github/amadeu01/chaos/catalog/TitleResourceTest.java`, `platform/ratings-worker/src/test/java/io/github/amadeu01/chaos/ratingsworker/RatingConsumerTest.java`

**Interfaces:**
- Consumes: `DependencyCalls.call/run` (Task 1).
- Produces: Dependencies `catalog->cache`, `catalog->store`, `ratings-worker->queue`, `ratings-worker->store`.

- [ ] **Step 1: Add `lib-telemetry` to both POMs** (catalog also gets test-scoped `org.wiremock:wiremock-standalone` and `org.awaitility:awaitility`; ratings-worker already has awaitility and gets `wiremock-standalone`).

- [ ] **Step 2: Write the failing tests**

Both test classes add a third test resource:

```java
@WithTestResource(value = WireMockResource.class, initArgs =
        @ResourceArg(name = WireMockResource.URL_PROPERTIES, value = "telemetry.url"))
```

with an `@InjectWireMock WireMockServer telemetry;` field.

`TitleResourceTest`, add:

```java
    @Test
    void reportsCacheAndStoreCalls() {
        telemetry.stubFor(post("/events").willReturn(aResponse().withStatus(202)));

        given().get("/titles").then().statusCode(200);

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            telemetry.verify(postRequestedFor(urlEqualTo("/events"))
                    .withRequestBody(matchingJsonPath("$.calls[?(@.callee == 'cache')]")));
            telemetry.verify(postRequestedFor(urlEqualTo("/events"))
                    .withRequestBody(matchingJsonPath("$.calls[?(@.callee == 'store')]")));
        });
    }
```

`RatingConsumerTest` disables the scheduler in tests (`%test.quarkus.scheduler.enabled=false`), so lib-telemetry's 250 ms flush does not run either; the test calls `Telemetry.flush()` itself. Add:

```java
    @Inject
    io.github.amadeu01.chaos.libtelemetry.Telemetry telemetryClient;

    @Test
    void reportsQueueAndStoreCalls() {
        telemetry.stubFor(post("/events").willReturn(aResponse().withStatus(202)));
        send("{\"viewerId\":\"v-9\",\"titleId\":\"t19\",\"stars\":4}");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            consumer.poll();
            telemetryClient.flush();
            telemetry.verify(postRequestedFor(urlEqualTo("/events"))
                    .withRequestBody(matchingJsonPath("$.calls[?(@.caller == 'ratings-worker' && @.callee == 'store')]")));
        });
        telemetry.verify(postRequestedFor(urlEqualTo("/events"))
                .withRequestBody(matchingJsonPath("$.calls[?(@.callee == 'queue')]")));
    }
```

Add imports as in Task 5.

- [ ] **Step 3: Run to verify the new tests fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl platform/catalog,platform/ratings-worker -am verify`
Expected: the two new tests FAIL; the others PASS.

- [ ] **Step 4: Route the calls through the seam**

`TitleCache`: its constructor gains a `DependencyCalls calls` parameter (store it in a `private final DependencyCalls calls;` field). `get()` reads `String json = calls.call("cache", () -> values.get(KEY));`. `put()` becomes:

```java
    public void put(List<Title> titles) {
        String json;
        try {
            json = mapper.writeValueAsString(titles);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Titles must always serialise", e);
        }
        calls.run("cache", () -> values.setex(KEY, TTL_SECONDS, json));
    }
```

`TitleRepository`: inject `DependencyCalls calls`; wrap each DynamoDB call:

```java
    public void saveAll(List<Title> titles) {
        titles.forEach(title -> calls.run("store", () -> dynamo.putItem(request -> request.tableName(TABLE).item(toItem(title)))));
    }

    public List<Title> findAll() {
        return calls.call("store", () -> dynamo.scan(request -> request.tableName(TABLE))).items().stream()
                .map(TitleRepository::fromItem)
                .sorted(Comparator.comparing(Title::id))
                .toList();
    }

    public Optional<Title> findById(String id) {
        Map<String, AttributeValue> item = calls.call("store", () -> dynamo.getItem(request -> request.tableName(TABLE)
                .key(Map.of("id", AttributeValue.fromS(id))))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(fromItem(item));
    }
```

`RatingConsumer`: inject `DependencyCalls calls`; in `poll()` the receive becomes
`List<Message> messages = calls.call("queue", () -> sqs.receiveMessage(request -> request.queueUrl(queueUrl()).maxNumberOfMessages(BATCH_SIZE)).messages());`
and the delete becomes
`calls.run("queue", () -> sqs.deleteMessage(request -> request.queueUrl(queueUrl()).receiptHandle(message.receiptHandle())));`.
In `handle()`, wrap the `putItem` call in `calls.run("store", () -> dynamo.putItem(...));` with the same arguments. Keep the order handle-then-delete and the store exception propagating.

- [ ] **Step 5: Run to verify all pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl platform/catalog,platform/ratings-worker -am verify`
Expected: PASS (catalog 8, ratings-worker 6).

- [ ] **Step 6: Commit and push**

```bash
git add platform/catalog platform/ratings-worker
git commit -F - <<'EOF'
:chart_with_upwards_trend: Measure the AWS and cache Dependencies

The same seam measures Redis, DynamoDB and SQS calls, not only HTTP:
catalog->cache, catalog->store, ratings-worker->queue and ->store now
appear on the dashboard.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 8: viewer-simulator reports Viewer calls and Playback Starts

**Files:**
- Modify: `control-plane/viewer-simulator/pom.xml`
- Modify: `control-plane/viewer-simulator/src/main/java/io/github/amadeu01/chaos/viewersimulator/{ViewerJourney,ViewerSimulator}.java`
- Test: `control-plane/viewer-simulator/src/test/java/io/github/amadeu01/chaos/viewersimulator/ViewerJourneyTest.java`

**Interfaces:**
- Consumes: `CallRecorder`, `Outcome`, `Telemetry.recordStart()` (Task 1).
- Produces: Dependency `viewer-simulator->edge-gateway` and Playback Start events (SPS in telemetry). The `SPS total=` log line stays.

- [ ] **Step 1: Add `lib-telemetry` to the POM.**

- [ ] **Step 2: Write the failing tests**

In `ViewerJourneyTest`: keep a `List<Outcome> recorded = new CopyOnWriteArrayList<>();` field and construct the journey with
`new ViewerJourney(HttpClient.newHttpClient(), new ObjectMapper(), URI.create(edge.baseUrl()), (callee, outcome, latencyMs) -> recorded.add(outcome))`.
Add:

```java
    @Test
    void recordsEveryRequestToEdgeGateway() {
        edge.stubFor(get(urlPathEqualTo("/home")).willReturn(okJson(
                "{\"titles\":[{\"id\":\"t01\"}],\"recommendedTitleIds\":[\"t09\"]}")));

        journey.run("v-1").join();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(recorded).containsExactly(Outcome.OK, Outcome.OK, Outcome.OK));
    }

    @Test
    void recordsAServerErrorAsAnError() {
        edge.stubFor(get(urlPathEqualTo("/home")).willReturn(aResponse().withStatus(503)));

        journey.run("v-1").join();

        assertThat(recorded).containsExactly(Outcome.ERROR);
    }

    @Test
    void recordsAnUnreachableEdgeAsAnError() {
        edge.stop();

        journey.run("v-1").join();

        assertThat(recorded).containsExactly(Outcome.ERROR);
    }
```

Imports: `io.github.amadeu01.chaos.libtelemetry.Outcome`, `java.util.List`, `java.util.concurrent.CopyOnWriteArrayList`.

- [ ] **Step 3: Run to verify they fail**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl control-plane/viewer-simulator -am verify`
Expected: FAIL to compile (constructor has no recorder parameter).

- [ ] **Step 4: Implement**

`ViewerJourney`: add `static final String EDGE = "edge-gateway";`, a `private final CallRecorder recorder;` field set by a fourth constructor parameter `CallRecorder recorder`, and replace `send`:

```java
    private CompletableFuture<HttpResponse<String>> send(HttpRequest.Builder request) {
        long start = System.nanoTime();
        return http.sendAsync(request.timeout(REQUEST_TIMEOUT).build(), HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, failure) -> recorder.recordCall(EDGE,
                        failure != null || response.statusCode() >= 500 ? Outcome.ERROR : Outcome.OK,
                        (System.nanoTime() - start) / 1_000_000));
    }
```

`ViewerSimulator`: inject `io.github.amadeu01.chaos.libtelemetry.Telemetry telemetry;`, pass `telemetry` as the journey's recorder in `onStart`, and next to `sps.recordStart(System.currentTimeMillis());` add `telemetry.recordStart();`.

- [ ] **Step 5: Run to verify they pass**

Run: `JAVA_HOME=/usr/lib/jvm/java-25-openjdk ./mvnw -q -pl control-plane/viewer-simulator -am verify`
Expected: PASS (17 tests).

- [ ] **Step 6: Commit and push**

```bash
git add control-plane/viewer-simulator
git commit -F - <<'EOF'
:chart_with_upwards_trend: Send Playback Starts and Viewer calls to telemetry

SPS now lives where the dashboard and, later, Experiments can read it:
viewer-simulator reports every Playback Start and every request it makes
to edge-gateway. The log line stays, so SPS is still readable when the
dashboard itself is the thing that broke.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 9: Compose, scripts and end-to-end check

**Files:**
- Modify: `docker-compose.yml`, `scripts/platform-up.sh`

**Interfaces:**
- Consumes: every module from Tasks 1 to 8.
- Produces: `http://127.0.0.1:8080` (dashboard) and `http://127.0.0.1:8082` (telemetry).

- [ ] **Step 1: Add the two containers**

In `docker-compose.yml`, under the control-plane section after `viewer-simulator`:

```yaml
  telemetry:
    <<: *quarkus
    build: { context: ./control-plane/telemetry, dockerfile: ../../docker/quarkus-jvm.Dockerfile }
    ports:
      - "127.0.0.1:8082:8080"

  dashboard:
    <<: *quarkus
    build: { context: ./control-plane/dashboard, dockerfile: ../../docker/quarkus-jvm.Dockerfile }
    ports:
      - "127.0.0.1:8080:8080"   # open http://127.0.0.1:8080
    depends_on:
      telemetry: { condition: service_healthy }
      viewer-simulator: { condition: service_started }
```

Services do not wait for telemetry: until it is up their events are dropped, which is the designed behaviour.

- [ ] **Step 2: Wait for the dashboard in `platform-up.sh`**

Add `http://127.0.0.1:8082/q/health/ready http://127.0.0.1:8080/q/health/ready` to the list of URLs the wait loop checks, and change the final line to:

```bash
echo "Platform is up. Open the dashboard: http://127.0.0.1:8080"
```

- [ ] **Step 3: End-to-end check**

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
scripts/platform-up.sh
sleep 20
curl -s http://127.0.0.1:8082/windows/latest | python3 -m json.tool | head -40
```

Expected: `sps.total` about 50; `dependencies` lists all 12 `caller->callee` pairs from `topology.json` with `callsPerSecond` > 0 (ratings-worker's only when messages arrive); every Service `GREEN`.

```bash
docker compose stop -t 1 recommendations; sleep 6
curl -s http://127.0.0.1:8082/windows/latest | python3 -c "import json,sys; s=json.load(sys.stdin); print(s['sps']); print({x['name']:x['health'] for x in s['services']})"
docker compose start recommendations; sleep 8
curl -s http://127.0.0.1:8082/windows/latest | python3 -c "import json,sys; s=json.load(sys.stdin); print(s['sps'])"
```

Expected: while stopped, `sps.total` is 0, recommendations, api and edge-gateway are `RED`; after the restart `sps.total` is back near 50.

```bash
docker compose stop -t 1 telemetry; sleep 3
docker compose logs --since 3s viewer-simulator | grep -o "SPS.*"
docker compose start telemetry
```

Expected: SPS stays about 50 while telemetry is down (Review Focus 1).

Record `docker stats --no-stream` total memory (expected under 2.5 GiB), then `scripts/platform-down.sh`.

Paste every command's raw output into the report.

- [ ] **Step 4: Commit and push**

```bash
git add docker-compose.yml scripts/platform-up.sh
git commit -F - <<'EOF'
:whale: Run telemetry and the dashboard with the platform

Two more containers: telemetry on 127.0.0.1:8082 and the dashboard on
127.0.0.1:8080. Nothing waits for telemetry on purpose: a platform that
cannot start without its monitoring has made monitoring a Critical
Dependency. Verified: stopping telemetry leaves SPS untouched.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```

---

### Task 10: Docs for telemetry and the dashboard (English and Portuguese)

**Files:**
- Create: `docs/en/telemetry.md`, `docs/en/dashboard.md`, `docs/pt-br/telemetry.md`, `docs/pt-br/dashboard.md`
- Modify: `README.md`, `README.pt-BR.md`, `docs/en/getting-started.md`, `docs/pt-br/getting-started.md`, `docs/en/architecture.md`, `docs/pt-br/architecture.md`

**Interfaces:**
- Consumes: the behaviour from Tasks 1 to 9 (ports 8080 and 8082, 250 ms flush, 1 s windows, 600 windows, health rule, layout A).

- [ ] **Step 1: Write `docs/en/telemetry.md`**

````markdown
# Telemetry

Chaos engineering starts with measuring Steady State. Every number on the dashboard comes from here.

## How a call becomes a number

1. A Service calls a Dependency through one seam, `DependencyCalls`:
   ```java
   Profile profile = calls.call("profile", () -> profiles.get(viewerId));
   ```
   The seam times the call and records `OK`, or `ERROR` when it throws. [ADR 0005](../adr/0005-own-telemetry-with-explicit-dependency-calls.md) explains why we measure here instead of using OpenTelemetry.
2. The event goes into a bounded buffer. Every 250 ms the buffer is sent to `telemetry` as one batch. An empty batch still goes out: it is the Service's heartbeat.
3. `telemetry` sorts events into one-second windows and keeps the last 600 (ten minutes).

## Observability is never a Critical Dependency

When telemetry is slow or down, Services keep serving. The buffer drops events and counts them; a request never waits for telemetry. Try it: `docker compose stop telemetry` and watch `docker compose logs -f viewer-simulator` keep printing SPS.

## What a window contains

`curl http://127.0.0.1:8082/windows/latest` shows the last complete second:

- **SPS**: Playback Starts in that second.
- **Per Dependency**: calls per second, error percentage, p50 and p99 latency, count per outcome, health.
- **Per Service**: health.

`curl 'http://127.0.0.1:8082/sps?from=0'` gives SPS second by second for the last ten minutes.

## The health rule

First match wins:

| Colour | When |
|---|---|
| red | the Service stopped sending heartbeats for more than 3 s, or at least 50 % of its incoming calls fail |
| amber | it serves Fallbacks, or at least 5 % of incoming calls fail, or p99 is above 1 s |
| green | otherwise |

Grey, for a stopped container, arrives with the chaos-controller in the next slice.
````

- [ ] **Step 2: Write `docs/pt-br/telemetry.md`**

````markdown
# Telemetria

Engenharia do caos começa medindo o **Steady State** (estado estável). Todo número do dashboard vem daqui.

## Como uma chamada vira um número

1. Um **Service** chama uma **Dependency** (dependência) por um único ponto, `DependencyCalls`:
   ```java
   Profile profile = calls.call("profile", () -> profiles.get(viewerId));
   ```
   Esse ponto mede o tempo da chamada e registra `OK`, ou `ERROR` quando ela lança exceção. O [ADR 0005](../adr/0005-own-telemetry-with-explicit-dependency-calls.md) (em inglês) explica por que medimos aqui em vez de usar OpenTelemetry.
2. O evento entra em um buffer limitado. A cada 250 ms o buffer é enviado ao `telemetry` como um lote. Um lote vazio também é enviado: é o heartbeat (sinal de vida) do Service.
3. O `telemetry` organiza os eventos em janelas de um segundo e guarda as últimas 600 (dez minutos).

## Observabilidade nunca é uma Critical Dependency

Quando o telemetry está lento ou fora do ar, os Services continuam atendendo. O buffer descarta eventos e conta quantos descartou; nenhuma requisição espera pelo telemetry. Experimente: `docker compose stop telemetry` e veja `docker compose logs -f viewer-simulator` continuar mostrando o SPS.

## O que uma janela contém

`curl http://127.0.0.1:8082/windows/latest` mostra o último segundo completo:

- **SPS**: Playback Starts (inícios de reprodução) naquele segundo.
- **Por Dependency**: chamadas por segundo, percentual de erro, latência p50 e p99, contagem por resultado, saúde.
- **Por Service**: saúde.

`curl 'http://127.0.0.1:8082/sps?from=0'` traz o SPS segundo a segundo dos últimos dez minutos.

## A regra de saúde

Vale a primeira que se aplicar:

| Cor | Quando |
|---|---|
| vermelho | o Service parou de enviar heartbeat por mais de 3 s, ou pelo menos 50 % das chamadas que ele recebe falham |
| âmbar | ele está servindo **Fallbacks** (respostas degradadas), ou pelo menos 5 % das chamadas falham, ou o p99 passa de 1 s |
| verde | caso contrário |

O cinza, para um contêiner parado, chega com o chaos-controller na próxima fatia.
````

- [ ] **Step 3: Write `docs/en/dashboard.md`**

```markdown
# Dashboard

Open `http://127.0.0.1:8080` after `scripts/platform-up.sh` (or IntelliJ's **Platform up**).

| Area | What it shows |
|---|---|
| Top bar | the Viewers/s slider and the connection state (`live`, `reconnecting…`, `telemetry unavailable`) |
| SPS strip | SPS now, and the last 60 seconds as a line: this is Steady State |
| Topology | every Service and Dependency. Node colour is health ([telemetry](telemetry.md)). Edge label is calls per second and errors; dashes move faster with more traffic. Thick edges are Critical Dependencies, dotted ones are asynchronous |
| Right drawer | details of what you clicked: health of a Service; rate, errors, p50, p99 and outcomes of a Dependency |
| Experiment panel | empty for now; Experiments arrive in slice 5 |

## Try it

1. Watch SPS settle around 50.
2. `docker compose stop recommendations`. Within seconds recommendations turns red, then api and edge-gateway, and SPS falls to 0: a Non-critical Dependency took the whole platform down. That is Act 2, the Cascading Failure.
3. `docker compose start recommendations` and watch Steady State return.

The dashboard only reads; Faults and Resilience Policy switches arrive in the next slices.
```

- [ ] **Step 4: Write `docs/pt-br/dashboard.md`**

```markdown
# Dashboard

Abra `http://127.0.0.1:8080` depois de `scripts/platform-up.sh` (ou do **Platform up** no IntelliJ).

| Área | O que mostra |
|---|---|
| Barra superior | o controle de Viewers/s e o estado da conexão (`live`, `reconnecting…`, `telemetry unavailable`) |
| Faixa de SPS | o SPS agora e os últimos 60 segundos em linha: esse é o **Steady State** (estado estável) |
| Topologia | todos os **Services** e **Dependencies**. A cor do nó é a saúde ([telemetria](telemetry.md)). O rótulo da aresta mostra chamadas por segundo e erros; os tracejados andam mais rápido com mais tráfego. Arestas grossas são **Critical Dependencies** (dependências críticas); pontilhadas são assíncronas |
| Painel lateral | detalhes do que você clicou: saúde de um Service; taxa, erros, p50, p99 e resultados de uma Dependency |
| Painel de Experiment | vazio por enquanto; os **Experiments** (experimentos) chegam na fatia 5 |

## Experimente

1. Veja o SPS se estabilizar perto de 50.
2. `docker compose stop recommendations`. Em segundos o recommendations fica vermelho, depois o api e o edge-gateway, e o SPS cai para 0: uma **Non-critical Dependency** (dependência não crítica) derrubou a plataforma inteira. Esse é o **Act** 2 (ato 2), a **Cascading Failure** (falha em cascata).
3. `docker compose start recommendations` e veja o Steady State voltar.

O dashboard só mostra; as **Faults** (falhas) e os controles de **Resilience Policy** chegam nas próximas fatias.
```

- [ ] **Step 5: Update the READMEs, getting-started and architecture pages (both languages)**

- `README.md`: status line becomes `> **Status:** slice 2 of 6. The platform runs, telemetry measures every Dependency call, and the dashboard shows it live at http://127.0.0.1:8080. Faults, Resilience Policies and Experiments arrive in the next slices.`; in Quick start, after `scripts/platform-up.sh`, add the line `# then open http://127.0.0.1:8080`; in Learn more, add `- [Dashboard](docs/en/dashboard.md)` and `- [Telemetry](docs/en/telemetry.md)`.
- `README.pt-BR.md`: status `> **Status:** fatia 2 de 6. A plataforma roda, a telemetria mede cada chamada entre Services e o dashboard mostra tudo ao vivo em http://127.0.0.1:8080. Faults, Resilience Policies e Experiments chegam nas próximas fatias.`; Quick start line `# depois abra http://127.0.0.1:8080`; Saiba mais `- [Dashboard](docs/pt-br/dashboard.md)` and `- [Telemetria](docs/pt-br/telemetry.md)`.
- `docs/en/getting-started.md` section 4 gets a first paragraph: `Open the dashboard at http://127.0.0.1:8080; see [Dashboard](dashboard.md). The log below shows the same SPS without a browser.` `docs/pt-br/getting-started.md`: `Abra o dashboard em http://127.0.0.1:8080; veja [Dashboard](dashboard.md). O log abaixo mostra o mesmo SPS sem navegador.`
- `docs/en/architecture.md`: in Repository layout, the `control-plane/` row reads `viewer-simulator, telemetry and dashboard; chaos-controller later`; the `libs/` row reads `test resources and lib-telemetry (the DependencyCalls seam)`; add a section before Repository layout:

  ```markdown
  ## Measuring every call

  Every Dependency call goes through `DependencyCalls`, which reports it to `telemetry`; see [Telemetry](telemetry.md). The dashboard draws what telemetry knows; see [Dashboard](dashboard.md).
  ```
- `docs/pt-br/architecture.md`: same rows (`viewer-simulator, telemetry e dashboard; o chaos-controller vem depois`; `recursos de teste e lib-telemetry (o ponto único DependencyCalls)`) and section:

  ```markdown
  ## Medindo cada chamada

  Toda chamada a uma Dependency passa por `DependencyCalls`, que a reporta ao `telemetry`; veja [Telemetria](telemetry.md). O dashboard desenha o que o telemetry sabe; veja [Dashboard](dashboard.md).
  ```

- [ ] **Step 6: Check links**

Run the same link check as slice 1 Task 13 Step 7 (Python script over `README*.md`, `docs/en/*.md`, `docs/pt-br/*.md`).
Expected: `all links resolve`.

- [ ] **Step 7: Commit and push**

```bash
git add README.md README.pt-BR.md docs/en docs/pt-br
git commit -F - <<'EOF'
:memo: Document telemetry and the dashboard in English and Portuguese

Students can now follow a single call from DependencyCalls to a colour on
the dashboard, read the health rule, and run Act 2 themselves by stopping
recommendations and watching the Cascading Failure spread.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
EOF
git push
```
