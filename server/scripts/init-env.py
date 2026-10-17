#!/usr/bin/env python3
from pathlib import Path
import os
import secrets

root = Path(__file__).resolve().parents[1]
body = (root / ".env.example").read_text().replace("MOZHI_ADMIN_API_TOKEN=\n", "MOZHI_ADMIN_API_TOKEN=" + secrets.token_urlsafe(36) + "\n")
body = body.replace("MOZHI_AUTH_SECRET=\n", "MOZHI_AUTH_SECRET=" + secrets.token_urlsafe(48) + "\n")
with os.fdopen(os.open(root / ".env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as f:
    f.write(body)
print("已生成 .env（0600）。请填写模型密钥；管理员服务凭证请通过私密方式同步至独立管理后台。")
