import base64
import json
import time
from collections.abc import Mapping
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from typing import Any, Literal

import httpx
import jwt
import pymysql
import pytest
from citybuddy_agent.actions import (
    ActionReceiptPayload,
    PendingActionPayload,
    PendingActionReference,
    StoredActionReceipt,
    action_argument_commitment,
    canonical_action_timestamp,
)
from citybuddy_agent.agent_control import (
    AgentEvent,
    AgentRunner,
    AgentRunResult,
    AttemptBudget,
    ToolBoundaryFailure,
)
from citybuddy_agent.application import (
    ActionConfirmationBoundary,
    AgentSettings,
    DirectJwtValidator,
    DirectPrincipal,
    HttpActionConfirmationBoundary,
    OboClient,
    SessionStore,
    create_app,
)
from citybuddy_agent.conversation import (
    ActionArbitrationConflictError,
    ActionReferenceSnapshot,
    ActionReferenceState,
    ConversationIntegrityError,
    ConversationOwnershipError,
    ConversationResult,
    ConversationStore,
    CorrelationConflictError,
    MysqlConversationStore,
    TurnStart,
)
from citybuddy_agent.evaluation import (
    ActionEvidenceIntegrityError,
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
from citybuddy_agent.retrieval import RetrievalDecision
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import HTTPException
from fastapi.testclient import TestClient


def streamed(callback: Any) -> Any:
    @contextmanager
    def stream(method: str, url: str, **kwargs: Any) -> Any:
        assert method == "POST"
        yield callback(url, **kwargs)

    return stream


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
        self.action_pending: PendingActionReference | None = None
        self.action_state: ActionReferenceState | None = None
        self.action_receipts: dict[str, ActionReceiptPayload] = {}
        self.confirmation_claim: tuple[str, tuple[str, str], TurnStart] | None = None

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

    def begin_or_resume_confirmation_turn(
        self,
        *,
        session_id: str,
        subject: str,
        sandbox_id: str | None,
        correlation_key: str,
        message: str,
        pending: PendingActionReference,
    ) -> TurnStart:
        key = (session_id, correlation_key)
        existing_result = self.results.get(key)
        if existing_result is not None:
            if existing_result[0] != message:
                raise CorrelationConflictError
            result = existing_result[1]
            return TurnStart(
                result.conversation_id,
                result.trace_id,
                result.turn_id,
                replay=result,
            )
        existing_start = self.pending.get(key)
        if existing_start is not None:
            if existing_start[0] != message:
                raise CorrelationConflictError
            return TurnStart(
                existing_start[1].conversation_id,
                existing_start[1].trace_id,
                existing_start[1].turn_id,
                confirmation_pending_id=pending.pending_action_id,
            )
        if (
            self.confirmation_claim is not None
            and self.confirmation_claim[0] == pending.pending_action_id
        ):
            raise ActionArbitrationConflictError
        start = self.begin_turn(
            session_id=session_id,
            subject=subject,
            sandbox_id=sandbox_id,
            correlation_key=correlation_key,
            message=message,
        )
        claimed = TurnStart(
            start.conversation_id,
            start.trace_id,
            start.turn_id,
            confirmation_pending_id=pending.pending_action_id,
        )
        self.pending[key] = (message, claimed)
        self.confirmation_claim = (pending.pending_action_id, key, claimed)
        self.action_state = "CONFIRMING"
        return claimed

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
        del self.pending[key]
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
                amount_minor=pending_action.amount_minor,
                currency=pending_action.currency,
                expires_at=pending_action.expires_at,
            )
            self.action_state = "PENDING"
        return result

    def current_pending_action(
        self, *, session_id: str, subject: str, sandbox_id: str | None
    ) -> PendingActionReference | None:
        current = self.current_action_reference(
            session_id=session_id,
            subject=subject,
            sandbox_id=sandbox_id,
        )
        if current is None or current.state not in {"PENDING", "CONFIRMING"}:
            return None
        return current.reference

    def current_action_reference(
        self, *, session_id: str, subject: str, sandbox_id: str | None
    ) -> ActionReferenceSnapshot | None:
        pending = self.action_pending
        if pending is None:
            return None
        if (
            pending.session_id != session_id
            or pending.user_subject != subject
            or pending.sandbox_id != sandbox_id
        ):
            return None
        if self.action_state is None:
            raise AssertionError("Pending action state is missing")
        return ActionReferenceSnapshot(pending, self.action_state)

    def complete_action_decline(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        response_text: str,
    ) -> ConversationResult:
        if self.action_pending != pending:
            raise AssertionError("Unexpected pending action")
        if self.confirmation_claim is not None:
            raise ActionArbitrationConflictError
        self.action_state = "DECLINED"
        return self._finish_action_result(
            start=start,
            response_text=response_text,
            outcome="action_declined",
        )

    def complete_action_receipt(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        receipt: ActionReceiptPayload,
        response_text: str,
        events: tuple[AgentEvent, ...] = (),
    ) -> ConversationResult:
        del events
        for message, result in self.results.values():
            del message
            if result.turn_id == start.turn_id and result.action_receipt is not None:
                return result
        if self.action_pending != pending:
            raise AssertionError("Unexpected pending action")
        self.action_state = "CONFIRMED"
        self.confirmation_claim = None
        self.action_receipts[start.turn_id] = receipt
        return self._finish_action_result(
            start=start,
            response_text=response_text,
            outcome="action_completed",
            receipt=receipt,
            pending=pending,
        )

    def complete_action_expired(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        response_text: str,
    ) -> ConversationResult:
        if self.action_pending != pending:
            raise AssertionError("Unexpected pending action")
        if self.confirmation_claim is not None:
            raise ActionArbitrationConflictError
        self.action_state = "EXPIRED"
        return self._finish_action_result(
            start=start,
            response_text=response_text,
            outcome="action_expired",
        )

    def _finish_action_result(
        self,
        *,
        start: TurnStart,
        response_text: str,
        outcome: str,
        receipt: ActionReceiptPayload | None = None,
        pending: PendingActionReference | None = None,
    ) -> ConversationResult:
        key, reserved = next(
            item for item in self.pending.items() if item[1][1].turn_id == start.turn_id
        )
        stored_receipt = (
            StoredActionReceipt(receipt, pending.source_turn_id, start.turn_id)
            if receipt is not None and pending is not None
            else None
        )
        result = ConversationResult(
            start.conversation_id,
            start.trace_id,
            start.turn_id,
            response_text,
            outcome,
            action_receipt=stored_receipt,
        )
        self.results[key] = (reserved[0], result)
        del self.pending[key]
        return result

    def fail_turn(
        self,
        *,
        start: TurnStart,
        failure_code: str,
        events: tuple[AgentEvent, ...] = (),
    ) -> None:
        del events
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


