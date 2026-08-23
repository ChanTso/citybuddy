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


class _CookielessTransport(httpx.BaseTransport):
    """Drop Set-Cookie before the client can store it.

    A client built per call was discarded with whatever cookie it had picked up, so no cookie ever
    travelled from one outbound request to the next. A shared client keeps them. The agent reaches
    commerce and auth for many different users, with one just-in-time token per request, so a
    stored cookie would travel from one user's request into another's. No boundary here sets one
    today; discarding them means that staying true is not a precondition for this change.

    Supplying a transport also turns off httpx's environment proxy support, which the agent does
    not use: every dependency is a fixed address on the same network.
    """

    def __init__(self, inner: httpx.HTTPTransport) -> None:
        self._inner = inner

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        response = self._inner.handle_request(request)
        if "set-cookie" in response.headers:
            del response.headers["set-cookie"]
        return response

    def close(self) -> None:
        # The base class default is a no-op, so without this a close would leave the inner
        # connection pool open while appearing to have shut it down.
        self._inner.close()


# The pool has to cover every outbound request in flight at once, or a turn waits for a
# connection instead of for its dependency. Starlette runs the sync request handlers on a
# 40-thread pool and a turn holds one outbound request at a time, so 40 is the ceiling the
# handlers can reach; the remainder covers the trace exporter, which has its own thread.
# The limits belong to the transport, not to the client, because a client given its own
# transport ignores the limits passed alongside it.
_MAX_CONNECTIONS = 48
_CLIENT = httpx.Client(
    transport=_CookielessTransport(
        httpx.HTTPTransport(
            limits=httpx.Limits(
                max_connections=_MAX_CONNECTIONS,
                max_keepalive_connections=_MAX_CONNECTIONS,
            )
        )
    )
)

# A pooled client can hand out a connection the peer closed between two requests, which surfaces
# as a remote protocol error rather than a network error. To every caller here it means what a
# network error means: the request produced no answer. This names RemoteProtocolError and not its
# base class: the sibling LocalProtocolError is this service violating HTTP itself, and proxy and
# unsupported-protocol errors are configuration faults. None of those is a dependency failure and
# none may be reported as one.
TRANSPORT_FAILURES: tuple[type[Exception], ...] = (
    httpx.TimeoutException,
    httpx.NetworkError,
    httpx.RemoteProtocolError,
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
