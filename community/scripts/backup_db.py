#!/usr/bin/env python3
"""Consistent online SQLite backup, including committed WAL data."""
from pathlib import Path
import os
import sqlite3
import sys

source = Path(os.environ.get("MOZHI_COMMUNITY_DB", "/data/community.sqlite3"))
destination = Path(sys.argv[1])
if not source.is_file() or destination.exists() or source.resolve() == destination.resolve():
    raise SystemExit("Source must exist and destination must be a new file.")
destination.parent.mkdir(parents=True, exist_ok=True)
os.umask(0o077)
with sqlite3.connect("file:" + str(source) + "?mode=ro", uri=True) as db, sqlite3.connect(destination) as copy:
    db.backup(copy)
    assert copy.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
print("Database backup verified.")
