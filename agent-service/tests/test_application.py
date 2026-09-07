import base64
import importlib.util
import inspect
import json
import logging
import secrets
import sys
import time
from collections.abc import Mapping
from datetime import UTC, datetime, timedelta
from types import FrameType
from typing import Any, Literal, cast

import httpx
import jwt
import pytest
from citybuddy_agent import http_client
from citybuddy_agent.actions import (
    PendingActionReference,
)
from citybuddy_agent.application import (
    AgentSettings,
    DirectJwtValidator,
    DirectPrincipal,
    HttpSandboxLiveness,
    MysqlSessionStore,
    OboClient,
    SessionStore,
    create_app,
)
from citybuddy_agent.conversation import (
    ConversationIntegrityError,
    MysqlConversationStore,
)
from citybuddy_agent.evaluation import (
    ActionEvaluationEvidenceInvalid,
    EvaluationEvidenceInvalid,
    EvaluationEvidenceNotFound,
    EvaluationEvidenceResponse,
    EvaluationEvidenceStore,
    EvidenceEventResponse,
    MysqlEvaluationEvidenceStore,
)
from citybuddy_agent.feedback import (
    FeedbackConflictError,
    FeedbackOwnershipError,
    FeedbackRecord,
    FeedbackStore,
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


class FixedSessionStore(MemorySessionStore):
    def __init__(self, session_id: str) -> None:
        super().__init__()
        self.session_id = session_id

    def create(self, subject: str, sandbox_id: str | None = None) -> str:
        self.owners[self.session_id] = subject
        self.sandboxes[self.session_id] = sandbox_id
        return self.session_id


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
        if self.mode == "action-invalid":
            raise ActionEvaluationEvidenceInvalid
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


def test_evaluation_evidence_rejects_conflicting_or_intermediate_terminal_lifecycle() -> None:
    now = datetime(2026, 7, 18, 12, 0, tzinfo=UTC)
    events = [
        EvidenceEventResponse(
            sequence=1,
            event_kind="USER_INPUT",
            outcome="accepted",
            occurred_at=now,
        ),
        EvidenceEventResponse(
            sequence=2,
            event_kind="AGENT_OUTCOME",
            outcome="completed",
            occurred_at=now,
        ),
        EvidenceEventResponse(
            sequence=3,
            event_kind="ASSISTANT_RESPONSE",
            outcome="completed",
            occurred_at=now,
        ),
        EvidenceEventResponse(
            sequence=4,
            event_kind="TURN_COMPLETED",
            outcome="completed",
            occurred_at=now,
        ),
    ]

    MysqlEvaluationEvidenceStore._validate_lifecycle(events, "completed")
    conflicting = [*events]
    conflicting[1] = conflicting[1].model_copy(update={"outcome": "provider_denied"})
    with pytest.raises(EvaluationEvidenceInvalid):
        MysqlEvaluationEvidenceStore._validate_lifecycle(conflicting, "completed")
    intermediate = [*events]
    intermediate[1] = intermediate[1].model_copy(
        update={"event_kind": "TURN_FAILED", "outcome": "failed"}
    )
    with pytest.raises(EvaluationEvidenceInvalid):
        MysqlEvaluationEvidenceStore._validate_lifecycle(intermediate, "completed")


def test_evaluation_evidence_normalizes_mysql_timestamps_to_utc() -> None:
    naive = datetime(2026, 7, 18, 12, 0)

    normalized = MysqlEvaluationEvidenceStore._utc_timestamp(naive)

    assert normalized.isoformat() == "2026-07-18T12:00:00+00:00"
    assert normalized.tzinfo is UTC


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


def evaluation_basic(
    secret: str = "evaluation-runtime-secret", client_id: str = "evaluation-manager"
) -> str:
    encoded = base64.b64encode(f"{client_id}:{secret}".encode()).decode()
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
    for non_ascii_credential in (
        evaluation_basic("wrong-secret", client_id="évaluation-manager"),
        evaluation_basic("wrong-sécret"),
    ):
        assert (
            client.get(
                url,
                headers={
                    "Authorization": non_ascii_credential,
                    "X-Eval-Sandbox-Id": "sandbox-1",
                },
            ).status_code
            == 401
        )
    malicious_credentials = (
        b"Basic \xc3\xa9",
        b"Basic !!!",
        b"Basic " + base64.b64encode(b"\xff:x"),
        b"Basic " + base64.b64encode(b"missing-colon"),
        b"Basic " + base64.b64encode(b":"),
        b"Bearer evaluator-token",
        b"Basic " + (b"A" * 2048),
        b"Basic " + base64.b64encode(b"evaluation-manager:x\x01"),
        b"Basic " + base64.b64encode(b"evaluation-manager:x\x00"),
    )
    for malicious_credential in malicious_credentials:
        malformed = client.get(
            url,
            headers=[
                (b"authorization", malicious_credential),
                (b"x-eval-sandbox-id", b"sandbox-1"),
            ],
        )
        assert malformed.status_code == 401
        assert malformed.json() == {"detail": "Unauthorized"}
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


def test_evaluation_evidence_rejects_invalid_input_and_conceals_association_failures(
    caplog: pytest.LogCaptureFixture,
) -> None:
    trace_id = "00000000-0000-0000-0000-000000000001"
    evidence = MemoryEvidenceStore()
    client = TestClient(
        create_app(
            evaluation_settings(),
            validator=object(),  # type: ignore[arg-type]
            sessions=MemorySessionStore(),
            conversations=object(),  # type: ignore[arg-type]
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
    assert "ACTION_EVALUATION_DURABLE_TRUTH_INCONSISTENT" not in caplog.text

    evidence.mode = "action-invalid"
    caplog.clear()
    action_invalid = client.get(f"/api/eval/evidence/{trace_id}", headers=headers)
    assert action_invalid.status_code == 409
    assert action_invalid.json() == {"detail": "Evidence unavailable"}
    assert "reason_code=ACTION_EVALUATION_DURABLE_TRUTH_INCONSISTENT" in caplog.text
    assert "ACTION_EVALUATION_DURABLE_TRUTH_INCONSISTENT" not in action_invalid.text


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


def test_create_app_prebuilds_configured_origins_and_closes_trace_before_clients(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []
    captured: dict[str, object] = {}

    class Clients:
        def close(self) -> None:
            events.append("clients")

    clients = Clients()

    def build(layout: str, urls: tuple[str, ...]) -> Clients:
        captured["layout"] = layout
        captured["urls"] = urls
        return clients

    class Sink:
        def emit(self, envelope: object) -> None:
            del envelope

        def close(self) -> None:
            events.append("trace")

    monkeypatch.setattr(http_client, "HttpClients", build)
    resolved = AgentSettings(
        http_client_layout="per-authority",
        jwks_url="http://citybuddy-bench-auth:8080/jwks",
        auth_exchange_url="http://citybuddy-bench-auth:8080/exchange",
        commerce_liveness_url="http://citybuddy-bench-commerce:8080/liveness",
        trace_export_url="",
    )

    with TestClient(create_app(resolved, trace_sink=Sink())):
        pass

    assert captured == {
        "layout": "per-authority",
        "urls": (
            "http://citybuddy-bench-auth:8080/jwks",
            "http://citybuddy-bench-auth:8080/exchange",
            "http://citybuddy-bench-commerce:8080/liveness",
            "",
        ),
    }
    assert events == ["trace", "clients"]


def test_two_live_apps_route_through_their_own_clients_and_close_independently(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []

    class OutboundClient:
        def __init__(self, owner: str) -> None:
            self.owner = owner

        def get(self, url: str, *, timeout: float) -> httpx.Response:
            del url, timeout
            return httpx.Response(200, json={"owner": self.owner})

    class Clients:
        def __init__(self, owner: str) -> None:
            self.owner = owner
            self.client = OutboundClient(owner)

        def client_for(self, url: str) -> httpx.Client:
            assert url.startswith(self.owner)
            return cast(httpx.Client, self.client)

        def close(self) -> None:
            events.append(f"{self.owner}:clients")

    class Sink:
        def __init__(self, owner: str) -> None:
            self.owner = owner

        def emit(self, envelope: object) -> None:
            del envelope

        def close(self) -> None:
            events.append(f"{self.owner}:trace")

    def build(layout: str, urls: tuple[str, ...]) -> Clients:
        del layout
        return Clients(urls[0])

    monkeypatch.setattr(http_client, "HttpClients", build)
    first_url = "http://first.test"
    second_url = "http://second.test"
    first = create_app(AgentSettings(jwks_url=first_url), trace_sink=Sink(first_url))
    second = create_app(AgentSettings(jwks_url=second_url), trace_sink=Sink(second_url))

    @first.get("/runtime-owner")
    def first_runtime_owner() -> dict[str, str]:
        return cast(dict[str, str], http_client.get(first_url, timeout=1.0).json())

    @second.get("/runtime-owner")
    def second_runtime_owner() -> dict[str, str]:
        return cast(dict[str, str], http_client.get(second_url, timeout=1.0).json())

    with TestClient(first) as first_client:
        assert first_client.get("/runtime-owner").json() == {"owner": first_url}
        with TestClient(second) as second_client:
            assert second_client.get("/runtime-owner").json() == {"owner": second_url}
            assert first_client.get("/runtime-owner").json() == {"owner": first_url}
        assert first_client.get("/runtime-owner").json() == {"owner": first_url}

    assert events == [
        f"{second_url}:trace",
        f"{second_url}:clients",
        f"{first_url}:trace",
        f"{first_url}:clients",
    ]


def test_create_app_closes_prebuilt_clients_when_factory_construction_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []

    class Clients:
        def close(self) -> None:
            events.append("clients")

    class Sink:
        def emit(self, envelope: object) -> None:
            del envelope

        def close(self) -> None:
            events.append("trace")

    monkeypatch.setattr(http_client, "HttpClients", lambda layout, urls: Clients())

    with pytest.raises(ValueError, match="Evaluation API credential is required"):
        create_app(
            AgentSettings(identity_enabled=True, evaluation_enabled=True),
            trace_sink=Sink(),
        )

    assert events == ["trace", "clients"]


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
    resolved = settings().model_copy(update={"evaluation_session_propagation_enabled": False})
    validator = DirectJwtValidator(resolved, CountingJwksSource([public_jwk]))
    sessions = MemorySessionStore()
    client = TestClient(create_app(resolved, validator=validator, sessions=sessions))
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


def test_mysql_session_store_generates_and_persists_the_exact_opaque_session(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    executions: list[tuple[str, tuple[object, ...]]] = []

    class RecordingCursor:
        def __enter__(self) -> "RecordingCursor":
            return self

        def __exit__(self, *args: object) -> None:
            return None

        def execute(self, statement: str, parameters: tuple[object, ...]) -> None:
            executions.append((statement, parameters))

        def fetchone(self) -> tuple[str, str]:
            return ("user-123", "sandbox-1")

    class RecordingConnection:
        def __init__(self) -> None:
            self.commits = 0
            self.cursor_instance = RecordingCursor()

        def __enter__(self) -> "RecordingConnection":
            return self

        def __exit__(self, *args: object) -> None:
            return None

        def cursor(self) -> RecordingCursor:
            return self.cursor_instance

        def commit(self) -> None:
            self.commits += 1

    connection = RecordingConnection()
    store = MysqlSessionStore(settings())
    monkeypatch.setattr(store, "_connect", lambda: connection)
    token_urlsafe_calls: list[int | None] = []

    def profile(frame: FrameType, event: str, arg: object) -> None:
        del arg
        if event == "call" and getattr(frame, "f_code", None) is secrets.token_urlsafe.__code__:
            token_urlsafe_calls.append(frame.f_locals["nbytes"])

    previous_profile = sys.getprofile()
    sys.setprofile(profile)
    try:
        session_id = store.create("user-123", "sandbox-1")
    finally:
        sys.setprofile(previous_profile)

    assert token_urlsafe_calls == [32]
    assert len(session_id) == 43
    assert set(session_id) <= set(
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    )
    assert executions[0][1] == (session_id, "user-123", "sandbox-1")
    assert executions[1][1][1:] == (session_id, "user-123")
    assert connection.commits == 1
    assert "session_id = secrets.token_urlsafe(32)" in inspect.getsource(MysqlSessionStore.create)

    store.verify_owner(session_id, "user-123", "sandbox-1")
    assert executions[2][1] == (session_id,)


@pytest.mark.parametrize(
    "session_id",
    [
        "-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    ],
)
def test_session_endpoint_returns_exact_canonical_edge_session(session_id: str) -> None:
    assert len(session_id) == 43
    private, public_jwk = key_fixture("current-key")
    validator = DirectJwtValidator(settings(), CountingJwksSource([public_jwk]))
    sessions = FixedSessionStore(session_id)
    client = TestClient(create_app(settings(), validator=validator, sessions=sessions))

    response = client.post(
        "/api/sessions",
        headers={"Authorization": f"Bearer {direct_token(private, 'current-key')}"},
        json={},
    )

    assert response.status_code == 201
    assert response.json() == {"sessionId": session_id}
    sessions.verify_owner(session_id, "user-123")


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

    monkeypatch.setattr(http_client, "post", exchange_response)

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


@pytest.mark.parametrize(
    "session_id",
    [
        "-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    ],
)
def test_obo_client_preserves_exact_canonical_edge_session(
    session_id: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    assert len(session_id) == 43
    sessions = FixedSessionStore(session_id)
    sessions.create("user-123", "sandbox-1")
    client = OboClient(evaluation_settings(), sessions)
    requests: list[dict[str, Any]] = []

    def exchange_response(*args: Any, **kwargs: Any) -> httpx.Response:
        requests.append(kwargs)
        return httpx.Response(200, json={"accessToken": "signed-eval-obo"})

    monkeypatch.setattr(http_client, "post", exchange_response)

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
    assert requests[0]["json"]["sessionId"] == session_id


@pytest.mark.parametrize("status", [401, 403])
def test_obo_client_preserves_identity_rejection_status(
    status: int, monkeypatch: pytest.MonkeyPatch
) -> None:
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    client = OboClient(settings(), sessions)
    monkeypatch.setattr(
        http_client,
        "post",
        lambda *args, **kwargs: httpx.Response(status),  # noqa: ARG005
    )

    with pytest.raises(HTTPException) as rejected:
        client.exchange("direct-token", "user-123", session_id, "catalog:read")

    assert rejected.value.status_code == status


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

    monkeypatch.setattr(http_client, "post", exchange_response)

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
        http_client,
        "post",
        lambda *args, **kwargs: httpx.Response(200, content=b"{"),
    )

    with pytest.raises(HTTPException) as malformed:
        client.exchange("direct-token", "user-123", session_id, "catalog:read")

    assert malformed.value.status_code == 502
    assert malformed.value.detail == "Identity exchange rejected"


def test_mysql_history_snapshot_is_recent_owner_bound_and_sql_limited() -> None:
    class HistoryCursor:
        def __init__(self) -> None:
            self.query = ""
            self.parameters: tuple[object, ...] = ()

        def execute(self, query: str, parameters: tuple[object, ...]) -> None:
            self.query = query
            self.parameters = parameters

        @staticmethod
        def fetchall() -> tuple[tuple[object, ...], ...]:
            return tuple(
                (
                    f"00000000-0000-0000-0000-{sequence:012d}",
                    sequence,
                    f"user-{sequence}",
                    f"assistant-{sequence}",
                )
                for sequence in range(20, 3, -1)
            )

    cursor = HistoryCursor()

    history = MysqlConversationStore._load_recent_history(  # noqa: SLF001
        cursor,  # type: ignore[arg-type]
        conversation_id="conversation-1",
        session_id="session-1",
        subject="user-1",
        before_turn_sequence=21,
    )

    assert [turn.turn_sequence for turn in history.turns] == list(range(5, 21))
    assert history.older_turns_available is True
    assert "session_id = %s" in cursor.query
    assert "user_subject = %s" in cursor.query
    assert "turn_sequence < %s" in cursor.query
    assert "state = 'COMPLETED'" in cursor.query
    assert "ORDER BY turn_sequence DESC LIMIT %s" in cursor.query
    assert cursor.parameters == ("conversation-1", "session-1", "user-1", 21, 17)


def test_mysql_history_snapshot_rejects_a_malformed_turn_id_as_integrity_failure() -> None:
    class HistoryCursor:
        @staticmethod
        def execute(query: str, parameters: tuple[object, ...]) -> None:
            del query, parameters

        @staticmethod
        def fetchall() -> tuple[tuple[object, ...], ...]:
            return (("00000000-0000-0000-0000-00000000000g", 1, "user", "assistant"),)

    with pytest.raises(
        ConversationIntegrityError,
        match="Durable conversation history is inconsistent",
    ):
        MysqlConversationStore._load_recent_history(  # noqa: SLF001
            HistoryCursor(),  # type: ignore[arg-type]
            conversation_id="conversation-1",
            session_id="session-1",
            subject="user-1",
            before_turn_sequence=2,
        )


def test_cb122_decline_lock_compares_the_complete_reference_after_target_version(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    expires_at = datetime.now(UTC) + timedelta(minutes=5)
    pending = PendingActionReference(
        pending_action_id="00000000-0000-0000-0000-000000000101",
        source_turn_id="00000000-0000-0000-0000-000000000102",
        source_trace_id="00000000-0000-0000-0000-000000000103",
        conversation_id="00000000-0000-0000-0000-000000000104",
        session_id="session-1",
        user_subject="user-123",
        sandbox_id="sandbox-1",
        action_type="REFUND_REQUEST",
        argument_commitment="a" * 64,
        order_id="00000000-0000-0000-0000-000000000105",
        target_version=7,
        amount_minor=500,
        currency="AUD",
        expires_at=expires_at,
    )

    class PendingCursor:
        def __init__(self) -> None:
            self.execute_calls = 0

        def execute(self, _sql: str, _arguments: tuple[object, ...]) -> None:
            self.execute_calls += 1

        def fetchone(self) -> tuple[object, ...]:
            return (
                pending.source_turn_id,
                pending.source_trace_id,
                pending.conversation_id,
                pending.session_id,
                pending.user_subject,
                pending.sandbox_id,
                pending.action_type,
                pending.argument_commitment,
                pending.order_id,
                pending.target_version,
                pending.amount_minor,
                pending.currency,
                "PENDING",
                expires_at,
                None,
                None,
                None,
            )

        @staticmethod
        def fetchall() -> tuple[()]:
            return ()

    def accept_source_turn(
        cls: type[MysqlConversationStore],
        rows: object,
        *,
        pending: PendingActionReference,
        persisted_expiry: object,
    ) -> None:
        del cls, rows, pending, persisted_expiry

    monkeypatch.setattr(
        MysqlConversationStore,
        "_validate_pending_source_turn",
        classmethod(accept_source_turn),
    )
    cursor: Any = PendingCursor()

    assert (
        MysqlConversationStore._lock_matching_pending(
            cursor,
            pending,
            require_expired=False,
        )
        == "PENDING"
    )
    assert cursor.execute_calls == 2


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
            conversations=object(),  # type: ignore[arg-type]
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


def test_feedback_liveness_unavailable_has_request_local_reason_without_public_leak(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    private, public_jwk = key_fixture("current-key")
    resolved = evaluation_settings()
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123", "sandbox-1")

    def unavailable(*args: object, **kwargs: object) -> httpx.Response:
        del args, kwargs
        raise httpx.ConnectError("private network detail")

    monkeypatch.setattr(http_client, "post", unavailable)
    client = TestClient(
        create_app(
            resolved,
            validator=DirectJwtValidator(resolved, CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=object(),  # type: ignore[arg-type]
            feedback=MemoryFeedbackStore(sessions, {}),
            liveness=HttpSandboxLiveness("https://commerce.test"),
        )
    )
    token = direct_token(
        private,
        "current-key",
        token_type="eval_direct_user",
        extra={"sandbox": "sandbox-1"},
    )

    with caplog.at_level(logging.WARNING):
        response = client.post(
            "/api/feedback",
            headers={
                "Authorization": f"Bearer {token}",
                "X-Eval-Sandbox-Id": "sandbox-1",
                "X-Session-Id": session_id,
                "Idempotency-Key": "liveness-unavailable",
            },
            json={"traceId": "00000000-0000-0000-0000-000000000821", "rating": "POSITIVE"},
        )

    assert response.status_code == 503
    assert response.json() == {"detail": "Service unavailable"}
    assert "ACTION_SANDBOX_LIVENESS_UNAVAILABLE" in caplog.text
    assert "private network detail" not in response.text
    assert "reason" not in response.json()


@pytest.mark.parametrize("evaluation", [False, True])
def test_evidence_factory_has_no_model_execution_or_chat_routes(evaluation: bool) -> None:
    private, public_jwk = key_fixture("current-key")
    resolved = evaluation_settings() if evaluation else settings()
    sessions = MemorySessionStore()
    app = create_app(
        resolved,
        validator=DirectJwtValidator(resolved, CountingJwksSource([public_jwk])),
        sessions=sessions,
        feedback=MemoryFeedbackStore(sessions, {}),
        evidence=MemoryEvidenceStore(),
        liveness=MemoryLiveness(),
    )
    with TestClient(app) as client:
        token = direct_token(private, "current-key")
        headers = {"Authorization": f"Bearer {token}"}
        assert client.post("/api/sessions", headers=headers, json={}).status_code == 201
        for path in ("/api/chat", "/api/chat/stream"):
            assert client.post(path, headers=headers, json={"message": "refund"}).status_code == 404
        assert not hasattr(app.state, "agent")
        assert not hasattr(app.state, "sse_filter")
        assert isinstance(app.state.conversations, MysqlConversationStore)
    assert importlib.util.find_spec("citybuddy_agent.agent_control") is None
    assert importlib.util.find_spec("citybuddy_agent.sse") is None


def test_evaluation_sessions_and_feedback_keep_liveness_and_exact_sandbox() -> None:
    private, public_jwk = key_fixture("current-key")
    resolved = evaluation_settings()
    sessions = MemorySessionStore()
    traces: dict[str, tuple[str, str]] = {}
    feedback = MemoryFeedbackStore(sessions, traces)
    liveness = MemoryLiveness()
    with TestClient(
        create_app(
            resolved,
            validator=DirectJwtValidator(resolved, CountingJwksSource([public_jwk])),
            sessions=sessions,
            feedback=feedback,
            liveness=liveness,
        )
    ) as client:
        token = direct_token(
            private, "current-key", token_type="eval_direct_user", extra={"sandbox": "sandbox-1"}
        )
        headers = {"Authorization": f"Bearer {token}", "X-Eval-Sandbox-Id": "sandbox-1"}
        created = client.post("/api/sessions", headers=headers, json={})
        assert created.status_code == 201
        session_id = created.json()["sessionId"]
        assert sessions.sandboxes[session_id] == "sandbox-1"
        assert (
            client.post(
                "/api/sessions", headers={**headers, "X-Eval-Sandbox-Id": "sandbox-2"}, json={}
            ).status_code
            == 401
        )
        trace_id = "00000000-0000-0000-0000-000000000821"
        traces[trace_id] = (session_id, "user-123")
        body = {"traceId": trace_id, "rating": "POSITIVE"}
        headers.update({"X-Session-Id": session_id, "Idempotency-Key": "feedback-one"})
        wrong = client.post(
            "/api/feedback", headers={**headers, "X-Eval-Sandbox-Id": "sandbox-2"}, json=body
        )
        missing = client.post(
            "/api/feedback",
            headers={k: v for k, v in headers.items() if k != "X-Eval-Sandbox-Id"},
            json=body,
        )
        assert wrong.status_code == missing.status_code == 401
        assert feedback.records == {}
        assert client.post("/api/feedback", headers=headers, json=body).status_code == 201
        assert len(liveness.calls) == 2
        liveness.active = False
        blocked = client.post(
            "/api/feedback", headers={**headers, "Idempotency-Key": "after-expiry"}, json=body
        )
        assert blocked.status_code == 403
        assert len(feedback.records) == 1
        assert client.post("/api/sessions", headers=headers, json={}).status_code == 403
        assert sessions.counter == 1


def test_feedback_checks_permission_before_history_and_redacts_database_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    import pymysql

    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    trace_id = "00000000-0000-0000-0000-000000000821"
    feedback = MemoryFeedbackStore(sessions, {trace_id: (session_id, "user-123")})
    calls = 0

    def unavailable(**kwargs: object) -> FeedbackRecord:
        nonlocal calls
        calls += 1
        raise pymysql.OperationalError("private database and credential detail")

    monkeypatch.setattr(feedback, "append", unavailable)
    with TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            feedback=feedback,
        )
    ) as client:
        headers = {"X-Session-Id": session_id, "Idempotency-Key": "feedback-one"}
        body = {"traceId": trace_id, "rating": "POSITIVE"}
        session_only = direct_token(private, "current-key", permissions=["support:session:create"])
        denied = client.post(
            "/api/feedback",
            headers={**headers, "Authorization": f"Bearer {session_only}"},
            json=body,
        )
        assert denied.status_code == 403
        assert calls == 0
        token = direct_token(private, "current-key")
        unavailable_response = client.post(
            "/api/feedback", headers={**headers, "Authorization": f"Bearer {token}"}, json=body
        )
        assert unavailable_response.status_code == 503
        assert unavailable_response.json() == {"detail": "Service unavailable"}
        assert calls == 1
