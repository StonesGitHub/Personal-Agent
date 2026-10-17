"""User-owned reminders and health observations; no model calls when a timer fires."""
from datetime import datetime, timedelta, timezone
from hashlib import sha256
import json
import re
import secrets
from typing import Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import Field
from .schemas import Input
from .store import Problem, encode, now


class ReminderData(Input):
    title: str = Field(min_length=1, max_length=80)
    body: str = Field(default="", max_length=1000)
    start_at: str = Field(min_length=20, max_length=40)
    timezone: str = Field(default="Asia/Shanghai", max_length=80)
    repeat: Literal["once", "daily", "weekly"] = "once"


class ReminderCreate(ReminderData):
    client_request_id: str = Field(pattern=r"^[a-zA-Z0-9_-]{16,80}$")


class ReminderTool(ReminderData):
    evidence: str = Field(min_length=2, max_length=2000)


class ReminderEdit(ReminderTool):
    id: str = Field(pattern=r"^[a-f0-9]{32}$")


class OwnedId(Input):
    id: str = Field(pattern=r"^[a-f0-9]{32}$")


class RemoveTool(OwnedId):
    evidence: str = Field(min_length=2, max_length=2000)


class HealthData(Input):
    subject: str = Field(min_length=1, max_length=40)
    category: Literal["diet", "sleep", "symptom", "activity", "measurement"]
    text: str = Field(min_length=1, max_length=2000)
    occurred_at: str = Field(min_length=20, max_length=40)


class HealthTool(HealthData):
    evidence: str = Field(min_length=2, max_length=2000)


class HealthCreate(HealthData):
    client_request_id: str = Field(pattern=r"^[a-zA-Z0-9_-]{16,80}$")


class InboxAck(Input):
    ids: list[int] = Field(min_length=1, max_length=200)


def instant(text):
    try:
        value = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if value.tzinfo is None:
            raise ValueError()
        return value
    except (ValueError, OverflowError):
        raise ValueError("需要包含时区偏移的 ISO 8601 时间") from None


