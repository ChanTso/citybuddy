import json
import time
from collections.abc import Mapping
from typing import Any

import httpx
import jwt
import pymysql
import pytest
from citybuddy_agent.agent_control import AgentEvent, AgentRunner, AgentRunResult
from citybuddy_agent.application import (
    AgentSettings,
    DirectJwtValidator,
    DirectPrincipal,
    OboClient,
    SessionStore,
    create_app,
)
from citybuddy_agent.conversation import (
    ConversationOwnershipError,
    ConversationResult,
    ConversationStore,
    CorrelationConflictError,
    TurnStart,
)
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import HTTPException
from fastapi.testclient import TestClient


class CountingJwksSource:
    def __init__(self, keys: list[dict[str, Any]]) -> None:
        self.keys = keys
        self.calls = 0

    def load(self) -> Mapping[str, Any]:
        self.calls += 1
        return {"keys": self.keys}


class MemorySessionStore(SessionStore):
    def __init__(self) -> None:
        self.owners: dict[str, str] = {}
        self.counter = 0

    def create(self, subject: str) -> str:
        self.counter += 1
        session_id = f"opaque-server-session-{self.counter}"
        self.owners[session_id] = subject
        return session_id

    def verify_owner(self, session_id: str, subject: str) -> None:
        if self.owners.get(session_id) != subject:
            raise HTTPException(status_code=403, detail="Forbidden")


class MemoryConversationStore(ConversationStore):
    def __init__(self, sessions: MemorySessionStore) -> None:
        self.sessions = sessions
        self.results: dict[tuple[str, str], tuple[str, ConversationResult]] = {}
        self.pending: dict[tuple[str, str], tuple[str, TurnStart]] = {}
        self.failures: list[tuple[str, str]] = []
        self.calls = 0

    def begin_turn(
        self,
        *,
        session_id: str,
        subject: str,
        correlation_key: str,
        message: str,
    ) -> TurnStart:
        self.calls += 1
        if self.sessions.owners.get(session_id) != subject:
            raise ConversationOwnershipError
        key = (session_id, correlation_key)
        existing = self.results.get(key)
        if existing is not None:
            if existing[0] != message:
                raise CorrelationConflictError
            result = existing[1]
            return TurnStart(result.conversation_id, result.trace_id, result.turn_id, result)
        start = TurnStart(
            conversation_id=f"server-conversation-{session_id}",
            trace_id=f"server-trace-{self.calls}",
            turn_id=f"server-turn-{self.calls}",
        )
        self.pending[key] = (message, start)
        return start

    def complete_turn(
        self,
        *,
        start: TurnStart,
        response_text: str,
        outcome: str,
        events: tuple[AgentEvent, ...],
    ) -> ConversationResult:
        del events
        key, pending = next(
            item for item in self.pending.items() if item[1][1].turn_id == start.turn_id
        )
        result = ConversationResult(
            start.conversation_id, start.trace_id, start.turn_id, response_text, outcome
        )
        self.results[key] = (pending[0], result)
        del self.pending[key]
        return result

    def fail_turn(self, *, start: TurnStart, failure_code: str) -> None:
        self.failures.append((start.turn_id, failure_code))
        for key, pending in tuple(self.pending.items()):
            if pending[1].turn_id == start.turn_id:
                del self.pending[key]


class MemoryAgent(AgentRunner):
    def __init__(self) -> None:
        self.calls = 0

    def run(
        self,
        *,
        message: str,
        direct_token: str,
        subject: str,
        session_id: str,
        trace_id: str,
        turn_id: str,
    ) -> AgentRunResult:
        self.calls += 1
        del message, direct_token, subject, session_id, trace_id, turn_id
        return AgentRunResult(
            "Bounded support response.",
            "completed",
            (AgentEvent("AGENT_OUTCOME", {"outcome": "completed"}),),
        )


def settings() -> AgentSettings:
    return AgentSettings(
        environment="test",
        identity_enabled=True,
        issuer="https://identity.citybuddy.test",
        user_audience="citybuddy-web",
        jwks_url="https://auth.test/auth/jwks",
        auth_exchange_url="https://auth.test/auth/token/exchange",
        service_client_id="agent-service",
        service_client_secret="runtime-only-secret",
        exchange_scopes=("catalog:read",),
    )


def key_fixture(kid: str) -> tuple[rsa.RSAPrivateKey, dict[str, Any]]:
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public_jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(private.public_key()))
    public_jwk.update({"kid": kid, "alg": "RS256", "use": "sig"})
    return private, public_jwk


