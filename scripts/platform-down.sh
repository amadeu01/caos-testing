#!/usr/bin/env bash
# Stops and removes every container of the platform.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down --remove-orphans
