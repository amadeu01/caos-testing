# Chaos Engineering Lecture Demo: Design

- **Date:** 2026-10-16
- **Status:** Draft, awaiting review
- **Vocabulary:** every capitalised domain term (Service, Dependency, Viewer, Playback Start, SPS, Fault, Ad-hoc Fault, Experiment, Hypothesis, Blast Radius, Control Group, Experiment Group, Abort Condition, Verdict, Resilience Policy, Fallback, Act) is defined in [`CONTEXT.md`](../../../CONTEXT.md).
- **Decisions:** [`docs/adr/`](../../adr/). ADR 0001 (real AWS SDK against per-service emulators), 0002 (bounded worker threads), 0003 (Quarkus), 0004 (Maven multi-module).

## 1. Purpose

A demonstration project for a lecture on chaos engineering, following the practices published by Netflix (Chaos Monkey, FIT, ChAP, *Principles of Chaos Engineering*). Students should leave able to:

1. Write a chaos Experiment the right way: Steady State defined by a business metric, a Hypothesis, a small Blast Radius with a Control Group, an Abort Condition, automatic rollback, and a Verdict.
2. Watch, live, how a Fault in one Service spreads (Cascading Failure) when Dependencies are unprotected.
3. Watch the same Fault being contained when Resilience Policies are in place and were proven by Experiments.

### Success criteria

- One command starts the whole platform on a laptop with 8 GB RAM, offline after the first image pull.
- Opening `http://localhost:8080` shows the live topology, SPS and controls within 60 seconds of start.
- Every Act in the lecture guide can be performed from the dashboard or an IntelliJ run configuration, and reset in one click.
- The CI pipeline runs the Experiment suite and turns red when a Resilience Policy is removed.
- The git history reads as a sequence of lessons (see [`CONTRIBUTING.md`](../../../CONTRIBUTING.md)).

### Constraints

- Java 25, Quarkus 3.40 LTS, Maven multi-module with `./mvnw`, IntelliJ IDEA (any edition, Red Hat Quarkus Tools plugin).
- Docker Compose for everything; no cloud account, no auth tokens.
- All published ports bound to `127.0.0.1`.
- Documentation for humans in English and Brazilian Portuguese. Code, identifiers, `CONTEXT.md`, ADRs and dashboard labels in English.

### Out of scope

Deployment to real AWS, authentication, persistent storage of telemetry, native images, Kubernetes, multiple instances per Service (no load balancing), Quarkus 4 (a later upgrade commit).

## 2. Architecture

Three planes plus shared libraries and the Experiment suite.

### 2.1 Platform (Fault targets)

```
viewer-simulator ─▶ edge-gateway ─▶ api ─┬─▶ profile
                                         ├─▶ catalog ─┬─▶ cache (Redis)
                                         │            └─▶ store (DynamoDB Local)
                                         ├─▶ recommendations
                                         ├─▶ playback ─▶ license
                                         └─▶ queue (ElasticMQ) ─▶ ratings-worker ─▶ store
```

A Viewer journey is: `GET /home` (api calls profile, catalog, recommendations), pick a title, `POST /play` (api calls playback, which calls license), then `POST /ratings` (api sends a message to queue). The journey counts as a Playback Start when `/play` returns a licensed stream. The rating is sent after the start and is not part of it.

| Dependency | Kind | Fallback |
|---|---|---|
| edge-gateway → api | Critical | none |
| api → profile | Non-critical | anonymous profile |
| api → catalog | Critical | none |
| catalog → cache | Non-critical | read from store |
| catalog → store | Critical | none |
| api → recommendations | Non-critical | popular titles held in api |
| api → playback | Critical | none |
| playback → license | Critical | none |
| api → queue | Non-critical | drop the rating, count it |
| ratings-worker → queue, ratings-worker → store | Asynchronous, off the journey | messages wait in queue |

