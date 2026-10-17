import sqlite3
import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient
from app import create_app


@pytest.fixture
def clients(tmp_path):
    path = str(tmp_path / "community.sqlite3")
    app = create_app(path, "only-a-test-moderator-secret")
    a, b, guest = TestClient(app), TestClient(app), TestClient(app)
    for client, name in [(a, "甲"), (b, "乙")]:
        response = client.post("/v1/sessions", json={"nickname": name})
        assert response.status_code == 200
        client.headers["Authorization"] = "Bearer " + response.json()["token"]
    return a, b, guest, path


def publish(client, **fields):
    body = {"client_id": str(uuid.uuid4()), "question": "为什么云是白色的？", "answer": "水滴散射了不同颜色的光。"}
    body.update(fields)
    response = client.post("/v1/posts", json=body)
    assert response.status_code == 200, response.text
    return response.json(), body


def test_two_people_share_like_comment_and_undo(clients):
    a, b, guest, _ = clients
    post, _ = publish(a)
    visible = b.get("/v1/posts").json()["items"][0]
    assert visible["nickname"] == "甲" and visible["mine"] is False
    endpoint = f"/v1/posts/{post['id']}"
    for _ in range(2):
        liked = b.put(endpoint + "/like", json={"liked": True}).json()
        assert liked["likes"] == 1 and liked["liked"]
    assert a.get(endpoint).json()["liked"] is False
    c = b.post(endpoint + "/comments", json={"client_id": str(uuid.uuid4()), "text": "原来如此！"})
    assert c.status_code == 200
    comment = a.get(endpoint + "/comments").json()["items"][0]
    assert comment["text"] == "原来如此！" and comment["nickname"] == "乙" and comment["mine"] is False
    assert guest.get(endpoint).json()["comments"] == 1
    assert b.put(endpoint + "/like", json={"liked": False}).json()["likes"] == 0


def test_only_author_can_delete_and_children_are_removed(clients):
    a, b, guest, path = clients
    post, _ = publish(a)
    endpoint = f"/v1/posts/{post['id']}"
    comment = b.post(endpoint + "/comments", json={"client_id": str(uuid.uuid4()), "text": "评论"}).json()
    assert a.delete(f"/v1/comments/{comment['id']}").status_code == 403
    assert b.delete(endpoint).status_code == 403
    assert guest.delete(endpoint).status_code == 401
    b.put(endpoint + "/like", json={"liked": True})
    assert a.delete(endpoint).status_code == 200
    assert b.get(endpoint).status_code == 404
    with sqlite3.connect(path) as connection:
        for table in ["posts", "likes", "comments"]:
            assert connection.execute("SELECT count(*) FROM " + table).fetchone()[0] == 0


def test_retry_is_idempotent_and_conflicting_edits_are_rejected(clients):
    a, b, _, _ = clients
    post, body = publish(a)
    assert a.post("/v1/posts", json=body).json()["id"] == post["id"]
    assert a.post("/v1/posts", json={**body, "answer": "已经改了"}).status_code == 409
    endpoint = f"/v1/posts/{post['id']}/comments"
    comment = {"client_id": str(uuid.uuid4()), "text": "同一条评论"}
    first = b.post(endpoint, json=comment).json()["id"]
    assert b.post(endpoint, json=comment).json()["id"] == first
    assert b.post(endpoint, json={**comment, "text": "不同内容"}).status_code == 409
    assert a.get(endpoint).json()["items"].__len__() == 1


def test_private_context_fields_and_empty_text_are_rejected(clients):
    a, _, _, _ = clients
    base = {"client_id": str(uuid.uuid4()), "question": "q", "answer": "a"}
    for field in ["api_key", "memory", "location", "system_prompt", "conversation"]:
        response = a.post("/v1/posts", json={**base, field: "private-secret"})
        assert response.status_code == 422 and "private-secret" not in response.text
    for fields in [{"question": " "}, {"answer": "a" * 24001}, {"question": "q" * 8001}]:
        assert a.post("/v1/posts", json={**base, **fields}).status_code == 422
    assert a.post("/v1/posts", content=b"x" * 150001).status_code == 413
    assert a.get("/v1/posts").json()["items"] == []


