import asyncio
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from mozhi_service.api import create_app, create_admin_api
from mozhi_service.runtime import Runtime
from mozhi_service.schemas import RunInput
from mozhi_service.settings import Settings, TOOLS
from mozhi_service.store import Store, Problem, encode
from mozhi_service.tools import calculate


@pytest.fixture
def env(tmp_path):
    settings = Settings(db_path=str(tmp_path / "agent.db"), model_key="fake-test-key", admin_token="admin-test-token-" * 3)
    store = Store(settings.db_path)
    admin = TestClient(create_admin_api(settings, store))
    public = TestClient(create_app(settings, store))
    ah = {"Authorization": "Bearer " + settings.admin_token}
    created = admin.post("/admin/v1/users", headers=ah, json={"nickname": "测试用户"}).json()
    return settings, store, admin, public, ah, {"Authorization": "Bearer " + created["token"]}, created["user"]


def payload(request="request-id-0000001", text="你好"):
    return RunInput(client_request_id=request, messages=[{"role": "user", "content": text}]).model_dump()


class ScriptModel:
    def __init__(self, *responses):
        self.responses = list(responses)
        self.requests = []

    async def complete(self, messages, tools, model, final_only):
        self.requests.append(json.loads(encode(messages)))
        response = self.responses.pop(0)
        if isinstance(response, Exception):
            raise response
        return response, {"prompt_tokens": 10, "completion_tokens": 5}


def tool(name, arguments, id="tool_1"):
    return {"role": "assistant", "content": None, "reasoning_content": "private-reasoning", "tool_calls": [{"id": id, "type": "function", "function": {"name": name, "arguments": encode(arguments)}}]}


def execute(env, model, body=None):
    settings, store, _, _, _, _, user = env
    rid = store.submit(user["id"], body or payload())
    assert store.claim() == rid
    asyncio.run(Runtime(store, settings, model).execute(rid))
    return store.run(rid, user["id"])


def test_chat_completes_without_tools(env):
    run = execute(env, ScriptModel({"content": "你好，今天想聊些什么？"}))
    assert run["state"] == "completed" and run["calls"] == 0
    assert run["prompt_tokens"] == 10


def test_task_plan_calculation_file_and_private_reasoning(env):
    model = ScriptModel(tool("update_plan", {"steps": [{"title": "计算成本并交付文件", "status": "in_progress"}]}),
                        tool("calculate", {"expression": "1000*0.02+100*0.15"}),
                        tool("create_artifact", {"name": "成本.csv", "content": "项目,成本\n总计,35\n"}),
                        {"content": "已生成成本文件。"})
    run = execute(env, model)
    assert run["state"] == "completed" and run["calls"] == 3
    assert run["artifacts"][0]["name"] == "成本.csv"
    assert model.requests[1][-2]["reasoning_content"] == "private-reasoning"
    assert "private-reasoning" not in encode(run)
    assert '"result":35.0' in model.requests[2][-1]["content"]


def test_idempotent_submission_and_quota_concurrency(env):
    _, store, _, _, _, _, user = env
    store.edit_user(user["id"], {"daily_run_limit": 1})
    barrier = threading.Barrier(2)
    def attempt(i):
        barrier.wait()
        try:
            return store.submit(user["id"], payload("unique-request-000" + str(i)))
        except Problem as e:
            return e.status
    with ThreadPoolExecutor(2) as pool:
        results = list(pool.map(attempt, [1, 2]))
    assert sum(isinstance(r, str) for r in results) == 1 and 429 in results
    rid = next(r for r in results if isinstance(r, str))
    original = store.run(rid, internal=True)["request"]
    assert store.submit(user["id"], original) == rid
    with pytest.raises(Problem) as e:
        store.submit(user["id"], {**original, "background": "changed"})
    assert e.value.status == 409


def test_ownership_credentials_and_public_admin_separation(env):
    _, store, admin, public, ah, uh, user = env
    run = execute(env, ScriptModel(tool("create_artifact", {"name": "private.txt", "content": "only owner"}), {"content": "done"}))
    other = admin.post("/admin/v1/users", headers=ah, json={"nickname": "其他用户"}).json()
    other_h = {"Authorization": "Bearer " + other["token"]}
    for path in ["/v1/agent/runs/" + run["id"], "/v1/agent/artifacts/" + run["artifacts"][0]["id"], "/v1/agent/runs/" + run["id"] + "/events"]:
        assert public.get(path, headers=other_h).status_code == 404
        assert public.get(path).status_code == 401
    assert public.get("/admin/v1/users", headers=ah).status_code == 404
    assert admin.get("/admin/v1/users", headers=uh).status_code == 401
    assert public.get("/v1/me", headers=ah).status_code == 401
    assert "token_hash" not in admin.get("/admin/v1/users", headers=ah).text
    admin.post("/admin/v1/users/" + user["id"] + "/rotate-token", headers=ah, json={})
    assert public.get("/v1/me", headers=uh).status_code == 401


