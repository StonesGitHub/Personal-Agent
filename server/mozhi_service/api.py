"""Public and administrator APIs are separate ASGI apps and separate listeners."""
import hmac
import re
from urllib.parse import quote

from fastapi import Depends, FastAPI, Header, Query, Request, File, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response

from .accounts import Accounts, CodeInput, VerifyInput, ChunkInput, CompleteInput
from .schemas import RunInput, UserCreate, UserPatch, ConfigUpdate
from .settings import Settings, TOOLS
from .store import Store, Problem, now
from .personal import PersonalStore, ReminderCreate, HealthCreate, InboxAck
from .skills import CATALOG
from .attachments import MAX_BYTES, clean_name, extract_text, recognize_image


def base_app(title):
    app = FastAPI(title=title, version="0.9.0", docs_url=None, redoc_url=None, openapi_url=None)

    @app.exception_handler(Problem)
    async def problem(_request, error):
        return JSONResponse({"detail": error.message}, status_code=error.status)

    @app.exception_handler(RequestValidationError)
    async def validation(_request, _error):
        return JSONResponse({"detail": "字段无效或超出允许范围"}, status_code=422)

    @app.middleware("http")
    async def limits(request: Request, call_next):
        if request.method in ("POST", "PUT", "PATCH"):
            body = bytearray()
            async for part in request.stream():
                body.extend(part)
                limit = MAX_BYTES + 65536 if request.url.path == "/v1/agent/attachments" else 300000
                if len(body) > limit:
                    return JSONResponse({"detail": "请求过大"}, status_code=413)
            request._body = bytes(body)
        response = await call_next(request)
        response.headers.update({"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})
        return response

    return app


def create_app(settings=None, store=None):
    settings = settings or Settings.env()
    store = store or Store(settings.db_path, settings.model)
    app = base_app("墨知 Agent API")
    app.state.store = store
    personal = PersonalStore(store)
    accounts = Accounts(store, settings)
    app.state.accounts = accounts

    @app.post("/v1/auth/code")
    def send_code(value: CodeInput, request: Request):
        return accounts.send_code(value.phone, request.client.host if request.client else "unknown")

    @app.post("/v1/auth/verify")
    def verify(value: VerifyInput, authorization: str | None = Header(default=None)):
        token = authorization[7:] if authorization and authorization.startswith("Bearer ") else ""
        return accounts.verify(value, token)

    @app.post("/v1/auth/logout")
    def logout(authorization: str | None = Header(default=None)):
        token = authorization[7:] if authorization and authorization.startswith("Bearer ") else ""
        return accounts.logout(token)


    def identity(authorization: str | None = Header(default=None)):
        if not authorization or not authorization.startswith("Bearer "):
            raise Problem(401, "请提供用户凭证")
        return store.user_by_token(authorization[7:])

    def permission(user, tool):
        if tool not in user["enabled_tools"] or tool not in store.config()["config"]["enabled_tools"]:
            raise Problem(403, "此操作未启用，请联系管理员调整工具权限")

    @app.post("/v1/account/archives/{ident}/chunks")
    def import_chunk(ident: str, value: ChunkInput, user=Depends(identity)):
        return accounts.chunk(user["id"], ident, value)

    @app.post("/v1/account/archives/{ident}/complete")
    def complete_archive(ident: str, value: CompleteInput, user=Depends(identity)):
        return accounts.complete(user["id"], ident, value)

    @app.get("/v1/account/archives")
    def archives(user=Depends(identity)):
        return accounts.archives(user["id"])

    @app.get("/v1/account/archives/{ident}/chunks")
    def archive_chunk(ident: str, seq: int = Query(default=0, ge=0, le=50000), user=Depends(identity)):
        return accounts.read_chunk(user["id"], ident, seq)

    @app.get("/v1/skills")
    def skills(user=Depends(identity)):
        return {"items": list(CATALOG.values())}

    @app.get("/v1/reminders")
    def reminders(user=Depends(identity)):
        return {"items": personal.reminders(user["id"]), "server_time": now()}

    @app.post("/v1/reminders")
    def add_reminder(value: ReminderCreate, user=Depends(identity)):
        permission(user, "create_reminder")
        try:
            return personal.write_reminder(user["id"], value.model_dump(exclude={"client_request_id"}), "client:" + value.client_request_id)
        except ValueError as error:
            raise Problem(422, str(error)) from None

    @app.post("/v1/reminders/{ident}/update")
    def update_reminder(ident: str, value: ReminderCreate, user=Depends(identity)):
        permission(user, "update_reminder")
        try:
            return personal.write_reminder(user["id"], value.model_dump(exclude={"client_request_id"}), "client:" + value.client_request_id, reminder_id=ident)
        except ValueError as error:
            raise Problem(422, str(error)) from None

    @app.post("/v1/reminders/{ident}/cancel")
    def cancel_reminder(ident: str, user=Depends(identity)):
        return personal.cancel(user["id"], ident)

    @app.get("/v1/reminders/inbox")
    def reminder_inbox(user=Depends(identity)):
        return {"items": personal.inbox(user["id"])}

    @app.post("/v1/reminders/inbox/ack")
    def acknowledge_reminders(value: InboxAck, user=Depends(identity)):
        return personal.acknowledge(user["id"], value.ids)

    @app.get("/v1/health-records")
    def health_records(before: int = Query(default=2**63 - 1, gt=0), user=Depends(identity)):
        return personal.health(user["id"], before)

    @app.post("/v1/health-records")
    def add_health_record(value: HealthCreate, user=Depends(identity)):
        permission(user, "save_health_record")
        try:
            return personal.save_health(user["id"], value.model_dump(exclude={"client_request_id"}), "health:" + value.client_request_id)
        except ValueError as error:
            raise Problem(422, str(error)) from None

    @app.post("/v1/health-records/{ident}/delete")
    def delete_health_record(ident: str, user=Depends(identity)):
        return personal.delete_health(user["id"], ident)

    @app.get("/health")
    def health():
        with store.db() as c:
            c.execute("SELECT 1").fetchone()
        return {"ok": True, "version": "0.9.0"}

    @app.get("/v1/me")
    def me(user=Depends(identity)):
        return accounts.profile(user["id"])

    @app.get("/v1/agent/status")
    def status(user=Depends(identity)):
        with store.db() as c:
            row = c.execute("SELECT heartbeat FROM worker_status WHERE id=1").fetchone()
        return {"ok": True, "version": "0.9.0", "worker_ready": bool(row and now() - row[0] < 20000),
                "model_configured": bool(settings.model_key), "nickname": user["nickname"],
                "enabled_tools": [t for t in user["enabled_tools"] if t in store.config()["config"]["enabled_tools"] and (t not in ("web_search", "read_url") or settings.web_base and settings.web_token)]}

    @app.post("/v1/agent/attachments")
    async def upload_attachment(file: UploadFile = File(...), user=Depends(identity)):
        name = clean_name(file.filename)
        try:
            data = await file.read(MAX_BYTES + 1)
        finally:
            await file.close()
        if not data or len(data) > MAX_BYTES:
            raise Problem(413, "附件大小必须在 1 字节至 8 MiB 之间")
        store.reserve_attachment(user["id"])
        suffix = name.rsplit(".", 1)[-1].lower()
        if suffix in ("docx", "xlsx"):
            content, kind = extract_text(name, data), "document"
        else:
            content, kind = await recognize_image(settings, name, data), "image"
        # No raw document or image bytes are persisted by this endpoint.
        return store.save_attachment(user["id"], name, kind, content)

    @app.post("/v1/agent/runs")
    def submit(value: RunInput, user=Depends(identity)):
        if not settings.model_key:
            raise Problem(503, "服务端尚未配置模型密钥")
        rid = store.submit(user["id"], value.model_dump())
        return store.run(rid, user["id"])

    @app.get("/v1/agent/runs")
    def runs(before: int = Query(default=2**63 - 1, gt=0), limit: int = Query(default=20, ge=1, le=100), user=Depends(identity)):
        with store.db() as c:
            rows = c.execute("SELECT rowid AS cursor,id,state,conversation_id,created,updated FROM runs WHERE user_id=? AND rowid<? ORDER BY rowid DESC LIMIT ?", (user["id"], before, limit + 1)).fetchall()
        return {"items": [dict(r) for r in rows[:limit]], "next_before": rows[limit - 1]["cursor"] if len(rows) > limit else 0}

    @app.get("/v1/agent/runs/{rid}")
    def get_run(rid: str, user=Depends(identity)):
        return store.run(rid, user["id"])

    @app.get("/v1/agent/requests/{client_request_id}")
    def by_request(client_request_id: str, user=Depends(identity)):
        with store.db() as c:
            row = c.execute("SELECT id FROM runs WHERE user_id=? AND client_request_id=?", (user["id"], client_request_id)).fetchone()
        if row is None:
            raise Problem(404, "任务尚未被服务端接收")
        return store.run(row[0], user["id"])

    @app.post("/v1/agent/requests/{client_request_id}/cancel")
    def cancel_request(client_request_id: str, user=Depends(identity)):
        if not re.fullmatch(r"[a-zA-Z0-9_-]{16,80}", client_request_id):
            raise Problem(422, "请求编号无效")
        return store.cancel_request(client_request_id, user["id"])

    @app.get("/v1/agent/runs/{rid}/events")
    def events(rid: str, after: int = Query(default=0, ge=0), user=Depends(identity)):
        store.run(rid, user["id"])
        with store.db() as c:
            rows = c.execute("SELECT id,kind,message,created FROM events WHERE run_id=? AND id>? ORDER BY id LIMIT 100", (rid, after)).fetchall()
        return {"items": [dict(r) for r in rows], "next_after": rows[-1]["id"] if rows else after}

    @app.post("/v1/agent/runs/{rid}/cancel")
    def cancel(rid: str, user=Depends(identity)):
        return store.cancel(rid, user["id"])

    @app.post("/v1/agent/runs/{rid}/resume")
    def resume(rid: str, user=Depends(identity)):
        return store.resume(rid, user["id"])

    @app.get("/v1/agent/artifacts/{aid}")
    def artifact(aid: str, user=Depends(identity)):
        a = store.artifact(aid, user["id"])
        return {k: a[k] for k in ("id", "name", "media_type", "content", "created", "run_id")}

    @app.get("/v1/agent/artifacts/{aid}/download")
    def download(aid: str, user=Depends(identity)):
        a = store.artifact(aid, user["id"])
        return Response(a["content"].encode("utf-8"), media_type=a["media_type"], headers={"Content-Disposition": "attachment; filename*=UTF-8''" + quote(a["name"], safe="")})

    return app


def create_admin_api(settings=None, store=None):
    settings = settings or Settings.env()
    store = store or Store(settings.db_path, settings.model)

    def admin(authorization: str | None = Header(default=None)):
        if len(settings.admin_token) < 32 or not hmac.compare_digest((authorization or "").encode(), ("Bearer " + settings.admin_token).encode()):
            raise Problem(401, "管理员认证失败")

    app = base_app("墨知内部管理 API")
    # Global dependency means even health/status cannot bypass admin authentication.
    from fastapi import APIRouter
    router = APIRouter(prefix="/admin/v1", dependencies=[Depends(admin)])

    @router.get("/overview")
    def overview():
        with store.db() as c:
            worker = c.execute("SELECT heartbeat FROM worker_status WHERE id=1").fetchone()
            return {"users": c.execute("SELECT count(*) FROM users").fetchone()[0],
                    "active_users": c.execute("SELECT count(*) FROM users WHERE status='active'").fetchone()[0],
                    "runs": {r[0]: r[1] for r in c.execute("SELECT state,count(*) FROM runs GROUP BY state")},
                    "worker_ready": bool(worker and now() - worker[0] < 20000),
                    "model_configured": bool(settings.model_key), "web_configured": bool(settings.web_base and settings.web_token),
                    "tools": list(TOOLS), "version": "0.9.0"}

    @router.get("/users")
    def users(q: str = Query(default="", max_length=100), offset: int = Query(default=0, ge=0), limit: int = Query(default=20, ge=1, le=100)):
        with store.db() as c:
            term = "%" + q + "%"
            total = c.execute("SELECT count(*) FROM users WHERE nickname LIKE ? OR id LIKE ?", (term, term)).fetchone()[0]
            rows = c.execute("SELECT u.*,(SELECT count(*) FROM runs r WHERE r.user_id=u.id) AS run_count FROM users u WHERE nickname LIKE ? OR id LIKE ? ORDER BY created DESC,id LIMIT ? OFFSET ?", (term, term, limit, offset)).fetchall()
        return {"items": [store.user_value(r) for r in rows], "total": total, "offset": offset, "limit": limit}

    @router.post("/users")
    def add_user(value: UserCreate):
        return store.create_user(value.model_dump())

    @router.patch("/users/{uid}")
    def edit_user(uid: str, value: UserPatch):
        return store.edit_user(uid, value.model_dump(exclude_unset=True))

    @router.post("/users/{uid}/rotate-token")
    def rotate(uid: str):
        return store.rotate_token(uid)

    @router.get("/config")
    def config():
        return store.config()

    @router.put("/config")
    def update_config(value: ConfigUpdate):
        return store.set_config(value.config.model_dump(), value.revision)

    @router.get("/runs")
    def runs(state: str = Query(default="", max_length=20), user_id: str = Query(default="", max_length=80), offset: int = Query(default=0, ge=0), limit: int = Query(default=20, ge=1, le=100)):
        where = "WHERE (?='' OR r.state=?) AND (?='' OR r.user_id=?)"
        args = (state, state, user_id, user_id)
        with store.db() as c:
            rows = c.execute("SELECT r.id,r.user_id,u.nickname,r.state,r.rounds,r.calls,r.prompt_tokens,r.completion_tokens,r.elapsed_ms,r.created,r.updated,r.error FROM runs r JOIN users u ON r.user_id=u.id " + where + " ORDER BY r.created DESC,r.id LIMIT ? OFFSET ?", (*args, limit, offset)).fetchall()
            total = c.execute("SELECT count(*) FROM runs r " + where, args).fetchone()[0]
        return {"items": [dict(r) for r in rows], "total": total, "offset": offset, "limit": limit}

    @router.post("/runs/{rid}/cancel")
    def cancel(rid: str):
        value = store.cancel(rid, admin=True)
        return {"id": value["id"], "state": value["state"]}

    @router.get("/audit")
    def audit(before: int = Query(default=2**63 - 1, gt=0)):
        with store.db() as c:
            rows = c.execute("SELECT * FROM audit WHERE id<? ORDER BY id DESC LIMIT 100", (before,)).fetchall()
        return {"items": [dict(r) for r in rows], "next_before": rows[-1]["id"] if len(rows) == 100 else 0}

    app.include_router(router)
    return app