class PendingAgent(AgentRunner):
    def __init__(self, pending: PendingActionPayload) -> None:
        self.pending = pending
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
        sandbox_id: str | None = None,
    ) -> AgentRunResult:
        self.calls += 1
        del message, direct_token, subject, session_id, trace_id, turn_id, sandbox_id
        return AgentRunResult(
            "Please confirm or decline the prepared refund request.",
            "action_pending",
            (
                AgentEvent(
                    "ACTION_PREPARED",
                    {
                        "pendingActionId": self.pending.pending_action_id,
                        "actionType": self.pending.action_type,
                        "argumentCommitment": self.pending.argument_commitment,
                        "expiresAt": canonical_action_timestamp(self.pending.expires_at),
                    },
                ),
            ),
            pending_action=self.pending,
        )


class MemoryActionBoundary(ActionConfirmationBoundary):
    def __init__(self, receipt: ActionReceiptPayload) -> None:
        self.receipt = receipt
        self.calls: list[tuple[str, PendingActionReference]] = []

    def confirm(
        self,
        *,
        direct_token: str,
        pending: PendingActionReference,
        budget: AttemptBudget,
    ) -> ActionReceiptPayload:
        budget.charge("identity_http", "refund:create")
        budget.charge("tool_http", "actions.refund.confirm")
        self.calls.append((direct_token, pending))
        return self.receipt


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
        if self.mode == "action_invalid":
            raise ActionEvidenceIntegrityError
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


class ScriptedCursor:
    def __init__(self, results: list[list[tuple[object, ...]]]) -> None:
        self.results = results
        self.current: list[tuple[object, ...]] = []

    def execute(self, query: str, arguments: object) -> None:
        del query, arguments
        self.current = self.results.pop(0)

    def fetchall(self) -> list[tuple[object, ...]]:
        return self.current


def test_evaluation_action_truth_rejects_missing_pending_and_receipt_projections() -> None:
    store = object.__new__(MysqlEvaluationEvidenceStore)
    now = datetime.now(UTC)
    prepared = EvidenceEventResponse(
        sequence=2,
        event_kind="ACTION_PREPARED",
        outcome="prepared",
        reference=action_argument_commitment(
            "REFUND_REQUEST",
            "00000000-0000-0000-0000-000000000040",
            400,
            "CNY",
        ),
        occurred_at=now,
    )
    receipt = EvidenceEventResponse(
        sequence=2,
        event_kind="ACTION_RECEIPT",
        outcome="REQUESTED",
        reference="00000000-0000-0000-0000-000000000122",
        occurred_at=now,
    )

    with pytest.raises(EvaluationEvidenceInvalid):
        store._validate_action_truth(
            ScriptedCursor([[]]),  # type: ignore[arg-type]
            turn_id="00000000-0000-0000-0000-000000000121",
            trace_id="00000000-0000-0000-0000-000000000125",
            conversation_id="00000000-0000-0000-0000-000000000126",
            session_id="session-1",
            subject="user-1",
            sandbox_id="sandbox-1",
            terminal_outcome="action_pending",
            events=(prepared,),
        )
    with pytest.raises(EvaluationEvidenceInvalid):
        store._validate_action_truth(
            ScriptedCursor([[], []]),  # type: ignore[arg-type]
            turn_id="00000000-0000-0000-0000-000000000127",
            trace_id="00000000-0000-0000-0000-000000000128",
            conversation_id="00000000-0000-0000-0000-000000000126",
            session_id="session-1",
            subject="user-1",
            sandbox_id="sandbox-1",
            terminal_outcome="action_completed",
            events=(receipt,),
        )


