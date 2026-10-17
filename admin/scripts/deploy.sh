#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
test -f .env || { echo 'Run python3 scripts/init-env.py first'; exit 1; }
file=compose.yaml
if [ "${1:-}" = "--remote" ]; then file=compose.remote.yaml; fi
docker compose -f "$file" config --quiet
docker compose -f "$file" up -d --build --wait
