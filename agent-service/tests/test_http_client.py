from __future__ import annotations

import http.server
import socket
import socketserver
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any, cast

import httpx
import pytest
from citybuddy_agent import http_client


class CountingServer(socketserver.ThreadingTCPServer):
    """Count accepted connections so connection reuse is observable from the server side."""

    allow_reuse_address = True
    # The client keeps its connection open, so the handler thread is still blocked on a read
    # when the test finishes; a joining shutdown would wait for that read to time out.
    daemon_threads = True

    def __init__(self, address: tuple[str, int], handler: type[Any]) -> None:
        super().__init__(address, handler)
        self.accepted = 0

    def get_request(self) -> tuple[socket.socket, Any]:
        self.accepted += 1
        return super().get_request()


class EmptyJsonHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    received_cookies: list[str | None] = []

    def do_GET(self) -> None:
        type(self).received_cookies.append(self.headers.get("Cookie"))
        body = b"{}"
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Set-Cookie", "JSESSIONID=one-users-session; Path=/")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: Any) -> None:
        del format, args


@contextmanager
def installed_runtime(
    layout: http_client.HttpClientLayout, urls: tuple[str, ...]
) -> Iterator[http_client.HttpClients]:
    runtime = http_client.HttpClients(layout, urls)
    http_client.install(runtime)
    try:
        yield runtime
    finally:
        try:
            runtime.close()
        finally:
            http_client.uninstall(runtime)


@pytest.mark.parametrize("layout", ("shared", "per-authority"))
def test_outbound_requests_reuse_one_connection(layout: http_client.HttpClientLayout) -> None:
    with CountingServer(("127.0.0.1", 0), EmptyJsonHandler) as server:
        serving = threading.Thread(target=server.serve_forever, daemon=True)
        serving.start()
        try:
            url = f"http://127.0.0.1:{server.server_address[1]}/"
            with installed_runtime(layout, (url,)):
                for _ in range(3):
                    assert http_client.get(url, timeout=2.0).status_code == 200
        finally:
            server.shutdown()
            serving.join(timeout=2.0)
    assert server.accepted == 1


@pytest.mark.parametrize("layout", ("shared", "per-authority"))
def test_a_cookie_from_one_request_is_not_sent_on_the_next(
    layout: http_client.HttpClientLayout,
) -> None:
    """One client serves every user's turn, so a stored cookie would cross between users."""
    EmptyJsonHandler.received_cookies = []
    with CountingServer(("127.0.0.1", 0), EmptyJsonHandler) as server:
        serving = threading.Thread(target=server.serve_forever, daemon=True)
        serving.start()
        try:
            url = f"http://127.0.0.1:{server.server_address[1]}/"
            with installed_runtime(layout, (url,)):
                for _ in range(3):
                    assert http_client.get(url, timeout=2.0).status_code == 200
        finally:
            server.shutdown()
            serving.join(timeout=2.0)
    assert EmptyJsonHandler.received_cookies == [None, None, None]


class FakeClient:
    def __init__(self) -> None:
        self.close_calls = 0

    def close(self) -> None:
        self.close_calls += 1


def fake_client_factory(created: list[FakeClient]) -> httpx.Client:
    client = FakeClient()
    created.append(client)
    return cast(httpx.Client, client)


def test_shared_layout_prebuilds_one_client_and_routes_every_configured_origin_through_it() -> None:
    created: list[FakeClient] = []
    runtime = http_client.HttpClients(
        "shared",
        (
            "http://MODEL.test:8000/v1/chat/completions?attempt=1",
            "https://auth.test/jwks",
            "https://auth.test:443/token/exchange",
        ),
        client_factory=lambda: fake_client_factory(created),
    )

    assert len(created) == 1
    shared = cast(httpx.Client, created[0])
    assert runtime.client_for("http://model.test:8000/another/path") is shared
    assert runtime.client_for("https://AUTH.test/anything?different=true") is shared
    assert runtime.origins == (
        http_client.Origin("http", "model.test", 8000),
        http_client.Origin("https", "auth.test", 443),
    )

    runtime.close()
    runtime.close()
    assert created[0].close_calls == 1


def test_per_authority_layout_prebuilds_one_client_per_normalized_origin() -> None:
    created: list[FakeClient] = []
    runtime = http_client.HttpClients(
        "per-authority",
        (
            "http://model.test:8000/v1/chat/completions",
            "https://auth.test/jwks",
            "https://auth.test:443/token/exchange?scope=one",
            "http://commerce.test/tools",
            "http://commerce.test:80/liveness",
            "http://elasticsearch.test:9200/knowledge/_search",
            "",
        ),
        client_factory=lambda: fake_client_factory(created),
    )

    model = runtime.client_for("http://MODEL.test:8000/other")
    auth = runtime.client_for("https://auth.test/other")
    commerce = runtime.client_for("http://commerce.test:80/other")
    elasticsearch = runtime.client_for("http://elasticsearch.test:9200/other")
    assert len(created) == 4
    assert model is not auth
    assert auth is runtime.client_for("https://AUTH.test:443/path?query=ignored")
    assert commerce is runtime.client_for("http://commerce.test/path")
    assert elasticsearch is runtime.client_for("http://ELASTICSEARCH.test:9200/path")

    runtime.close()
    assert [client.close_calls for client in created] == [1, 1, 1, 1]


def test_unprebuilt_origin_is_a_configuration_error_in_both_layouts() -> None:
    for layout in ("shared", "per-authority"):
        runtime = http_client.HttpClients(layout, ("https://configured.test/path",))
        try:
            with pytest.raises(RuntimeError, match="was not prebuilt"):
                runtime.client_for("https://unexpected.test/path")
        finally:
            runtime.close()


def test_direct_runtime_install_cannot_overwrite_a_live_owner() -> None:
    first = http_client.HttpClients("shared", ("http://first.test",))
    second = http_client.HttpClients("shared", ("http://second.test",))
    http_client.install(first)
    try:
        with pytest.raises(RuntimeError, match="already installed"):
            http_client.install(second)
    finally:
        http_client.uninstall(first)
        first.close()

    try:
        http_client.install(second)
    finally:
        http_client.uninstall(second)
        second.close()


def test_partially_built_per_authority_layout_is_closed_when_client_creation_fails() -> None:
    created: list[FakeClient] = []

    def create() -> httpx.Client:
        if len(created) == 2:
            raise RuntimeError("client construction failed")
        return fake_client_factory(created)

    with pytest.raises(RuntimeError, match="client construction failed"):
        http_client.HttpClients(
            "per-authority",
            ("http://one.test", "http://two.test", "http://three.test"),
            client_factory=create,
        )

    assert [client.close_calls for client in created] == [1, 1]