def direct_token(
    private: rsa.RSAPrivateKey,
    kid: str,
    *,
    subject: str = "user-123",
    token_type: str = "direct_user",
    audience: str | list[str] = "citybuddy-web",
    issuer: str = "https://identity.citybuddy.test",
    expires_delta: int = 300,
    not_before_delta: int = 0,
    extra: dict[str, Any] | None = None,
    permissions: list[str] | None = None,
) -> str:
    now = int(time.time())
    payload: dict[str, Any] = {
        "iss": issuer,
        "aud": audience,
        "sub": subject,
        "token_type": token_type,
        "principal_state": "ACTIVE",
        "permissions": permissions or ["support:session:create", "support:chat"],
        "iat": now,
        "nbf": now + not_before_delta,
        "exp": now + expires_delta,
    }
    payload.update(extra or {})
    return jwt.encode(payload, private, algorithm="RS256", headers={"kid": kid})


def test_create_app_keeps_identity_routes_disabled_without_runtime_configuration() -> None:
    explicit = AgentSettings(environment="test")

    app = create_app(explicit)

    assert app.title == "agent-service"
    assert app.state.settings is explicit
    assert TestClient(app).post("/api/sessions", json={}).status_code == 404


def test_direct_validator_refreshes_once_for_unknown_kid_and_accepts_overlap() -> None:
    current_private, current_jwk = key_fixture("current-key")
    overlap_private, overlap_jwk = key_fixture("overlap-key")
    source = CountingJwksSource([current_jwk, overlap_jwk])
    validator = DirectJwtValidator(settings(), source)

    principal = validator.validate(direct_token(overlap_private, "overlap-key"))

    assert principal.subject == "user-123"
    assert source.calls == 1
    validator.validate(direct_token(current_private, "current-key"))
    assert source.calls == 1

    unknown_private, _ = key_fixture("unknown-key")
    with pytest.raises(HTTPException) as failure:
        validator.validate(direct_token(unknown_private, "unknown-key"))
    assert failure.value.status_code == 401
    assert source.calls == 2


@pytest.mark.parametrize(
    ("overrides", "extra"),
    [
        ({"token_type": "agent_obo"}, None),
        ({"audience": "commerce-service"}, None),
        ({"audience": ["citybuddy-web", "other-audience"]}, None),
        ({"issuer": "https://wrong.example"}, None),
        ({"expires_delta": -120}, None),
        ({"not_before_delta": 120}, None),
        ({}, {"principal_state": "DISABLED"}),
        ({}, {"session": "forged"}),
        ({}, {"sandbox": "eval-not-enabled"}),
    ],
)
def test_direct_validator_rejects_wrong_mode_audience_time_and_context(
    overrides: dict[str, Any], extra: dict[str, Any] | None
) -> None:
    private, public_jwk = key_fixture("current-key")
    validator = DirectJwtValidator(settings(), CountingJwksSource([public_jwk]))

    token = direct_token(private, "current-key", extra=extra, **overrides)

    with pytest.raises(HTTPException) as failure:
        validator.validate(token)
    assert failure.value.status_code == 401


def test_direct_validator_fails_closed_when_jwks_is_unavailable() -> None:
    class FailedSource:
        def load(self) -> Mapping[str, Any]:
            raise httpx.ConnectError("unavailable")

    validator = DirectJwtValidator(settings(), FailedSource())
    private, _ = key_fixture("unavailable-key")

    with pytest.raises(HTTPException) as failure:
        validator.validate(direct_token(private, "unavailable-key"))
    assert failure.value.status_code == 401


def test_direct_validator_expires_retired_known_key_after_bounded_cache() -> None:
    private, public_jwk = key_fixture("overlap-key")
    source = CountingJwksSource([public_jwk])
    immediate_refresh = settings().model_copy(update={"jwks_cache_seconds": 0})
    validator = DirectJwtValidator(immediate_refresh, source)
    token = direct_token(private, "overlap-key")

    assert validator.validate(token).subject == "user-123"
    source.keys = []

    with pytest.raises(HTTPException) as retired:
        validator.validate(token)
    assert retired.value.status_code == 401
    assert source.calls == 2


