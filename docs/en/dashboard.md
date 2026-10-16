# Dashboard

Open `http://127.0.0.1:8080` after `scripts/platform-up.sh` (or IntelliJ's **Platform up**).

| Area | What it shows |
|---|---|
| Top bar | the Viewers/s slider and the connection state (`live`, `reconnecting…`, `telemetry unavailable`) |
| SPS strip | SPS now, and the last 60 seconds as a line: this is Steady State |
| Topology | every Service and Dependency. Node colour is health ([telemetry](telemetry.md)). Edge label is calls per second and errors; dashes move faster with more traffic. Thick edges are Critical Dependencies, dotted ones are asynchronous |
| Right drawer | details of what you clicked: health of a Service; rate, errors, p50, p99 and outcomes of a Dependency |
| Experiment panel | empty for now; Experiments arrive in slice 5 |

The view runs about 2 s behind real time: it shows the last settled second ([telemetry](telemetry.md)).

`telemetry unavailable` also appears when telemetry answers with something that is not JSON, not only when it is unreachable. In that state, and while the dashboard reconnects, the graph dims and SPS shows `–`, so a stale picture is never mistaken for a healthy one.

## Try it

1. Watch SPS settle around 50.
2. `docker compose stop recommendations`. Within seconds recommendations turns red, then api and edge-gateway, and SPS falls to 0: a Non-critical Dependency took the whole platform down. That is Act 2, the Cascading Failure.
3. `docker compose start recommendations` and watch Steady State return.

Apart from the Viewers/s slider, the dashboard only reads; Faults and Resilience Policy switches arrive in the next slices.
