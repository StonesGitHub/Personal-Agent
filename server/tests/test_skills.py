import asyncio
from datetime import datetime, timedelta, timezone
from pathlib import Path
import json
import os
import subprocess
import sys
import time

import pytest
from pydantic import ValidationError

from test_agent import env, payload, ScriptModel, tool, execute
from mozhi_service.personal import PersonalStore, schedule, instant
from mozhi_service.schemas import RunInput
from mozhi_service.skills import CATALOG, instructions
from mozhi_service.store import Problem, Store, now


def reminder(**extra):
    return {"title": "出发提醒", "body": "带上水杯", "start_at": (datetime.now(timezone(timedelta(hours=8))) + timedelta(hours=1)).isoformat(),
            "timezone": "Asia/Shanghai", "repeat": "once", **extra}


def health(**extra):
    return {"subject": "本人", "category": "sleep", "text": "昨晚睡了7小时", "occurred_at": datetime.now(timezone.utc).isoformat(), **extra}


def test_skill_resources_identical_and_disabled_not_loaded():
    root = Path(__file__).resolve().parents[2]
    assert (root / "skills/builtin.json").read_bytes() == (root / "app/src/main/assets/builtin_skills.json").read_bytes() == (root / "server/mozhi_service/builtin_skills.json").read_bytes()
    request = payload(text="周末出游并设置出发提醒")
    loaded = instructions(request)
    assert "# 出游" in loaded and "# 定时任务提醒" in loaded
    assert "Asia/Shanghai" in loaded
    request["builtin_skills"] = []
    assert "# 出游" not in instructions(request)
    assert "# 健康管理" not in instructions(request)
    assert set(CATALOG) == {"travel", "reminders", "health"}


def test_invalid_timezone_and_unknown_skill_rejected():
    for extra in ({"timezone": "Imaginary/Zone"}, {"builtin_skills": ["arbitrary_code"]}):
        with pytest.raises(ValidationError):
            RunInput(**{**payload(), **extra})


def test_old_pending_request_still_retries_after_new_optional_fields(env):
    _, store, _, api, _, auth, user = env
    body = payload()
    for field in ("builtin_skills", "timezone", "allow_personal_data"):
        body.pop(field)
    ident = store.submit(user["id"], body)
    response = api.post("/v1/agent/runs", headers=auth, json=body)
    assert response.status_code == 200 and response.json()["id"] == ident
    response = api.post("/v1/agent/runs", headers=auth, json={**body, "builtin_skills": []})
    assert response.status_code == 409


def test_travel_load_and_map_links_use_real_tool_observations(env):
    model = ScriptModel(tool("load_skill", {"id": "travel"}), tool("map_links", {"places": [{"name": "测试[地点]", "city": "示例城市", "address": ""}]}), {"content": "已整理出游方案，具体开放情况待核实。"})
    run = execute(env, model, payload(text="安排一次出游"))
    assert run["state"] == "completed"
    assert "mozhi://map?" in model.requests[-1][-1]["content"]
    assert "未核实" in model.requests[-1][-1]["content"]


def test_create_idempotent_and_worker_recovery_delivers_once(env):
    _, store, _, api, _, auth, user = env
    value = {**reminder(), "client_request_id": "reminder-request-001"}
    first = api.post("/v1/reminders", headers=auth, json=value)
    assert first.status_code == 200
    ident = first.json()["reminder"]["id"]
    assert api.post("/v1/reminders", headers=auth, json=value).json()["reminder"]["id"] == ident
    assert api.post("/v1/reminders", headers=auth, json={**value, "title": "不同内容"}).status_code == 409
    p = PersonalStore(Store(store.path))
    due = first.json()["reminder"]["next_fire"]
    assert p.tick(due - 1) == 0 and p.tick(due) == 1 and p.tick(due + 100) == 0
    assert p.reminders(user["id"]) == []
    events = api.get("/v1/reminders/inbox", headers=auth).json()["items"]
    assert len(events) == 1 and events[0]["reminder_id"] == ident
    api.post("/v1/reminders/inbox/ack", headers=auth, json={"ids": [events[0]["id"]]})
    assert api.get("/v1/reminders/inbox", headers=auth).json()["items"] == []


