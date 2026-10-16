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

When telemetry is slow or down, Services keep serving. The buffer drops events and counts them; a request never waits for telemetry. Each Service sends from its own thread, not from its 16 request threads, so a Service whose threads are all stuck can still tell you so. Each Service sends at most one batch at a time: while one is in flight, the next flush waits and events stay in the bounded buffer. On the receiving side, `telemetry` ignores batches without a service name and skips incomplete events instead of failing. Try it: `docker compose stop telemetry` and watch `docker compose logs -f viewer-simulator` keep printing SPS.

## What a window contains

`curl http://127.0.0.1:8082/windows/latest` shows the last *settled* second, about 2 s ago. `telemetry` waits a second for late batches before it calls a window complete, so the numbers never flicker while a second is still filling:

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
