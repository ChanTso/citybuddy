import base64
import inspect
import json
import logging
import secrets
import sys
import time
from collections.abc import Mapping
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from types import FrameType
from typing import Any, Literal, cast

import httpx
import jwt
import pymysql
import pytest
from citybuddy_agent import http_client
from citybuddy_agent.actions import (
    ActionReceiptResponse,
    PendingActionPayload,
    PendingActionReference,
)
from citybuddy_agent.agent_control import (
    EMPTY_CONVERSATION_HISTORY,
    AgentEvent,
    AgentRunner,
    AgentRunResult,
    AttemptBudget,
    ConversationHistory,
    ConversationTurn,
    ToolBoundaryFailure,
)
from citybuddy_agent.application import (
    ACTION_REQUEST_FAILURE_REASONS,
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
    ActionArbitrationConflictError,
    ConversationIntegrityError,
    ConversationOwnershipError,
    ConversationResult,
    ConversationStore,
    CorrelationConflictError,
    MysqlConversationStore,
    TurnStart,
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
from citybuddy_agent.metrics import MetricsRuntime, PrometheusCityBuddyMetrics
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


class FixedSessionStore(MemorySessionStore):
    def __init__(self, session_id: str) -> None:
        super().__init__()
        self.session_id = session_id

    def create(self, subject: str, sandbox_id: str | None = None) -> str:
        self.owners[self.session_id] = subject
        self.sandboxes[self.session_id] = sandbox_id
        return self.session_id


class MemoryConversationStore(ConversationStore):
    def __init__(self, sessions: MemorySessionStore) -> None:
        self.sessions = sessions
        self.results: dict[tuple[str, str], tuple[str, ConversationResult]] = {}
        self.pending: dict[tuple[str, str], tuple[str, TurnStart]] = {}
        self.failures: list[tuple[str, str]] = []
        self.action_pending: PendingActionReference | None = None
        self.action_state = "PENDING"
        self.claims = 0
        self.confirmed_receipts: list[ActionReceiptResponse] = []
        self.calls = 0
        self.turns: dict[str, list[ConversationTurn]] = {}

    def replay_turn(
        self,
        *,
        session_id: str,
        subject: str,
        sandbox_id: str | None,
        correlation_key: str,
        message: str,
    ) -> ConversationResult | None:
        if (
            self.sessions.owners.get(session_id) != subject
            or self.sessions.sandboxes.get(session_id) != sandbox_id
        ):
            raise ConversationOwnershipError
        existing = self.results.get((session_id, correlation_key))
        if existing is None:
            return None
        if existing[0] != message:
            raise CorrelationConflictError
        return existing[1]

    def current_pending_action(
        self, *, session_id: str, subject: str, sandbox_id: str | None
    ) -> tuple[PendingActionReference, str] | None:
        if (
            self.sessions.owners.get(session_id) != subject
            or self.sessions.sandboxes.get(session_id) != sandbox_id
        ):
            raise ConversationOwnershipError
        if self.action_pending is None:
            return None
        return self.action_pending, self.action_state

    def claim_action_confirmation(self, *, pending: PendingActionReference) -> None:
        if self.action_pending is None or self.action_state != "PENDING":
            raise ActionArbitrationConflictError
        self.action_state = "CONFIRMING"
        self.claims += 1

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
            history=ConversationHistory(tuple(self.turns.get(session_id, ()))),
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
        pending_action: PendingActionPayload | None = None,
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
        session_turns = self.turns.setdefault(key[0], [])
        session_turns.append(
            ConversationTurn(
                turn_id=start.turn_id,
                turn_sequence=len(session_turns) + 1,
                user_text=pending[0],
                assistant_text=response_text,
            )
        )
        if pending_action is not None:
            self.action_pending = PendingActionReference(
                pending_action_id=pending_action.pending_action_id,
                source_turn_id=start.turn_id,
                source_trace_id=start.trace_id,
                conversation_id=start.conversation_id,
                session_id=key[0],
                user_subject=self.sessions.owners[key[0]],
                sandbox_id=self.sessions.sandboxes[key[0]],
                action_type=pending_action.action_type,
                argument_commitment=pending_action.argument_commitment,
                order_id=pending_action.order_id,
                target_version=pending_action.target_version,
                amount_minor=pending_action.amount_minor,
                currency=pending_action.currency,
                expires_at=pending_action.expires_at,
            )
        del self.pending[key]
        return result

    def complete_action_decline(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        response_text: str,
    ) -> ConversationResult:
        self.action_pending = None
        return self.complete_turn(
            start=start,
            response_text=response_text,
            outcome="action_declined",
            events=(),
        )

    def complete_action_expired(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        response_text: str,
    ) -> ConversationResult:
        self.action_pending = None
        return self.complete_turn(
            start=start,
            response_text=response_text,
            outcome="action_expired",
            events=(),
        )

    def complete_action_confirmed(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        receipt: ActionReceiptResponse,
        response_text: str,
    ) -> ConversationResult:
        if self.action_state != "CONFIRMING":
            raise ActionArbitrationConflictError
        self.action_pending = None
        self.action_state = "CONFIRMED"
        self.confirmed_receipts.append(receipt)
        result = self.complete_turn(
            start=start,
            response_text=response_text,
            outcome="action_completed",
            events=(),
        )
        return replace(result, receipt_id=receipt.receipt_id)

    def fail_turn(self, *, start: TurnStart, failure_code: str) -> None:
        self.failures.append((start.turn_id, failure_code))
        for key, pending in tuple(self.pending.items()):
            if pending[1].turn_id == start.turn_id:
                del self.pending[key]


class MemoryAgent(AgentRunner):
    def __init__(self, *, request_reasons: tuple[str, ...] = ()) -> None:
        self.calls = 0
        self.sandbox_ids: list[str | None] = []
        self.histories: list[ConversationHistory] = []
        self.request_reasons = request_reasons

    def run(
        self,
        *,
        message: str,
        direct_token: str,
        subject: str,
        session_id: str,
        trace_id: str,
        turn_id: str,
        history: ConversationHistory = EMPTY_CONVERSATION_HISTORY,
        sandbox_id: str | None = None,
    ) -> AgentRunResult:
        self.calls += 1
        self.sandbox_ids.append(sandbox_id)
        self.histories.append(history)
        del message, direct_token, subject, session_id, trace_id, turn_id
        return AgentRunResult(
            "Bounded support response.",
            "completed",
            (AgentEvent("AGENT_OUTCOME", {"outcome": "completed"}),),
            request_reasons=self.request_reasons,
        )


class PreparedActionAgent(AgentRunner):
    def __init__(self, *, expires_at: datetime | None = None) -> None:
        self.calls = 0
        self.pending = PendingActionPayload.model_validate(
            {
                "pendingActionId": "00000000-0000-0000-0000-000000000121",
                "actionType": "REFUND_REQUEST",
                "userSubject": "user-1",
                "supportSessionId": "session-1",
                "traceId": "00000000-0000-0000-0000-000000000123",
                "turnId": "00000000-0000-0000-0000-000000000122",
                "requiredScope": "refund:create",
                "sandboxId": None,
                "orderId": "00000000-0000-0000-0000-000000000040",
                "targetVersion": 1,
                "amountMinor": 400,
                "currency": "CNY",
                "state": "PREPARED",
                "expiresAt": (expires_at or datetime(2030, 7, 29, 12, 0, 0, 123456, tzinfo=UTC))
                .isoformat(timespec="microseconds")
                .replace("+00:00", "Z"),
                "replayed": False,
            }
        )

    def run(
        self,
        *,
        message: str,
        direct_token: str,
        subject: str,
        session_id: str,
        trace_id: str,
        turn_id: str,
        history: ConversationHistory = EMPTY_CONVERSATION_HISTORY,
        sandbox_id: str | None = None,
    ) -> AgentRunResult:
        del message, direct_token, subject, session_id, trace_id, turn_id, history, sandbox_id
        self.calls += 1
        return AgentRunResult(
            "A refund request is ready for your explicit decision.",
            "action_pending",
            (
                AgentEvent(
                    "ACTION_PREPARED",
                    {
                        "pendingActionId": self.pending.pending_action_id,
                        "actionType": self.pending.action_type,
                        "argumentCommitment": self.pending.argument_commitment,
                        "targetVersion": self.pending.target_version,
                        "expiresAt": self.pending.expires_at.isoformat(
                            timespec="microseconds"
                        ).replace("+00:00", "Z"),
                    },
                ),
                AgentEvent("AGENT_OUTCOME", {"outcome": "action_pending"}),
            ),
            pending_action=self.pending,
        )


class BoundaryFailingAgent(AgentRunner):
    def __init__(self, failure: ToolBoundaryFailure) -> None:
        self.failure = failure

    def run(
        self,
        *,
        message: str,
        direct_token: str,
        subject: str,
        session_id: str,
        trace_id: str,
        turn_id: str,
        history: ConversationHistory = EMPTY_CONVERSATION_HISTORY,
        sandbox_id: str | None = None,
    ) -> AgentRunResult:
        del message, direct_token, subject, session_id, trace_id, turn_id, history, sandbox_id
        raise self.failure


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


def test_action_request_failure_producer_inventory_is_closed() -> None:
    assert ACTION_REQUEST_FAILURE_REASONS == {
        "AGENT_REQUEST_INVALID",
        "AGENT_AUTHENTICATION_REJECTED",
        "AGENT_AUTHORIZATION_REJECTED",
        "ACTION_SESSION_OWNERSHIP_REJECTED",
        "ACTION_IDEMPOTENCY_CONFLICT",
        "ACTION_TURN_IN_PROGRESS",
        "ACTION_TURN_PREVIOUSLY_FAILED",
        "ACTION_DURABLE_TRUTH_INCONSISTENT",
        "ACTION_EVALUATION_DURABLE_TRUTH_INCONSISTENT",
        "ACTION_LOCAL_ARBITRATION_CONFLICT",
        "ACTION_STREAM_PROJECTION_INVALID",
        "ACTION_STREAM_UNEXPECTED_FAILURE",
        "ACTION_PREPARATION_IDENTITY_UNAUTHENTICATED",
        "ACTION_PREPARATION_IDENTITY_FORBIDDEN",
        "ACTION_PREPARATION_IDENTITY_UNAVAILABLE",
        "ACTION_PREPARATION_COMMERCE_VALIDATION_REJECTED",
        "ACTION_PREPARATION_COMMERCE_UNAUTHENTICATED",
        "ACTION_PREPARATION_COMMERCE_FORBIDDEN",
        "ACTION_PREPARATION_TARGET_NOT_FOUND",
        "ACTION_PREPARATION_INTENT_CONFLICT",
        "ACTION_PREPARATION_COMMERCE_UNAVAILABLE",
        "ACTION_PREPARATION_COMMERCE_TIMEOUT",
        "ACTION_PREPARATION_COMMERCE_INDETERMINATE",
        "ACTION_PREPARATION_RESPONSE_INVALID",
        "ACTION_PREPARATION_DURABLE_TRUTH_INCONSISTENT",
        "ACTION_SANDBOX_LIVENESS_UNAVAILABLE",
        "ACTION_SANDBOX_LIVENESS_REJECTED",
        "ACTION_SESSION_PERSISTENCE_UNAVAILABLE",
        "ACTION_REPLAY_PERSISTENCE_UNAVAILABLE",
        "ACTION_REFERENCE_PERSISTENCE_UNAVAILABLE",
        "ACTION_TURN_RESERVATION_PERSISTENCE_UNAVAILABLE",
        "ACTION_EXPIRY_PERSISTENCE_UNAVAILABLE",
        "ACTION_DECLINE_PERSISTENCE_UNAVAILABLE",
        "ACTION_CLARIFICATION_PERSISTENCE_UNAVAILABLE",
        "AGENT_TURN_COMPLETION_PERSISTENCE_UNAVAILABLE",
        "ACTION_CONFIRMATION_UNAVAILABLE",
        "ACTION_CONFIRMATION_IDENTITY_UNAUTHENTICATED",
        "ACTION_CONFIRMATION_IDENTITY_FORBIDDEN",
        "ACTION_CONFIRMATION_IDENTITY_UNAVAILABLE",
        "ACTION_CONFIRMATION_COMMERCE_VALIDATION_REJECTED",
        "ACTION_CONFIRMATION_COMMERCE_UNAUTHENTICATED",
        "ACTION_CONFIRMATION_COMMERCE_FORBIDDEN",
        "ACTION_CONFIRMATION_TARGET_NOT_FOUND",
        "ACTION_CONFIRMATION_INTENT_CONFLICT",
        "ACTION_CONFIRMATION_COMMERCE_UNAVAILABLE",
        "ACTION_CONFIRMATION_COMMERCE_TIMEOUT",
        "ACTION_CONFIRMATION_COMMERCE_INDETERMINATE",
        "ACTION_CONFIRMATION_RESPONSE_INVALID",
        "ACTION_CONFIRMATION_PERSISTENCE_UNAVAILABLE",
    }


def test_action_tool_denial_producer_is_logged_request_locally_without_public_leakage(
    caplog: pytest.LogCaptureFixture,
) -> None:
    private, public_jwk = key_fixture("current-key")
    resolved = settings()
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    reason = "ACTION_PREPARATION_COMMERCE_FORBIDDEN"
    client = TestClient(
        create_app(
            resolved,
            validator=DirectJwtValidator(resolved, CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=MemoryConversationStore(sessions),
            agent=MemoryAgent(request_reasons=(reason,)),
            feedback=MemoryFeedbackStore(sessions, {}),
        )
    )

    with caplog.at_level(logging.WARNING):
        response = client.post(
            "/api/chat",
            headers={
                "Authorization": f"Bearer {direct_token(private, 'current-key')}",
                "X-Session-Id": session_id,
                "Idempotency-Key": "prepare-forbidden",
            },
            json={"message": "prepare a refund"},
        )

    assert response.status_code == 200
    assert response.json()["reply"] == "Bounded support response."
    assert f"reason_code={reason}" in caplog.text
    assert reason not in response.text


def test_action_prepare_binding_failure_is_409_with_no_pending_closure_or_reason_leak(
    caplog: pytest.LogCaptureFixture,
) -> None:
    private, public_jwk = key_fixture("current-key")
    resolved = settings()
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    reason = "ACTION_PREPARATION_DURABLE_TRUTH_INCONSISTENT"
    client = TestClient(
        create_app(
            resolved,
            validator=DirectJwtValidator(resolved, CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=conversations,
            agent=BoundaryFailingAgent(
                ToolBoundaryFailure(
                    status_code=409,
                    reason=reason,
                    detail="Action preparation conflict",
                )
            ),
            feedback=MemoryFeedbackStore(sessions, {}),
        )
    )

    with caplog.at_level(logging.WARNING):
        response = client.post(
            "/api/chat",
            headers={
                "Authorization": f"Bearer {direct_token(private, 'current-key')}",
                "X-Session-Id": session_id,
                "Idempotency-Key": "prepare-damaged",
            },
            json={"message": "prepare a refund"},
        )

    assert response.status_code == 409
    assert response.json() == {"detail": "Action preparation conflict"}
    assert f"reason_code={reason}" in caplog.text
    assert reason not in response.text
    assert conversations.action_pending is None
    assert len(conversations.failures) == 1


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
        model_proxy_url="http://127.0.0.1:8000",
        jwks_url="http://citybuddy-bench-auth:8080/jwks",
        auth_exchange_url="http://citybuddy-bench-auth:8080/exchange",
        commerce_tools_url="http://citybuddy-bench-commerce:8080/tools",
        commerce_liveness_url="http://citybuddy-bench-commerce:8080/liveness",
        elasticsearch_url="http://citybuddy-bench-elasticsearch:9200",
        trace_export_url="",
    )

    with TestClient(create_app(resolved, trace_sink=Sink())):
        pass

    assert captured == {
        "layout": "per-authority",
        "urls": (
            "http://127.0.0.1:8000",
            "http://citybuddy-bench-auth:8080/jwks",
            "http://citybuddy-bench-auth:8080/exchange",
            "http://citybuddy-bench-commerce:8080/tools",
            "http://citybuddy-bench-commerce:8080/liveness",
            "http://citybuddy-bench-elasticsearch:9200",
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
    first = create_app(AgentSettings(model_proxy_url=first_url), trace_sink=Sink(first_url))
    second = create_app(AgentSettings(model_proxy_url=second_url), trace_sink=Sink(second_url))

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
        "receiptId",
        "citations",
    }
    assert first.json()["citations"] == []
    assert first.json()["outcome"] == "completed"
    assert "order" not in first.json()["reply"].lower()
    assert len(conversations.results) == 1
    assert agent.calls == 1


def test_chat_passes_only_completed_turns_from_the_owned_session_to_the_agent() -> None:
    private, public_jwk = key_fixture("current-key")
    validator = DirectJwtValidator(settings(), CountingJwksSource([public_jwk]))
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    other_session_id = sessions.create("user-123")
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
    authorization = f"Bearer {direct_token(private, 'current-key')}"

    first = client.post(
        "/api/chat",
        headers={
            "Authorization": authorization,
            "X-Session-Id": session_id,
            "Idempotency-Key": "context-turn-1",
        },
        json={"message": "First session message"},
    )
    second = client.post(
        "/api/chat",
        headers={
            "Authorization": authorization,
            "X-Session-Id": session_id,
            "Idempotency-Key": "context-turn-2",
        },
        json={"message": "Second session message"},
    )
    other = client.post(
        "/api/chat",
        headers={
            "Authorization": authorization,
            "X-Session-Id": other_session_id,
            "Idempotency-Key": "context-other-session",
        },
        json={"message": "Other session message"},
    )

    assert first.status_code == second.status_code == other.status_code == 200
    assert agent.histories[0] == ConversationHistory()
    assert agent.histories[1].turns == (
        ConversationTurn(
            turn_id=first.json()["turnId"],
            turn_sequence=1,
            user_text="First session message",
            assistant_text="Bounded support response.",
        ),
    )
    assert agent.histories[2] == ConversationHistory()


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


def test_pending_decline_is_local_and_confirmation_without_a_confirmer_fails_closed(
    caplog: pytest.LogCaptureFixture,
) -> None:
    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    agent = PreparedActionAgent()
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
    }

    prepared = client.post(
        "/api/chat",
        headers={**headers, "Idempotency-Key": "prepare"},
        json={"message": "prepare refund"},
    )
    calls_after_prepare = conversations.calls
    with caplog.at_level(logging.WARNING):
        confirmation = client.post(
            "/api/chat",
            headers={**headers, "Idempotency-Key": "confirm"},
            json={"message": "confirm"},
        )

    assert prepared.status_code == 200
    assert prepared.json()["outcome"] == "action_pending"
    # No confirmation boundary is configured for this app, so a confirmation may not proceed and
    # may not say why. The reference must survive: nothing has been committed anywhere.
    assert confirmation.status_code == 503
    assert confirmation.json() == {"detail": "Action confirmation unavailable"}
    # The confirmation turn is reserved before the boundary is reached, so the check is not that
    # nothing happened but that nothing was left half-done: the turn is failed, not processing,
    # and the reference is still pending and still confirmable.
    assert conversations.calls == calls_after_prepare + 1
    assert [code for _, code in conversations.failures] == ["agent_execution_failed"]
    assert conversations.action_pending is not None
    assert conversations.confirmed_receipts == []
    assert agent.calls == 1
    assert "reason_code=ACTION_CONFIRMATION_UNAVAILABLE" in caplog.text
    assert "ACTION_CONFIRMATION_UNAVAILABLE" not in confirmation.text

    declined = client.post(
        "/api/chat/stream",
        headers={**headers, "Idempotency-Key": "decline"},
        json={"message": "decline"},
    )
    assert declined.status_code == 200
    assert declined.text.count("event: token\n") == 1
    assert declined.text.count("event: done\n") == 1
    assert '"outcome":"action_declined"' in declined.text
    assert "event: action_receipt" not in declined.text
    assert conversations.action_pending is None
    assert agent.calls == 1


class RecordingConfirmer:
    """Stands in for the commerce confirmation boundary and records what it was asked to commit."""

    def __init__(self, *, receipt_id: str = "00000000-0000-0000-0000-0000000001a1") -> None:
        self.calls: list[PendingActionReference] = []
        self.receipt_id = receipt_id

    def confirm_action(
        self,
        *,
        pending: PendingActionReference,
        direct_token: str,
        subject: str,
        session_id: str,
        sandbox_id: str | None,
        budget: AttemptBudget,
        events: list[AgentEvent],
    ) -> ActionReceiptResponse:
        del direct_token, subject, session_id, sandbox_id, budget, events
        self.calls.append(pending)
        return ActionReceiptResponse.model_validate(
            {
                "receiptId": self.receipt_id,
                "pendingActionId": pending.pending_action_id,
                "actionType": "REFUND_REQUEST",
                "status": "REQUESTED",
                "orderId": pending.order_id,
                "refundId": "00000000-0000-0000-0000-0000000001b1",
                "resourceVersion": 2,
                "amountMinor": pending.amount_minor,
                "currency": pending.currency,
                "committedAt": "2030-07-29T12:00:00.123456Z",
                "replayed": False,
            }
        )


def test_an_exact_confirmation_commits_the_refund_and_returns_its_receipt() -> None:
    """The flagship flow end to end: prepare, confirm, and a receipt the client can render."""
    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    confirmer = RecordingConfirmer()
    client = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=conversations,
            agent=PreparedActionAgent(),
            confirmer=confirmer,
        )
    )
    headers = {
        "Authorization": f"Bearer {direct_token(private, 'current-key')}",
        "X-Session-Id": session_id,
    }

    prepared = client.post(
        "/api/chat",
        headers={**headers, "Idempotency-Key": "prepare"},
        json={"message": "prepare refund"},
    )
    pending_before = conversations.action_pending
    confirmed = client.post(
        "/api/chat",
        headers={**headers, "Idempotency-Key": "confirm"},
        json={"message": "confirm"},
    )

    assert prepared.json()["outcome"] == "action_pending"
    assert confirmed.status_code == 200
    assert confirmed.json()["outcome"] == "action_completed"
    assert conversations.action_pending is None
    assert [receipt.receipt_id for receipt in conversations.confirmed_receipts] == [
        confirmer.receipt_id
    ]
    # Commerce binds a confirmation to the turn that prepared the action, so the confirmation must
    # carry the stored source correlation and not the correlation of the turn confirming it.
    assert pending_before is not None
    assert [reference.pending_action_id for reference in confirmer.calls] == [
        pending_before.pending_action_id
    ]
    assert confirmer.calls[0].source_turn_id == pending_before.source_turn_id


