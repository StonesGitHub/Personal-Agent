#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
test -f .env || { echo 'Run python3 scripts/init-env.py first'; exit 1; }
docker compose config --quiet
docker compose up -d --build --wait
