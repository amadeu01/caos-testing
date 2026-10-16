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

## Telemetry graphs

The **Telemetry graphs** link in the top bar opens `http://127.0.0.1:8080/telemetry.html`, which charts the last 1, 5 (default) or 10 minutes. Use it to see *when* each Dependency broke and recovered, which the topology's single "now" cannot show.

- **SPS** across the top: Steady State over time. Control and Experiment lines appear only when they are non-zero.
- **One card per Dependency** (`caller → callee`): calls per second, error percentage, and p50 and p99 latency in ms. The thin strip under each card has one cell per second, coloured green, amber or red by the Dependency's health, grey when there was no data.

Stop a Service and watch error % and p99 climb on the Dependencies that call it, then the strip turn red; start it again and read off how long recovery took. History loads once, then the page follows the same live connection as the dashboard and dims when telemetry is unavailable.

## Try it

1. Watch SPS settle around 50.
2. `docker compose stop recommendations`. recommendations stops answering, and no Dependency has a timeout yet, so api's calls to it wait. All 16 api request threads fill, api starts rejecting requests, edge-gateway's calls to api fail and SPS falls to 0. recommendations turns red when its heartbeat stops (3 s). A Non-critical Dependency took the whole platform down: that is Act 2, the Cascading Failure.
3. `docker compose start recommendations`. Steady State returns once the stuck calls have failed.

Apart from the Viewers/s slider, the dashboard only reads; Faults and Resilience Policy switches arrive in the next slices.