class FailingConfirmer:
    """Commits at commerce and then loses the response, the case the claim state exists for."""

    def __init__(self) -> None:
        self.calls = 0

    def confirm_action(
        self,
        *,
        pending: PendingActionReference,
        direct_token: str,
        subject: str,
        session_id: str,
        sandbox_id: str | None,
        budget: AttemptBudget,
        events: list[AgentEvent],
    ) -> ActionReceiptResponse:
        del pending, direct_token, subject, session_id, sandbox_id, budget, events
        self.calls += 1
        raise ToolBoundaryFailure(
            status_code=503,
            reason="ACTION_CONFIRMATION_COMMERCE_TIMEOUT",
            detail="Action confirmation unavailable",
        )


def test_a_claimed_action_cannot_be_declined_or_expired_out_from_under_commerce() -> None:
    """Commerce may already hold the refund, so nothing may record that it did not happen."""
    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    confirmer = FailingConfirmer()
    client = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=conversations,
            agent=PreparedActionAgent(),
            confirmer=confirmer,
        )
    )
    headers = {
        "Authorization": f"Bearer {direct_token(private, 'current-key')}",
        "X-Session-Id": session_id,
    }
    client.post(
        "/api/chat",
        headers={**headers, "Idempotency-Key": "prepare"},
        json={"message": "prepare refund"},
    )

    # The confirmation claims the reference, then the commerce response is lost.
    lost = client.post(
        "/api/chat",
        headers={**headers, "Idempotency-Key": "confirm"},
        json={"message": "confirm"},
    )
    assert lost.status_code == 503
    assert conversations.claims == 1
    assert conversations.action_state == "CONFIRMING"

    # A decline now must not resolve it, because the refund may exist at commerce.
    declined = client.post(
        "/api/chat",
        headers={**headers, "Idempotency-Key": "decline"},
        json={"message": "decline"},
    )
    assert declined.json()["outcome"] == "action_clarification"
    assert conversations.action_state == "CONFIRMING"
    assert conversations.action_pending is not None

    # Retrying the confirmation reuses the existing claim rather than taking a second one.
    client.post(
        "/api/chat",
        headers={**headers, "Idempotency-Key": "confirm-again"},
        json={"message": "confirm"},
    )
    assert conversations.claims == 1
    assert confirmer.calls == 2