def test_session_endpoint_uses_token_subject_and_rejects_client_identity_and_eval_header() -> None:
    private, public_jwk = key_fixture("current-key")
    validator = DirectJwtValidator(settings(), CountingJwksSource([public_jwk]))
    sessions = MemorySessionStore()
    client = TestClient(create_app(settings(), validator=validator, sessions=sessions))
    token = direct_token(private, "current-key")

    response = client.post("/api/sessions", headers={"Authorization": f"Bearer {token}"}, json={})

    assert response.status_code == 201
    session_id = response.json()["sessionId"]
    assert session_id == "opaque-server-session-1"
    assert sessions.owners[session_id] == "user-123"
    assert (
        client.post(
            "/api/sessions",
            headers={"Authorization": f"Bearer {token}"},
            json={"user_subject": "other-user"},
        ).status_code
        == 422
    )
    assert (
        client.post(
            "/api/sessions",
            headers={
                "Authorization": f"Bearer {token}",
                "X-Eval-Sandbox-Id": "forbidden-production-context",
            },
            json={},
        ).status_code
        == 401
    )
    chat_only = direct_token(private, "current-key", permissions=["support:chat"])
    assert (
        client.post(
            "/api/sessions",
            headers={"Authorization": f"Bearer {chat_only}"},
            json={},
        ).status_code
        == 403
    )


