"""Standalone admin site and BFF. Imports no mobile, community, or Agent source code."""
from dataclasses import dataclass
from hashlib import pbkdf2_hmac, sha256
from pathlib import Path
import hmac
import os
import re
import secrets
import time
from urllib.parse import urlsplit

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, ConfigDict, Field

ROOT = Path(__file__).parent
COOKIE = "mozhi_admin_session"


def password_hash(password, salt=None):
    salt = salt or secrets.token_hex(16)
    return "pbkdf2_sha256$310000$" + salt + "$" + pbkdf2_hmac("sha256", password.encode(), salt.encode(), 310000).hex()


def verify_password(password, encoded):
    try:
        algorithm, rounds, salt, expected = encoded.split("$")
        if algorithm != "pbkdf2_sha256" or rounds != "310000" or len(expected) != 64:
            return False
        actual = password_hash(password, salt).rsplit("$", 1)[-1]
        return hmac.compare_digest(expected, actual)
    except (ValueError, TypeError):
        return False


@dataclass
class Settings:
    api_url: str
    api_token: str
    password: str
    username: str = "admin"
    origin: str = "https://localhost"
    secure_cookie: bool = True

    @classmethod
    def env(cls):
        return cls(os.environ.get("MOZHI_ADMIN_API_URL", "http://127.0.0.1:8081"),
                   os.environ.get("MOZHI_ADMIN_API_TOKEN", ""), os.environ.get("MOZHI_ADMIN_PASSWORD_HASH", ""),
                   os.environ.get("MOZHI_ADMIN_USERNAME", "admin"), os.environ.get("MOZHI_ADMIN_ORIGIN", "https://localhost").rstrip("/"),
                   os.environ.get("MOZHI_ADMIN_SECURE_COOKIE", "1") != "0")


class Login(BaseModel):
    model_config = ConfigDict(extra="forbid")
    username: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=512)


ROUTES = {
    "GET": r"(?:overview|users|config|runs|audit)",
    "POST": r"(?:users|users/[a-f0-9]{32}/rotate-token|runs/[a-f0-9]{32}/cancel)",
    "PATCH": r"users/[a-f0-9]{32}",
    "PUT": r"config",
}


