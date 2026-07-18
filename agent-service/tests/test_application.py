import json
import time
from collections.abc import Mapping
from datetime import UTC, datetime
from typing import Any, Literal

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
from citybuddy_agent.evaluation import (
    EvaluationEvidenceInvalid,
    EvaluationEvidenceNotFound,
    EvaluationEvidenceResponse,
    EvaluationEvidenceStore,
    EvidenceEventResponse,
)
from citybuddy_agent.feedback import (
    FeedbackConflictError,
    FeedbackOwnershipError,
    FeedbackRecord,
    FeedbackStore,
)
from citybuddy_agent.retrieval import RetrievalDecision
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
        self.sandboxes: dict[str, str | None] = {}
        self.counter = 0

    def create(self, subject: str, sandbox_id: str | None = None) -> str:
        self.counter += 1
        session_id = f"opaque-server-session-{self.counter}"
        self.owners[session_id] = subject
        self.sandboxes[session_id] = sandbox_id
        return session_id

    def verify_owner(self, session_id: str, subject: str, sandbox_id: str | None = None) -> None:
        if self.owners.get(session_id) != subject or self.sandboxes.get(session_id) != sandbox_id:
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
        sandbox_id: str | None,
        correlation_key: str,
        message: str,
    ) -> TurnStart:
        self.calls += 1
        if (
            self.sessions.owners.get(session_id) != subject
            or self.sessions.sandboxes.get(session_id) != sandbox_id
        ):
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
        retrieval_decision: RetrievalDecision | None = None,
    ) -> ConversationResult:
        del events
        key, pending = next(
            item for item in self.pending.items() if item[1][1].turn_id == start.turn_id
        )
        result = ConversationResult(
            start.conversation_id,
            start.trace_id,
            start.turn_id,
            response_text,
            outcome,
            retrieval_decision.evidence if retrieval_decision is not None else (),
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
        self.sandbox_ids: list[str | None] = []

    def run(
        self,
        *,
        message: str,
        direct_token: str,
        subject: str,
        session_id: str,
        trace_id: str,
        turn_id: str,
        sandbox_id: str | None = None,
    ) -> AgentRunResult:
        self.calls += 1
        self.sandbox_ids.append(sandbox_id)
        del message, direct_token, subject, session_id, trace_id, turn_id
        return AgentRunResult(
            "Bounded support response.",
            "completed",
            (AgentEvent("AGENT_OUTCOME", {"outcome": "completed"}),),
        )


class MemoryFeedbackStore(FeedbackStore):
    def __init__(self, sessions: MemorySessionStore, traces: dict[str, tuple[str, str]]) -> None:
        self.sessions = sessions
        self.traces = traces
        self.records: dict[tuple[str, str], tuple[tuple[str, str, str | None], FeedbackRecord]] = {}

    def append(
        self,
        *,
        session_id: str,
        subject: str,
        trace_id: str,
        idempotency_key: str,
        rating: Literal["POSITIVE", "NEGATIVE"],
        comment: str | None,
    ) -> FeedbackRecord:
        if self.sessions.owners.get(session_id) != subject or self.traces.get(trace_id) != (
            session_id,
            subject,
        ):
            raise FeedbackOwnershipError
        key = (session_id, idempotency_key)
        intent = (trace_id, rating, comment)
        existing = self.records.get(key)
        if existing is not None:
            if existing[0] != intent:
                raise FeedbackConflictError
            return existing[1]
        record = FeedbackRecord(f"server-feedback-{len(self.records) + 1}", trace_id, rating)
        self.records[key] = (intent, record)
        return record


class MemoryLiveness:
    def __init__(self) -> None:
        self.active = True
        self.calls: list[tuple[str, str]] = []

    def require_active(self, direct_token: str, sandbox_id: str) -> None:
        self.calls.append((direct_token, sandbox_id))
        if not self.active:
            raise HTTPException(status_code=403, detail="Forbidden")


class MemoryEvidenceStore(EvaluationEvidenceStore):
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []
        self.mode = "ok"

    def load(self, trace_id: str, sandbox_id: str) -> EvaluationEvidenceResponse:
        self.calls.append((trace_id, sandbox_id))
        if self.mode == "missing":
            raise EvaluationEvidenceNotFound
        if self.mode == "invalid":
            raise EvaluationEvidenceInvalid
        now = datetime(2026, 7, 18, 12, 0, tzinfo=UTC)
        return EvaluationEvidenceResponse(
            schema_version="agent-evidence-v1",
            trace_id=trace_id,
            session_id="sandbox-session",
            turn_id="00000000-0000-0000-0000-000000000002",
            terminal_outcome="completed",
            events=(
                EvidenceEventResponse(
                    sequence=1,
                    event_kind="USER_INPUT",
                    outcome="accepted",
                    occurred_at=now,
                ),
                EvidenceEventResponse(
                    sequence=2,
                    event_kind="TURN_COMPLETED",
                    outcome="completed",
                    occurred_at=now,
                ),
            ),
            feedback=(),
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


def evaluation_settings() -> AgentSettings:
    return settings().model_copy(
        update={
            "evaluation_enabled": True,
            "evaluation_client_id": "evaluation-manager",
            "evaluation_client_secret": "evaluation-runtime-secret",
            "commerce_liveness_url": "https://commerce.test",
        }
    )


def evaluation_basic(secret: str = "evaluation-runtime-secret") -> str:
    import base64

    encoded = base64.b64encode(f"evaluation-manager:{secret}".encode()).decode()
    return f"Basic {encoded}"


def test_evaluation_evidence_route_is_profile_bound_and_independently_authenticated() -> None:
    trace_id = "00000000-0000-0000-0000-000000000001"
    evidence = MemoryEvidenceStore()
    production = TestClient(
        create_app(
            settings(),
            validator=object(),  # type: ignore[arg-type]
            sessions=MemorySessionStore(),
            conversations=object(),  # type: ignore[arg-type]
            agent=MemoryAgent(),
            feedback=object(),  # type: ignore[arg-type]
            evidence=evidence,
        )
    )
    assert (
        production.get(
            f"/api/eval/evidence/{trace_id}",
            headers={
                "Authorization": evaluation_basic(),
                "X-Eval-Sandbox-Id": "sandbox-1",
            },
        ).status_code
        == 404
    )

    sessions = MemorySessionStore()
    client = TestClient(
        create_app(
            evaluation_settings(),
            validator=object(),  # type: ignore[arg-type]
            sessions=sessions,
            conversations=object(),  # type: ignore[arg-type]
            agent=MemoryAgent(),
            feedback=object(),  # type: ignore[arg-type]
            evidence=evidence,
            liveness=MemoryLiveness(),
        )
    )
    url = f"/api/eval/evidence/{trace_id}"
    assert client.get(url, headers={"X-Eval-Sandbox-Id": "sandbox-1"}).status_code == 401
    assert (
        client.get(
            url,
            headers={
                "Authorization": "Bearer direct-user-token",
                "X-Eval-Sandbox-Id": "sandbox-1",
            },
        ).status_code
        == 401
    )
    assert (
        client.get(
            url,
            headers={
                "Authorization": evaluation_basic("wrong-secret"),
                "X-Eval-Sandbox-Id": "sandbox-1",
            },
        ).status_code
        == 401
    )
    response = client.get(
        url,
        headers={
            "Authorization": evaluation_basic(),
            "X-Eval-Sandbox-Id": "sandbox-1",
        },
    )
    assert response.status_code == 200
    assert response.json() == {
        "schemaVersion": "agent-evidence-v1",
        "traceId": trace_id,
        "sessionId": "sandbox-session",
        "turnId": "00000000-0000-0000-0000-000000000002",
        "terminalOutcome": "completed",
        "events": [
            {
                "sequence": 1,
                "eventKind": "USER_INPUT",
                "outcome": "accepted",
                "occurredAt": "2026-07-18T12:00:00Z",
            },
            {
                "sequence": 2,
                "eventKind": "TURN_COMPLETED",
                "outcome": "completed",
                "occurredAt": "2026-07-18T12:00:00Z",
            },
        ],
        "feedback": [],
    }


def test_evaluation_evidence_rejects_invalid_input_and_conceals_association_failures() -> None:
    trace_id = "00000000-0000-0000-0000-000000000001"
    evidence = MemoryEvidenceStore()
    client = TestClient(
        create_app(
            evaluation_settings(),
            validator=object(),  # type: ignore[arg-type]
            sessions=MemorySessionStore(),
            conversations=object(),  # type: ignore[arg-type]
            agent=MemoryAgent(),
            feedback=object(),  # type: ignore[arg-type]
            evidence=evidence,
            liveness=MemoryLiveness(),
        )
    )
    headers = {
        "Authorization": evaluation_basic(),
        "X-Eval-Sandbox-Id": "sandbox-1",
    }
    assert client.get("/api/eval/evidence/not-a-uuid", headers=headers).status_code == 422
    assert (
        client.get(f"/api/eval/evidence/{trace_id}?owner=user", headers=headers).status_code == 422
    )
    assert evidence.calls == []

    evidence.mode = "missing"
    missing = client.get(f"/api/eval/evidence/{trace_id}", headers=headers)
    assert missing.status_code == 404
    assert missing.json() == {"detail": "Evidence not found"}

    evidence.mode = "invalid"
    invalid = client.get(f"/api/eval/evidence/{trace_id}", headers=headers)
    assert invalid.status_code == 409
    assert invalid.json() == {"detail": "Evidence unavailable"}


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


def test_evaluation_session_and_chat_require_liveness_and_exact_sandbox() -> None:
    private, public_jwk = key_fixture("current-key")
    resolved = evaluation_settings()
    validator = DirectJwtValidator(resolved, CountingJwksSource([public_jwk]))
    sessions = MemorySessionStore()
    conversations = MemoryConversationStore(sessions)
    agent = MemoryAgent()
    liveness = MemoryLiveness()
    app = create_app(
        resolved,
        validator=validator,
        sessions=sessions,
        conversations=conversations,
        agent=agent,
        feedback=MemoryFeedbackStore(sessions, {}),
        liveness=liveness,
    )
    client = TestClient(app)
    token = direct_token(
        private,
        "current-key",
        token_type="eval_direct_user",
        extra={"sandbox": "sandbox-1"},
    )
    headers = {
        "Authorization": f"Bearer {token}",
        "X-Eval-Sandbox-Id": "sandbox-1",
    }

    created = client.post("/api/sessions", headers=headers, json={})
    assert created.status_code == 201
    session_id = created.json()["sessionId"]
    assert sessions.sandboxes[session_id] == "sandbox-1"
    assert (
        client.post(
            "/api/sessions",
            headers={**headers, "X-Eval-Sandbox-Id": "sandbox-2"},
            json={},
        ).status_code
        == 401
    )

    chat = client.post(
        "/api/chat",
        headers={
            **headers,
            "X-Session-Id": session_id,
            "Idempotency-Key": "eval-turn-1",
        },
        json={"message": "Show product-1"},
    )
    assert chat.status_code == 200
    assert agent.sandbox_ids == ["sandbox-1"]
    assert len(liveness.calls) == 2

    liveness.active = False
    blocked = client.post(
        "/api/chat",
        headers={
            **headers,
            "X-Session-Id": session_id,
            "Idempotency-Key": "eval-turn-2",
        },
        json={"message": "Show product-1"},
    )
    assert blocked.status_code == 403
    assert conversations.calls == 1
    assert agent.calls == 1


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


def test_evaluation_obo_preserves_exact_sandbox_header(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123", "sandbox-1")
    client = OboClient(evaluation_settings(), sessions)
    requests: list[dict[str, Any]] = []

    def exchange_response(*args: Any, **kwargs: Any) -> httpx.Response:
        requests.append(kwargs)
        return httpx.Response(200, json={"accessToken": "signed-eval-obo"})

    monkeypatch.setattr(httpx, "post", exchange_response)

    assert (
        client.exchange(
            "eval-direct-token",
            "user-123",
            session_id,
            "catalog:read",
            "sandbox-1",
        )
        == "signed-eval-obo"
    )
    assert requests[0]["headers"] == {
        "X-User-Authorization": "Bearer eval-direct-token",
        "X-Eval-Sandbox-Id": "sandbox-1",
    }
    with pytest.raises(HTTPException) as mismatch:
        client.exchange(
            "eval-direct-token",
            "user-123",
            session_id,
            "catalog:read",
            "sandbox-2",
        )
    assert mismatch.value.status_code == 403


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
    assert set(first.json()) == {
        "conversationId",
        "traceId",
        "turnId",
        "reply",
        "outcome",
        "citations",
    }
    assert first.json()["citations"] == []
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
            sandbox_id: str | None,
            correlation_key: str,
            message: str,
        ) -> TurnStart:
            del session_id, subject, sandbox_id, correlation_key, message
            raise pymysql.OperationalError(1142, "private SQL detail")

        def complete_turn(
            self,
            *,
            start: TurnStart,
            response_text: str,
            outcome: str,
            events: tuple[AgentEvent, ...],
            retrieval_decision: RetrievalDecision | None = None,
        ) -> ConversationResult:
            del start, response_text, outcome, events, retrieval_decision
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
            sandbox_id: str | None = None,
        ) -> AgentRunResult:
            del message, direct_token, subject, session_id, trace_id, turn_id, sandbox_id
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


def test_stream_projects_durable_result_and_replay_through_fixed_sse_schema() -> None:
    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    agent = MemoryAgent()
    client = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=conversations,
            agent=agent,
        )
    )
    headers = {
        "Authorization": f"Bearer {direct_token(private, 'current-key')}",
        "X-Session-Id": session_id,
        "Idempotency-Key": "stream-one",
    }

    first = client.post("/api/chat/stream", headers=headers, json={"message": "hello"})
    replay = client.post("/api/chat/stream", headers=headers, json={"message": "hello"})

    assert first.status_code == 200
    assert first.headers["content-type"].startswith("text/event-stream")
    assert first.headers["cache-control"] == "no-cache, no-store"
    assert replay.content == first.content
    assert first.text.count("event: token\n") == 1
    assert first.text.count("event: done\n") == 1
    assert "event: error" not in first.text
    assert '"sequence":1,"text":"Bounded support response."' in first.text
    assert '"sequence":2' in first.text
    assert "ROUTING_DECISION" not in first.text
    assert "tool" not in first.text.lower()
    assert agent.calls == 1
    assert len(conversations.results) == 1

    forbidden = client.post(
        "/api/chat/stream",
        headers={**headers, "X-Session-Id": "forged-session", "Idempotency-Key": "forged"},
        json={"message": "hello"},
    )
    assert forbidden.status_code == 403
    assert forbidden.json() == {"detail": "Forbidden"}
    assert conversations.calls == 2


