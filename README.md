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
