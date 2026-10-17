#!/usr/bin/env python3
"""Create private secrets without overwriting existing configuration or printing keys."""
from pathlib import Path
import os
import secrets

root = Path(__file__).resolve().parent.parent
text = (root / ".env.example").read_text()
text = text.replace("MOZHI_MODERATOR_TOKEN=\n", "MOZHI_MODERATOR_TOKEN=" + secrets.token_urlsafe(36) + "\n")
text = text.replace("MOZHI_WEB_TOKENS=\n", "MOZHI_WEB_TOKENS=" + secrets.token_urlsafe(36) + "\n")
try:
    fd = os.open(root / ".env", os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
except FileExistsError:
    raise SystemExit("Existing .env preserved; edit it in place.")
with os.fdopen(fd, "w") as f:
    f.write(text)
print("Created private .env. Configure domain/provider before deployment; secrets were not printed.")