def test_stream_withholds_action_claim_and_private_execution_failure() -> None:
    class FixedAgent(AgentRunner):
        def __init__(self, result: AgentRunResult | None = None) -> None:
            self.result = result

        def run(
            self,
            *,
            message: str,
            direct_token: str,
            subject: str,
            session_id: str,
            trace_id: str,
            turn_id: str,
            sandbox_id: str | None = None,
        ) -> AgentRunResult:
            del message, direct_token, subject, session_id, trace_id, turn_id, sandbox_id
            if self.result is None:
                raise RuntimeError("private provider stack and credential detail")
            return self.result

    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    headers = {
        "Authorization": f"Bearer {direct_token(private, 'current-key')}",
        "X-Session-Id": session_id,
        "Idempotency-Key": "unsafe-action",
    }
    unsafe_conversations = MemoryConversationStore(sessions)
    unsafe = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=unsafe_conversations,
            agent=FixedAgent(AgentRunResult("I cancelled it for you.", "completed", tuple())),
        )
    ).post("/api/chat/stream", headers=headers, json={"message": "refund"})

    assert unsafe.status_code == 200
    assert unsafe.text.count("event: error\n") == 1
    assert '"code":"unsafe_output"' in unsafe.text
    assert "cancelled" not in unsafe.text.lower()
    assert len(unsafe_conversations.results) == 1

    failed_conversations = MemoryConversationStore(sessions)
    failed = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=failed_conversations,
            agent=FixedAgent(),
        ),
        raise_server_exceptions=False,
    ).post(
        "/api/chat/stream",
        headers={**headers, "Idempotency-Key": "private-failure"},
        json={"message": "fail"},
    )
    assert failed.status_code == 200
    assert failed.text.count("event: error\n") == 1
    assert '"code":"stream_unavailable"' in failed.text
    assert "private provider" not in failed.text
    assert failed_conversations.failures == [("server-turn-1", "agent_execution_failed")]