Platform Services are Quarkus apps: `edge-gateway`, `api`, `profile`, `catalog`, `recommendations`, `playback`, `license`, `ratings-worker`. Infrastructure containers: `cache` (Redis), `store` (`amazon/dynamodb-local`), `queue` (`softwaremill/elasticmq-native`). Service-to-Service calls are blocking REST through the Quarkus REST Client. AWS calls use AWS SDK v2 through Quarkiverse Amazon Services, pointed at the emulators. Quarkus Dev Services are disabled everywhere.

Endpoints are blocking and run on a small bounded worker pool with a bounded queue (ADR 0002). Starting values: `quarkus.thread-pool.max-threads=16`, `quarkus.thread-pool.queue-size=32`, tuned during implementation so that Act 4 visibly exhausts `api`.

Catalog data is a seeded set of about 50 fictional titles, loaded into `store` at startup by `catalog`.

### 2.2 Control plane (never a Fault target)

- **chaos-controller**: owns Fault rules and their TTLs, stops and starts platform containers through the Docker API (mounted `/var/run/docker.sock`), holds Resilience Policy switches, holds the active Experiment and its group percentages, applies Act presets and reset.
- **telemetry**: receives events, aggregates 1-second windows, serves SPS and per-Dependency figures.
- **dashboard**: serves the HTML page and pushes live state over WebSockets Next.
- **viewer-simulator**: runs Viewer journeys at a configurable rate (default 50 Viewers/s) and emits Playback Start events.

The dashboard offers no control that stops a control-plane container.

### 2.3 Shared libraries (used by every platform Service)

- **lib-telemetry**: emits call and breaker events; propagates the group header `X-Chaos-Group` (`control`, `experiment` or absent) on incoming requests, outgoing REST calls and SQS message attributes.
- **lib-fault-injection**: FIT-style Fault application on the caller side, as a REST Client request filter and an AWS SDK `ExecutionInterceptor`. Polls Fault rules every second.
- **lib-resilience**: `ResilientDependency`, one per Dependency, wrapping a named SmallRye Fault Tolerance `TypedGuard` (timeout, retry with exponential backoff and jitter, circuit breaker, bulkhead, Fallback). Polls Resilience Policy switches every second and routes each call through the enabled parts of the guard. Publishes breaker state changes from `CircuitBreakerMaintenance.onStateChange`.

### 2.4 Repository layout

```
pom.xml                  root aggregator, Quarkus 3.40 BOM, Java 25
libs/                    lib-telemetry, lib-fault-injection, lib-resilience
platform/                edge-gateway, api, profile, catalog, recommendations, playback, license, ratings-worker
control-plane/           chaos-controller, telemetry, dashboard, viewer-simulator
experiments/             JUnit 5 Experiment suite and the @ChaosExperiment extension
docker-compose.yml
.run/                    IntelliJ run configurations
docs/en/, docs/pt-br/    human documentation
README.md, README.pt-BR.md
```

Each Quarkus module ships the Quarkus-generated `src/main/docker/Dockerfile.jvm` on an Eclipse Temurin 25 JRE base, with a JVM memory cap so the 15 containers fit in about 2.5 GB.

## 3. Telemetry and data flow

### 3.1 Events

- **Call event**: written by the caller for every Dependency call. Fields: `timestamp`, `caller`, `callee`, `group`, `outcome`, `latencyMs`. `outcome` is one of `ok`, `error`, `timeout`, `rejected` (bulkhead full), `short-circuited` (breaker open), `fallback`.
- **Breaker event**: `timestamp`, `caller`, `callee`, `state` (`closed`, `open`, `half-open`).
- **Playback Start event**: emitted by viewer-simulator when `/play` succeeds, tagged with the group that edge-gateway echoed back in the response header.

### 3.2 Transport

Services buffer events in a bounded in-memory queue and POST batches to telemetry every 250 ms. When telemetry is slow or down, new events are dropped and the drop count is reported with the next batch. Emitting telemetry never blocks a request.

