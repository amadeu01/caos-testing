# Own telemetry with an explicit DependencyCalls seam

Every platform Service sends Dependency call events to our own `telemetry` Service, and every Dependency call goes through one visible seam, `DependencyCalls.call("profile", () -> ...)`, instead of OpenTelemetry auto-instrumentation or Micrometer metrics scraped by Prometheus. The lecture needs one-second windows on stage (a 5 to 15 s scrape interval feels broken when you just stopped a Service), offline startup without extra containers, and code students can read end to end. The explicit seam is also where slice 3 injects Faults and slice 4 adds Resilience Policies, so each lesson changes one place.

## Considered Options

- **OpenTelemetry with an OTLP collector and a tracing UI.** Industry standard and worth a slide, but adds containers, hides the measuring inside agents and filters, and its UIs are not a live topology.
- **Micrometer and Prometheus.** Quarkus supports it out of the box, but scraping is too coarse for live chaos on a projector, and Grafana would be a second UI.
- **JAX-RS client filters instead of an explicit seam.** Automatic, but filters never see connection failures, and AWS SDK and Redis calls would need a second mechanism.