def test_live_disable_blocks_tool_and_user_suspend_cancels(env):
    settings, store, admin, public, ah, uh, user = env
    rid = store.submit(user["id"], payload())
    store.edit_user(user["id"], {"enabled_tools": []})
    store.claim()
    model = ScriptModel(tool("create_artifact", {"name": "blocked.txt", "content": "never save"}), {"content": "工具已关闭"})
    asyncio.run(Runtime(store, settings, model).execute(rid))
    assert store.run(rid)["artifacts"] == []
    rid = store.submit(user["id"], payload("request-id-0000002"))
    store.edit_user(user["id"], {"status": "suspended"})
    assert store.run(rid)["state"] == "cancelled"
    assert public.get("/v1/me", headers=uh).status_code == 403


def test_budget_and_invalid_tool_batch(env):
    _, store, _, _, _, _, _ = env
    config = store.config()
    config["config"]["max_rounds"] = 1
    store.set_config(config["config"], config["revision"])
    run = execute(env, ScriptModel(tool("create_artifact", {"name": "no.txt", "content": "no"})))
    assert run["state"] == "failed" and not run["artifacts"]


def test_restart_manual_resume_and_artifact_idempotency(env):
    settings, store, _, _, _, _, user = env
    rid = store.submit(user["id"], payload())
    store.claim()
    message = tool("create_artifact", {"name": "once.txt", "content": "one file"})
    messages = [{"role": "user", "content": "create"}, message]
    store.checkpoint(rid, messages=messages, rounds=1)
    runtime = Runtime(store, settings, ScriptModel({"content": "done"}))
    call = {**message["tool_calls"][0], "id": "1:tool_1"}
    asyncio.run(runtime.tools.call(store.run(rid, internal=True), call))
    store.recover()
    assert store.run(rid)["state"] == "interrupted"
    assert store.claim() is None
    store.resume(rid, user["id"])
    store.claim()
    asyncio.run(runtime.execute(rid))
    assert store.run(rid)["state"] == "completed"
    assert len(store.run(rid)["artifacts"]) == 1


def test_cancel_interrupts_inflight_model(env):
    settings, store, _, _, _, _, user = env
    rid = store.submit(user["id"], payload())
    store.claim()
    class Slow:
        async def complete(self, *args):
            await asyncio.sleep(30)
            return {"content": "should not finish"}, {}
    async def run():
        task = asyncio.create_task(Runtime(store, settings, Slow()).execute(rid))
        await asyncio.sleep(0.05)
        store.cancel(rid, user["id"])
        await asyncio.wait_for(task, 2)
    asyncio.run(run())
    assert store.run(rid)["state"] == "cancelled"


@pytest.mark.parametrize("expr", ["__import__('os').system('id')", "2**1000000", "1/0", "1e999", "(-2)**0.5"])
def test_calculator_rejects_code_and_unbounded_values(expr):
    with pytest.raises(ValueError):
        calculate(expr)


def test_config_revision_and_strict_fields(env):
    _, _, admin, public, ah, uh, _ = env
    config = admin.get("/admin/v1/config", headers=ah).json()
    assert admin.put("/admin/v1/config", headers=ah, json=config).status_code == 200
    assert admin.put("/admin/v1/config", headers=ah, json=config).status_code == 409
    assert public.post("/v1/agent/runs", headers=uh, json={**payload(), "model_key": "cannot-inject"}).status_code == 422
    assert public.post("/v1/agent/runs", headers=uh, json={**payload(), "allow_web": "false"}).status_code == 422


def test_cancel_before_submit_prevents_late_execution(env):
    _, store, _, public, _, uh, user = env
    body = payload()
    path = "/v1/agent/requests/" + body["client_request_id"] + "/cancel"
    assert public.post(path, headers=uh, json={}).json()["state"] == "cancelled"
    assert public.post(path, headers=uh, json={}).status_code == 200
    assert public.post("/v1/agent/runs", headers=uh, json=body).status_code == 409
    assert store.claim() is None


def test_reused_provider_call_id_across_rounds_creates_distinct_files(env):
    run = execute(env, ScriptModel(tool("create_artifact", {"name": "first.txt", "content": "first"}), tool("create_artifact", {"name": "second.txt", "content": "second"}), {"content": "done"}))
    assert {a["name"] for a in run["artifacts"]} == {"first.txt", "second.txt"}
