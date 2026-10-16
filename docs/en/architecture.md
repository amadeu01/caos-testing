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

A **Dependency** is a call from one Service to another.

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
| ratings-worker → queue | Asynchronous, off the Viewer journey | ratings wait in the queue |
| ratings-worker → store | Asynchronous, off the Viewer journey | ratings wait in the queue |

"Could" is deliberate: in this slice no Dependency has a Fallback yet, so every failure spreads. Later slices add Resilience Policies and let you switch them on and off.

## AWS without an AWS account

Services use the real AWS SDK v2, pointed at local emulators: DynamoDB Local for DynamoDB, ElasticMQ for SQS, and Redis standing in for ElastiCache. Each emulator is its own container. [ADR 0001](../adr/0001-aws-sdk-against-per-service-emulators.md) explains why this is not LocalStack.

## Threads, on purpose

Every platform Service handles requests on a small, bounded pool of 16 worker threads. That is deliberate: it lets a slow Dependency visibly exhaust a Service in Act 4 (an Act is a scripted lecture step). See [ADR 0002](../adr/0002-fixed-platform-thread-pools.md).

## Measuring every call

Every Dependency call goes through `DependencyCalls`, which reports it to `telemetry`; see [Telemetry](telemetry.md). The dashboard draws what telemetry knows; see [Dashboard](dashboard.md).

## Repository layout

| Path | Contents |
|---|---|
| `platform/` | the 8 platform Services (Fault targets) |
| `control-plane/` | viewer-simulator, telemetry and dashboard; chaos-controller later |
| `libs/` | test resources and lib-telemetry (the DependencyCalls seam) |
| `infra/` | emulator configuration and table creation |
| `scripts/`, `.run/` | start and stop, from the shell or IntelliJ |