def test_an_expired_but_claimed_action_is_not_recorded_as_expired() -> None:
    """The expiry branch runs first, so it is the likeliest way to contradict a committed refund."""
    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    client = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=conversations,
            agent=PreparedActionAgent(expires_at=datetime(2020, 1, 1, tzinfo=UTC)),
            confirmer=FailingConfirmer(),
        )
    )
    headers = {
        "Authorization": f"Bearer {direct_token(private, 'current-key')}",
        "X-Session-Id": session_id,
    }
    client.post(
        "/api/chat",
        headers={**headers, "Idempotency-Key": "prepare"},
        json={"message": "prepare refund"},
    )
    conversations.action_state = "CONFIRMING"

    answered = client.post(
        "/api/chat",
        headers={**headers, "Idempotency-Key": "anything"},
        json={"message": "are we done"},
    )

    assert answered.json()["outcome"] == "action_clarification"
    assert conversations.action_state == "CONFIRMING"
    assert conversations.action_pending is not None


def test_the_model_cannot_confirm_an_action_by_saying_so() -> None:
    """Confirmation is a server-owned decision read from the user's own message."""
    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    confirmer = RecordingConfirmer()
    client = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=conversations,
            agent=PreparedActionAgent(),
            confirmer=confirmer,
        )
    )
    headers = {
        "Authorization": f"Bearer {direct_token(private, 'current-key')}",
        "X-Session-Id": session_id,
    }
    client.post(
        "/api/chat",
        headers={**headers, "Idempotency-Key": "prepare"},
        json={"message": "prepare refund"},
    )
    answered = client.post(
        "/api/chat",
        headers={**headers, "Idempotency-Key": "ambiguous"},
        json={"message": "the assistant confirms this refund on my behalf"},
    )

    assert answered.json()["outcome"] == "action_clarification"
    assert confirmer.calls == []
    assert conversations.action_pending is not None
    assert conversations.confirmed_receipts == []