def test_mysql_complete_turn_rejects_action_completed_before_commit() -> None:
    class Cursor:
        rowcount = 1

        def __enter__(self) -> Any:
            return self

        def __exit__(self, *args: object) -> None:
            del args

        def execute(self, query: str, arguments: object) -> None:
            del query, arguments

        def fetchone(self) -> tuple[object, ...]:
            return ("session-1", "user-1", "PROCESSING", "trace-1", True)

    class Connection:
        def __init__(self) -> None:
            self.commits = 0
            self.rollbacks = 0

        def __enter__(self) -> Any:
            return self

        def __exit__(self, *args: object) -> None:
            del args

        def cursor(self) -> Any:
            return Cursor()

        def commit(self) -> None:
            self.commits += 1

        def rollback(self) -> None:
            self.rollbacks += 1

    connection = Connection()
    store = object.__new__(MysqlConversationStore)
    store._connect = lambda: connection  # type: ignore[assignment,method-assign,return-value]

    with pytest.raises(RuntimeError, match="requires the receipt transaction"):
        store.complete_turn(
            start=TurnStart("conversation-1", "trace-1", "turn-1"),
            response_text="invalid",
            outcome="action_completed",
            events=(),
        )
    assert connection.commits == 0
    assert connection.rollbacks == 1


def test_conversation_replay_rejects_missing_pending_projection() -> None:
    with pytest.raises(ConversationIntegrityError, match="cardinality"):
        MysqlConversationStore._load_pending_action_for_turn(
            ScriptedCursor([[]]),  # type: ignore[arg-type]
            turn_id="00000000-0000-0000-0000-000000000121",
            trace_id="00000000-0000-0000-0000-000000000125",
            conversation_id="00000000-0000-0000-0000-000000000126",
            session_id="session-1",
            subject="user-1",
            sandbox_id="sandbox-1",
        )


@pytest.mark.parametrize(
    ("terminal_outcome", "event_type", "state", "event_outcome"),
    [
        ("action_declined", "ACTION_DECLINED", "DECLINED", "declined"),
        ("action_expired", "ACTION_EXPIRED", "EXPIRED", "expired"),
    ],
)
def test_resolved_action_replay_and_evaluation_require_the_pending_projection(
    terminal_outcome: str,
    event_type: str,
    state: str,
    event_outcome: str,
) -> None:
    pending_action_id = "00000000-0000-0000-0000-000000000121"
    source_turn_id = "00000000-0000-0000-0000-000000000122"
    source_trace_id = "00000000-0000-0000-0000-000000000123"
    conversation_id = "00000000-0000-0000-0000-000000000124"
    resolution_turn_id = "00000000-0000-0000-0000-000000000125"
    order_id = "00000000-0000-0000-0000-000000000040"
    commitment = action_argument_commitment("REFUND_REQUEST", order_id, 400, "CNY")
    now = datetime.now(UTC)
    expires_at = now + timedelta(minutes=5)
    event_payload = json.dumps({"pendingActionId": pending_action_id, "outcome": event_outcome})
    prepared_payload = json.dumps(
        {
            "pendingActionId": pending_action_id,
            "actionType": "REFUND_REQUEST",
            "argumentCommitment": commitment,
            "expiresAt": canonical_action_timestamp(expires_at),
        }
    )
    pending_row = (
        pending_action_id,
        source_turn_id,
        source_trace_id,
        conversation_id,
        "session-1",
        "user-1",
        "sandbox-1",
        "REFUND_REQUEST",
        commitment,
        order_id,
        400,
        "CNY",
        state,
        expires_at,
        now,
        None,
        None,
    )

    conversation_cursor = ScriptedCursor(
        [
            [(event_payload,)],
            [
                (
                    source_turn_id,
                    source_trace_id,
                    conversation_id,
                    "session-1",
                    "user-1",
                    "sandbox-1",
                    state,
                    now,
                )
            ],
            [pending_row],
            [(prepared_payload,)],
            [],
            [(resolution_turn_id, event_type)],
        ]
    )
    MysqlConversationStore._validate_resolved_action_turn(
        conversation_cursor,  # type: ignore[arg-type]
        turn_id=resolution_turn_id,
        session_id="session-1",
        subject="user-1",
        sandbox_id="sandbox-1",
        outcome=terminal_outcome,
    )

    resolution_event = EvidenceEventResponse(
        sequence=2,
        event_kind=event_type,  # type: ignore[arg-type]
        outcome=event_outcome,
        occurred_at=now,
    )
    evaluation_cursor = ScriptedCursor(
        [
            [],
            [(event_payload,)],
            [pending_row],
            [
                (
                    source_trace_id,
                    conversation_id,
                    "session-1",
                    "user-1",
                    "COMPLETED",
                    "action_pending",
                )
            ],
            [(prepared_payload,)],
            [],
            [(resolution_turn_id, event_type)],
            [],
        ]
    )
    MysqlEvaluationEvidenceStore._validate_action_truth(
        object.__new__(MysqlEvaluationEvidenceStore),
        evaluation_cursor,  # type: ignore[arg-type]
        turn_id=resolution_turn_id,
        trace_id="00000000-0000-0000-0000-000000000126",
        conversation_id=conversation_id,
        session_id="session-1",
        subject="user-1",
        sandbox_id="sandbox-1",
        terminal_outcome=terminal_outcome,  # type: ignore[arg-type]
        events=(resolution_event,),
    )

    missing_projection = ScriptedCursor([[(event_payload,)], []])
    with pytest.raises(ConversationIntegrityError, match="cardinality"):
        MysqlConversationStore._validate_resolved_action_turn(
            missing_projection,  # type: ignore[arg-type]
            turn_id=resolution_turn_id,
            session_id="session-1",
            subject="user-1",
            sandbox_id="sandbox-1",
            outcome=terminal_outcome,
        )