def test_repeat_coalesces_outage_and_preserves_wall_clock(env):
    *_, user = env
    p = PersonalStore(env[1])
    result = p.write_reminder(user["id"], reminder(repeat="daily"), "daily-test")["reminder"]
    later = result["next_fire"] + 4 * 86400000 + 5000
    assert p.tick(later) == 1
    assert len(p.inbox(user["id"])) == 1
    following = p.reminders(user["id"])[0]["next_fire"]
    assert following == result["next_fire"] + 5 * 86400000


def test_dst_gap_overlap_weekly_and_bad_offset():
    def ms(v): return int(instant(v).timestamp() * 1000)
    r = reminder(start_at="2026-03-07T02:30:00-05:00", timezone="America/New_York", repeat="daily")
    gap = schedule(r, ms("2026-03-07T04:00:00-05:00"))
    assert gap == ms("2026-03-08T03:30:00-04:00")
    assert schedule(r, gap) == ms("2026-03-09T02:30:00-04:00")
    r.update(start_at="2026-10-31T01:30:00-04:00")
    overlap = schedule(r, ms("2026-10-31T02:00:00-04:00"))
    assert overlap == ms("2026-11-01T01:30:00-04:00")
    assert schedule(r, overlap) == ms("2026-11-02T01:30:00-05:00")
    r.update(repeat="weekly")
    assert schedule(r, ms("2026-10-31T02:00:00-04:00")) == ms("2026-11-07T01:30:00-05:00")
    with pytest.raises(ValueError):
        schedule(reminder(start_at="2026-10-09T08:00:00+00:00"), 0)


def test_update_and_cancel_with_owner_isolation(env):
    _, store, admin, api, ah, auth, user = env
    r = api.post("/v1/reminders", headers=auth, json={**reminder(), "client_request_id": "reminder-request-001"}).json()["reminder"]
    other = admin.post("/admin/v1/users", headers=ah, json={"nickname": "另一个用户"}).json()
    other_auth = {"Authorization": "Bearer " + other["token"]}
    assert api.post(f"/v1/reminders/{r['id']}/cancel", headers=other_auth).status_code == 404
    revised = api.post(f"/v1/reminders/{r['id']}/update", headers=auth, json={**reminder(title="新内容", repeat="weekly"), "client_request_id": "reminder-request-002"})
    assert revised.status_code == 200 and revised.json()["reminder"]["id"] == r["id"]
    api.post(f"/v1/reminders/{r['id']}/cancel", headers=auth)
    assert api.get("/v1/reminders", headers=auth).json()["items"] == []
    PersonalStore(store).tick(now() + 86400000)
    assert PersonalStore(store).inbox(user["id"]) == []


def test_api_no_auth_no_cross_user_health_and_delete(env):
    _, _, admin, api, ah, auth, _ = env
    for route in ("/v1/reminders", "/v1/reminders/inbox", "/v1/health-records", "/v1/skills"):
        assert api.get(route).status_code == 401
    args = {**health(), "client_request_id": "health-request-001"}
    created = api.post("/v1/health-records", headers=auth, json=args).json()
    assert api.post("/v1/health-records", headers=auth, json=args).json()["id"] == created["id"]
    other = admin.post("/admin/v1/users", headers=ah, json={"nickname": "另一个用户"}).json()
    other_auth = {"Authorization": "Bearer " + other["token"]}
    assert api.get("/v1/health-records", headers=other_auth).json()["items"] == []
    api.post(f"/v1/health-records/{created['id']}/delete", headers=other_auth)
    assert len(api.get("/v1/health-records", headers=auth).json()["items"]) == 1
    api.post(f"/v1/health-records/{created['id']}/delete", headers=auth)
    assert api.get("/v1/health-records", headers=auth).json()["items"] == []


