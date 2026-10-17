import json
import socket
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient
from app import create_app
from web_service import WebService, WebError, Page, PublicFetcher, PinnedHTTPS, public_url, resolve_public, deepseek_transcript

TOKEN = "synthetic-web-token-not-a-real-key-1234567890"
SHARE = "https://chat.deepseek.com/share/synthetic123"


def shared():
    return {"code": 0, "data": {"biz_code": 0, "biz_data": {"title": "合成示例", "messages": [
        {"role": "USER", "fragments": [{"type": "REQUEST", "content": "问题"}]},
        {"role": "ASSISTANT", "fragments": [{"type": "THINK", "content": "excluded-reasoning"}, {"type": "RESPONSE", "content": "**回答**"}, {"type": "TIP", "content": "excluded-tip"}]}]}}}


class FakeFetcher:
    def __init__(self):
        self.calls = []

    def get(self, url, **kwargs):
        self.calls.append((url, kwargs))
        if "api/v0/share/content" in url:
            return Page(url, "application/json", json.dumps(shared()).encode())
        if "api.search.brave" in url:
            return Page(url, "application/json", b'{"web":{"results":[{"url":"https://example.com/","title":"Example","description":"a <b>summary</b>"}]}}')
        if "duckduckgo" in url:
            return Page(url, "text/html", b'<div class="result result--ad"><a class="result__a" href="https://ad.example.com">ad</a></div><div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2F">Example</a><div class="result__snippet">Snippet</div></div>')
        return Page(url, "text/html", b'<title>Example</title><script>hidden</script><article><p>A readable public article.</p><a href="https://example.com/next">Next</a></article>')


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("MOZHI_WEB_TOKENS", TOKEN)
    monkeypatch.setenv("MOZHI_SEARCH_PROVIDER", "duckduckgo")
    app = create_app(str(tmp_path / "service.db"))
    app.state.web_service = WebService(FakeFetcher())
    return TestClient(app)


def auth():
    return {"Authorization": "Bearer " + TOKEN}


@pytest.mark.parametrize("url", ["http://example.com", "https://localhost/", "https://127.0.0.1/", "https://10.0.0.1/", "https://169.254.169.254/", "https://100.64.0.1/", "https://[::1]/", "https://[64:ff9b::7f00:1]/", "https://[::ffff:127.0.0.1]/", "https://a.internal/", "https://a.local/", "https://example.com:444/", "https://user:pw@example.com/", "https://example.com/\r\nX-Key:secret", "https://example.com\\@127.0.0.1/"])
def test_private_and_ambiguous_urls_rejected(url):
    with pytest.raises(WebError):
        public_url(url)


def test_dns_checked_once_then_connection_pins_ip():
    rows = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("93.184.215.14", 443))]
    with patch("web_service.socket.getaddrinfo", return_value=rows):
        assert resolve_public("example.com") == ["93.184.215.14"]
    with patch("web_service.socket.create_connection") as connect, patch("web_service.ssl.create_default_context") as context:
        conn = PinnedHTTPS("example.com", "93.184.215.14", 12)
        conn.connect()
        connect.assert_called_once_with(("93.184.215.14", 443), 12)
        context.return_value.wrap_socket.assert_called_once_with(connect.return_value, server_hostname="example.com")


def test_mixed_private_dns_blocks_whole_host():
    rows = [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (x, 443)) for x in ["93.184.215.14", "10.0.0.1"]]
    with patch("web_service.socket.getaddrinfo", return_value=rows), pytest.raises(WebError):
        resolve_public("example.com")


def test_redirect_revalidated_before_connecting_private_target():
    with patch("web_service.resolve_public", return_value=["93.184.215.14"]), patch("web_service.PinnedHTTPS") as connection:
        response = connection.return_value.getresponse.return_value
        response.status = 302
        response.getheader.return_value = "https://127.0.0.1/secrets"
        with pytest.raises(WebError):
            PublicFetcher().get("https://example.com")
        assert connection.call_count == 1