def test_pending_action_expiry_requires_exact_prepared_event_anchor() -> None:
    pending_action_id = "00000000-0000-0000-0000-000000000121"
    source_turn_id = "00000000-0000-0000-0000-000000000122"
    source_trace_id = "00000000-0000-0000-0000-000000000123"
    conversation_id = "00000000-0000-0000-0000-000000000124"
    order_id = "00000000-0000-0000-0000-000000000040"
    commitment = action_argument_commitment("REFUND_REQUEST", order_id, 400, "CNY")
    expires_at = datetime(2026, 7, 28, 4, 0, 0, 123456, tzinfo=UTC)
    pending_row = (
        pending_action_id,
        source_turn_id,
        source_trace_id,
        conversation_id,
        "session-1",
        "user-1",
        "sandbox-1",
        "REFUND_REQUEST",
        commitment,
        order_id,
        400,
        "CNY",
        "PENDING",
        expires_at,
        None,
        None,
        None,
    )
    damaged_payload = json.dumps(
        {
            "pendingActionId": pending_action_id,
            "actionType": "REFUND_REQUEST",
            "argumentCommitment": commitment,
            "expiresAt": canonical_action_timestamp(expires_at + timedelta(seconds=1)),
        }
    )

    with pytest.raises(ConversationIntegrityError, match="preparation event is inconsistent"):
        MysqlConversationStore._load_pending_action_for_turn(
            ScriptedCursor([[pending_row], [(damaged_payload,)]]),  # type: ignore[arg-type]
            turn_id=source_turn_id,
            trace_id=source_trace_id,
            conversation_id=conversation_id,
            session_id="session-1",
            subject="user-1",
            sandbox_id="sandbox-1",
        )

    evaluation_cursor = ScriptedCursor(
        [
            [
                (
                    source_trace_id,
                    conversation_id,
                    "session-1",
                    "user-1",
                    "COMPLETED",
                    "action_pending",
                )
            ],
            [(damaged_payload,)],
        ]
    )
    with pytest.raises(EvaluationEvidenceInvalid):
        MysqlEvaluationEvidenceStore._validate_pending_truth_row(
            object.__new__(MysqlEvaluationEvidenceStore),
            evaluation_cursor,  # type: ignore[arg-type]
            pending=pending_row,
            session_id="session-1",
            subject="user-1",
            sandbox_id="sandbox-1",
        )


def test_receipt_replay_requires_the_committed_pending_projection() -> None:
    now = datetime.now(UTC)
    receipt = ActionReceiptPayload.model_validate(
        {
            "receiptId": "00000000-0000-0000-0000-000000000121",
            "pendingActionId": "00000000-0000-0000-0000-000000000122",
            "actionType": "REFUND_REQUEST",
            "status": "REQUESTED",
            "orderId": "00000000-0000-0000-0000-000000000040",
            "refundId": "00000000-0000-0000-0000-000000000123",
            "resourceVersion": 1,
            "amountMinor": 400,
            "currency": "CNY",
            "committedAt": now,
            "replayed": True,
        }
    )
    stored = StoredActionReceipt(
        receipt,
        "00000000-0000-0000-0000-000000000124",
        "00000000-0000-0000-0000-000000000125",
    )
    cursor = ScriptedCursor(
        [
            [
                (
                    "00000000-0000-0000-0000-000000000126",
                    "00000000-0000-0000-0000-000000000127",
                    "session-1",
                    "user-1",
                    "COMPLETED",
                    "action_pending",
                )
            ],
            [],
        ]
    )
    with pytest.raises(ConversationIntegrityError, match="cardinality"):
        MysqlConversationStore._validate_receipt_pending_truth(
            cursor,  # type: ignore[arg-type]
            stored=stored,
            session_id="session-1",
            subject="user-1",
            sandbox_id="sandbox-1",
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
    caplog.clear()
    invalid = client.get(f"/api/eval/evidence/{trace_id}", headers=headers)
    assert invalid.status_code == 409
    assert invalid.json() == {"detail": "Evidence unavailable"}
    assert "reason_code=EVALUATION_ACTION_DURABLE_TRUTH_INCONSISTENT" not in caplog.text

    evidence.mode = "action_invalid"
    caplog.clear()
    action_invalid = client.get(f"/api/eval/evidence/{trace_id}", headers=headers)
    assert action_invalid.status_code == 409
    assert action_invalid.json() == {"detail": "Evidence unavailable"}
    assert "reason_code=EVALUATION_ACTION_DURABLE_TRUTH_INCONSISTENT" in caplog.text


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

    monkeypatch.setattr(httpx, "stream", streamed(exchange_response))

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

    monkeypatch.setattr(httpx, "stream", streamed(exchange_response))

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
        "stream",
        streamed(lambda *args, **kwargs: httpx.Response(200, content=b"{")),
    )

    with pytest.raises(HTTPException) as malformed:
        client.exchange("direct-token", "user-123", session_id, "catalog:read")

    assert malformed.value.status_code == 503
    assert malformed.value.detail == "Identity exchange unavailable"


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
            raise pymysql.OperationalError(1142, "private SQL detail")

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

        def begin_or_resume_confirmation_turn(
            self,
            *,
            session_id: str,
            subject: str,
            sandbox_id: str | None,
            correlation_key: str,
            message: str,
            pending: PendingActionReference,
        ) -> TurnStart:
            del session_id, subject, sandbox_id, correlation_key, message, pending
            raise AssertionError("unreachable")

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

        def current_pending_action(
            self, *, session_id: str, subject: str, sandbox_id: str | None
        ) -> PendingActionReference | None:
            del session_id, subject, sandbox_id
            raise AssertionError("unreachable")

        def current_action_reference(
            self, *, session_id: str, subject: str, sandbox_id: str | None
        ) -> ActionReferenceSnapshot | None:
            del session_id, subject, sandbox_id
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

        def complete_action_receipt(
            self,
            *,
            start: TurnStart,
            pending: PendingActionReference,
            receipt: ActionReceiptPayload,
            response_text: str,
            events: tuple[AgentEvent, ...] = (),
        ) -> ConversationResult:
            del start, pending, receipt, response_text, events
            raise AssertionError("unreachable")

        def fail_turn(
            self,
            *,
            start: TurnStart,
            failure_code: str,
            events: tuple[AgentEvent, ...] = (),
        ) -> None:
            del start, failure_code, events
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
    assert conversations.calls == 1


