#!/usr/bin/env bash
# Builds every module and starts the whole platform in Docker Compose, then waits until it is ready.
set -euo pipefail
cd "$(dirname "$0")/.."

# --no-build: skip the Maven build (IntelliJ builds first, with the project SDK).
if [[ "${1:-}" != "--no-build" ]]; then
  ./mvnw -q package -DskipTests
fi
docker compose up -d --build

echo "Waiting for edge-gateway and viewer-simulator..."
for url in http://127.0.0.1:8000/q/health/ready http://127.0.0.1:8090/q/health/ready; do
  for _ in $(seq 1 120); do
    curl -fs "$url" >/dev/null 2>&1 && break
    sleep 1
  done
  if ! curl -fs "$url" >/dev/null 2>&1; then
    echo "Timed out waiting for $url" >&2
    docker compose ps
    exit 1
  fi
done

echo "Platform is up. Watch SPS with: docker compose logs -f viewer-simulator"