def test_disabled_skill_or_no_explicit_instruction_cannot_write(env):
    model = ScriptModel(tool("create_reminder", {**reminder(), "evidence": "明天出游"}), {"content": "还没有创建提醒。"})
    run = execute(env, model, payload(text="明天出游"))
    assert run["state"] == "completed" and "error" in model.requests[-1][-1]["content"]
    assert PersonalStore(env[1]).reminders(env[-1]["id"]) == []
    request = payload("disabled-skill-0001", "请提醒我出发")
    request["builtin_skills"] = []
    model = ScriptModel(tool("create_reminder", {**reminder(), "evidence": "请提醒我出发"}), {"content": "提醒技能尚未开启。"})
    run = execute(env, model, request)
    assert run["state"] == "completed" and "error" in model.requests[-1][-1]["content"]


def test_agent_reminder_effect_survives_later_failure_without_duplication(env):
    args = {**reminder(), "evidence": "请提醒我出发"}
    model = ScriptModel(tool("create_reminder", args), tool("create_reminder", args), ValueError("provider failed"))
    run = execute(env, model, payload(text="请提醒我出发"))
    assert run["state"] == "failed"
    assert len(PersonalStore(env[1]).reminders(env[-1]["id"])) == 1
    assert json.loads(model.requests[1][-1]["content"])["saved"]


def test_health_requires_current_user_record_request_and_real_time(env):
    body = payload(text="请记录我昨晚睡了7小时")
    model = ScriptModel(tool("save_health_record", {**health(), "evidence": "请记录我昨晚睡了7小时"}), {"content": "已记录睡眠观察。"})
    assert execute(env, model, body)["state"] == "completed"
    p = PersonalStore(env[1])
    assert len(p.health(env[-1]["id"])["items"]) == 1
    with pytest.raises(ValueError):
        p.save_health(env[-1]["id"], health(occurred_at=(datetime.now(timezone.utc) + timedelta(days=1)).isoformat()), "future")


def test_existing_user_permissions_not_silently_expanded_and_can_still_cancel(env):
    _, store, admin, api, ah, auth, user = env
    r = api.post("/v1/reminders", headers=auth, json={**reminder(), "client_request_id": "reminder-request-001"}).json()["reminder"]
    admin.patch("/admin/v1/users/" + user["id"], headers=ah, json={"enabled_tools": []})
    assert api.post("/v1/reminders", headers=auth, json={**reminder(), "client_request_id": "reminder-request-002"}).status_code == 403
    assert api.post(f"/v1/reminders/{r['id']}/cancel", headers=auth).status_code == 200
    admin.patch("/admin/v1/users/" + user["id"], headers=ah, json={"status": "suspended"})
    assert api.get("/v1/reminders", headers=auth).status_code == 403


def test_personal_context_off_disables_health_tools(env):
    model = ScriptModel(tool("list_health_records", {}), {"content": "本轮未读取健康记录。"})
    body = payload(text="查看过去睡眠记录")
    body["allow_personal_data"] = False
    assert execute(env, model, body)["state"] == "completed"
    assert "error" in model.requests[-1][-1]["content"]


def test_real_worker_process_fires_and_restart_does_not_repeat(env):
    settings, store, _, _, _, _, user = env
    p = PersonalStore(store)
    value = reminder(start_at=(datetime.now(timezone(timedelta(hours=8))) + timedelta(seconds=1.5)).isoformat())
    p.write_reminder(user["id"], value, "worker-process-test")
    server = Path(__file__).resolve().parents[1]
    environment = {**os.environ, "PYTHONPATH": str(server), "MOZHI_AGENT_DB": settings.db_path}
    for turn in range(2):
        process = subprocess.Popen([sys.executable, "-m", "mozhi_service.worker"], cwd=server, env=environment, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 8
            while time.monotonic() < deadline:
                assert process.poll() is None
                if len(p.inbox(user["id"])) == 1 and (turn == 0 or time.monotonic() > deadline - 7.5):
                    break
                time.sleep(0.05)
            assert len(p.inbox(user["id"])) == 1
        finally:
            process.terminate()
            process.communicate(timeout=5)
        assert process.returncode == 0