def create_app(settings=None, transport=None):
    settings = settings or Settings.env()
    parsed = urlsplit(settings.api_url)
    if parsed.scheme not in ("https", "http") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("MOZHI_ADMIN_API_URL must be a fixed internal HTTP(S) service URL")
    if not settings.secure_cookie and urlsplit(settings.origin).hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("Insecure cookies are permitted only for localhost development")
    app = FastAPI(title="墨知管理后台", docs_url=None, redoc_url=None, openapi_url=None)
    sessions, attempts = {}, {}

    def session(request):
        token = request.cookies.get(COOKIE, "")
        digest = sha256(token.encode()).hexdigest()
        value = sessions.get(digest)
        if not value or value["expires"] < time.time():
            sessions.pop(digest, None)
            raise HTTPException(401, "登录已过期，请重新登录")
        return value

    def origin(request):
        provided = request.headers.get("origin")
        if provided and provided != settings.origin:
            raise HTTPException(403, "请求来源无效")

    def csrf(request):
        origin(request)
        value = session(request)
        if not hmac.compare_digest(request.headers.get("x-csrf-token", ""), value["csrf"]):
            raise HTTPException(403, "请求校验失败，请刷新页面")
        return value

    @app.exception_handler(RequestValidationError)
    async def invalid(_request, _exception):
        return JSONResponse({"detail": "提交字段无效"}, status_code=422)

    @app.middleware("http")
    async def headers(request, call_next):
        if request.method in ("POST", "PUT", "PATCH"):
            body = bytearray()
            async for part in request.stream():
                body.extend(part)
                if len(body) > 64000:
                    return JSONResponse({"detail": "请求过大"}, status_code=413)
            request._body = bytes(body)
        response = await call_next(request)
        response.headers.update({"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff", "X-Frame-Options": "DENY",
            "Referrer-Policy": "no-referrer", "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
            "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"})
        return response

    @app.get("/health")
    async def health():
        return {"ok": True}

    @app.get("/")
    async def index():
        return FileResponse(ROOT / "static/index.html")

    @app.get("/assets/{name}")
    async def asset(name: str):
        if name not in {"app.js", "style.css"}:
            raise HTTPException(404)
        return FileResponse(ROOT / "static" / name)

    @app.post("/api/login")
    async def login(value: Login, request: Request):
        origin(request)
        if len(settings.api_token) < 32 or not settings.password:
            raise HTTPException(503, "管理后台尚未完成初始化")
        t = time.time()
        for key in list(attempts):
            if attempts[key][0] + 900 < t:
                attempts.pop(key)
        ip = request.client.host if request.client else "unknown"
        started, count = attempts.get(ip, (t, 0))
        if count >= 10 or sum(x[1] for x in attempts.values()) >= 100:
            raise HTTPException(429, "登录尝试过多，请15分钟后重试")
        attempts[ip] = (started, count + 1)
        if not hmac.compare_digest(value.username.encode(), settings.username.encode()) or not verify_password(value.password, settings.password):
            raise HTTPException(401, "用户名或密码错误")
        attempts.pop(ip, None)
        for key in list(sessions):
            if sessions[key]["expires"] < t:
                sessions.pop(key)
        if len(sessions) >= 100:
            raise HTTPException(429, "登录会话已满")
        token, csrf_token = secrets.token_urlsafe(36), secrets.token_urlsafe(32)
        sessions[sha256(token.encode()).hexdigest()] = {"csrf": csrf_token, "expires": t + 8 * 3600}
        response = JSONResponse({"csrf": csrf_token, "username": settings.username})
        response.set_cookie(COOKIE, token, max_age=8 * 3600, httponly=True, secure=settings.secure_cookie, samesite="strict", path="/")
        return response

    @app.get("/api/session")
    async def current_session(request: Request):
        value = session(request)
        return {"csrf": value["csrf"], "username": settings.username}

    @app.post("/api/logout")
    async def logout(request: Request):
        csrf(request)
        sessions.pop(sha256(request.cookies.get(COOKIE, "").encode()).hexdigest(), None)
        response = JSONResponse({"ok": True})
        response.delete_cookie(COOKIE, httponly=True, secure=settings.secure_cookie, samesite="strict", path="/")
        return response

    @app.api_route("/api/{path:path}", methods=["GET", "POST", "PATCH", "PUT"])
    async def proxy(path: str, request: Request):
        session(request)
        if not re.fullmatch(ROUTES.get(request.method, r"(?!)"), path):
            raise HTTPException(404, "接口不存在")
        if request.method != "GET":
            csrf(request)
        params = {k: v for k, v in request.query_params.items() if k in {"q", "offset", "limit", "state", "user_id", "before"}}
        if any(len(v) > 200 for v in params.values()):
            raise HTTPException(422, "查询参数过长")
        try:
            async with httpx.AsyncClient(timeout=20, transport=transport, trust_env=False, follow_redirects=False) as client:
                async with client.stream(request.method, settings.api_url.rstrip("/") + "/admin/v1/" + path,
                    params=params, content=await request.body(), headers={"Authorization": "Bearer " + settings.api_token, "Content-Type": "application/json"}) as response:
                    body = bytearray()
                    async for part in response.aiter_bytes():
                        body.extend(part)
                        if len(body) > 2_000_000:
                            raise HTTPException(502, "管理服务响应过大")
                    if response.status_code in (401, 403):
                        raise HTTPException(502, "后台与管理服务的连接凭证无效，请检查部署配置")
                    if 300 <= response.status_code < 400 or response.status_code >= 500:
                        raise HTTPException(502, "管理服务暂时不可用")
                    import json
                    return JSONResponse(json.loads(body), status_code=response.status_code)
        except (httpx.HTTPError, ValueError):
            raise HTTPException(502, "无法连接管理服务，请检查服务地址和网络") from None

    return app
