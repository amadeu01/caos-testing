# Bounded worker threads instead of virtual threads

Services handle requests as blocking endpoints on Quarkus's bounded worker thread pool, deliberately configured small, and never use virtual threads (`@RunOnVirtualThread`) or the reactive event loop for Service-to-Service calls. The lecture needs a slow Dependency to visibly exhaust the caller's request threads so that a missing timeout or bulkhead causes a Cascading Failure; virtual threads and non-blocking I/O largely hide that effect. Do not "upgrade" to either without replacing that lesson.
