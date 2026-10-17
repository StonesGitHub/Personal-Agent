#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
umask 077
mkdir -p backups
name="community-$(date -u +%Y%m%dT%H%M%SZ)-${RANDOM}.sqlite3"
docker compose exec -T discovery python scripts/backup_db.py "/tmp/$name"
docker compose cp "discovery:/tmp/$name" "backups/$name"
docker compose exec -T discovery rm "/tmp/$name"
chmod 600 "backups/$name"
printf '%s\n' "Verified backup: backups/$name"
