#!/usr/bin/env python3
"""Run in the service container: consistent SQLite backup, including artifacts."""
from pathlib import Path
import os
import sqlite3
import sys

destination = Path(sys.argv[1])
os.umask(0o077)
if destination.exists():
    raise SystemExit("Backup target already exists")
with sqlite3.connect(os.environ.get("MOZHI_AGENT_DB", "/data/agent.sqlite3")) as source:
    with sqlite3.connect(destination) as target:
        source.backup(target)
        if target.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
            raise SystemExit("Backup validation failed")
print("Backup completed")
