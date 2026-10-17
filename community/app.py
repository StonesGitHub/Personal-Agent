"""Mozhi discovery: a small, independent shared Q&A service for an invited pilot."""
from contextlib import contextmanager
from hashlib import sha256
from pathlib import Path
import hmac
import os
import secrets
import sqlite3
import time
from typing import Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, StrictBool


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class SessionInput(Input):
    nickname: str = Field(default="", max_length=24)


class PostInput(Input):
    client_id: str = Field(pattern=r"^[a-zA-Z0-9-]{16,64}$")
    question: str = Field(min_length=1, max_length=8000)
    answer: str = Field(min_length=1, max_length=24000)


class LikeInput(Input):
    liked: StrictBool


class CommentInput(Input):
    client_id: str = Field(pattern=r"^[a-zA-Z0-9-]{16,64}$")
    text: str = Field(min_length=1, max_length=2000)


class ReportInput(Input):
    kind: Literal["post", "comment"]
    target_id: int = Field(gt=0)
    reason: str = Field(min_length=1, max_length=300)


SCHEMA = """
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS users (
 id TEXT PRIMARY KEY, token_hash TEXT UNIQUE NOT NULL, nickname TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS posts (
 id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL REFERENCES users(id),
 client_id TEXT NOT NULL, question TEXT NOT NULL, answer TEXT NOT NULL, created INTEGER NOT NULL,
 UNIQUE(user_id, client_id));
CREATE TABLE IF NOT EXISTS likes (
 post_id INTEGER NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
 user_id TEXT NOT NULL REFERENCES users(id), PRIMARY KEY(post_id,user_id));
CREATE TABLE IF NOT EXISTS comments (
 id INTEGER PRIMARY KEY AUTOINCREMENT, post_id INTEGER NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
 user_id TEXT NOT NULL REFERENCES users(id), client_id TEXT NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL,
 UNIQUE(user_id,client_id));
CREATE INDEX IF NOT EXISTS comment_page ON comments(post_id,id);
CREATE TABLE IF NOT EXISTS reports (
 kind TEXT NOT NULL, target_id INTEGER NOT NULL, user_id TEXT NOT NULL REFERENCES users(id),
 reason TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(kind,target_id,user_id));
CREATE TABLE IF NOT EXISTS quotas (
 bucket TEXT NOT NULL, window INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(bucket,window));
"""


