#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
[[ -f .env ]] || { echo 'Run python3 scripts/init-env.py first.' >&2; exit 1; }
files=(-f compose.yaml)
if [[ ${1:-} == --https ]]; then files+=(-f compose.https.yaml); elif [[ $# -ne 0 ]]; then echo 'Usage: scripts/deploy.sh [--https]' >&2; exit 1; fi
docker compose "${files[@]}" config --quiet
docker compose "${files[@]}" up -d --build --wait --wait-timeout 120
docker compose "${files[@]}" ps
printf '%s\n' 'Service started. Run scripts/smoke.py against the selected endpoint.'
