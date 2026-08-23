from __future__ import annotations

import http.server
import socket
import socketserver
import threading
from typing import Any

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


def test_outbound_requests_reuse_one_connection() -> None:
    with CountingServer(("127.0.0.1", 0), EmptyJsonHandler) as server:
        serving = threading.Thread(target=server.serve_forever, daemon=True)
        serving.start()
        try:
            url = f"http://127.0.0.1:{server.server_address[1]}/"
            for _ in range(3):
                assert http_client.get(url, timeout=2.0).status_code == 200
        finally:
            server.shutdown()
            serving.join(timeout=2.0)
    assert server.accepted == 1


def test_a_cookie_from_one_request_is_not_sent_on_the_next() -> None:
    """One client serves every user's turn, so a stored cookie would cross between users."""
    EmptyJsonHandler.received_cookies = []
    with CountingServer(("127.0.0.1", 0), EmptyJsonHandler) as server:
        serving = threading.Thread(target=server.serve_forever, daemon=True)
        serving.start()
        try:
            url = f"http://127.0.0.1:{server.server_address[1]}/"
            for _ in range(3):
                assert http_client.get(url, timeout=2.0).status_code == 200
        finally:
            server.shutdown()
            serving.join(timeout=2.0)
    assert EmptyJsonHandler.received_cookies == [None, None, None]