### 3.3 Aggregation

telemetry keeps 600 one-second windows (10 minutes) in memory. Each window holds:

- per Dependency: calls/s, error percentage, p50 and p99 latency, count per outcome, current breaker state
- SPS: total, Control Group, Experiment Group
- per Service: health (rule below), combining container state from chaos-controller with traffic evidence

### 3.4 Health rule

Applied in this order, first match wins:

1. **grey**: container stopped
2. **red**: error percentage ≥ 50% or unreachable
3. **amber**: serving Fallbacks, or error percentage ≥ 5%, or p99 latency > 1 s
4. **green**: otherwise

### 3.5 Consumers

- dashboard reads the latest window every 500 ms and pushes it to browsers; breaker events are forwarded immediately.
- Experiments read `GET /sps?group={control|experiment}&from={epochMs}`.

## 4. Faults, Resilience Policies and Experiments

### 4.1 Fault types

| Type | Target | Scope | Effect |
|---|---|---|---|
| `stop` | a Service | all Viewers only | container stopped through the Docker API; started again on removal |
| `latency` | a Dependency | all or Experiment Group | caller waits `delayMs` before the call |
| `error` | a Dependency | all or Experiment Group | call fails with probability `errorRate` (HTTP 503, or an SDK service exception) |
| `unavailable` | a Dependency | all or Experiment Group | call fails at once with a connection-refused exception |

`stop` cannot be limited to the Experiment Group because the container serves every Viewer. Experiments therefore use `unavailable` to simulate a stopped Service, as Netflix ChAP does through FIT; `stop` is for Ad-hoc Faults and the Chaos Monkey Acts.

### 4.2 chaos-controller API

- `POST /faults` with `{type, target, params, scope, ttl}` returns a Fault id. `DELETE /faults/{id}`. `GET /faults` lists active rules with `expiresAt`.
- `GET /policies`, `PUT /policies/{caller}/{callee}` with `{timeout, retry, breaker, bulkhead, fallback}` booleans. All switches are on at startup.
- `POST /experiments` with `{name, hypothesis, controlPercent, experimentPercent}` registers the active Experiment; a second registration while one is active returns `409 Conflict`. `DELETE /experiments/current` ends it. `PUT /experiments/current/progress` receives live progress for the dashboard.
- `POST /acts/{n}` applies an Act preset. `POST /reset` removes all Faults, switches every policy on, ends any Experiment, and starts stopped containers.
- `GET /services` lists platform containers and their state.

Ad-hoc Faults from the dashboard use the same API with scope `all` and a default TTL of 2 minutes; the dashboard shows a warning banner while any is active.

edge-gateway assigns each new Viewer journey to the Control Group, the Experiment Group or neither, using the active Experiment's percentages, and returns the assignment in `X-Chaos-Group`.

### 4.3 Dead man's switch

1. Every Fault rule carries `expiresAt`; lib-fault-injection ignores expired rules on its own, so Faults lapse even if chaos-controller is down.
2. The Experiment extension removes its Faults and ends the Experiment in a `finally` block.
3. On startup chaos-controller starts any container labelled `chaos.target=true` that is stopped.

### 4.4 Resilience Policy defaults

Starting values per Dependency, tuned during implementation: timeout 300 ms (catalog and store 500 ms); retry 2 attempts, 50 ms base delay, exponential backoff with jitter; circuit breaker opens at 50% failures over 20 calls, stays open 5 s; bulkhead 8 concurrent calls; Fallback as listed in 2.1. Each of the five parts can be switched off independently, which Act 4 needs to show a retry storm.

### 4.5 Experiments as JUnit tests

```java
@ChaosExperiment(
    hypothesis = "Viewers still start playback when recommendations is unavailable",
    blastRadius = @BlastRadius(control = 20, experiment = 20),
    duration = "60s",
    tolerance = 0.10,
    abortWhen = @AbortCondition(belowRatio = 0.5, forSeconds = 5))
void recommendationsUnavailable(ChaosRun run) {
    run.inject(Fault.unavailable("api", "recommendations"));
}
```

