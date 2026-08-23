"""One process-wide HTTP client for the agent's outbound boundaries.

httpx's module-level helpers construct a client per call, and constructing a client builds a TLS
trust store from the system CA bundle. That was 13.1 ms of CPU on every outbound request, on
turns whose whole p99 is tens of milliseconds, and none of these URLs is https
(`bench/agent/README.md`). One long-lived client pays it once at import and reuses connections
between turns.
"""

from __future__ import annotations

from collections.abc import Mapping
from contextlib import AbstractContextManager
from typing import Any

import httpx

# The pool has to cover every outbound request in flight at once, or a turn waits for a
# connection instead of for its dependency. Starlette runs the sync request handlers on a
# 40-thread pool and a turn holds one outbound request at a time, so 40 is the ceiling the
# handlers can reach; the remainder covers the trace exporter, which has its own thread.
_MAX_CONNECTIONS = 48
_CLIENT = httpx.Client(
    limits=httpx.Limits(
        max_connections=_MAX_CONNECTIONS,
        max_keepalive_connections=_MAX_CONNECTIONS,
    )
)

# A pooled client can hand out a connection the peer closed between two requests, which surfaces
# as a protocol error rather than a network error. To every caller here it means what a network
# error means: the request produced no answer. Proxy and unsupported-protocol errors are not in
# this set — those are configuration faults and must not be classified as a dependency failure.
TRANSPORT_FAILURES: tuple[type[Exception], ...] = (
    httpx.TimeoutException,
    httpx.NetworkError,
    httpx.ProtocolError,
)


def get(url: str, *, timeout: float) -> httpx.Response:
    return _CLIENT.get(url, timeout=timeout)


def post(
    url: str,
    *,
    json: Any = None,
    headers: Mapping[str, str] | None = None,
    auth: tuple[str, str] | None = None,
    timeout: float,
) -> httpx.Response:
    return _CLIENT.post(
        url,
        json=json,
        headers=headers,
        auth=httpx.USE_CLIENT_DEFAULT if auth is None else auth,
        timeout=timeout,
    )


def request(method: str, url: str, *, json: Any = None, timeout: float) -> httpx.Response:
    return _CLIENT.request(method, url, json=json, timeout=timeout)


def stream(
    method: str,
    url: str,
    *,
    content: bytes | None = None,
    json: Any = None,
    headers: Mapping[str, str] | None = None,
    timeout: float | httpx.Timeout,
) -> AbstractContextManager[httpx.Response]:
    return _CLIENT.stream(method, url, content=content, json=json, headers=headers, timeout=timeout)