def test_obo_client_rechecks_owner_and_server_allowlist(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    client = OboClient(settings(), sessions)
    principal = DirectPrincipal(subject="user-123", permissions=("support:session:create",))
    requests: list[dict[str, Any]] = []

    def exchange_response(*args: Any, **kwargs: Any) -> httpx.Response:
        requests.append(kwargs)
        return httpx.Response(200, json={"accessToken": "signed-obo"})

    monkeypatch.setattr(httpx, "post", exchange_response)

    assert (
        client.exchange("direct-token", principal.subject, session_id, "catalog:read")
        == "signed-obo"
    )
    assert requests[0]["json"] == {
        "sessionId": session_id,
        "userSubject": "user-123",
        "scope": "catalog:read",
    }
    with pytest.raises(HTTPException) as widened:
        client.exchange("direct-token", principal.subject, session_id, "catalog:write")
    assert widened.value.status_code == 403
    with pytest.raises(HTTPException) as cross_user:
        client.exchange(
            "direct-token",
            "other-user",
            session_id,
            "catalog:read",
        )
    assert cross_user.value.status_code == 403
    with pytest.raises(HTTPException) as forged:
        client.exchange("direct-token", principal.subject, "forged-session", "catalog:read")
    assert forged.value.status_code == 403


def test_obo_client_rejects_malformed_exchange_response(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    client = OboClient(settings(), sessions)
    monkeypatch.setattr(
        httpx,
        "post",
        lambda *args, **kwargs: httpx.Response(200, content=b"{"),
    )

    with pytest.raises(HTTPException) as malformed:
        client.exchange("direct-token", "user-123", session_id, "catalog:read")

    assert malformed.value.status_code == 502
    assert malformed.value.detail == "Identity exchange rejected"


def test_chat_persists_server_owned_result_and_replays_same_intent() -> None:
    private, public_jwk = key_fixture("current-key")
    validator = DirectJwtValidator(settings(), CountingJwksSource([public_jwk]))
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    agent = MemoryAgent()
    client = TestClient(
        create_app(
            settings(),
            validator=validator,
            sessions=sessions,
            conversations=conversations,
            agent=agent,
        )
    )
    headers = {
        "Authorization": f"Bearer {direct_token(private, 'current-key')}",
        "X-Session-Id": session_id,
        "Idempotency-Key": "turn-request-1",
    }

    first = client.post("/api/chat", headers=headers, json={"message": "Where is my order?"})
    replay = client.post("/api/chat", headers=headers, json={"message": "Where is my order?"})

    assert first.status_code == 200
    assert replay.status_code == 200
    assert replay.json() == first.json()
    assert set(first.json()) == {"conversationId", "traceId", "turnId", "reply", "outcome"}
    assert first.json()["outcome"] == "completed"
    assert "order" not in first.json()["reply"].lower()
    assert len(conversations.results) == 1
    assert agent.calls == 1


def test_chat_rejects_conflict_identity_substitution_and_private_context() -> None:
    private, public_jwk = key_fixture("current-key")
    validator = DirectJwtValidator(settings(), CountingJwksSource([public_jwk]))
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    client = TestClient(
        create_app(
            settings(),
            validator=validator,
            sessions=sessions,
            conversations=conversations,
            agent=MemoryAgent(),
        )
    )
    token = direct_token(private, "current-key")
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Session-Id": session_id,
        "Idempotency-Key": "turn-request-1",
    }
    assert client.post("/api/chat", headers=headers, json={"message": "first"}).status_code == 200
    assert (
        client.post("/api/chat", headers=headers, json={"message": "different"}).status_code == 409
    )
    malformed = client.post(
        "/api/chat",
        headers=headers,
        json={"message": "private input", "traceId": "client-selected"},
    )
    assert malformed.status_code == 422
    assert malformed.json() == {"detail": "Invalid request"}
    assert "private input" not in malformed.text
    calls_before_owner_rejections = conversations.calls
    assert (
        client.post(
            "/api/chat",
            headers={**headers, "X-Session-Id": "unknown-session"},
            json={"message": "first"},
        ).status_code
        == 403
    )
    assert conversations.calls == calls_before_owner_rejections
    other_token = direct_token(private, "current-key", subject="other-user")
    assert (
        client.post(
            "/api/chat",
            headers={**headers, "Authorization": f"Bearer {other_token}"},
            json={"message": "first"},
        ).status_code
        == 403
    )
    assert conversations.calls == calls_before_owner_rejections
    assert (
        client.post(
            "/api/chat",
            headers={**headers, "X-Eval-Sandbox-Id": "forbidden"},
            json={"message": "first"},
        ).status_code
        == 401
    )
    assert (
        client.post(
            "/api/chat",
            headers={
                "X-Session-Id": session_id,
                "Idempotency-Key": "missing-auth",
            },
            json={"message": "first"},
        ).status_code
        == 401
    )


def test_chat_requires_route_permission_before_conversation_access() -> None:
    private, public_jwk = key_fixture("current-key")
    validator = DirectJwtValidator(settings(), CountingJwksSource([public_jwk]))
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    client = TestClient(
        create_app(
            settings(),
            validator=validator,
            sessions=sessions,
            conversations=conversations,
            agent=MemoryAgent(),
        )
    )
    token = direct_token(private, "current-key", permissions=["support:session:create"])

    response = client.post(
        "/api/chat",
        headers={
            "Authorization": f"Bearer {token}",
            "X-Session-Id": session_id,
            "Idempotency-Key": "denied",
        },
        json={"message": "not authorized"},
    )

    assert response.status_code == 403
    assert conversations.calls == 0


def test_chat_redacts_mysql_failure() -> None:
    class FailedConversationStore(ConversationStore):
        def begin_turn(
            self,
            *,
            session_id: str,
            subject: str,
            correlation_key: str,
            message: str,
        ) -> TurnStart:
            del session_id, subject, correlation_key, message
            raise pymysql.OperationalError(1142, "private SQL detail")

        def complete_turn(
            self,
            *,
            start: TurnStart,
            response_text: str,
            outcome: str,
            events: tuple[AgentEvent, ...],
        ) -> ConversationResult:
            raise AssertionError("unreachable")

        def fail_turn(self, *, start: TurnStart, failure_code: str) -> None:
            raise AssertionError("unreachable")

    private, public_jwk = key_fixture("current-key")
    validator = DirectJwtValidator(settings(), CountingJwksSource([public_jwk]))
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    client = TestClient(
        create_app(
            settings(),
            validator=validator,
            sessions=sessions,
            conversations=FailedConversationStore(),
            agent=MemoryAgent(),
        )
    )
    response = client.post(
        "/api/chat",
        headers={
            "Authorization": f"Bearer {direct_token(private, 'current-key')}",
            "X-Session-Id": session_id,
            "Idempotency-Key": "failed",
        },
        json={"message": "hello"},
    )

    assert response.status_code == 503
    assert response.json() == {"detail": "Service unavailable"}
    assert "private SQL detail" not in response.text


def test_unexpected_agent_error_is_visible_and_marks_the_reserved_turn_failed() -> None:
    class FailedAgent(AgentRunner):
        def run(
            self,
            *,
            message: str,
            direct_token: str,
            subject: str,
            session_id: str,
            trace_id: str,
            turn_id: str,
        ) -> AgentRunResult:
            del message, direct_token, subject, session_id, trace_id, turn_id
            raise RuntimeError("private provider configuration detail")

    private, public_jwk = key_fixture("current-key")
    validator = DirectJwtValidator(settings(), CountingJwksSource([public_jwk]))
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    client = TestClient(
        create_app(
            settings(),
            validator=validator,
            sessions=sessions,
            conversations=conversations,
            agent=FailedAgent(),
        ),
        raise_server_exceptions=False,
    )

    response = client.post(
        "/api/chat",
        headers={
            "Authorization": f"Bearer {direct_token(private, 'current-key')}",
            "X-Session-Id": session_id,
            "Idempotency-Key": "unexpected-failure",
        },
        json={"message": "hello"},
    )

    assert response.status_code == 500
    assert "private provider configuration detail" not in response.text
    assert conversations.failures == [("server-turn-1", "agent_execution_failed")]