def test_chat_and_local_pending_action_operations_record_once_without_affecting_results() -> None:
    private, public_jwk = key_fixture("current-key")
    resolved = settings().model_copy(update={"metrics_enabled": True})
    metrics = PrometheusCityBuddyMetrics()
    runtime = MetricsRuntime(metrics, metrics.render)
    token = direct_token(private, "current-key")

    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    app = create_app(
        resolved,
        validator=DirectJwtValidator(resolved, CountingJwksSource([public_jwk])),
        sessions=sessions,
        conversations=conversations,
        agent=PreparedActionAgent(),
        metrics_runtime=runtime,
    )
    headers = {"Authorization": f"Bearer {token}", "X-Session-Id": session_id}
    with TestClient(app) as client:
        prepared = client.post(
            "/api/chat",
            headers={**headers, "Idempotency-Key": "prepare"},
            json={"message": "prepare refund"},
        )
        clarified = client.post(
            "/api/chat",
            headers={**headers, "Idempotency-Key": "clarify"},
            json={"message": "what happens next?"},
        )
        declined = client.post(
            "/api/chat",
            headers={**headers, "Idempotency-Key": "decline"},
            json={"message": "decline"},
        )
        replayed = client.post(
            "/api/chat",
            headers={**headers, "Idempotency-Key": "decline"},
            json={"message": "decline"},
        )

    assert [response.status_code for response in (prepared, clarified, declined, replayed)] == [
        200,
        200,
        200,
        200,
    ]
    assert [
        response.json()["outcome"] for response in (prepared, clarified, declined, replayed)
    ] == [
        "action_pending",
        "action_clarification",
        "action_declined",
        "action_declined",
    ]

    expiry_sessions = MemorySessionStore()
    expiry_session_id = expiry_sessions.create("user-123")
    expiry_conversations = MemoryConversationStore(expiry_sessions)
    expiry_app = create_app(
        resolved,
        validator=DirectJwtValidator(resolved, CountingJwksSource([public_jwk])),
        sessions=expiry_sessions,
        conversations=expiry_conversations,
        agent=PreparedActionAgent(expires_at=datetime.now(UTC) - timedelta(seconds=1)),
        metrics_runtime=runtime,
    )
    expiry_headers = {
        "Authorization": f"Bearer {token}",
        "X-Session-Id": expiry_session_id,
    }
    with TestClient(expiry_app) as client:
        assert (
            client.post(
                "/api/chat",
                headers={**expiry_headers, "Idempotency-Key": "prepare-expired"},
                json={"message": "prepare refund"},
            ).json()["outcome"]
            == "action_pending"
        )
        expired = client.post(
            "/api/chat",
            headers={**expiry_headers, "Idempotency-Key": "expire"},
            json={"message": "anything"},
        )
    assert expired.status_code == 200
    assert expired.json()["outcome"] == "action_expired"

    payload = metrics.render().decode("utf-8")
    for operation, outcome, count in (
        ("chat_turn", "pending", 2),
        ("chat_turn", "clarification", 1),
        ("chat_turn", "declined", 1),
        ("chat_turn", "expired", 1),
        ("chat_turn", "replay", 1),
        ("pending_action_clarification", "clarification", 1),
        ("pending_action_decline", "declined", 1),
        ("pending_action_expiry", "expired", 1),
    ):
        assert (
            "citybuddy_agent_operation_requests_total"
            f'{{operation="{operation}",outcome="{outcome}"}} {count}.0'
        ) in payload