def schedule(value, after):
    """Next occurrence strictly after a UTC millisecond instant, anchored to local wall time.

    DST gap: shift forward by the gap; overlap: first occurrence only. Missed repeats
    coalesce into one inbox item; the following date always uses the original clock.
    """
    try:
        zone = ZoneInfo(value["timezone"])
    except (ZoneInfoNotFoundError, ValueError, KeyError):
        raise ValueError("时区无效，请使用 Asia/Shanghai 等 IANA 时区") from None
    start = instant(value["start_at"])
    local = start.astimezone(zone)
    if local.replace(tzinfo=None) != start.replace(tzinfo=None) or local.utcoffset() != start.utcoffset():
        raise ValueError("首次时间的 UTC 偏移与指定时区不一致，或本地时间不存在")
    first = int(start.timestamp() * 1000)
    if first > after:
        return first
    if value["repeat"] == "once":
        return 0
    step = 1 if value["repeat"] == "daily" else 7
    current = datetime.fromtimestamp(after / 1000, zone)
    days = max(0, (current.date() - local.date()).days // step * step)
    for offset in (days, days + step, days + 2 * step):
        date = local.date() + timedelta(days=offset)
        candidate = datetime.combine(date, local.time().replace(tzinfo=None), zone).replace(fold=0)
        result = int(candidate.timestamp() * 1000)
        if result > after:
            return result
    raise ValueError("无法计算下一次提醒时间")


def consent(run, evidence, action):
    text = run["request"]["messages"][-1]["content"]
    patterns = {
        "reminder": r"提醒|闹钟|定时|通知|设置|改成|改为|修改|调整|取消|删除|停止|remind|schedule|cancel",
        "health": r"记录|记下|保存|登记|删除|清除|移除|record|log|delete",
    }
    if evidence not in text or not re.search(patterns[action], evidence, re.I):
        raise ValueError("需要逐字引用本轮用户明确的操作要求，不能从资料中推断授权")


class PersonalStore:
    def __init__(self, store):
        self.store = store

    def check(self, c, uid, run=None):
        user = c.execute("SELECT status FROM users WHERE id=?", (uid,)).fetchone()
        if not user or user[0] != "active":
            raise Problem(403, "此用户已停用")
        if run and not c.execute("SELECT 1 FROM runs WHERE id=? AND user_id=? AND state='running'", (run, uid)).fetchone():
            raise Problem(409, "任务已停止")

    @staticmethod
    def reminder(row):
        return {"id": row["id"], **json.loads(row["body"]), "state": row["state"], "next_fire": row["next_fire"], "created": row["created"], "updated": row["updated"]}

    def reminders(self, uid):
        with self.store.db() as c:
            return [self.reminder(r) for r in c.execute("SELECT * FROM reminders WHERE user_id=? AND state='active' ORDER BY next_fire,id", (uid,))]

    def write_reminder(self, uid, value, key, *, rid=None, reminder_id=None):
        data = ReminderData.model_validate(value).model_dump()
        fingerprint = sha256(encode({"id": reminder_id, **data}).encode()).hexdigest()
        stamp = now()
        with self.store.db() as c:
            c.execute("BEGIN IMMEDIATE")
            self.check(c, uid, rid)
            prior = c.execute("SELECT fingerprint,response FROM personal_effects WHERE user_id=? AND effect_key=?", (uid, key)).fetchone()
            if prior:
                if prior["fingerprint"] != fingerprint:
                    raise Problem(409, "请求编号已被其他内容使用")
                return json.loads(prior["response"])
            due = int(instant(data["start_at"]).timestamp() * 1000)
            schedule(data, due - 1)  # Also validates timezone / wall clock agreement.
            if not stamp + 1000 <= due <= stamp + 3660 * 86400000:
                raise Problem(422, "首次提醒需在未来，且不超过十年；请重新确认日期和时区")
            previous = c.execute("SELECT * FROM reminders WHERE id=? AND user_id=?", (reminder_id, uid)).fetchone() if reminder_id else None
            if reminder_id and previous is None:
                raise Problem(404, "提醒不存在")
            if (not previous or previous["state"] != "active") and c.execute("SELECT count(*) FROM reminders WHERE user_id=? AND state='active'", (uid,)).fetchone()[0] >= 100:
                raise Problem(429, "最多保存100个有效提醒，请先取消不需要的提醒")
            ident = reminder_id or secrets.token_hex(16)
            c.execute("INSERT INTO reminders(id,user_id,body,state,next_fire,created,updated) VALUES(?,?,?,'active',?,?,?) ON CONFLICT(id) DO UPDATE SET body=excluded.body,state='active',next_fire=excluded.next_fire,updated=excluded.updated", (ident, uid, encode(data), due, stamp, stamp))
            if previous:
                c.execute("UPDATE reminder_events SET acknowledged=1 WHERE reminder_id=?", (ident,))
            result = {"saved": True, "reminder": self.reminder(c.execute("SELECT * FROM reminders WHERE id=?", (ident,)).fetchone()),
                      "delivery": "服务端已保存。手机同步并允许通知后可由本机闹钟提醒；服务端到期记录不代表已推送到手机。"}
            c.execute("INSERT INTO personal_effects VALUES(?,?,?,?,?)", (uid, key, fingerprint, encode(result), stamp))
            return result

    def cancel(self, uid, ident, *, rid=None):
        with self.store.db() as c:
            c.execute("BEGIN IMMEDIATE")
            self.check(c, uid, rid)
            if not c.execute("SELECT 1 FROM reminders WHERE id=? AND user_id=?", (ident, uid)).fetchone():
                raise Problem(404, "提醒不存在")
            c.execute("UPDATE reminders SET state='cancelled',next_fire=0,updated=? WHERE id=?", (now(), ident))
            c.execute("UPDATE reminder_events SET acknowledged=1 WHERE reminder_id=?", (ident,))
        return {"id": ident, "state": "cancelled", "saved": True, "delivery": "手机下次同步后撤销本机闹钟；离线设备仍可能显示旧提醒。"}

    def tick(self, at=None):
        stamp = now() if at is None else at
        with self.store.db() as c:
            c.execute("BEGIN IMMEDIATE")
            rows = c.execute("SELECT r.* FROM reminders r JOIN users u ON r.user_id=u.id WHERE r.state='active' AND r.next_fire<=? AND u.status='active' ORDER BY r.next_fire LIMIT 500", (stamp,)).fetchall()
            for row in rows:
                data = json.loads(row["body"])
                c.execute("INSERT OR IGNORE INTO reminder_events(user_id,reminder_id,scheduled_at,title,body,created) VALUES(?,?,?,?,?,?)", (row["user_id"], row["id"], row["next_fire"], data["title"], data["body"], stamp))
                following = schedule(data, stamp)
                c.execute("UPDATE reminders SET state=?,next_fire=?,updated=? WHERE id=?", ("active" if following else "completed", following, stamp, row["id"]))
            c.execute("DELETE FROM reminder_events WHERE created<?", (stamp - 30 * 86400000,))
        return len(rows)

    def inbox(self, uid):
        with self.store.db() as c:
            rows = c.execute("SELECT id,reminder_id,scheduled_at,title,body,created FROM reminder_events WHERE user_id=? AND acknowledged=0 ORDER BY id LIMIT 200", (uid,)).fetchall()
        return [dict(r) for r in rows]

    def acknowledge(self, uid, ids):
        with self.store.db() as c:
            c.executemany("UPDATE reminder_events SET acknowledged=1 WHERE user_id=? AND id=?", [(uid, i) for i in ids])
        return {"saved": True}

    def save_health(self, uid, value, key, *, rid=None):
        data = HealthData.model_validate(value).model_dump()
        at = int(instant(data["occurred_at"]).timestamp() * 1000)
        if not 0 < at <= now() + 60000:
            raise ValueError("健康记录应为实际发生的观察，不是未来计划")
        fingerprint = sha256(encode(data).encode()).hexdigest()
        with self.store.db() as c:
            c.execute("BEGIN IMMEDIATE")
            self.check(c, uid, rid)
            prior = c.execute("SELECT fingerprint,response FROM personal_effects WHERE user_id=? AND effect_key=?", (uid, key)).fetchone()
            if prior:
                if prior["fingerprint"] != fingerprint:
                    raise Problem(409, "请求编号已被其他内容使用")
                return json.loads(prior["response"])
            if c.execute("SELECT count(*) FROM health_records WHERE user_id=?", (uid,)).fetchone()[0] >= 2000:
                raise Problem(429, "健康记录已达2000条，请先整理")
            ident = secrets.token_hex(16)
            c.execute("INSERT INTO health_records VALUES(?,?,?,?,?)", (ident, uid, encode(data), at, now()))
            result = {"id": ident, "saved": True, "record": data}
            c.execute("INSERT INTO personal_effects VALUES(?,?,?,?,?)", (uid, key, fingerprint, encode({"id": ident, "saved": True}), now()))
            return result

    def health(self, uid, before=2**63 - 1):
        with self.store.db() as c:
            rows = c.execute("SELECT rowid AS cursor,* FROM health_records WHERE user_id=? AND rowid<? ORDER BY rowid DESC LIMIT 31", (uid, before)).fetchall()
        return {"items": [{"id": r["id"], "cursor": r["cursor"], **json.loads(r["body"]), "created": r["created"]} for r in rows[:30]], "next_before": rows[29]["cursor"] if len(rows) > 30 else 0}

    def delete_health(self, uid, ident, *, rid=None):
        with self.store.db() as c:
            c.execute("BEGIN IMMEDIATE")
            self.check(c, uid, rid)
            # Idempotent and owner-scoped; no existence disclosure across users.
            c.execute("DELETE FROM health_records WHERE id=? AND user_id=?", (ident, uid))
        return {"id": ident, "deleted": True}