def action_payloads() -> tuple[PendingActionPayload, ActionReceiptPayload]:
    pending_id = "00000000-0000-0000-0000-000000000121"
    order_id = "00000000-0000-0000-0000-000000000040"
    pending = PendingActionPayload.model_validate(
        {
            "pendingActionId": pending_id,
            "actionType": "REFUND_REQUEST",
            "orderId": order_id,
            "amountMinor": 400,
            "currency": "CNY",
            "state": "PREPARED",
            "expiresAt": datetime.now(UTC) + timedelta(minutes=10),
            "replayed": False,
        }
    )
    receipt = ActionReceiptPayload.model_validate(
        {
            "receiptId": "00000000-0000-0000-0000-000000000122",
            "pendingActionId": pending_id,
            "actionType": "REFUND_REQUEST",
            "status": "REQUESTED",
            "orderId": order_id,
            "refundId": "00000000-0000-0000-0000-000000000071",
            "resourceVersion": 1,
            "amountMinor": 400,
            "currency": "CNY",
            "committedAt": datetime.now(UTC),
            "replayed": False,
        }
    )
    return pending, receipt


def test_pending_action_requires_exact_confirmation_and_projects_one_receipt() -> None:
    pending, receipt = action_payloads()
    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    agent = PendingAgent(pending)
    actions = MemoryActionBoundary(receipt)
    client = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=conversations,
            agent=agent,
            actions=actions,
        )
    )
    token = direct_token(private, "current-key")
    common = {
        "Authorization": f"Bearer {token}",
        "X-Session-Id": session_id,
    }

    prepared = client.post(
        "/api/chat",
        headers={**common, "Idempotency-Key": "prepare"},
        json={"message": "refund my order"},
    )
    assert prepared.status_code == 200
    assert prepared.json()["outcome"] == "action_pending"
    assert "actionReceipt" not in prepared.json()
    assert conversations.action_pending is not None

    ambiguous = client.post(
        "/api/chat",
        headers={**common, "Idempotency-Key": "ambiguous"},
        json={"message": "maybe later"},
    )
    assert ambiguous.status_code == 200
    assert ambiguous.json()["outcome"] == "action_clarification"
    assert conversations.action_pending is not None
    assert actions.calls == []

    confirmed = client.post(
        "/api/chat",
        headers={**common, "Idempotency-Key": "confirm"},
        json={"message": "confirm refund"},
    )
    assert confirmed.status_code == 200
    assert confirmed.json()["outcome"] == "action_completed"
    assert confirmed.json()["actionReceipt"] == {
        "receiptId": receipt.receipt_id,
        "status": "REQUESTED",
    }
    assert len(actions.calls) == 1
    assert actions.calls[0][0] == token
    assert actions.calls[0][1].pending_action_id == pending.pending_action_id
    assert conversations.action_state == "CONFIRMED"
    assert agent.calls == 1

    replay = client.post(
        "/api/chat",
        headers={**common, "Idempotency-Key": "confirm"},
        json={"message": "confirm refund"},
    )
    assert replay.json() == confirmed.json()
    assert len(actions.calls) == 1
    assert agent.calls == 1