Lifecycle run by the extension:

1. **Precondition.** Platform healthy and total SPS above a minimum; otherwise the test is skipped with "no Steady State, no Experiment".
2. Register the Experiment with chaos-controller, then observe a 10 s baseline.
3. Inject the declared Faults with TTL = duration + 30 s, scope Experiment Group.
4. Every second, compare Experiment Group SPS with Control Group SPS; if the ratio stays below `belowRatio` for `forSeconds`, abort.

A Fault on an asynchronous Dependency (ratings-worker → queue, ratings-worker → store) is not tied to a Viewer, so it is injected with scope `all`. An Experiment containing a scope `all` Fault compares total SPS with its own 10 s baseline instead of comparing the two groups, because both groups would move together and the group ratio would hide the effect.
5. In `finally`, remove the Faults and end the Experiment.
6. **Verdict.** Held when Experiment Group SPS stays within `tolerance` of Control Group SPS (or total SPS within `tolerance` of baseline, for scope `all`) over the run: the test passes. Disproved otherwise: the test fails with the measured SPS figures. Aborted: the test fails with a message starting `ABORTED`.

Progress is sent to chaos-controller every second so the dashboard shows the run live.

The experiments module is a plain JUnit 5 module (not `@QuarkusTest`) that talks to the already running stack.

### 4.6 Shipped Experiments

| # | Fault | Expected Verdict | In CI |
|---|---|---|---|
| 1 | `unavailable` api → recommendations | held (popular titles Fallback) | yes |
| 2 | `latency` 2 s api → profile | held (timeout, bulkhead, anonymous profile) | yes |
| 3 | `unavailable` catalog → cache | held (read from store, latency rises) | yes |
| 4 | `unavailable` ratings-worker → queue, scope `all` | held (SPS unaffected, queue grows) | yes |
| 5 | `unavailable` api → playback | disproved (Critical Dependency) | no, tagged `lecture` |
| 6 | `error` 50% playback → license | held (retry with jitter) | yes |

## 5. Dashboard

Layout A (chosen with the visual companion):

- **Top bar:** Act buttons 1 to 8, Viewers/s slider, Reset.
- **SPS strip:** always visible; lines for total, Control Group and Experiment Group.
- **Topology (centre, widest area):** cytoscape.js graph (vendored, works offline) of the platform. Node colour follows the health rule in 3.4. Edges animate dots in proportion to call rate. A dashed red edge means its breaker is open.
- **Right drawer:** for the selected Service: stop/start. For the selected Dependency: latency and error sliders, `unavailable` toggle, five Resilience Policy switches, breaker state, live calls/s, error % and p99.
- **Bottom Experiment panel:** name, Hypothesis, elapsed/duration, live SPS ratio, Abort Condition, Verdict when finished, and a short history of past Verdicts.

The page is served by the dashboard module from `META-INF/resources`; the only JavaScript is the page script and the vendored cytoscape.js.

## 6. Lecture Acts

Each Act is a preset reachable from the dashboard and from an IntelliJ run configuration. The lecture guide describes, per Act, the goal, the steps, what students should observe, the Netflix source, and discussion questions.