def test_pagination_has_no_duplicates_when_new_posts_arrive(clients):
    a, b, _, _ = clients
    expected = [publish(a)[0]["id"] for _ in range(4)]
    page = b.get("/v1/posts?limit=2").json()
    publish(a)
    older = b.get(f"/v1/posts?limit=2&before={page['next_before']}").json()
    ids = [p["id"] for p in page["items"] + older["items"]]
    assert ids == list(reversed(expected)) and older["next_before"] == 0


def test_data_survives_service_restart_and_credentials_are_not_public(clients):
    a, _, guest, path = clients
    post, _ = publish(a)
    restarted = TestClient(create_app(path))
    assert restarted.get("/v1/posts").json()["items"][0]["question"] == post["question"]
    result = guest.get("/v1/posts").text
    assert "token" not in result and "user_id" not in result and "client_id" not in result
    with sqlite3.connect(path) as connection:
        assert a.headers["Authorization"][7:] not in str(connection.execute("SELECT * FROM users").fetchall())


def test_reports_and_moderation(clients):
    a, b, guest, _ = clients
    post, _ = publish(a)
    value = {"kind": "post", "target_id": post["id"], "reason": "不适合公开"}
    assert b.post("/v1/reports", json=value).status_code == 200
    assert b.post("/v1/reports", json=value).status_code == 200
    assert guest.get("/moderation/reports").status_code == 403
    headers = {"Authorization": "Bearer only-a-test-moderator-secret"}
    assert len(guest.get("/moderation/reports", headers=headers).json()["items"]) == 1
    assert guest.delete(f"/moderation/posts/{post['id']}", headers=headers).status_code == 200
    assert guest.get("/moderation/reports", headers=headers).json()["items"] == []


def test_rate_limit_and_invalid_sessions(clients):
    a, _, guest, _ = clients
    assert guest.get("/v1/posts", headers={"Authorization": "Bearer made-up"}).status_code == 401
    for n in range(10):
        assert guest.post("/v1/sessions", json={}).status_code == 200
    assert guest.post("/v1/sessions", json={}).status_code == 429
    assert guest.post("/v1/posts", json={"client_id": str(uuid.uuid4()), "question": "q", "answer": "a"}).status_code == 401


def test_concurrent_retries_and_likes_do_not_duplicate(clients):
    a, b, _, _ = clients
    post, body = publish(a)
    with ThreadPoolExecutor(max_workers=4) as pool:
        responses = list(pool.map(lambda _: a.post("/v1/posts", json=body), range(4)))
    assert all(r.status_code == 200 and r.json()["id"] == post["id"] for r in responses)
    with ThreadPoolExecutor(max_workers=4) as pool:
        responses = list(pool.map(lambda _: b.put(f"/v1/posts/{post['id']}/like", json={"liked": True}), range(4)))
    assert all(r.status_code == 200 for r in responses)
    assert a.get(f"/v1/posts/{post['id']}").json()["likes"] == 1


def test_comment_owner_can_remove_own_text(clients):
    a, b, _, _ = clients
    post, _ = publish(a)
    endpoint = f"/v1/posts/{post['id']}/comments"
    comment = b.post(endpoint, json={"client_id": str(uuid.uuid4()), "text": "我的评论"}).json()
    assert b.get(endpoint).json()["items"][0]["mine"]
    assert b.delete(f"/v1/comments/{comment['id']}").status_code == 200
    assert a.get(endpoint).json()["items"] == []


def test_my_posts_only_contains_current_author(clients):
    a, b, guest, _ = clients
    own, _ = publish(a)
    publish(b)
    assert [p["id"] for p in a.get("/v1/posts?mine=true").json()["items"]] == [own["id"]]
    assert a.get("/v1/posts?mine=true").json()["items"][0]["mine"]
    assert guest.get("/v1/posts?mine=true").status_code == 401
