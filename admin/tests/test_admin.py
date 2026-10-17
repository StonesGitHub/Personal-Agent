import httpx
import pytest
from fastapi.testclient import TestClient
from app import Settings, create_app, password_hash


@pytest.fixture
def env():
    seen = []
    def backend(request):
        seen.append(request)
        assert request.headers["authorization"] == "Bearer " + "internal-api-token-" * 3
        if request.url.path == "/admin/v1/overview":
            return httpx.Response(200, json={"users": 0, "runs": {}, "active_users": 0, "worker_ready": True, "model_configured": True})
        if request.url.path == "/admin/v1/users":
            return httpx.Response(200, json={"items": [], "total": 0})
        return httpx.Response(404, json={"detail": "not found"})
    settings = Settings("http://internal-api", "internal-api-token-" * 3, password_hash("test-password-123"), origin="https://admin.test")
    client = TestClient(create_app(settings, httpx.MockTransport(backend)), base_url="https://admin.test")
    return client, seen


def login(client):
    response = client.post("/api/login", json={"username": "admin", "password": "test-password-123"})
    assert response.status_code == 200
    return response


def test_auth_cookie_csrf_and_logout(env):
    client, seen = env
    assert client.get("/api/users").status_code == 401
    response = login(client)
    cookie = response.headers["set-cookie"]
    assert "HttpOnly" in cookie and "Secure" in cookie and "SameSite=strict" in cookie
    csrf = response.json()["csrf"]
    assert client.get("/api/users").status_code == 200
    assert client.post("/api/users", json={}).status_code == 403
    assert client.post("/api/users", json={}, headers={"X-CSRF-Token": csrf, "Origin": "https://evil.test"}).status_code == 403
    assert client.post("/api/users", json={}, headers={"X-CSRF-Token": csrf}).status_code == 200
    stolen = client.cookies.get("mozhi_admin_session")
    assert client.post("/api/logout", json={}, headers={"X-CSRF-Token": csrf}).status_code == 200
    client.cookies.set("mozhi_admin_session", stolen)
    assert client.get("/api/users").status_code == 401


def test_proxy_allowlist_and_credentials_never_in_page(env):
    client, seen = env
    login(client)
    assert client.get("/api/overview").status_code == 200
    assert client.get("/api/anything").status_code == 404
    assert client.get("/api/users/abc/token").status_code == 404
    assert client.get("/assets/secret.env").status_code == 404
    for path in ["/", "/assets/app.js", "/api/users"]:
        response = client.get(path)
        assert "internal-api-token" not in response.text
        assert "test-password-123" not in response.text
    assert "localStorage" not in client.get("/assets/app.js").text
    assert "frame-ancestors 'none'" in client.get("/").headers["content-security-policy"]


def test_login_rate_limit_and_wrong_origin(env):
    client, _ = env
    assert client.post("/api/login", headers={"Origin": "https://evil.test"}, json={"username": "admin", "password": "test-password-123"}).status_code == 403
    for _ in range(10):
        assert client.post("/api/login", json={"username": "admin", "password": "wrong"}).status_code == 401
    assert client.post("/api/login", json={"username": "admin", "password": "wrong"}).status_code == 429


def test_bff_is_standalone_and_errors_are_sanitized():
    def bad_backend(_request):
        return httpx.Response(401, json={"detail": "internal-sensitive-provider-response"})
    settings = Settings("http://private", "test-token-" * 5, password_hash("test-password-123"), origin="https://admin.test")
    client = TestClient(create_app(settings, httpx.MockTransport(bad_backend)), base_url="https://admin.test")
    login(client)
    response = client.get("/api/users")
    assert response.status_code == 502
    assert "internal-sensitive" not in response.text


def test_nonlocal_insecure_cookie_fails_closed():
    with pytest.raises(ValueError):
        create_app(Settings("http://private", "x" * 40, "hash", origin="http://public.test", secure_cookie=False))