1. **Steady State.** Traffic flows, SPS flat, all green.
2. **Chaos Monkey, unprotected.** All policies off; stop recommendations. `/home` fails, Viewers never press play, SPS drops to zero: a Cascading Failure from a Non-critical Dependency.
3. **Chaos Monkey, protected.** Policies on; stop recommendations again. api turns amber, popular titles are served, SPS holds.
4. **Slow is worse than down.** 2 s latency on api → profile with timeout and bulkhead off: api's worker pool fills, `/play` stalls too, SPS collapses. Then retries on and breaker off: profile's load multiplies (retry storm). Then all switches on: SPS recovers.
5. **Critical Dependency.** Stop playback with policies on: SPS drops anyway. Some failures cannot be hidden; they must be prevented or made rare.
6. **Experiment as code.** Run Experiment 1 from the IntelliJ gutter and watch it on the dashboard; then run it with the Fallback switched off and watch the Abort Condition fire.
7. **Blast Radius.** During an Experiment, the 60% of Viewers outside both groups keep starting playback; only the Experiment Group line moves.
8. **Continuous verification.** A pull request switching off the api → recommendations Fallback default turns CI red. Done live when the network allows; a prepared branch `lecture/broken-fallback` with an open pull request and a recorded red run is the offline backup.

## 7. Documentation

- `README.md` (English) and `README.pt-BR.md`, each linking to the other at the top: what this is, prerequisites (JDK 25, Docker, IntelliJ), quick start, Act overview, a security note about `docker.sock` and binding to `127.0.0.1`.
- `docs/en/` and `docs/pt-br/`, the same pages in each language: getting-started, architecture, telemetry, faults-and-experiments, writing-experiments, dashboard, lecture-guide, glossary.
- Domain terms stay in English in both languages; Portuguese text puts the term in bold with a gloss on first use, for example "**Blast Radius** (raio de impacto)". Each glossary page points to `CONTEXT.md` as the source of truth.
- `CONTRIBUTING.md` gains the rule that a documentation change updates both languages in the same commit.
- The lecture guide cites Netflix sources: the Chaos Monkey, FIT and ChAP posts on the Netflix Tech Blog, the Hystrix post, *Chaos Engineering* (Basiri et al., IEEE Software 2016) and principlesofchaos.org.

## 8. Testing and CI

Development is test first.

1. **Unit** (JUnit 5, AssertJ, no Quarkus): `ResilientDependency` switch combinations and breaker opening; Fault rule matching by caller, callee, group and expiry; Hypothesis and Abort Condition arithmetic.
2. **Service** (`@QuarkusTest` per Service): downstream REST mocked with WireMock; DynamoDB Local and ElasticMQ started by a Testcontainers test resource using the same images as Compose. Checks Fallbacks, group header propagation and emitted telemetry.
3. **Extension:** the `@ChaosExperiment` lifecycle against a fake chaos-controller and fake telemetry: skip without Steady State, abort path, Faults removed when the test body throws.
4. **System:** the Experiment suite against the running Compose stack.

Commands: `./mvnw verify` runs levels 1 to 3. `./mvnw verify -Pexperiments` runs level 4 and needs the stack up; Experiments tagged `lecture` run only with `-Dgroups=lecture`.

GitHub Actions (`.github/workflows/ci.yml`):

- `build`: `./mvnw verify`.
- `chaos` (after `build`): package, `docker compose up --wait`, Steady State warm-up, `./mvnw verify -Pexperiments`, upload JUnit reports and Compose logs, `docker compose down`.

## 9. Security

chaos-controller mounts the Docker socket, which is root-equivalent on the host. Every published port binds to `127.0.0.1`, there is no authentication, and the README and lecture guide warn never to expose the stack on a shared network.

## 10. Delivery slices

The implementation plan builds the system in slices, each ending in something runnable and in its own run of contextual commits, with both documentation languages updated in the slice that introduces the behaviour:

1. Maven skeleton, Compose with emulators, platform Services and viewer-simulator; SPS visible in logs (Act 1 without dashboard).
2. lib-telemetry, telemetry and the dashboard topology with live SPS (Act 1).
3. lib-fault-injection, chaos-controller Faults, stop/start and TTL; dashboard drawer (Acts 2 and 5).
4. lib-resilience, policy switches and breaker events (Acts 3 and 4).
5. Experiment extension, groups at edge-gateway, the six Experiments, Experiment panel (Acts 6 and 7).
6. CI workflow, `lecture/broken-fallback` branch, README and lecture guide completed in both languages (Act 8).
