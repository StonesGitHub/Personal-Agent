#!/usr/bin/env python3
"""Keep independently deployed Android/server skill resources identical."""
from pathlib import Path
import json
import sys

root = Path(__file__).resolve().parents[1]
source = (root / "skills/builtin.json").read_bytes()
catalog = json.loads(source)
assert {s["id"] for s in catalog["skills"]} == {"travel", "reminders", "health"}
for skill in catalog["skills"]:
    assert len(skill["body"]) <= 6000 and len(skill["trigger"]) <= 200
for path in (root / "app/src/main/assets/builtin_skills.json", root / "server/mozhi_service/builtin_skills.json"):
    if "--check" in sys.argv:
        if not path.exists() or path.read_bytes() != source:
            raise SystemExit(f"Skill resources differ: {path.relative_to(root)}; run scripts/sync-skills.py")
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(source)