def test_provider_credential_never_follows_redirect():
    with patch("web_service.resolve_public", return_value=["93.184.215.14"]), patch("web_service.PinnedHTTPS") as connection:
        response = connection.return_value.getresponse.return_value
        response.status = 302
        response.getheader.return_value = "https://other.example.com/"
        with pytest.raises(WebError):
            PublicFetcher().get("https://api.search.brave.com/", headers={"X-Subscription-Token": "test"})
        assert connection.call_count == 1


def test_gateway_disabled_by_default_and_bad_token_fails(tmp_path, monkeypatch, client):
    monkeypatch.delenv("MOZHI_WEB_TOKENS", raising=False)
    disabled = TestClient(create_app(str(tmp_path / "disabled.db")))
    assert disabled.post("/v1/web/search", json={"query": "hi"}).status_code == 503
    for header in ({}, {"Authorization": "Bearer wrong"}, {"Authorization": TOKEN}):
        assert client.post("/v1/web/search", json={"query": "hi"}, headers=header).status_code == 401
    assert client.get("/v1/web/status", headers=auth()).json()["ok"]


def test_search_read_import_and_sources_match(client):
    search = client.post("/v1/web/search", json={"query": "public docs"}, headers=auth())
    assert search.status_code == 200 and len(search.json()["results"]) == 1
    read = client.post("/v1/web/read", json={"url": "https://example.com/"}, headers=auth()).json()
    assert read["url"] == "https://example.com/" and "hidden" not in read["text"]
    imported = client.post("/v1/web/import", json={"url": SHARE}, headers=auth()).json()
    assert imported["import_status"] == "preview_only"
    assert [m["role"] for m in imported["transcript"]["messages"]] == ["user", "assistant"]
    assert imported["transcript"]["omitted"] == 2 and "excluded" not in json.dumps(imported)
    assert client.get("/v1/posts").json()["items"] == []  # Import does not publish.


def test_fields_limits_and_private_data_not_reflected(client):
    for body in ({"query": "x"}, {"query": "x" * 201}, {"query": "hi", "memory": "private-text"}, {"query": "hi", "api_key": "secret"}):
        response = client.post("/v1/web/search", json=body, headers=auth())
        assert response.status_code == 422 and "private-text" not in response.text and "secret" not in response.text
    assert client.post("/v1/web/read", content=b"x" * 150001, headers=auth()).status_code == 413


def test_rate_limit_persists_in_database(client):
    for _ in range(30):
        assert client.get("/v1/web/status", headers=auth()).status_code == 200
    assert client.get("/v1/web/status", headers=auth()).status_code == 429


def test_brave_adapter_keeps_key_server_side(monkeypatch):
    monkeypatch.setenv("MOZHI_SEARCH_PROVIDER", "brave")
    monkeypatch.setenv("BRAVE_SEARCH_API_KEY", "only-a-test-provider-key")
    fetcher = FakeFetcher()
    results = WebService(fetcher).search("public docs")
    assert results["provider"] == "brave" and results["results"][0]["snippet"] == "a summary"
    assert fetcher.calls[0][1]["headers"] == {"X-Subscription-Token": "only-a-test-provider-key"}
    assert "only-a-test" not in json.dumps(results)


def test_dynamic_shell_and_empty_search_are_errors():
    class Empty:
        def get(self, url, **_):
            return Page(url, "text/html", b"<body><script>render()</script></body>")
    service = WebService(Empty())
    with pytest.raises(WebError):
        service.read("https://example.com/")
    with pytest.raises(WebError):
        service.search("missing")


def test_truncated_webpage_cannot_be_silently_imported(client):
    class Long:
        def read(self, _):
            return {"text": "long", "truncated": True}
    client.app.state.web_service = Long()
    assert client.post("/v1/web/import", json={"url": "https://example.com/"}, headers=auth()).status_code == 422


def test_bad_share_and_host_spoof_fail():
    with pytest.raises(WebError):
        deepseek_transcript({"code": 1}, SHARE)
    fetcher = FakeFetcher()
    WebService(fetcher).read("https://chat.deepseek.com.evil.example/share/abcdefg")
    assert "api/v0" not in fetcher.calls[0][0]
