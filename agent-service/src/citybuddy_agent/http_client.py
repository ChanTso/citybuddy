"""Worker-local pooled HTTP clients for the agent's outbound boundaries."""

from __future__ import annotations

from collections.abc import Callable, Iterable, Iterator, Mapping
from contextlib import AbstractContextManager, contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from typing import Any, Literal

import httpx


class _CookielessTransport(httpx.BaseTransport):
    """Drop Set-Cookie before the client can store it.

    The agent reaches commerce and auth for many different users, with one just-in-time token per
    request, so a stored cookie would travel from one user's request into another's. No boundary
    here sets one today; discarding them means that staying true is not a precondition for sharing
    a client.

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


# A client pool must cover every outbound request in flight. Starlette's sync handlers have 40
# worker threads and the trace exporter has its own thread, so 48 preserves the existing ceiling
# and headroom for both shared and per-authority layouts.
_MAX_CONNECTIONS = 48


def _new_client() -> httpx.Client:
    return httpx.Client(
        transport=_CookielessTransport(
            httpx.HTTPTransport(
                limits=httpx.Limits(
                    max_connections=_MAX_CONNECTIONS,
                    max_keepalive_connections=_MAX_CONNECTIONS,
                )
            )
        )
    )


def _unique_clients(clients: Iterable[httpx.Client]) -> tuple[httpx.Client, ...]:
    return tuple({id(client): client for client in clients}.values())


def _close_clients(clients: Iterable[httpx.Client]) -> None:
    first_failure: BaseException | None = None
    for client in _unique_clients(clients):
        try:
            client.close()
        except BaseException as exception:
            if first_failure is None:
                first_failure = exception
    if first_failure is not None:
        raise first_failure


@dataclass(frozen=True, order=True)
class Origin:
    scheme: str
    host: str
    port: int


HttpClientLayout = Literal["shared", "per-authority"]


def origin(url: str) -> Origin:
    """Return the normalized HTTP authority used to choose a prebuilt client."""
    try:
        parsed = httpx.URL(url)
    except httpx.InvalidURL as exception:
        raise ValueError("Outbound HTTP URL has an invalid authority") from exception
    scheme = parsed.scheme
    host = parsed.host
    if scheme not in {"http", "https"} or not host:
        raise ValueError("Outbound HTTP URL must use http or https with an authority")
    return Origin(
        scheme=scheme,
        host=host,
        port=parsed.port if parsed.port is not None else (80 if scheme == "http" else 443),
    )


class HttpClients:
    """One worker's fully prebuilt outbound client layout."""

    def __init__(
        self,
        layout: HttpClientLayout,
        urls: Iterable[str],
        *,
        client_factory: Callable[[], httpx.Client] = _new_client,
    ) -> None:
        configured_origins = tuple(sorted({origin(url) for url in urls if url}))
        clients: list[httpx.Client] = []
        try:
            if layout == "shared":
                shared = client_factory()
                clients.append(shared)
                by_origin = {configured: shared for configured in configured_origins}
            elif layout == "per-authority":
                by_origin = {}
                for configured in configured_origins:
                    client = client_factory()
                    clients.append(client)
                    by_origin[configured] = client
            else:
                raise ValueError("HTTP client layout must be shared or per-authority")
        except BaseException as construction_failure:
            try:
                _close_clients(clients)
            except BaseException as cleanup_failure:
                raise cleanup_failure from construction_failure
            raise
        self.layout = layout
        self.origins = configured_origins
        self._by_origin = by_origin
        self._clients = _unique_clients(clients)
        self._closed = False

    def client_for(self, url: str) -> httpx.Client:
        requested = origin(url)
        try:
            return self._by_origin[requested]
        except KeyError as exception:
            raise RuntimeError(
                "Outbound HTTP origin was not prebuilt: "
                f"{requested.scheme}://{requested.host}:{requested.port}"
            ) from exception

    def close(self) -> None:
        if self._closed:
            return
        self._closed = True
        _close_clients(self._clients)


_BOUND_RUNTIME: ContextVar[HttpClients | None] = ContextVar(
    "citybuddy_agent_http_clients", default=None
)
_INSTALLED_RUNTIME: HttpClients | None = None


@contextmanager
def use(runtime: HttpClients) -> Iterator[None]:
    """Bind one app's clients to work performed in the current request context."""
    token = _BOUND_RUNTIME.set(runtime)
    try:
        yield
    finally:
        _BOUND_RUNTIME.reset(token)


def install(runtime: HttpClients) -> None:
    """Install the exclusive fallback runtime used by direct integration scripts."""
    global _INSTALLED_RUNTIME
    if _INSTALLED_RUNTIME is not None and _INSTALLED_RUNTIME is not runtime:
        raise RuntimeError("Outbound HTTP clients are already installed")
    _INSTALLED_RUNTIME = runtime


def uninstall(runtime: HttpClients) -> None:
    global _INSTALLED_RUNTIME
    if _INSTALLED_RUNTIME is runtime:
        _INSTALLED_RUNTIME = None


def _client_for(url: str) -> httpx.Client:
    runtime = _BOUND_RUNTIME.get()
    if runtime is None:
        runtime = _INSTALLED_RUNTIME
    if runtime is None:
        raise RuntimeError("Outbound HTTP clients are not configured")
    return runtime.client_for(url)


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
    return _client_for(url).get(url, timeout=timeout)


def post(
    url: str,
    *,
    json: Any = None,
    headers: Mapping[str, str] | None = None,
    auth: tuple[str, str] | None = None,
    timeout: float,
) -> httpx.Response:
    return _client_for(url).post(
        url,
        json=json,
        headers=headers,
        auth=httpx.USE_CLIENT_DEFAULT if auth is None else auth,
        timeout=timeout,
    )


def request(method: str, url: str, *, json: Any = None, timeout: float) -> httpx.Response:
    return _client_for(url).request(method, url, json=json, timeout=timeout)


def stream(
    method: str,
    url: str,
    *,
    content: bytes | None = None,
    json: Any = None,
    headers: Mapping[str, str] | None = None,
    timeout: float | httpx.Timeout,
) -> AbstractContextManager[httpx.Response]:
    return _client_for(url).stream(
        method,
        url,
        content=content,
        json=json,
        headers=headers,
        timeout=timeout,
    )
