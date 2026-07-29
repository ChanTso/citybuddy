"""Bounded evaluation-only projection of authoritative support evidence."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Literal, Protocol, cast

import pymysql
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field

from .actions import (
    ACTION_SOURCE_TURN_EVENTS_SQL,
    ActionJsonError,
    ActionReceiptPayload,
    ActionSourceTurnClosureError,
    action_argument_commitment,
    parse_canonical_action_timestamp,
    strict_json_object,
    validate_action_source_turn_closure,
)

MAX_EVIDENCE_EVENTS = 48
MAX_FEEDBACK_RECORDS = 8
MAX_RETRIEVAL_SOURCES = 3

TerminalOutcome = Literal[
    "completed",
    "budget_exhausted",
    "provider_denied",
    "retrieval_denied",
    "action_pending",
    "action_declined",
    "action_expired",
    "action_clarification",
    "action_completed",
    "failed",
]
EventKind = Literal[
    "USER_INPUT",
    "ROUTING_DECISION",
    "BUDGET_CHARGED",
    "CIRCUIT_OUTCOME",
    "MODEL_OUTCOME",
    "TOOL_LIFECYCLE",
    "TOOL_DENIED",
    "RETRIEVAL_DECISION",
    "ACTION_PREPARED",
    "ACTION_DECLINED",
    "ACTION_EXPIRED",
    "ACTION_RECEIPT",
    "AGENT_OUTCOME",
    "ASSISTANT_RESPONSE",
    "TURN_COMPLETED",
    "TURN_FAILED",
]

_TERMINAL_OUTCOMES = {
    "completed",
    "budget_exhausted",
    "provider_denied",
    "retrieval_denied",
    "action_pending",
    "action_declined",
    "action_expired",
    "action_clarification",
    "action_completed",
}
_EVENT_TYPES = {
    "USER_INPUT",
    "ROUTING_DECISION",
    "BUDGET_CHARGED",
    "CIRCUIT_OUTCOME",
    "MODEL_OUTCOME",
    "TOOL_LIFECYCLE",
    "TOOL_DENIED",
    "RETRIEVAL_DECISION",
    "ACTION_PREPARED",
    "ACTION_DECLINED",
    "ACTION_EXPIRED",
    "ACTION_RECEIPT",
    "AGENT_OUTCOME",
    "ASSISTANT_RESPONSE",
    "TURN_COMPLETED",
    "TURN_FAILED",
}
_ATTEMPT_KINDS = {"model_http", "reranker_http", "identity_http", "tool_http"}
_CIRCUIT_STATES = {"open", "opened", "probe-rejected", "half-open", "closed"}
_MODEL_RESULTS = {
    "ok",
    "denied",
    "transient",
    "rerank-ok",
    "rerank-denied",
    "rerank-transient",
}


class EvaluationEvidenceNotFound(Exception):
    """The requested trace is not associated with the supplied sandbox."""


class EvaluationEvidenceInvalid(Exception):
    """Persisted evidence is incomplete, conflicting, or outside the safe schema."""


class ActionEvidenceIntegrityError(EvaluationEvidenceInvalid):
    """Agent action evidence disagrees with its durable projection."""


class EvidenceEventResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    sequence: int = Field(ge=1, le=MAX_EVIDENCE_EVENTS)
    event_kind: EventKind = Field(serialization_alias="eventKind")
    outcome: str | None = Field(default=None, min_length=1, max_length=32)
    reference: str | None = Field(default=None, min_length=1, max_length=128)
    attempt: int | None = Field(default=None, ge=1, le=32)
    attempt_limit: int | None = Field(default=None, serialization_alias="attemptLimit", ge=1, le=32)
    occurred_at: AwareDatetime = Field(serialization_alias="occurredAt")


class RetrievalSourceResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    rank: int = Field(ge=1, le=MAX_RETRIEVAL_SOURCES)
    source_id: str = Field(serialization_alias="sourceId", min_length=1, max_length=128)
    chunk_id: str = Field(serialization_alias="chunkId", min_length=1, max_length=128)
    source_version: int = Field(serialization_alias="sourceVersion", ge=1)
    doc_type: Literal["faq", "product"] = Field(serialization_alias="docType")


class RetrievalDecisionResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    outcome: Literal["SUFFICIENT", "INSUFFICIENT"]
    reason: Literal[
        "sufficient",
        "empty_candidates",
        "below_threshold",
        "ambiguous_margin",
        "reranker_denied",
    ]
    index_version: str = Field(serialization_alias="indexVersion", min_length=1, max_length=64)
    calibration_version: str = Field(
        serialization_alias="calibrationVersion", min_length=1, max_length=64
    )
    candidate_count: int = Field(serialization_alias="candidateCount", ge=0, le=5)
    evidence_count: int = Field(serialization_alias="evidenceCount", ge=0, le=3)
    sources: tuple[RetrievalSourceResponse, ...] = Field(max_length=MAX_RETRIEVAL_SOURCES)


class FeedbackEvidenceResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    rating: Literal["POSITIVE", "NEGATIVE"]
    occurred_at: AwareDatetime = Field(serialization_alias="occurredAt")


class EvaluationEvidenceResponse(BaseModel):
    model_config = ConfigDict(frozen=True)

    schema_version: Literal["agent-evidence-v1"] = Field(serialization_alias="schemaVersion")
    trace_id: str = Field(serialization_alias="traceId", min_length=36, max_length=36)
    session_id: str = Field(serialization_alias="sessionId", min_length=1, max_length=64)
    turn_id: str = Field(serialization_alias="turnId", min_length=36, max_length=36)
    terminal_outcome: TerminalOutcome = Field(serialization_alias="terminalOutcome")
    events: tuple[EvidenceEventResponse, ...] = Field(min_length=2, max_length=MAX_EVIDENCE_EVENTS)
    retrieval: RetrievalDecisionResponse | None = None
    feedback: tuple[FeedbackEvidenceResponse, ...] = Field(max_length=MAX_FEEDBACK_RECORDS)


class EvaluationEvidenceStore(Protocol):
    def load(self, trace_id: str, sandbox_id: str) -> EvaluationEvidenceResponse: ...


class EvaluationConnectionSettings(Protocol):
    mysql_host: str
    mysql_port: int
    mysql_password: str


class MysqlEvaluationEvidenceStore:
    """Read one consistent, bounded projection from agent-owned durable truth."""

    def __init__(self, settings: EvaluationConnectionSettings) -> None:
        self._settings = settings

    def load(self, trace_id: str, sandbox_id: str) -> EvaluationEvidenceResponse:
        with self._connect() as connection:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT turn_record.trace_id, turn_record.turn_id, "
                        "turn_record.session_id, turn_record.user_subject, "
                        "turn_record.state, turn_record.outcome, "
                        "turn_record.conversation_id "
                        "FROM support_turn turn_record "
                        "JOIN support_conversation conversation "
                        "ON conversation.conversation_id = turn_record.conversation_id "
                        "AND conversation.session_id = turn_record.session_id "
                        "AND conversation.user_subject = turn_record.user_subject "
                        "JOIN support_session session_record "
                        "ON session_record.session_id = turn_record.session_id "
                        "AND session_record.user_subject = turn_record.user_subject "
                        "WHERE turn_record.trace_id = %s AND session_record.sandbox_id = %s "
                        "LIMIT 2",
                        (trace_id, sandbox_id),
                    )
                    turns = cursor.fetchall()
                    if len(turns) != 1:
                        raise EvaluationEvidenceNotFound
                    turn = turns[0]
                    terminal_outcome = self._terminal_outcome(turn[4], turn[5])
                    action_root = self._turn_has_action_root(
                        cursor,
                        turn_id=str(turn[1]),
                        terminal_outcome=terminal_outcome,
                    )
                    try:
                        events = self._load_events(
                            cursor,
                            trace_id=trace_id,
                            turn_id=str(turn[1]),
                            session_id=str(turn[2]),
                            subject=str(turn[3]),
                            terminal_outcome=terminal_outcome,
                        )
                        self._validate_action_truth(
                            cursor,
                            turn_id=str(turn[1]),
                            trace_id=str(turn[0]),
                            conversation_id=str(turn[6]),
                            session_id=str(turn[2]),
                            subject=str(turn[3]),
                            sandbox_id=sandbox_id,
                            terminal_outcome=terminal_outcome,
                            events=events,
                        )
                    except EvaluationEvidenceInvalid as exception:
                        if action_root:
                            raise ActionEvidenceIntegrityError from exception
                        raise
                    retrieval = self._load_retrieval(
                        cursor,
                        trace_id=trace_id,
                        turn_id=str(turn[1]),
                        session_id=str(turn[2]),
                        subject=str(turn[3]),
                        events=events,
                    )
                    feedback = self._load_feedback(
                        cursor,
                        trace_id=trace_id,
                        session_id=str(turn[2]),
                        subject=str(turn[3]),
                    )
                connection.commit()
            except Exception:
                connection.rollback()
                raise
        return EvaluationEvidenceResponse(
            schema_version="agent-evidence-v1",
            trace_id=str(turn[0]),
            session_id=str(turn[2]),
            turn_id=str(turn[1]),
            terminal_outcome=terminal_outcome,
            events=events,
            retrieval=retrieval,
            feedback=feedback,
        )

    @staticmethod
    def _turn_has_action_root(
        cursor: pymysql.cursors.Cursor,
        *,
        turn_id: str,
        terminal_outcome: TerminalOutcome,
    ) -> bool:
        if terminal_outcome in {
            "action_pending",
            "action_declined",
            "action_expired",
            "action_completed",
        }:
            return True
        cursor.execute(
            "SELECT pending_action_id FROM pending_action_reference "
            "WHERE source_turn_id = %s OR confirmation_turn_id = %s LIMIT 2",
            (turn_id, turn_id),
        )
        pending_roots = cursor.fetchall()
        cursor.execute(
            "SELECT receipt_id FROM action_receipt_projection "
            "WHERE confirmation_turn_id = %s LIMIT 2",
            (turn_id,),
        )
        return bool(pending_roots or cursor.fetchall())

    @staticmethod
    def _terminal_outcome(state: object, outcome: object) -> TerminalOutcome:
        if state == "FAILED" and outcome is None:
            return "failed"
        if state == "COMPLETED" and outcome in _TERMINAL_OUTCOMES:
            return outcome  # type: ignore[return-value]
        raise EvaluationEvidenceInvalid

    def _load_events(
        self,
        cursor: pymysql.cursors.Cursor,
        *,
        trace_id: str,
        turn_id: str,
        session_id: str,
        subject: str,
        terminal_outcome: TerminalOutcome,
    ) -> tuple[EvidenceEventResponse, ...]:
        cursor.execute(
            "SELECT sequence, event_type, payload_json, created_at, turn_id, session_id, "
            "user_subject FROM support_event WHERE trace_id = %s "
            "ORDER BY sequence LIMIT %s",
            (trace_id, MAX_EVIDENCE_EVENTS + 1),
        )
        rows = cursor.fetchall()
        if len(rows) < 2 or len(rows) > MAX_EVIDENCE_EVENTS:
            raise EvaluationEvidenceInvalid
        events: list[EvidenceEventResponse] = []
        for expected, row in enumerate(rows, start=1):
            if (
                row[0] != expected
                or row[4] != turn_id
                or row[5] != session_id
                or row[6] != subject
                or row[1] not in _EVENT_TYPES
                or not isinstance(row[3], datetime)
            ):
                raise EvaluationEvidenceInvalid
            events.append(
                self._project_event(
                    expected,
                    str(row[1]),
                    row[2],
                    self._utc_timestamp(row[3]),
                )
            )
        if events[0].event_kind != "USER_INPUT" or events[0].outcome != "accepted":
            raise EvaluationEvidenceInvalid
        expected_terminal = "TURN_FAILED" if terminal_outcome == "failed" else "TURN_COMPLETED"
        if events[-1].event_kind != expected_terminal:
            raise EvaluationEvidenceInvalid
        if terminal_outcome != "failed" and events[-1].outcome != terminal_outcome:
            raise EvaluationEvidenceInvalid
        self._validate_lifecycle(events, terminal_outcome)
        return tuple(events)

    @staticmethod
    def _validate_lifecycle(
        events: list[EvidenceEventResponse], terminal_outcome: TerminalOutcome
    ) -> None:
        if any(event.event_kind in {"TURN_COMPLETED", "TURN_FAILED"} for event in events[:-1]):
            raise EvaluationEvidenceInvalid
        if terminal_outcome == "failed":
            if (
                events[0].event_kind != "USER_INPUT"
                or events[-1].event_kind != "TURN_FAILED"
                or any(event.event_kind != "BUDGET_CHARGED" for event in events[1:-1])
            ):
                raise EvaluationEvidenceInvalid
            return
        if [event.event_kind for event in events[-3:]] != [
            "AGENT_OUTCOME",
            "ASSISTANT_RESPONSE",
            "TURN_COMPLETED",
        ]:
            raise EvaluationEvidenceInvalid
        for event in events:
            if event.event_kind in {"AGENT_OUTCOME", "ASSISTANT_RESPONSE", "TURN_COMPLETED"}:
                if event.outcome != terminal_outcome:
                    raise EvaluationEvidenceInvalid

    @staticmethod
    def _payload(value: object) -> dict[str, object]:
        try:
            decoded = strict_json_object(value.encode("utf-8")) if isinstance(value, str) else value
        except ActionJsonError as exception:
            raise EvaluationEvidenceInvalid from exception
        if not isinstance(decoded, dict):
            raise EvaluationEvidenceInvalid
        return decoded

    def _project_event(
        self, sequence: int, event_type: str, raw_payload: object, occurred_at: datetime
    ) -> EvidenceEventResponse:
        payload = self._payload(raw_payload)
        outcome: str | None = None
        reference: str | None = None
        attempt: int | None = None
        attempt_limit: int | None = None
        if event_type == "USER_INPUT":
            if payload.get("accepted") is not True:
                raise EvaluationEvidenceInvalid
            outcome = "accepted"
        elif event_type == "ROUTING_DECISION":
            tier = payload.get("tier")
            limit = payload.get("attemptLimit")
            if tier != "standard" or not self._bounded_int(limit, 1, 32):
                raise EvaluationEvidenceInvalid
            outcome = str(tier)
            attempt_limit = cast(int, limit)
        elif event_type == "BUDGET_CHARGED":
            attempt_value = payload.get("attempt")
            limit = payload.get("limit")
            kind = payload.get("kind")
            if (
                not self._bounded_int(attempt_value, 1, 32)
                or not self._bounded_int(limit, 1, 32)
                or cast(int, attempt_value) > cast(int, limit)
                or kind not in _ATTEMPT_KINDS
            ):
                raise EvaluationEvidenceInvalid
            outcome = str(kind)
            attempt = cast(int, attempt_value)
            attempt_limit = cast(int, limit)
        elif event_type == "CIRCUIT_OUTCOME":
            state = payload.get("state")
            if state not in _CIRCUIT_STATES:
                raise EvaluationEvidenceInvalid
            outcome = str(state)
        elif event_type == "MODEL_OUTCOME":
            result = payload.get("result")
            if result not in _MODEL_RESULTS:
                raise EvaluationEvidenceInvalid
            outcome = str(result)
        elif event_type == "TOOL_LIFECYCLE":
            tool = payload.get("tool")
            state = payload.get("state")
            if not self._bounded_string(tool, 64) or state not in {"requested", "succeeded"}:
                raise EvaluationEvidenceInvalid
            outcome = str(state)
            reference = str(tool)
        elif event_type == "TOOL_DENIED":
            tool = payload.get("tool")
            if not self._bounded_string(tool, 64) or payload.get("outcome") != "deny_with_feedback":
                raise EvaluationEvidenceInvalid
            outcome = "denied"
            reference = str(tool)
        elif event_type == "RETRIEVAL_DECISION":
            index_version = payload.get("indexVersion")
            decision_outcome = payload.get("outcome")
            if not self._bounded_string(index_version, 64) or decision_outcome not in {
                "SUFFICIENT",
                "INSUFFICIENT",
            }:
                raise EvaluationEvidenceInvalid
            outcome = str(decision_outcome)
            reference = str(index_version)
        elif event_type == "ACTION_PREPARED":
            pending_action_id = payload.get("pendingActionId")
            action_type = payload.get("actionType")
            commitment = payload.get("argumentCommitment")
            try:
                parse_canonical_action_timestamp(payload.get("expiresAt"))
            except ValueError:
                raise EvaluationEvidenceInvalid from None
            if (
                set(payload) != {"pendingActionId", "actionType", "argumentCommitment", "expiresAt"}
                or not self._canonical_uuid(pending_action_id)
                or action_type != "REFUND_REQUEST"
                or not self._commitment(commitment)
            ):
                raise EvaluationEvidenceInvalid
            outcome = "prepared"
            reference = str(commitment)
        elif event_type == "ACTION_DECLINED":
            if (
                set(payload) != {"pendingActionId", "outcome"}
                or not self._canonical_uuid(payload.get("pendingActionId"))
                or payload.get("outcome") != "declined"
            ):
                raise EvaluationEvidenceInvalid
            outcome = "declined"
            reference = str(payload["pendingActionId"])
        elif event_type == "ACTION_EXPIRED":
            if (
                set(payload) != {"pendingActionId", "outcome"}
                or not self._canonical_uuid(payload.get("pendingActionId"))
                or payload.get("outcome") != "expired"
            ):
                raise EvaluationEvidenceInvalid
            outcome = "expired"
            reference = str(payload["pendingActionId"])
        elif event_type == "ACTION_RECEIPT":
            receipt_id = payload.get("receiptId")
            pending_action_id = payload.get("pendingActionId")
            status = payload.get("status")
            commitment = payload.get("receiptCommitment")
            if (
                set(payload) != {"receiptId", "pendingActionId", "status", "receiptCommitment"}
                or not self._canonical_uuid(receipt_id)
                or not self._canonical_uuid(pending_action_id)
                or status != "REQUESTED"
                or not self._commitment(commitment)
            ):
                raise EvaluationEvidenceInvalid
            outcome = str(status)
            reference = str(receipt_id)
        elif event_type in {"AGENT_OUTCOME", "ASSISTANT_RESPONSE", "TURN_COMPLETED"}:
            value = payload.get("outcome")
            if value not in _TERMINAL_OUTCOMES:
                raise EvaluationEvidenceInvalid
            outcome = str(value)
        elif event_type == "TURN_FAILED":
            if not self._bounded_string(payload.get("code"), 64):
                raise EvaluationEvidenceInvalid
            outcome = "failed"
        else:
            raise EvaluationEvidenceInvalid
        return EvidenceEventResponse(
            sequence=sequence,
            event_kind=event_type,  # type: ignore[arg-type]
            outcome=outcome,
            reference=reference,
            attempt=attempt,
            attempt_limit=attempt_limit,
            occurred_at=occurred_at,
        )

    def _validate_action_truth(
        self,
        cursor: pymysql.cursors.Cursor,
        *,
        turn_id: str,
        trace_id: str,
        conversation_id: str,
        session_id: str,
        subject: str,
        sandbox_id: str,
        terminal_outcome: TerminalOutcome,
        events: tuple[EvidenceEventResponse, ...],
    ) -> None:
        cursor.execute(
            "SELECT pending_action_id, source_turn_id, source_trace_id, conversation_id, "
            "session_id, user_subject, sandbox_id, action_type, argument_commitment, "
            "order_id, amount_minor, currency, state, expires_at, resolved_at, "
            "confirmation_turn_id, confirmation_trace_id "
            "FROM pending_action_reference WHERE source_turn_id = %s LIMIT 2",
            (turn_id,),
        )
        pending_rows = cursor.fetchall()
        prepared_events = [event for event in events if event.event_kind == "ACTION_PREPARED"]
        resolution_events = [
            event for event in events if event.event_kind in {"ACTION_DECLINED", "ACTION_EXPIRED"}
        ]
        if terminal_outcome == "action_pending":
            if len(pending_rows) != 1 or len(prepared_events) != 1:
                raise EvaluationEvidenceInvalid
            pending = pending_rows[0]
            self._validate_pending_truth_row(
                cursor,
                pending=pending,
                session_id=session_id,
                subject=subject,
                sandbox_id=sandbox_id,
            )
            if (
                tuple(pending[1:7])
                != (turn_id, trace_id, conversation_id, session_id, subject, sandbox_id)
                or prepared_events[0].outcome != "prepared"
                or prepared_events[0].reference != pending[8]
            ):
                raise EvaluationEvidenceInvalid
        elif terminal_outcome in {"action_declined", "action_expired"}:
            if pending_rows or prepared_events or len(resolution_events) != 1:
                raise EvaluationEvidenceInvalid
            expected_state, expected_outcome = {
                "action_declined": ("DECLINED", "declined"),
                "action_expired": ("EXPIRED", "expired"),
            }[terminal_outcome]
            pending_action_id = resolution_events[0].reference
            if (
                not self._canonical_uuid(pending_action_id)
                or resolution_events[0].outcome != expected_outcome
            ):
                raise EvaluationEvidenceInvalid
            cursor.execute(
                "SELECT pending_action_id, source_turn_id, source_trace_id, conversation_id, "
                "session_id, user_subject, sandbox_id, action_type, argument_commitment, "
                "order_id, amount_minor, currency, state, expires_at, resolved_at, "
                "confirmation_turn_id, confirmation_trace_id "
                "FROM pending_action_reference WHERE pending_action_id = %s LIMIT 2",
                (pending_action_id,),
            )
            resolved_rows = cursor.fetchall()
            if len(resolved_rows) != 1 or resolved_rows[0][12] != expected_state:
                raise EvaluationEvidenceInvalid
            self._validate_pending_truth_row(
                cursor,
                pending=resolved_rows[0],
                session_id=session_id,
                subject=subject,
                sandbox_id=sandbox_id,
            )
        elif pending_rows or prepared_events or resolution_events:
            raise EvaluationEvidenceInvalid

        receipt_events = [event for event in events if event.event_kind == "ACTION_RECEIPT"]
        if terminal_outcome != "action_completed":
            cursor.execute(
                "SELECT receipt_id FROM action_receipt_projection "
                "WHERE confirmation_turn_id = %s LIMIT 2",
                (turn_id,),
            )
            if cursor.fetchall() or receipt_events:
                raise EvaluationEvidenceInvalid
            return
        if len(receipt_events) != 1:
            raise EvaluationEvidenceInvalid
        cursor.execute(
            "SELECT pending_action_id FROM action_receipt_projection "
            "WHERE confirmation_turn_id = %s LIMIT 2",
            (turn_id,),
        )
        receipt_owner_rows = cursor.fetchall()
        if len(receipt_owner_rows) != 1:
            raise EvaluationEvidenceInvalid
        pending_action_id = str(receipt_owner_rows[0][0])
        receipt, projection = self._load_valid_receipt_projection(
            cursor,
            pending_action_id=str(pending_action_id),
            session_id=session_id,
            subject=subject,
            sandbox_id=sandbox_id,
        )
        if (
            receipt_events[0].outcome != receipt.status
            or receipt_events[0].reference != receipt.receipt_id
            or (
                projection[3] == turn_id
                and (projection[4] != trace_id or receipt_events[0].sequence != projection[18])
            )
        ):
            raise EvaluationEvidenceInvalid
        cursor.execute(
            "SELECT pending_action_id, source_turn_id, source_trace_id, conversation_id, "
            "session_id, user_subject, sandbox_id, action_type, argument_commitment, "
            "order_id, amount_minor, currency, state, expires_at, resolved_at, "
            "confirmation_turn_id, confirmation_trace_id "
            "FROM pending_action_reference WHERE pending_action_id = %s LIMIT 2",
            (receipt.pending_action_id,),
        )
        owner_rows = cursor.fetchall()
        if len(owner_rows) != 1:
            raise EvaluationEvidenceInvalid
        owner = owner_rows[0]
        self._validate_pending_truth_row(
            cursor,
            pending=owner,
            session_id=session_id,
            subject=subject,
            sandbox_id=sandbox_id,
        )
        if (
            owner[7] != receipt.action_type
            or owner[8] != receipt.argument_commitment
            or tuple(owner[9:12]) != (receipt.order_id, receipt.amount_minor, receipt.currency)
            or owner[12] != "CONFIRMED"
            or tuple(owner[15:17]) != tuple(projection[3:5])
            or projection[2] != owner[1]
        ):
            raise EvaluationEvidenceInvalid

    def _validate_pending_truth_row(
        self,
        cursor: pymysql.cursors.Cursor,
        *,
        pending: tuple[object, ...],
        session_id: str,
        subject: str,
        sandbox_id: str,
    ) -> None:
        if (
            len(pending) != 17
            or not self._canonical_uuid(pending[0])
            or tuple(pending[4:7]) != (session_id, subject, sandbox_id)
            or pending[7] != "REFUND_REQUEST"
            or not isinstance(pending[8], str)
            or not isinstance(pending[9], str)
            or type(pending[10]) is not int
            or not isinstance(pending[11], str)
            or pending[12] not in {"PENDING", "CONFIRMING", "DECLINED", "EXPIRED", "CONFIRMED"}
            or (pending[12] in {"PENDING", "CONFIRMING"}) != (pending[14] is None)
            or not isinstance(pending[13], datetime)
        ):
            raise EvaluationEvidenceInvalid
        confirmation_binding = tuple(pending[15:17])
        if (
            pending[12] == "PENDING"
            and confirmation_binding != (None, None)
            or pending[12] in {"CONFIRMING", "CONFIRMED"}
            and not all(isinstance(value, str) for value in confirmation_binding)
            or pending[12] in {"DECLINED", "EXPIRED"}
            and confirmation_binding != (None, None)
        ):
            raise EvaluationEvidenceInvalid
        try:
            commitment = action_argument_commitment(
                str(pending[7]),
                pending[9],
                pending[10],
                pending[11],
            )
        except (TypeError, ValueError):
            raise EvaluationEvidenceInvalid from None
        if pending[8] != commitment:
            raise EvaluationEvidenceInvalid
        cursor.execute(
            "SELECT trace_id, conversation_id, session_id, user_subject, state, outcome "
            "FROM support_turn WHERE turn_id = %s LIMIT 2",
            (pending[1],),
        )
        source_rows = cursor.fetchall()
        if len(source_rows) != 1 or tuple(source_rows[0]) != (
            pending[2],
            pending[3],
            session_id,
            subject,
            "COMPLETED",
            "action_pending",
        ):
            raise EvaluationEvidenceInvalid
        cursor.execute(ACTION_SOURCE_TURN_EVENTS_SQL, (pending[1],))
        prepared_rows = cursor.fetchall()
        persisted_expiry = pending[13]
        if persisted_expiry.tzinfo is None:
            persisted_expiry = persisted_expiry.replace(tzinfo=UTC)
        try:
            validate_action_source_turn_closure(
                prepared_rows,
                expected_trace_id=str(pending[2]),
                expected_session_id=session_id,
                expected_user_subject=subject,
                pending_action_id=str(pending[0]),
                action_type=str(pending[7]),
                argument_commitment=str(pending[8]),
                expires_at=persisted_expiry,
            )
        except ActionSourceTurnClosureError:
            raise EvaluationEvidenceInvalid from None
        cursor.execute(
            "SELECT pending_action_id FROM action_receipt_projection "
            "WHERE pending_action_id = %s LIMIT 2",
            (pending[0],),
        )
        projection_rows = cursor.fetchall()
        if pending[12] == "CONFIRMED":
            if len(projection_rows) != 1:
                raise EvaluationEvidenceInvalid
            receipt, projection = self._load_valid_receipt_projection(
                cursor,
                pending_action_id=str(pending[0]),
                session_id=session_id,
                subject=subject,
                sandbox_id=sandbox_id,
            )
            if (
                projection[2] != pending[1]
                or tuple(projection[3:5]) != confirmation_binding
                or receipt.action_type != pending[7]
                or receipt.argument_commitment != pending[8]
                or tuple((receipt.order_id, receipt.amount_minor, receipt.currency))
                != tuple(pending[9:12])
            ):
                raise EvaluationEvidenceInvalid
        elif projection_rows:
            raise EvaluationEvidenceInvalid
        if pending[12] == "CONFIRMING":
            cursor.execute(
                "SELECT trace_id, session_id, user_subject, state "
                "FROM support_turn WHERE turn_id = %s LIMIT 2",
                (confirmation_binding[0],),
            )
            confirmation_rows = cursor.fetchall()
            if len(confirmation_rows) != 1 or tuple(confirmation_rows[0]) != (
                confirmation_binding[1],
                session_id,
                subject,
                "PROCESSING",
            ):
                raise EvaluationEvidenceInvalid

    def _load_valid_receipt_projection(
        self,
        cursor: pymysql.cursors.Cursor,
        *,
        pending_action_id: str,
        session_id: str,
        subject: str,
        sandbox_id: str,
    ) -> tuple[ActionReceiptPayload, tuple[object, ...]]:
        cursor.execute(
            "SELECT receipt_id, pending_action_id, source_turn_id, confirmation_turn_id, "
            "confirmation_trace_id, session_id, user_subject, sandbox_id, action_type, "
            "argument_commitment, status, order_id, refund_id, resource_version, amount_minor, "
            "currency, committed_at, receipt_commitment, published_event_sequence "
            "FROM action_receipt_projection WHERE pending_action_id = %s LIMIT 2",
            (pending_action_id,),
        )
        projection_rows = cursor.fetchall()
        if len(projection_rows) != 1:
            raise EvaluationEvidenceInvalid
        projection = projection_rows[0]
        committed_at = projection[16]
        if not isinstance(committed_at, datetime):
            raise EvaluationEvidenceInvalid
        if committed_at.tzinfo is None:
            committed_at = committed_at.replace(tzinfo=UTC)
        try:
            receipt = ActionReceiptPayload.model_validate(
                {
                    "receiptId": str(projection[0]),
                    "pendingActionId": str(projection[1]),
                    "actionType": str(projection[8]),
                    "status": str(projection[10]),
                    "orderId": str(projection[11]),
                    "refundId": str(projection[12]),
                    "resourceVersion": int(projection[13]),
                    "amountMinor": int(projection[14]),
                    "currency": str(projection[15]),
                    "committedAt": committed_at,
                    "replayed": True,
                }
            )
        except (TypeError, ValueError):
            raise EvaluationEvidenceInvalid from None
        if (
            tuple(projection[5:8]) != (session_id, subject, sandbox_id)
            or projection[9] != receipt.argument_commitment
            or projection[17] != receipt.receipt_commitment
        ):
            raise EvaluationEvidenceInvalid
        cursor.execute(
            "SELECT trace_id, session_id, user_subject, state, outcome "
            "FROM support_turn WHERE turn_id = %s LIMIT 2",
            (projection[3],),
        )
        confirmation_rows = cursor.fetchall()
        if len(confirmation_rows) != 1 or tuple(confirmation_rows[0]) != (
            projection[4],
            session_id,
            subject,
            "COMPLETED",
            "action_completed",
        ):
            raise EvaluationEvidenceInvalid
        cursor.execute(
            "SELECT trace_id, session_id, user_subject, event_type, payload_json "
            "FROM support_event WHERE turn_id = %s AND sequence = %s LIMIT 2",
            (projection[3], projection[18]),
        )
        event_rows = cursor.fetchall()
        if (
            len(event_rows) != 1
            or tuple(event_rows[0][:4]) != (projection[4], session_id, subject, "ACTION_RECEIPT")
            or self._payload(event_rows[0][4])
            != {
                "receiptId": receipt.receipt_id,
                "pendingActionId": receipt.pending_action_id,
                "status": receipt.status,
                "receiptCommitment": receipt.receipt_commitment,
            }
        ):
            raise EvaluationEvidenceInvalid
        return receipt, projection

    @staticmethod
    def _canonical_uuid(value: object) -> bool:
        if not isinstance(value, str):
            return False
        try:
            return str(uuid.UUID(value)) == value
        except ValueError:
            return False

    @staticmethod
    def _commitment(value: object) -> bool:
        return (
            isinstance(value, str)
            and len(value) == 64
            and all(character in "0123456789abcdef" for character in value)
        )

    def _load_retrieval(
        self,
        cursor: pymysql.cursors.Cursor,
        *,
        trace_id: str,
        turn_id: str,
        session_id: str,
        subject: str,
        events: tuple[EvidenceEventResponse, ...],
    ) -> RetrievalDecisionResponse | None:
        cursor.execute(
            "SELECT decision_id, turn_id, session_id, user_subject, index_version, "
            "calibration_version, sufficiency_outcome, reason_code, candidate_count, "
            "evidence_count FROM retrieval_decision WHERE trace_id = %s LIMIT 2",
            (trace_id,),
        )
        rows = cursor.fetchall()
        event_count = sum(event.event_kind == "RETRIEVAL_DECISION" for event in events)
        if not rows:
            if event_count != 0:
                raise EvaluationEvidenceInvalid
            return None
        if len(rows) != 1 or event_count != 1:
            raise EvaluationEvidenceInvalid
        row = rows[0]
        if row[1] != turn_id or row[2] != session_id or row[3] != subject:
            raise EvaluationEvidenceInvalid
        retrieval_event = next(
            event for event in events if event.event_kind == "RETRIEVAL_DECISION"
        )
        if retrieval_event.outcome != row[6] or retrieval_event.reference != row[4]:
            raise EvaluationEvidenceInvalid
        evidence_count = row[9]
        if not self._bounded_int(evidence_count, 0, MAX_RETRIEVAL_SOURCES):
            raise EvaluationEvidenceInvalid
        cursor.execute(
            "SELECT evidence_rank, source_id, chunk_id, source_version, doc_type "
            "FROM retrieval_evidence WHERE decision_id = %s "
            "ORDER BY evidence_rank LIMIT %s",
            (row[0], MAX_RETRIEVAL_SOURCES + 1),
        )
        evidence_rows = cursor.fetchall()
        if len(evidence_rows) != evidence_count:
            raise EvaluationEvidenceInvalid
        sources: list[RetrievalSourceResponse] = []
        for expected, evidence in enumerate(evidence_rows, start=1):
            if (
                evidence[0] != expected
                or not self._bounded_string(evidence[1], 128)
                or not self._bounded_string(evidence[2], 128)
                or not self._bounded_int(evidence[3], 1, 2**63 - 1)
                or evidence[4] not in {"faq", "product"}
            ):
                raise EvaluationEvidenceInvalid
            sources.append(
                RetrievalSourceResponse(
                    rank=expected,
                    source_id=str(evidence[1]),
                    chunk_id=str(evidence[2]),
                    source_version=int(evidence[3]),
                    doc_type=evidence[4],
                )
            )
        try:
            return RetrievalDecisionResponse(
                outcome=row[6],
                reason=row[7],
                index_version=str(row[4]),
                calibration_version=str(row[5]),
                candidate_count=int(row[8]),
                evidence_count=int(evidence_count),
                sources=tuple(sources),
            )
        except (TypeError, ValueError) as exception:
            raise EvaluationEvidenceInvalid from exception

    def _load_feedback(
        self,
        cursor: pymysql.cursors.Cursor,
        *,
        trace_id: str,
        session_id: str,
        subject: str,
    ) -> tuple[FeedbackEvidenceResponse, ...]:
        cursor.execute(
            "SELECT rating, created_at, session_id, user_subject FROM support_feedback "
            "WHERE trace_id = %s ORDER BY created_at, feedback_id LIMIT %s",
            (trace_id, MAX_FEEDBACK_RECORDS + 1),
        )
        rows = cursor.fetchall()
        if len(rows) > MAX_FEEDBACK_RECORDS:
            raise EvaluationEvidenceInvalid
        feedback: list[FeedbackEvidenceResponse] = []
        for row in rows:
            if (
                row[0] not in {"POSITIVE", "NEGATIVE"}
                or not isinstance(row[1], datetime)
                or row[2] != session_id
                or row[3] != subject
            ):
                raise EvaluationEvidenceInvalid
            feedback.append(
                FeedbackEvidenceResponse(
                    rating=row[0],
                    occurred_at=self._utc_timestamp(row[1]),
                )
            )
        return tuple(feedback)

    @staticmethod
    def _utc_timestamp(value: datetime) -> datetime:
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)

    @staticmethod
    def _bounded_int(value: object, minimum: int, maximum: int) -> bool:
        return (
            isinstance(value, int) and not isinstance(value, bool) and minimum <= value <= maximum
        )

    @staticmethod
    def _bounded_string(value: object, maximum: int) -> bool:
        return isinstance(value, str) and 0 < len(value) <= maximum

    def _connect(self) -> pymysql.Connection[pymysql.cursors.Cursor]:
        return pymysql.connect(
            host=self._settings.mysql_host,
            port=self._settings.mysql_port,
            user="agent_app",
            password=self._settings.mysql_password,
            database="cs_db",
            autocommit=False,
            init_command="SET time_zone = '+00:00'",
        )