def test_stream_maps_bounded_non_success_outcomes_to_one_terminal_error() -> None:
    class DeniedAgent(AgentRunner):
        def run(
            self,
            *,
            message: str,
            direct_token: str,
            subject: str,
            session_id: str,
            trace_id: str,
            turn_id: str,
            sandbox_id: str | None = None,
        ) -> AgentRunResult:
            del message, direct_token, subject, session_id, trace_id, turn_id, sandbox_id
            return AgentRunResult("private provider response", "provider_denied", tuple())

    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    response = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=MemoryConversationStore(sessions),
            agent=DeniedAgent(),
        )
    ).post(
        "/api/chat/stream",
        headers={
            "Authorization": f"Bearer {direct_token(private, 'current-key')}",
            "X-Session-Id": session_id,
            "Idempotency-Key": "provider-denied",
        },
        json={"message": "hello"},
    )

    assert response.status_code == 200
    assert response.text.count("event: error\n") == 1
    assert "event: token" not in response.text
    assert "event: done" not in response.text
    assert '"code":"provider_unavailable"' in response.text
    assert "private provider response" not in response.text


def test_feedback_is_owner_scoped_append_only_and_idempotent() -> None:
    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    trace_id = "00000000-0000-0000-0000-000000000821"
    feedback = MemoryFeedbackStore(sessions, {trace_id: (session_id, "user-123")})
    client = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=MemoryConversationStore(sessions),
            agent=MemoryAgent(),
            feedback=feedback,
        )
    )
    headers = {
        "Authorization": f"Bearer {direct_token(private, 'current-key')}",
        "X-Session-Id": session_id,
        "Idempotency-Key": "feedback-one",
    }
    body = {"traceId": trace_id, "rating": "POSITIVE", "comment": "Helpful"}

    first = client.post("/api/feedback", headers=headers, json=body)
    replay = client.post("/api/feedback", headers=headers, json=body)

    assert first.status_code == 201
    assert replay.json() == first.json()
    assert first.json() == {
        "feedbackId": "server-feedback-1",
        "traceId": trace_id,
        "rating": "POSITIVE",
    }
    assert len(feedback.records) == 1
    assert (
        client.post(
            "/api/feedback",
            headers=headers,
            json={**body, "rating": "NEGATIVE"},
        ).status_code
        == 409
    )
    assert (
        client.post(
            "/api/feedback",
            headers={**headers, "Idempotency-Key": "unknown-trace"},
            json={**body, "traceId": "00000000-0000-0000-0000-000000000999"},
        ).status_code
        == 403
    )
    assert (
        client.post(
            "/api/feedback",
            headers={**headers, "Idempotency-Key": "client-owner"},
            json={**body, "userSubject": "other-user"},
        ).status_code
        == 422
    )
    other_token = direct_token(private, "current-key", subject="other-user")
    assert (
        client.post(
            "/api/feedback",
            headers={
                **headers,
                "Authorization": f"Bearer {other_token}",
                "Idempotency-Key": "cross-user",
            },
            json=body,
        ).status_code
        == 403
    )
    assert (
        client.post(
            "/api/feedback",
            headers={**headers, "X-Eval-Sandbox-Id": "forbidden"},
            json=body,
        ).status_code
        == 401
    )