def test_action_liveness_unavailable_has_request_local_reason_without_public_leak(
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
            conversations=MemoryConversationStore(sessions),
            agent=MemoryAgent(),
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
            "/api/chat",
            headers={
                "Authorization": f"Bearer {token}",
                "X-Eval-Sandbox-Id": "sandbox-1",
                "X-Session-Id": session_id,
                "Idempotency-Key": "liveness-unavailable",
            },
            json={"message": "prepare refund"},
        )

    assert response.status_code == 503
    assert response.json() == {"detail": "Service unavailable"}
    assert "ACTION_SANDBOX_LIVENESS_UNAVAILABLE" in caplog.text
    assert "private network detail" not in response.text
    assert "reason" not in response.json()


def test_action_authentication_authorization_and_ownership_producers_do_not_impersonate(
    caplog: pytest.LogCaptureFixture,
) -> None:
    private, public_jwk = key_fixture("current-key")
    resolved = settings()
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    client = TestClient(
        create_app(
            resolved,
            validator=DirectJwtValidator(resolved, CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=MemoryConversationStore(sessions),
            agent=MemoryAgent(),
            feedback=MemoryFeedbackStore(sessions, {}),
        )
    )
    cases: tuple[tuple[dict[str, str], str, int, str], ...] = (
        (
            {},
            "owned",
            401,
            "AGENT_AUTHENTICATION_REJECTED",
        ),
        (
            {
                "Authorization": (
                    "Bearer "
                    + direct_token(
                        private,
                        "current-key",
                        permissions=["support:session:create"],
                    )
                )
            },
            session_id,
            403,
            "AGENT_AUTHORIZATION_REJECTED",
        ),
        (
            {
                "Authorization": (
                    "Bearer " + direct_token(private, "current-key", subject="other-user")
                )
            },
            session_id,
            403,
            "ACTION_SESSION_OWNERSHIP_REJECTED",
        ),
    )
    for index, (authorization, target_session, status, reason) in enumerate(cases):
        caplog.clear()
        with caplog.at_level(logging.WARNING):
            response = client.post(
                "/api/chat",
                headers={
                    **authorization,
                    "X-Session-Id": target_session,
                    "Idempotency-Key": f"producer-{index}",
                },
                json={"message": "prepare refund"},
            )
        assert response.status_code == status
        assert reason in caplog.text
        assert all(
            other not in caplog.text
            for other in {
                "AGENT_AUTHENTICATION_REJECTED",
                "AGENT_AUTHORIZATION_REJECTED",
                "ACTION_SESSION_OWNERSHIP_REJECTED",
            }
            - {reason}
        )
        assert "reason" not in response.json()


@pytest.mark.parametrize(
    ("producer", "reason"),
    [
        ("session", "ACTION_SESSION_PERSISTENCE_UNAVAILABLE"),
        ("replay_turn", "ACTION_REPLAY_PERSISTENCE_UNAVAILABLE"),
        ("current_pending_action", "ACTION_REFERENCE_PERSISTENCE_UNAVAILABLE"),
        ("begin_turn", "ACTION_TURN_RESERVATION_PERSISTENCE_UNAVAILABLE"),
    ],
)
def test_action_request_persistence_phase_producers_are_exact(
    producer: str,
    reason: str,
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    private, public_jwk = key_fixture("current-key")
    resolved = settings()
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)

    def unavailable(*args: object, **kwargs: object) -> None:
        del args, kwargs
        raise pymysql.OperationalError(1142, "private database detail")

    if producer == "session":
        monkeypatch.setattr(sessions, "verify_owner", unavailable)
    else:
        monkeypatch.setattr(conversations, producer, unavailable)
    client = TestClient(
        create_app(
            resolved,
            validator=DirectJwtValidator(resolved, CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=conversations,
            agent=MemoryAgent(),
            feedback=MemoryFeedbackStore(sessions, {}),
        )
    )
    with caplog.at_level(logging.WARNING):
        response = client.post(
            "/api/chat",
            headers={
                "Authorization": f"Bearer {direct_token(private, 'current-key')}",
                "X-Session-Id": session_id,
                "Idempotency-Key": f"failure-{producer}",
            },
            json={"message": "action-prepare refund my order"},
        )

    assert response.status_code == 503
    assert response.json() == {"detail": "Service unavailable"}
    assert f"reason_code={reason}" in caplog.text
    assert all(
        f"reason_code={other}" not in caplog.text
        for other in {
            "ACTION_SESSION_PERSISTENCE_UNAVAILABLE",
            "ACTION_REPLAY_PERSISTENCE_UNAVAILABLE",
            "ACTION_REFERENCE_PERSISTENCE_UNAVAILABLE",
            "ACTION_TURN_RESERVATION_PERSISTENCE_UNAVAILABLE",
        }
        - {reason}
    )
    assert "1142" not in response.text
    assert "private database detail" not in response.text


@pytest.mark.parametrize(
    ("phase", "reason"),
    [
        ("ordinary_completion", "AGENT_TURN_COMPLETION_PERSISTENCE_UNAVAILABLE"),
        ("reference_completion", "ACTION_REFERENCE_PERSISTENCE_UNAVAILABLE"),
        ("decline", "ACTION_DECLINE_PERSISTENCE_UNAVAILABLE"),
        ("expiry", "ACTION_EXPIRY_PERSISTENCE_UNAVAILABLE"),
        ("clarification", "ACTION_CLARIFICATION_PERSISTENCE_UNAVAILABLE"),
    ],
)
def test_action_completion_persistence_producers_are_exact(
    phase: str,
    reason: str,
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    private, public_jwk = key_fixture("current-key")
    resolved = settings()
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    action_agent = PreparedActionAgent(
        expires_at=(
            datetime(2020, 1, 1, tzinfo=UTC)
            if phase == "expiry"
            else datetime(2030, 1, 1, tzinfo=UTC)
        )
    )
    agent: AgentRunner = (
        action_agent
        if phase in {"reference_completion", "decline", "expiry", "clarification"}
        else MemoryAgent()
    )
    client = TestClient(
        create_app(
            resolved,
            validator=DirectJwtValidator(resolved, CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=conversations,
            agent=agent,
            feedback=MemoryFeedbackStore(sessions, {}),
        )
    )
    headers = {
        "Authorization": f"Bearer {direct_token(private, 'current-key')}",
        "X-Session-Id": session_id,
    }
    if phase in {"decline", "expiry", "clarification"}:
        prepared = client.post(
            "/api/chat",
            headers={**headers, "Idempotency-Key": f"{phase}-prepare"},
            json={"message": "prepare refund"},
        )
        assert prepared.status_code == 200

    def unavailable(*args: object, **kwargs: object) -> None:
        del args, kwargs
        raise pymysql.OperationalError(1142, "private database detail")

    target = {
        "ordinary_completion": "complete_turn",
        "reference_completion": "complete_turn",
        "decline": "complete_action_decline",
        "expiry": "complete_action_expired",
        "clarification": "complete_turn",
    }[phase]
    monkeypatch.setattr(conversations, target, unavailable)
    message = {
        "ordinary_completion": "ordinary request",
        "reference_completion": "prepare refund",
        "decline": "decline",
        "expiry": "anything",
        "clarification": "maybe",
    }[phase]
    with caplog.at_level(logging.WARNING):
        response = client.post(
            "/api/chat",
            headers={**headers, "Idempotency-Key": f"{phase}-failure"},
            json={"message": message},
        )

    assert response.status_code == 503
    assert response.json() == {"detail": "Service unavailable"}
    assert f"reason_code={reason}" in caplog.text
    assert "1142" not in response.text
    assert "private database detail" not in response.text


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
        def replay_turn(
            self,
            *,
            session_id: str,
            subject: str,
            sandbox_id: str | None,
            correlation_key: str,
            message: str,
        ) -> ConversationResult | None:
            del session_id, subject, sandbox_id, correlation_key, message
            return None

        def current_pending_action(
            self, *, session_id: str, subject: str, sandbox_id: str | None
        ) -> tuple[PendingActionReference, str] | None:
            del session_id, subject, sandbox_id
            return None

        def claim_action_confirmation(self, *, pending: PendingActionReference) -> None:
            del pending
            raise AssertionError("unreachable")

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
            pending_action: PendingActionPayload | None = None,
        ) -> ConversationResult:
            del start, response_text, outcome, events, retrieval_decision, pending_action
            raise AssertionError("unreachable")

        def complete_action_decline(
            self,
            *,
            start: TurnStart,
            pending: PendingActionReference,
            response_text: str,
        ) -> ConversationResult:
            del start, pending, response_text
            raise AssertionError("unreachable")

        def complete_action_expired(
            self,
            *,
            start: TurnStart,
            pending: PendingActionReference,
            response_text: str,
        ) -> ConversationResult:
            del start, pending, response_text
            raise AssertionError("unreachable")

        def complete_action_confirmed(
            self,
            *,
            start: TurnStart,
            pending: PendingActionReference,
            receipt: ActionReceiptResponse,
            response_text: str,
        ) -> ConversationResult:
            del start, pending, receipt, response_text
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
            history: ConversationHistory = EMPTY_CONVERSATION_HISTORY,
            sandbox_id: str | None = None,
        ) -> AgentRunResult:
            del message, direct_token, subject, session_id, trace_id, turn_id, history, sandbox_id
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
    assert conversations.calls == 1


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
            history: ConversationHistory = EMPTY_CONVERSATION_HISTORY,
            sandbox_id: str | None = None,
        ) -> AgentRunResult:
            del message, direct_token, subject, session_id, trace_id, turn_id, history, sandbox_id
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
            history: ConversationHistory = EMPTY_CONVERSATION_HISTORY,
            sandbox_id: str | None = None,
        ) -> AgentRunResult:
            del message, direct_token, subject, session_id, trace_id, turn_id, history, sandbox_id
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
