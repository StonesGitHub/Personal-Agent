"""SQLite queue and checkpoints; run one worker on one host, with an OS process lock."""
from contextlib import contextmanager
from hashlib import sha256
from pathlib import Path
import json
import secrets
import sqlite3
import time

from .settings import DEFAULT_CONFIG


def now():
    return int(time.time() * 1000)


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


class Problem(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message
        super().__init__(message)


SCHEMA = """
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS users (
 id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, nickname TEXT NOT NULL,
 status TEXT NOT NULL DEFAULT 'active', daily_run_limit INTEGER NOT NULL,
 enabled_tools TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS config (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, body TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS runs (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), client_request_id TEXT NOT NULL,
 conversation_id TEXT NOT NULL, payload_hash TEXT NOT NULL, request TEXT NOT NULL,
 state TEXT NOT NULL, config TEXT NOT NULL, messages TEXT NOT NULL DEFAULT '[]',
 plan TEXT NOT NULL DEFAULT '[]', output TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
 rounds INTEGER NOT NULL DEFAULT 0, calls INTEGER NOT NULL DEFAULT 0,
 prompt_tokens INTEGER NOT NULL DEFAULT 0, completion_tokens INTEGER NOT NULL DEFAULT 0,
 elapsed_ms INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL, updated INTEGER NOT NULL,
 UNIQUE(user_id,client_request_id));
CREATE INDEX IF NOT EXISTS run_queue ON runs(state,created);
CREATE INDEX IF NOT EXISTS run_user ON runs(user_id,created);
CREATE UNIQUE INDEX IF NOT EXISTS active_conversation ON runs(user_id,conversation_id)
 WHERE state IN ('queued','running') AND conversation_id<>'';
CREATE TABLE IF NOT EXISTS events (
 id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL REFERENCES runs(id),
 kind TEXT NOT NULL, message TEXT NOT NULL, created INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS event_run ON events(run_id,id);
CREATE TABLE IF NOT EXISTS artifacts (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), run_id TEXT NOT NULL REFERENCES runs(id),
 call_id TEXT NOT NULL, name TEXT NOT NULL, media_type TEXT NOT NULL,
 content TEXT NOT NULL, created INTEGER NOT NULL, UNIQUE(run_id,call_id));
CREATE TABLE IF NOT EXISTS attachment_uploads (
 id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL REFERENCES users(id), created INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS attachment_uploads_user ON attachment_uploads(user_id,created);
CREATE TABLE IF NOT EXISTS attachments (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), name TEXT NOT NULL,
 kind TEXT NOT NULL, content TEXT NOT NULL, created INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS attachments_user ON attachments(user_id,created);
CREATE TABLE IF NOT EXISTS audit (
 id INTEGER PRIMARY KEY AUTOINCREMENT, action TEXT NOT NULL, target TEXT NOT NULL,
 detail TEXT NOT NULL, created INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS worker_status (id INTEGER PRIMARY KEY CHECK(id=1), heartbeat INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS reminders (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), body TEXT NOT NULL,
 state TEXT NOT NULL, next_fire INTEGER NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS reminder_due ON reminders(state,next_fire);
CREATE INDEX IF NOT EXISTS reminder_user ON reminders(user_id,state);
CREATE TABLE IF NOT EXISTS reminder_events (
 id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL REFERENCES users(id),
 reminder_id TEXT NOT NULL REFERENCES reminders(id), scheduled_at INTEGER NOT NULL,
 title TEXT NOT NULL, body TEXT NOT NULL, created INTEGER NOT NULL, acknowledged INTEGER NOT NULL DEFAULT 0,
 UNIQUE(reminder_id,scheduled_at));
CREATE INDEX IF NOT EXISTS reminder_inbox ON reminder_events(user_id,acknowledged,id);
CREATE TABLE IF NOT EXISTS health_records (
 id TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id), body TEXT NOT NULL,
 occurred_at INTEGER NOT NULL, created INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS health_user ON health_records(user_id,occurred_at);
CREATE TABLE IF NOT EXISTS personal_effects (
 user_id TEXT NOT NULL REFERENCES users(id), effect_key TEXT NOT NULL, fingerprint TEXT NOT NULL,
 response TEXT NOT NULL, created INTEGER NOT NULL, PRIMARY KEY(user_id,effect_key));
CREATE TABLE IF NOT EXISTS cancelled_requests (
 user_id TEXT NOT NULL REFERENCES users(id), client_request_id TEXT NOT NULL,
 created INTEGER NOT NULL, PRIMARY KEY(user_id,client_request_id));
"""


class Store:
    def __init__(self, path, model="deepseek-flash"):
        self.path = path
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        from .accounts import SCHEMA as ACCOUNT_SCHEMA
        with self.db() as c:
            c.executescript(SCHEMA + ACCOUNT_SCHEMA)
            c.execute("INSERT OR IGNORE INTO config VALUES(1,1,?)", (encode({**DEFAULT_CONFIG, "model": model}),))

    @contextmanager
    def db(self):
        c = sqlite3.connect(self.path, timeout=10)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA foreign_keys=ON")
        try:
            with c:
                yield c
        finally:
            c.close()

    @staticmethod
    def event(c, rid, kind, message):
        c.execute("INSERT INTO events(run_id,kind,message,created) VALUES(?,?,?,?)", (rid, kind, message[:1000], now()))

    @staticmethod
    def audit(c, action, target, detail=""):
        c.execute("INSERT INTO audit(action,target,detail,created) VALUES(?,?,?,?)", (action, target, detail, now()))

    @staticmethod
    def user_value(row):
        return {k: json.loads(row[k]) if k == "enabled_tools" else row[k] for k in row.keys() if k != "token_hash"}

    def user_by_token(self, token):
        if not token or len(token) > 200:
            raise Problem(401, "用户凭证无效")
        with self.db() as c:
            hashed = sha256(token.encode()).hexdigest()
            row = c.execute("SELECT * FROM users WHERE token_hash=?", (hashed,)).fetchone()
            if row is None:
                row = c.execute("SELECT u.* FROM users u JOIN auth_sessions s ON s.user_id=u.id WHERE s.token_hash=? AND s.expires>?", (hashed, now())).fetchone()
        if row is None:
            raise Problem(401, "用户凭证无效")
        if row["status"] != "active":
            raise Problem(403, "此用户已停用")
        return self.user_value(row)

    def user(self, uid):
        with self.db() as c:
            row = c.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
        if row is None:
            raise Problem(404, "用户不存在")
        return self.user_value(row)

    def create_user(self, data):
        uid, token = secrets.token_hex(16), secrets.token_urlsafe(36)
        with self.db() as c:
            c.execute("INSERT INTO users VALUES(?,?,?,'active',?,?,?,?)", (uid, sha256(token.encode()).hexdigest(), data["nickname"], data["daily_run_limit"], encode(data["enabled_tools"]), now(), now()))
            self.audit(c, "user.create", uid)
        return {"user": self.user(uid), "token": token}

    def edit_user(self, uid, data):
        allowed = {"nickname", "status", "daily_run_limit", "enabled_tools"}
        if not data or not set(data) <= allowed or any(v is None for v in data.values()):
            raise Problem(422, "请选择有效的用户字段")
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            if c.execute("SELECT 1 FROM users WHERE id=?", (uid,)).fetchone() is None:
                raise Problem(404, "用户不存在")
            values = [encode(v) if k == "enabled_tools" else v for k, v in data.items()]
            c.execute("UPDATE users SET " + ",".join(k + "=?" for k in data) + ",updated=? WHERE id=?", (*values, now(), uid))
            if data.get("status") == "suspended":
                self.cancel_user_runs(c, uid, "用户被停用")
            self.audit(c, "user.update", uid, encode(sorted(data)))
        return self.user(uid)

    def rotate_token(self, uid):
        token = secrets.token_urlsafe(36)
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            if not c.execute("UPDATE users SET token_hash=?,updated=? WHERE id=?", (sha256(token.encode()).hexdigest(), now(), uid)).rowcount:
                raise Problem(404, "用户不存在")
            c.execute("DELETE FROM auth_sessions WHERE user_id=?", (uid,))
            self.cancel_user_runs(c, uid, "用户凭证已重置")
            self.audit(c, "user.rotate_token", uid)
        return {"token": token}

    def cancel_user_runs(self, c, uid, reason):
        ids = c.execute("SELECT id FROM runs WHERE user_id=? AND state IN ('queued','running','interrupted')", (uid,)).fetchall()
        c.execute("UPDATE runs SET state='cancelled',error=?,updated=? WHERE user_id=? AND state IN ('queued','running','interrupted')", (reason, now(), uid))
        for row in ids:
            self.event(c, row[0], "cancelled", reason)

    def config(self):
        with self.db() as c:
            row = c.execute("SELECT * FROM config WHERE id=1").fetchone()
        return {"revision": row["revision"], "config": json.loads(row["body"])}

    def set_config(self, config, revision):
        with self.db() as c:
            if not c.execute("UPDATE config SET body=?,revision=revision+1 WHERE id=1 AND revision=?", (encode(config), revision)).rowcount:
                raise Problem(409, "配置已被其他管理员修改，请刷新后重试")
            self.audit(c, "config.update", "runtime", "revision=" + str(revision + 1))
        return self.config()

    def reserve_attachment(self, uid):
        """Account for upload attempts before potentially billable vision inference."""
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            timestamp = now()
            c.execute("DELETE FROM attachment_uploads WHERE created<?", (timestamp - 86400000,))
            user = c.execute("SELECT daily_run_limit FROM users WHERE id=?", (uid,)).fetchone()
            if user is None or user[0] <= 0:
                raise Problem(429, "用户附件上传额度已关闭")
            minute = c.execute("SELECT count(*) FROM attachment_uploads WHERE user_id=? AND created>=?",
                               (uid, timestamp - 60000)).fetchone()[0]
            day = c.execute("SELECT count(*) FROM attachment_uploads WHERE user_id=? AND created>=?",
                            (uid, timestamp - 86400000)).fetchone()[0]
            if minute >= 6 or day >= min(30, user[0] * 3):
                raise Problem(429, "附件上传次数已达限额，请稍后再试")
            c.execute("INSERT INTO attachment_uploads(user_id,created) VALUES(?,?)", (uid, timestamp))

    def save_attachment(self, uid, name, kind, content):
        ident = secrets.token_hex(16)
        with self.db() as c:
            c.execute("DELETE FROM attachments WHERE created<?", (now() - 30 * 86400000,))
            c.execute("INSERT INTO attachments VALUES(?,?,?,?,?,?)", (ident, uid, name, kind, content, now()))
        return {"id": ident, "name": name, "kind": kind, "characters": len(content)}

    def attachment(self, uid, ident):
        with self.db() as c:
            row = c.execute("SELECT id,name,kind,content FROM attachments WHERE id=? AND user_id=?", (ident, uid)).fetchone()
        if row is None:
            raise Problem(404, "附件不存在或无权访问")
        return dict(row)

    def submit(self, uid, payload):
        body = encode(payload)
        fingerprint = sha256(body.encode()).hexdigest()
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            prior = c.execute("SELECT id,payload_hash,request FROM runs WHERE user_id=? AND client_request_id=?", (uid, payload["client_request_id"])).fetchone()
            if prior:
                if prior["payload_hash"] != fingerprint:
                    # New optional request fields must not break an old APK's pending idempotent retry.
                    from .schemas import RunInput
                    try:
                        same = RunInput.model_validate_json(prior["request"]).model_dump() == RunInput.model_validate(payload).model_dump()
                    except ValueError:
                        same = False
                    if not same:
                        raise Problem(409, "此请求编号已被其他内容使用")
                return prior["id"]
            for aid in payload.get("attachments", []):
                if c.execute("SELECT 1 FROM attachments WHERE id=? AND user_id=?", (aid, uid)).fetchone() is None:
                    raise Problem(404, "附件不存在或无权访问")
            if c.execute("SELECT 1 FROM cancelled_requests WHERE user_id=? AND client_request_id=?", (uid, payload["client_request_id"])).fetchone():
                raise Problem(409, "此请求已经取消，不会再次创建任务")
            user = c.execute("SELECT * FROM users WHERE id=?", (uid,)).fetchone()
            if user is None or user["status"] != "active":
                raise Problem(403, "此用户已停用")
            # Quota day uses Asia/Shanghai UTC+8, even if server uses UTC.
            day_start = ((int(time.time()) + 28800) // 86400 * 86400 - 28800) * 1000
            count = c.execute("SELECT count(*) FROM runs WHERE user_id=? AND created>=?", (uid, day_start)).fetchone()[0]
            if count >= user["daily_run_limit"]:
                raise Problem(429, "今日任务额度已用完")
            active = c.execute("SELECT count(*) FROM runs WHERE user_id=? AND state IN ('queued','running')", (uid,)).fetchone()[0]
            if active >= 3:
                raise Problem(429, "请等待已有任务结束，最多同时保留三个任务")
            if payload["conversation_id"] and c.execute("SELECT 1 FROM runs WHERE user_id=? AND conversation_id=? AND state IN ('queued','running')", (uid, payload["conversation_id"])).fetchone():
                raise Problem(409, "此对话已有未完成任务")
            if c.execute("SELECT count(*) FROM runs WHERE state='queued'").fetchone()[0] >= 1000:
                raise Problem(429, "任务队列已满，请稍后重试")
            config = json.loads(c.execute("SELECT body FROM config WHERE id=1").fetchone()[0])
            config["enabled_tools"] = [t for t in config["enabled_tools"] if t in json.loads(user["enabled_tools"]) and (payload["allow_web"] or t not in ("web_search", "read_url"))]
            rid = secrets.token_hex(16)
            c.execute("INSERT INTO runs(id,user_id,client_request_id,conversation_id,payload_hash,request,state,config,created,updated) VALUES(?,?,?,?,?,?,'queued',?,?,?)", (rid, uid, payload["client_request_id"], payload["conversation_id"], fingerprint, body, encode(config), now(), now()))
            self.event(c, rid, "queued", "任务已保存，等待执行")
        return rid

    def run(self, rid, uid=None, *, internal=False):
        with self.db() as c:
            row = c.execute("SELECT * FROM runs WHERE id=?" + (" AND user_id=?" if uid else ""), (rid, uid) if uid else (rid,)).fetchone()
            if row is None:
                raise Problem(404, "任务不存在")
            value = dict(row)
            if internal:
                for key in ("request", "messages", "config", "plan"):
                    value[key] = json.loads(value[key])
            else:
                for key in ("request", "messages", "config", "payload_hash"):
                    value.pop(key)
                value["plan"] = json.loads(value["plan"])
                value["artifacts"] = [dict(a) for a in c.execute("SELECT id,name,media_type,created,length(content) AS size FROM artifacts WHERE run_id=? ORDER BY created", (rid,))]
        return value

    def cancel(self, rid, uid=None, *, admin=False):
        self.run(rid, uid)
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            if c.execute("UPDATE runs SET state='cancelled',updated=? WHERE id=? AND state IN ('queued','running','interrupted')", (now(), rid)).rowcount:
                self.event(c, rid, "cancelled", "已取消；之前生成的文件仍保留")
                if admin:
                    self.audit(c, "run.cancel", rid)
        return self.run(rid, uid)

    def cancel_request(self, client_request_id, uid):
        # A durable cancellation can arrive before a delayed submission. Both use the same lock.
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            c.execute("INSERT OR IGNORE INTO cancelled_requests VALUES(?,?,?)", (uid, client_request_id, now()))
            row = c.execute("SELECT id FROM runs WHERE user_id=? AND client_request_id=?", (uid, client_request_id)).fetchone()
            if row and c.execute("UPDATE runs SET state='cancelled',updated=? WHERE id=? AND state IN ('queued','running','interrupted')", (now(), row[0])).rowcount:
                self.event(c, row[0], "cancelled", "用户已取消此请求")
        return self.run(row[0], uid) if row else {"id": "", "state": "cancelled"}

    def resume(self, rid, uid):
        self.run(rid, uid)
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            r = c.execute("SELECT * FROM runs WHERE id=?", (rid,)).fetchone()
            if r["state"] in ("queued", "running"):
                return self.run(rid, uid)
            if r["state"] != "interrupted":
                raise Problem(409, "只有中断的任务可从检查点继续")
            if c.execute("SELECT count(*) FROM runs WHERE user_id=? AND state IN ('queued','running')", (uid,)).fetchone()[0] >= 3:
                raise Problem(429, "请等待已有任务结束")
            try:
                c.execute("UPDATE runs SET state='queued',error='',updated=? WHERE id=?", (now(), rid))
            except sqlite3.IntegrityError:
                raise Problem(409, "此对话已有其他任务运行") from None
            self.event(c, rid, "queued", "将从已保存进度继续；未保存的模型请求可能再次计费")
        return self.run(rid, uid)

    def heartbeat(self):
        with self.db() as c:
            c.execute("INSERT INTO worker_status VALUES(1,?) ON CONFLICT(id) DO UPDATE SET heartbeat=excluded.heartbeat", (now(),))

    def recover(self):
        with self.db() as c:
            rows = c.execute("SELECT id FROM runs WHERE state='running'").fetchall()
            c.execute("UPDATE runs SET state='interrupted',error='执行进程中断，可继续任务',updated=? WHERE state='running'", (now(),))
            for row in rows:
                self.event(c, row[0], "interrupted", "执行进程中断，检查点已保留")

    def claim(self):
        with self.db() as c:
            c.execute("BEGIN IMMEDIATE")
            row = c.execute("SELECT id FROM runs WHERE state='queued' ORDER BY created,id LIMIT 1").fetchone()
            if not row:
                return None
            c.execute("UPDATE runs SET state='running',updated=? WHERE id=?", (now(), row[0]))
            self.event(c, row[0], "running", "开始执行")
            return row[0]

    def checkpoint(self, rid, **values):
        allowed = {"messages", "rounds", "calls", "prompt_tokens", "completion_tokens", "elapsed_ms"}
        assert set(values) <= allowed
        with self.db() as c:
            return c.execute("UPDATE runs SET " + ",".join(k + "=?" for k in values) + ",updated=? WHERE id=? AND state='running'", (*[encode(v) if k == "messages" else v for k, v in values.items()], now(), rid)).rowcount > 0

    def finish(self, rid, state, output="", error=""):
        assert state in ("completed", "failed", "interrupted")
        with self.db() as c:
            if c.execute("UPDATE runs SET state=?,output=?,error=?,updated=? WHERE id=? AND state='running'", (state, output, error, now(), rid)).rowcount:
                self.event(c, rid, state, {"completed": "任务完成", "failed": "任务失败", "interrupted": "任务中断"}[state])

    def artifact(self, aid, uid):
        with self.db() as c:
            row = c.execute("SELECT * FROM artifacts WHERE id=? AND user_id=?", (aid, uid)).fetchone()
        if row is None:
            raise Problem(404, "文件不存在")
        return dict(row)
