# Quarkus for every Java module

Every runnable Java module (platform Services, chaos controller, telemetry, dashboard) is built on Quarkus. This is a hard requirement from the course owner, not a technical preference, and it overrides earlier choices made with Spring Boot in mind. As a consequence the resilience mechanisms come from SmallRye Fault Tolerance (MicroProfile Fault Tolerance) rather than Resilience4j.
