"""Forward HTTP requests while deterministically dropping selected upstream responses."""

from __future__ import annotations

import argparse
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock
from urllib.parse import urlsplit


class DropState:
    def __init__(self, upstream: str, path_prefix: str, drop_count: int) -> None:
        parsed = urlsplit(upstream)
        if parsed.scheme != "http" or not parsed.hostname or parsed.port is None:
            raise ValueError("upstream must be an explicit http host and port")
        self.host = parsed.hostname
        self.port = parsed.port
        self.path_prefix = path_prefix
        self.remaining = drop_count
        self.lock = Lock()

    def should_drop(self, path: str) -> bool:
        with self.lock:
            if not path.startswith(self.path_prefix) or self.remaining == 0:
                return False
            self.remaining -= 1
            return True


class Handler(BaseHTTPRequestHandler):
    server: DropServer
    max_body_bytes = 1_048_576

    def do_GET(self) -> None:  # noqa: N802
        self._forward()

    def do_POST(self) -> None:  # noqa: N802
        self._forward()

    def _forward(self) -> None:
        body = self._read_body()
        headers = {
            name: value
            for name, value in self.headers.items()
            if name.lower() not in {"connection", "content-length", "host", "transfer-encoding"}
        }
        connection = http.client.HTTPConnection(
            self.server.state.host, self.server.state.port, timeout=5
        )
        try:
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            dropped = self.server.state.should_drop(self.path)
            print(
                f"upstream_status={response.status} path={self.path} dropped={dropped}",
                flush=True,
            )
            if dropped:
                self.close_connection = True
                return
            self.send_response(response.status)
            content_type = response.getheader("Content-Type")
            if content_type:
                self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        finally:
            connection.close()

    def _read_body(self) -> bytes | None:
        length_header = self.headers.get("Content-Length")
        if length_header is not None:
            length = int(length_header)
            if length > self.max_body_bytes:
                raise ValueError("request body exceeds proxy bound")
            return self.rfile.read(length) if length else None

        if self.headers.get("Transfer-Encoding", "").lower() != "chunked":
            return None
        body = bytearray()
        while True:
            size_line = self.rfile.readline(128)
            size = int(size_line.split(b";", 1)[0].strip(), 16)
            if size == 0:
                while self.rfile.readline(8_192) not in {b"\r\n", b"\n", b""}:
                    pass
                return bytes(body)
            if len(body) + size > self.max_body_bytes:
                raise ValueError("request body exceeds proxy bound")
            body.extend(self.rfile.read(size))
            if self.rfile.read(2) != b"\r\n":
                raise ValueError("invalid chunk delimiter")

    def log_message(self, format: str, *args: object) -> None:
        del format, args


class DropServer(ThreadingHTTPServer):
    def __init__(self, address: tuple[str, int], state: DropState) -> None:
        super().__init__(address, Handler)
        self.state = state


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--upstream", required=True)
    parser.add_argument("--path-prefix", required=True)
    parser.add_argument("--drop-count", type=int, default=1)
    args = parser.parse_args()
    if args.drop_count < 1 or args.drop_count > 20:
        raise ValueError("drop-count must be between 1 and 20")
    state = DropState(args.upstream, args.path_prefix, args.drop_count)
    DropServer(("127.0.0.1", args.port), state).serve_forever()


if __name__ == "__main__":
    main()
