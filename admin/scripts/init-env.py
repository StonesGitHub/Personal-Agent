#!/usr/bin/env python3
from pathlib import Path
from hashlib import pbkdf2_hmac
import getpass
import os
import secrets

root = Path(__file__).resolve().parents[1]
if (root / ".env").exists():
    raise SystemExit(".env already exists; not overwritten")
password = getpass.getpass("设置管理后台密码（至少12位）: ")
if len(password) < 12 or password != getpass.getpass("再次输入密码: "):
    raise SystemExit("密码过短或两次不一致")
salt = secrets.token_hex(16)
hashed = "pbkdf2_sha256$310000$" + salt + "$" + pbkdf2_hmac("sha256", password.encode(), salt.encode(), 310000).hex()
# Single quotes prevent Docker Compose from treating '$' in the hash as interpolation.
body = (root / ".env.example").read_text().replace("MOZHI_ADMIN_PASSWORD_HASH=\n", "MOZHI_ADMIN_PASSWORD_HASH='" + hashed + "'\n")
with os.fdopen(os.open(root / ".env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as f:
    f.write(body)
print("已生成 .env（0600）。请填写管理 API 地址、内部凭证与后台 HTTPS 域名。")