def create_app(db_path: str | None = None, admin_token: str | None = None) -> FastAPI:
    path = db_path or os.environ.get("MOZHI_COMMUNITY_DB", "data/community.sqlite3")
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    admin_secret = admin_token if admin_token is not None else os.environ.get("MOZHI_MODERATOR_TOKEN", "")

    @contextmanager
    def db():
        connection = sqlite3.connect(path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    with db() as connection:
        connection.executescript(SCHEMA)

    app = FastAPI(title="墨知服务", version="0.5.0", docs_url=None, redoc_url=None)

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request, _exception):
        # Do not reflect raw submitted text or credentials in error bodies/logs.
        return JSONResponse({"detail": "Invalid content or unsupported fields"}, status_code=422)

    @app.middleware("http")
    async def bounded_body(request: Request, call_next):
        if request.method in {"POST", "PUT", "PATCH"}:
            raw = bytearray()
            async for piece in request.stream():
                raw.extend(piece)
                if len(raw) > 150_000:
                    return JSONResponse({"detail": "Body too large"}, status_code=413)
            request._body = bytes(raw)
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    def user(authorization: str | None = Header(default=None)):
        if not authorization:
            return None
        if not authorization.startswith("Bearer ") or len(authorization) > 256:
            raise HTTPException(401, "Invalid session")
        hashed = sha256(authorization[7:].encode()).hexdigest()
        with db() as connection:
            result = connection.execute("SELECT id,nickname FROM users WHERE token_hash=?", (hashed,)).fetchone()
        if result is None:
            raise HTTPException(401, "Invalid session")
        return dict(result)

    def signed_in(identity=Depends(user)):
        if identity is None:
            raise HTTPException(401, "Session required")
        return identity

    def moderator(authorization: str | None = Header(default=None)):
        expected = "Bearer " + admin_secret
        if not admin_secret or not hmac.compare_digest(authorization or "", expected):
            raise HTTPException(403, "Moderator required")

    def quota(key: str, limit: int, seconds: int):
        now = int(time.time())
        window = now // seconds
        bucket = sha256(key.encode()).hexdigest()
        with db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("DELETE FROM quotas WHERE window < ? AND bucket=?", (window, bucket))
            row = connection.execute("SELECT count FROM quotas WHERE bucket=? AND window=?", (bucket, window)).fetchone()
            if row is not None and row[0] >= limit:
                raise HTTPException(429, "Please try later")
            connection.execute("INSERT INTO quotas VALUES(?,?,1) ON CONFLICT(bucket,window) DO UPDATE SET count=count+1", (bucket, window))

    def require_post(connection, post_id):
        row = connection.execute("SELECT * FROM posts WHERE id=?", (post_id,)).fetchone()
        if row is None:
            raise HTTPException(404, "Post not found")
        return row

    def post_value(connection, post_id, identity):
        row = require_post(connection, post_id)
        viewer = identity["id"] if identity else ""
        return {"id": row["id"], "question": row["question"], "answer": row["answer"],
                "created": row["created"], "nickname": connection.execute("SELECT nickname FROM users WHERE id=?", (row["user_id"],)).fetchone()[0],
                "mine": viewer == row["user_id"],
                "liked": connection.execute("SELECT 1 FROM likes WHERE post_id=? AND user_id=?", (post_id, viewer)).fetchone() is not None,
                "likes": connection.execute("SELECT count(*) FROM likes WHERE post_id=?", (post_id,)).fetchone()[0],
                "comments": connection.execute("SELECT count(*) FROM comments WHERE post_id=?", (post_id,)).fetchone()[0]}

    @app.get("/health")
    def health():
        with db() as connection:
            connection.execute("SELECT 1").fetchone()
        return {"ok": True, "version": "0.5.0"}

    @app.post("/v1/sessions")
    def create_session(value: SessionInput, request: Request):
        quota("session:" + (request.client.host if request.client else "unknown"), 12, 3600)
        token, uid = secrets.token_urlsafe(32), secrets.token_hex(16)
        nickname = value.nickname or "墨友" + uid[:4]
        with db() as connection:
            connection.execute("INSERT INTO users VALUES(?,?,?)", (uid, sha256(token.encode()).hexdigest(), nickname))
        return {"token": token, "nickname": nickname}

    @app.get("/v1/posts")
    def feed(before: int | None = Query(default=None, gt=0), limit: int = Query(default=10, ge=1, le=20), mine: bool = False, identity=Depends(user)):
        if mine and identity is None:
            raise HTTPException(401, "Session required")
        with db() as connection:
            rows = connection.execute("SELECT id FROM posts WHERE id < ? AND (?=0 OR user_id=?) ORDER BY id DESC LIMIT ?", (before or 2**63-1, int(mine), identity["id"] if identity else "", limit+1)).fetchall()
            page = rows[:limit]
            return {"items": [post_value(connection, row[0], identity) for row in page], "next_before": page[-1][0] if len(rows)>limit else 0}

    @app.get("/v1/posts/{post_id}")
    def get_post(post_id: int, identity=Depends(user)):
        with db() as connection:
            return post_value(connection, post_id, identity)

    @app.post("/v1/posts")
    def publish(value: PostInput, identity=Depends(signed_in)):
        quota("publish:"+identity["id"], 20, 3600)
        with db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            old = connection.execute("SELECT * FROM posts WHERE user_id=? AND client_id=?", (identity["id"], value.client_id)).fetchone()
            if old is not None:
                if (old["question"], old["answer"]) != (value.question, value.answer):
                    raise HTTPException(409, "This share was already submitted")
                return post_value(connection, old["id"], identity)
            cursor = connection.execute("INSERT INTO posts(user_id,client_id,question,answer,created) VALUES(?,?,?,?,?)", (identity["id"], value.client_id, value.question, value.answer, int(time.time()*1000)))
            return post_value(connection, cursor.lastrowid, identity)

    @app.put("/v1/posts/{post_id}/like")
    def like(post_id: int, value: LikeInput, identity=Depends(signed_in)):
        quota("like:"+identity["id"], 120, 60)
        with db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            require_post(connection, post_id)
            if value.liked:
                connection.execute("INSERT OR IGNORE INTO likes VALUES(?,?)", (post_id, identity["id"]))
            else:
                connection.execute("DELETE FROM likes WHERE post_id=? AND user_id=?", (post_id, identity["id"]))
            return post_value(connection, post_id, identity)

    @app.get("/v1/posts/{post_id}/comments")
    def comments(post_id: int, before: int | None = Query(default=None, gt=0), identity=Depends(user)):
        with db() as connection:
            require_post(connection, post_id)
            rows = connection.execute("SELECT c.*,u.nickname FROM comments c JOIN users u ON c.user_id=u.id WHERE post_id=? AND c.id<? ORDER BY c.id DESC LIMIT 21", (post_id, before or 2**63-1)).fetchall()
            page = rows[:20]
            return {"items": [{"id": x["id"], "text": x["text"], "created": x["created"], "nickname": x["nickname"], "mine": bool(identity and x["user_id"]==identity["id"])} for x in page], "next_before": page[-1]["id"] if len(rows)>20 else 0}

    @app.post("/v1/posts/{post_id}/comments")
    def comment(post_id: int, value: CommentInput, identity=Depends(signed_in)):
        quota("comment:"+identity["id"], 30, 60)
        with db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            require_post(connection, post_id)
            old = connection.execute("SELECT * FROM comments WHERE user_id=? AND client_id=?", (identity["id"], value.client_id)).fetchone()
            if old is not None:
                if old["post_id"]!=post_id or old["text"]!=value.text:
                    raise HTTPException(409, "Comment already submitted")
                return {"id": old["id"]}
            cursor = connection.execute("INSERT INTO comments(post_id,user_id,client_id,text,created) VALUES(?,?,?,?,?)", (post_id, identity["id"], value.client_id, value.text, int(time.time()*1000)))
            return {"id": cursor.lastrowid}

    def delete_post(connection, post_id):
        # Also remove personal information from reports for deleted content.
        connection.execute("DELETE FROM reports WHERE kind='comment' AND target_id IN (SELECT id FROM comments WHERE post_id=?)", (post_id,))
        connection.execute("DELETE FROM reports WHERE kind='post' AND target_id=?", (post_id,))
        connection.execute("DELETE FROM posts WHERE id=?", (post_id,))

    @app.delete("/v1/posts/{post_id}")
    def remove_post(post_id: int, identity=Depends(signed_in)):
        with db() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = require_post(connection, post_id)
            if row["user_id"]!=identity["id"]:
                raise HTTPException(403, "Not your post")
            delete_post(connection, post_id)
        return {"ok": True}

    @app.delete("/v1/comments/{comment_id}")
    def remove_comment(comment_id: int, identity=Depends(signed_in)):
        with db() as connection:
            row = connection.execute("SELECT * FROM comments WHERE id=?", (comment_id,)).fetchone()
            if row is None:
                raise HTTPException(404, "Comment not found")
            if row["user_id"]!=identity["id"]:
                raise HTTPException(403, "Not your comment")
            connection.execute("DELETE FROM comments WHERE id=?", (comment_id,))
            connection.execute("DELETE FROM reports WHERE kind='comment' AND target_id=?", (comment_id,))
        return {"ok": True}

    @app.post("/v1/reports")
    def report(value: ReportInput, identity=Depends(signed_in)):
        quota("report:"+identity["id"], 20, 3600)
        with db() as connection:
            table = "posts" if value.kind=="post" else "comments"
            if connection.execute("SELECT id FROM "+table+" WHERE id=?", (value.target_id,)).fetchone() is None:
                raise HTTPException(404, "Content not found")
            connection.execute("INSERT OR IGNORE INTO reports VALUES(?,?,?,?,?)", (value.kind, value.target_id, identity["id"], value.reason, int(time.time()*1000)))
        return {"ok": True}

    @app.get("/moderation/reports", dependencies=[Depends(moderator)])
    def reported():
        with db() as connection:
            return {"items": [dict(row) for row in connection.execute("SELECT kind,target_id,reason,created FROM reports ORDER BY created DESC LIMIT 100")]}

    @app.delete("/moderation/posts/{post_id}", dependencies=[Depends(moderator)])
    def moderate_post(post_id: int):
        with db() as connection:
            require_post(connection, post_id)
            delete_post(connection, post_id)
        return {"ok": True}

    @app.delete("/moderation/comments/{comment_id}", dependencies=[Depends(moderator)])
    def moderate_comment(comment_id: int):
        with db() as connection:
            connection.execute("DELETE FROM comments WHERE id=?", (comment_id,))
            connection.execute("DELETE FROM reports WHERE kind='comment' AND target_id=?", (comment_id,))
        return {"ok": True}

    from web_service import install_web
    install_web(app, quota)
    return app