def test_post_claim_unavailability_keeps_same_confirmation_turn_recoverable() -> None:
    pending, receipt = action_payloads()

    class UnavailableOnceAction(MemoryActionBoundary):
        def confirm(
            self,
            *,
            direct_token: str,
            pending: PendingActionReference,
            budget: AttemptBudget,
        ) -> ActionReceiptPayload:
            self.calls.append((direct_token, pending))
            if len(self.calls) == 1:
                raise ToolBoundaryFailure(
                    status_code=503,
                    reason="ACTION_CONFIRMATION_IDENTITY_UNAVAILABLE",
                    detail="Action confirmation indeterminate",
                )
            return self.receipt

    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    actions = UnavailableOnceAction(receipt)
    client = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=conversations,
            agent=PendingAgent(pending),
            actions=actions,
        )
    )
    common = {
        "Authorization": f"Bearer {direct_token(private, 'current-key')}",
        "X-Session-Id": session_id,
    }
    assert (
        client.post(
            "/api/chat",
            headers={**common, "Idempotency-Key": "prepare-recovery"},
            json={"message": "refund my order"},
        ).status_code
        == 200
    )

    first = client.post(
        "/api/chat",
        headers={**common, "Idempotency-Key": "confirm-recovery"},
        json={"message": "confirm refund"},
    )
    assert first.status_code == 503
    assert conversations.failures == []
    claim = conversations.confirmation_claim
    assert claim is not None

    conflicting_decline = client.post(
        "/api/chat",
        headers={**common, "Idempotency-Key": "decline-during-confirm"},
        json={"message": "do not confirm"},
    )
    assert conflicting_decline.status_code == 409
    assert conversations.action_pending is not None

    recovered = client.post(
        "/api/chat",
        headers={**common, "Idempotency-Key": "confirm-recovery"},
        json={"message": "confirm refund"},
    )
    assert recovered.status_code == 200
    assert recovered.json()["outcome"] == "action_completed"
    assert recovered.json()["turnId"] == claim[2].turn_id
    assert len(actions.calls) == 2
    assert conversations.confirmation_claim is None


def test_confirmation_requeries_the_fixed_result_with_the_shared_attempt_budget(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    pending_payload, receipt = action_payloads()
    pending = PendingActionReference(
        pending_action_id=pending_payload.pending_action_id,
        source_turn_id="00000000-0000-0000-0000-000000000124",
        source_trace_id="00000000-0000-0000-0000-000000000125",
        conversation_id="00000000-0000-0000-0000-000000000126",
        session_id="session-1",
        user_subject="user-1",
        sandbox_id="sandbox-1",
        action_type=pending_payload.action_type,
        argument_commitment=pending_payload.argument_commitment,
        order_id=pending_payload.order_id,
        amount_minor=pending_payload.amount_minor,
        currency=pending_payload.currency,
        expires_at=pending_payload.expires_at,
    )

    class FreshObo(OboClient):
        def __init__(self) -> None:
            self.calls = 0

        def exchange(self, *args: object) -> str:
            self.calls += 1
            assert args == ("direct", "user-1", "session-1", "refund:create", "sandbox-1")
            return f"obo-{self.calls}"

    requests: list[dict[str, Any]] = []

    def post(*args: object, **kwargs: Any) -> httpx.Response:
        requests.append({"args": args, **kwargs})
        if len(requests) == 1:
            raise httpx.ReadTimeout("response lost after commerce commit")
        return httpx.Response(200, json=receipt.model_dump(by_alias=True, mode="json"))

    monkeypatch.setattr(httpx, "stream", streamed(post))
    obo = FreshObo()
    events: list[AgentEvent] = []
    budget = AttemptBudget(4, events)

    observed = HttpActionConfirmationBoundary("https://commerce.test", obo).confirm(
        direct_token="direct",
        pending=pending,
        budget=budget,
    )

    assert observed.receipt_id == receipt.receipt_id
    assert obo.calls == 2
    assert budget.used == 4
    assert [event.payload["kind"] for event in events] == [
        "identity_http",
        "tool_http",
        "identity_http",
        "tool_http",
    ]
    assert {request["args"][0] for request in requests} == {
        f"https://commerce.test/internal/tools/actions/{pending.pending_action_id}/confirm"
    }
    assert {request["headers"]["X-Agent-Turn-Id"] for request in requests} == {
        pending.source_turn_id
    }


def test_confirmation_attributes_real_identity_transport_failure() -> None:
    pending_payload, _ = action_payloads()
    pending = PendingActionReference(
        pending_action_id=pending_payload.pending_action_id,
        source_turn_id="00000000-0000-0000-0000-000000000124",
        source_trace_id="00000000-0000-0000-0000-000000000125",
        conversation_id="00000000-0000-0000-0000-000000000126",
        session_id="session-1",
        user_subject="user-1",
        sandbox_id="sandbox-1",
        action_type=pending_payload.action_type,
        argument_commitment=pending_payload.argument_commitment,
        order_id=pending_payload.order_id,
        amount_minor=pending_payload.amount_minor,
        currency=pending_payload.currency,
        expires_at=pending_payload.expires_at,
    )

    class UnavailableObo(OboClient):
        def __init__(self) -> None:
            pass

        def exchange(self, *args: object) -> str:
            del args
            raise httpx.ConnectError("identity unavailable")

    with pytest.raises(ToolBoundaryFailure) as unavailable:
        HttpActionConfirmationBoundary("https://commerce.test", UnavailableObo()).confirm(
            direct_token="direct",
            pending=pending,
            budget=AttemptBudget(2, []),
        )

    assert unavailable.value.status_code == 503
    assert unavailable.value.reason == "ACTION_CONFIRMATION_IDENTITY_UNAVAILABLE"
    assert unavailable.value.detail == "Action confirmation indeterminate"


@pytest.mark.parametrize(
    ("field", "changed"),
    [
        ("pendingActionId", "00000000-0000-0000-0000-000000000999"),
        ("actionType", "OTHER"),
        ("orderId", "00000000-0000-0000-0000-000000000999"),
        ("amountMinor", 401),
        ("currency", "USD"),
    ],
)
def test_confirmation_rejects_every_contradictory_pending_identity(
    monkeypatch: pytest.MonkeyPatch, field: str, changed: object
) -> None:
    pending_payload, receipt = action_payloads()
    pending = PendingActionReference(
        pending_action_id=pending_payload.pending_action_id,
        source_turn_id="00000000-0000-0000-0000-000000000124",
        source_trace_id="00000000-0000-0000-0000-000000000125",
        conversation_id="00000000-0000-0000-0000-000000000126",
        session_id="session-1",
        user_subject="user-1",
        sandbox_id=None,
        action_type=pending_payload.action_type,
        argument_commitment=pending_payload.argument_commitment,
        order_id=pending_payload.order_id,
        amount_minor=pending_payload.amount_minor,
        currency=pending_payload.currency,
        expires_at=pending_payload.expires_at,
    )
    response = receipt.model_dump(by_alias=True, mode="json")
    response[field] = changed

    class FixedObo(OboClient):
        def __init__(self) -> None:
            pass

        def exchange(self, *args: object) -> str:
            del args
            return "obo"

    monkeypatch.setattr(
        httpx,
        "stream",
        streamed(lambda *args, **kwargs: httpx.Response(200, json=response)),
    )

    with pytest.raises(ToolBoundaryFailure) as rejected:
        HttpActionConfirmationBoundary("https://commerce.test", FixedObo()).confirm(
            direct_token="direct",
            pending=pending,
            budget=AttemptBudget(2, []),
        )
    assert rejected.value.status_code == 502
    assert rejected.value.detail == "Invalid action confirmation response"
    assert rejected.value.reason == "ACTION_CONFIRMATION_RESPONSE_INVALID"


def test_expired_pending_action_is_a_distinct_terminal_without_commerce() -> None:
    pending, receipt = action_payloads()
    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    conversations.action_pending = PendingActionReference(
        pending_action_id=pending.pending_action_id,
        source_turn_id="00000000-0000-0000-0000-000000000124",
        source_trace_id="00000000-0000-0000-0000-000000000125",
        conversation_id=f"server-conversation-{session_id}",
        session_id=session_id,
        user_subject="user-123",
        sandbox_id=None,
        action_type=pending.action_type,
        argument_commitment=pending.argument_commitment,
        order_id=pending.order_id,
        amount_minor=pending.amount_minor,
        currency=pending.currency,
        expires_at=datetime.now(UTC) - timedelta(seconds=1),
    )
    conversations.action_state = "PENDING"
    actions = MemoryActionBoundary(receipt)
    client = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=conversations,
            agent=PendingAgent(pending),
            actions=actions,
        )
    )

    expired = client.post(
        "/api/chat",
        headers={
            "Authorization": f"Bearer {direct_token(private, 'current-key')}",
            "X-Session-Id": session_id,
            "Idempotency-Key": "expired",
        },
        json={"message": "maybe later"},
    )

    assert expired.status_code == 200
    assert expired.json()["outcome"] == "action_expired"
    assert "actionReceipt" not in expired.json()
    assert actions.calls == []
    resolved_action = conversations.current_action_reference(
        session_id=session_id,
        subject="user-123",
        sandbox_id=None,
    )
    assert resolved_action is not None
    assert resolved_action.state == "EXPIRED"


def test_committed_confirmation_recovery_precedes_copied_expiry_and_liveness() -> None:
    pending, receipt = action_payloads()
    private, public_jwk = key_fixture("current-key")
    resolved = evaluation_settings()
    sessions = MemorySessionStore()
    conversations = MemoryConversationStore(sessions)
    liveness = MemoryLiveness()
    actions = MemoryActionBoundary(receipt)
    client = TestClient(
        create_app(
            resolved,
            validator=DirectJwtValidator(resolved, CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=conversations,
            agent=PendingAgent(pending),
            actions=actions,
            liveness=liveness,
        )
    )
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
    conversations.action_pending = PendingActionReference(
        pending_action_id=pending.pending_action_id,
        source_turn_id="00000000-0000-0000-0000-000000000124",
        source_trace_id="00000000-0000-0000-0000-000000000125",
        conversation_id=f"server-conversation-{session_id}",
        session_id=session_id,
        user_subject="user-123",
        sandbox_id="sandbox-1",
        action_type=pending.action_type,
        argument_commitment=pending.argument_commitment,
        order_id=pending.order_id,
        amount_minor=pending.amount_minor,
        currency=pending.currency,
        expires_at=datetime.now(UTC) - timedelta(seconds=1),
    )
    conversations.action_state = "PENDING"
    liveness.active = False

    recovered = client.post(
        "/api/chat",
        headers={
            **headers,
            "X-Session-Id": session_id,
            "Idempotency-Key": "committed-recovery",
        },
        json={"message": "confirm refund"},
    )

    assert recovered.status_code == 200
    assert recovered.json()["actionReceipt"]["receiptId"] == receipt.receipt_id
    assert len(actions.calls) == 1
    assert liveness.calls == [(token, "sandbox-1")]


def test_decline_never_calls_commerce_and_stream_receipt_precedes_text() -> None:
    pending, receipt = action_payloads()
    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    conversations = MemoryConversationStore(sessions)
    agent = PendingAgent(pending)
    actions = MemoryActionBoundary(receipt)
    client = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=conversations,
            agent=agent,
            actions=actions,
        )
    )
    headers = {
        "Authorization": f"Bearer {direct_token(private, 'current-key')}",
        "X-Session-Id": session_id,
    }
    assert (
        client.post(
            "/api/chat",
            headers={**headers, "Idempotency-Key": "prepare-decline"},
            json={"message": "refund my order"},
        ).status_code
        == 200
    )
    declined = client.post(
        "/api/chat",
        headers={**headers, "Idempotency-Key": "decline"},
        json={"message": "do not confirm"},
    )
    assert declined.status_code == 200
    assert declined.json()["outcome"] == "action_declined"
    assert actions.calls == []
    rejected_confirm = client.post(
        "/api/chat",
        headers={**headers, "Idempotency-Key": "confirm-after-decline"},
        json={"message": "confirm refund"},
    )
    assert rejected_confirm.status_code == 409
    assert rejected_confirm.json() == {"detail": "Action confirmation conflict"}
    assert actions.calls == []
    assert agent.calls == 1

    conversations.action_pending = PendingActionReference(
        pending_action_id=pending.pending_action_id,
        source_turn_id="server-turn-source",
        source_trace_id="server-trace-source",
        conversation_id=f"server-conversation-{session_id}",
        session_id=session_id,
        user_subject="user-123",
        sandbox_id=None,
        action_type=pending.action_type,
        argument_commitment=pending.argument_commitment,
        order_id=pending.order_id,
        amount_minor=pending.amount_minor,
        currency=pending.currency,
        expires_at=pending.expires_at,
    )
    conversations.action_state = "PENDING"
    streamed = client.post(
        "/api/chat/stream",
        headers={**headers, "Idempotency-Key": "confirm-stream"},
        json={"message": "yes"},
    )
    assert streamed.status_code == 200
    assert streamed.text.index("event: action_receipt") < streamed.text.index("event: token")
    assert streamed.text.count("event: action_receipt") == 1
    assert streamed.text.count("event: done") == 1
    assert '"status":"REQUESTED"' in streamed.text


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


@pytest.mark.parametrize(
    ("status", "reason"),
    [
        (502, "invalid_commerce_response"),
        (503, "identity_unavailable"),
        (503, "commerce_timeout"),
        (503, "commerce_indeterminate"),
        (503, "commerce_unavailable"),
    ],
)
def test_chat_maps_sensitive_tool_faults_without_terminal_denial(
    status: int,
    reason: str,
) -> None:
    class FailingAgent:
        def run(self, **kwargs: object) -> AgentRunResult:
            del kwargs
            raise ToolBoundaryFailure(
                status_code=status,
                reason=reason,
                detail=(
                    "Invalid commerce tool response"
                    if status == 502
                    else "Commerce tool unavailable"
                ),
            )

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
            agent=FailingAgent(),
        )
    )

    response = client.post(
        "/api/chat",
        headers={
            "Authorization": f"Bearer {direct_token(private, 'current-key')}",
            "X-Session-Id": session_id,
            "Idempotency-Key": f"tool-fault-{reason}",
        },
        json={"message": "refund my order"},
    )

    assert response.status_code == status
    assert response.json() == {
        "detail": (
            "Invalid commerce tool response" if status == 502 else "Commerce tool unavailable"
        )
    }
    assert conversations.failures[-1][1] == reason


def test_chat_attributes_action_durable_truth_conflict_without_public_detail(
    caplog: pytest.LogCaptureFixture,
) -> None:
    class IntegrityConversationStore(MemoryConversationStore):
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
            raise ConversationIntegrityError("damaged action projection")

    private, public_jwk = key_fixture("current-key")
    sessions = MemorySessionStore()
    session_id = sessions.create("user-123")
    response = TestClient(
        create_app(
            settings(),
            validator=DirectJwtValidator(settings(), CountingJwksSource([public_jwk])),
            sessions=sessions,
            conversations=IntegrityConversationStore(sessions),
            agent=MemoryAgent(),
        )
    ).post(
        "/api/chat",
        headers={
            "Authorization": f"Bearer {direct_token(private, 'current-key')}",
            "X-Session-Id": session_id,
            "Idempotency-Key": "damaged-action",
        },
        json={"message": "confirm refund"},
    )

    assert response.status_code == 409
    assert response.json() == {"detail": "Conversation evidence conflict"}
    assert "reason_code=CONVERSATION_ACTION_DURABLE_TRUTH_INCONSISTENT" in caplog.text
    assert "damaged action projection" not in response.text
