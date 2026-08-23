"""Bounded evaluation-only projection of authoritative support evidence."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Literal, Protocol, cast

import pymysql
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field

from .actions import (
    ACTION_TURN_EVENTS_SQL,
    PENDING_ACTION_RESOLUTION_TURN_SQL,
    PENDING_ACTION_SOURCE_TURN_SQL,
    ActionEvidenceError,
    ActionJsonError,
    PendingActionReference,
    strict_json_object,
    validate_pending_action_events,
    validate_pending_action_reference,
    validate_pending_action_resolution,
    validate_resolved_action_events,
)
from .agent_control import TOOL_BOUNDARY_FAILURE_REASONS

MAX_EVIDENCE_EVENTS = 48
MAX_FEEDBACK_RECORDS = 8
MAX_RETRIEVAL_SOURCES = 3

TerminalOutcome = Literal[
    "completed",
    "budget_exhausted",
    "provider_denied",
    "retrieval_denied",
    "action_pending",
    "action_clarification",
    "action_declined",
    "action_expired",
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
    "action_clarification",
    "action_declined",
    "action_expired",
}
_ACTION_TERMINAL_OUTCOMES = {
    "action_pending",
    "action_clarification",
    "action_declined",
    "action_expired",
}
_ACTION_EVENT_TYPES = {
    "ACTION_PREPARED",
    "ACTION_DECLINED",
    "ACTION_EXPIRED",
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


class ActionEvaluationEvidenceInvalid(EvaluationEvidenceInvalid):
    """Persisted action evidence has an inconsistent durable closure."""


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
                        "turn_record.conversation_id, conversation.conversation_id, "
                        "conversation.session_id, conversation.user_subject, "
                        "session_record.user_subject "
                        "FROM support_turn turn_record "
                        "LEFT JOIN support_conversation conversation "
                        "ON conversation.conversation_id = turn_record.conversation_id "
                        "JOIN support_session session_record "
                        "ON session_record.session_id = turn_record.session_id "
                        "WHERE turn_record.trace_id = %s AND session_record.sandbox_id = %s "
                        "LIMIT 2",
                        (trace_id, sandbox_id),
                    )
                    turns = cursor.fetchall()
                    if len(turns) != 1:
                        raise EvaluationEvidenceNotFound
                    turn = turns[0]
                    action_truth_present = self._action_truth_present(cursor, str(turn[1]))
                    terminal_outcome: TerminalOutcome | None = None
                    try:
                        terminal_outcome = self._terminal_outcome(turn[4], turn[5])
                        if tuple(turn[7:10]) != (turn[6], turn[2], turn[3]) or turn[10] != turn[3]:
                            raise EvaluationEvidenceInvalid
                        events = self._load_events(
                            cursor,
                            trace_id=trace_id,
                            turn_id=str(turn[1]),
                            session_id=str(turn[2]),
                            subject=str(turn[3]),
                            sandbox_id=sandbox_id,
                            conversation_id=str(turn[6]),
                            terminal_outcome=terminal_outcome,
                        )
                    except EvaluationEvidenceInvalid as exception:
                        if action_truth_present or terminal_outcome in _ACTION_TERMINAL_OUTCOMES:
                            raise ActionEvaluationEvidenceInvalid from exception
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
    def _action_truth_present(cursor: pymysql.cursors.Cursor, turn_id: str) -> bool:
        cursor.execute(
            "SELECT event_type FROM support_event WHERE turn_id = %s ORDER BY sequence LIMIT %s",
            (turn_id, MAX_EVIDENCE_EVENTS + 1),
        )
        event_rows = cursor.fetchall()
        cursor.execute(
            "SELECT pending_action_id FROM pending_action_reference "
            "WHERE source_turn_id = %s OR resolution_turn_id = %s LIMIT 2",
            (turn_id, turn_id),
        )
        reference_rows = cursor.fetchall()
        return bool(reference_rows) or any(
            row and row[0] in _ACTION_EVENT_TYPES for row in event_rows
        )

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
        sandbox_id: str,
        conversation_id: str,
        terminal_outcome: TerminalOutcome,
    ) -> tuple[EvidenceEventResponse, ...]:
        cursor.execute(
            "SELECT event_id, trace_id, session_id, user_subject, sequence, event_type, "
            "IF(OCTET_LENGTH(payload_json) <= 4096, payload_json, NULL), "
            "created_at, turn_id FROM support_event WHERE turn_id = %s "
            "ORDER BY sequence LIMIT %s",
            (turn_id, MAX_EVIDENCE_EVENTS + 1),
        )
        rows = cursor.fetchall()
        if len(rows) < 2 or len(rows) > MAX_EVIDENCE_EVENTS:
            raise EvaluationEvidenceInvalid
        events: list[EvidenceEventResponse] = []
        for expected, row in enumerate(rows, start=1):
            if (
                row[1] != trace_id
                or row[2] != session_id
                or row[3] != subject
                or row[4] != expected
                or row[5] not in _EVENT_TYPES
                or not isinstance(row[7], datetime)
                or row[8] != turn_id
            ):
                raise EvaluationEvidenceInvalid
            events.append(
                self._project_event(
                    expected,
                    str(row[5]),
                    row[6],
                    self._utc_timestamp(row[7]),
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
        self._validate_action_truth(
            cursor,
            rows=rows,
            trace_id=trace_id,
            turn_id=turn_id,
            session_id=session_id,
            subject=subject,
            sandbox_id=sandbox_id,
            conversation_id=conversation_id,
            terminal_outcome=terminal_outcome,
        )
        return tuple(events)

    @staticmethod
    def _validate_lifecycle(
        events: list[EvidenceEventResponse], terminal_outcome: TerminalOutcome
    ) -> None:
        if any(event.event_kind in {"TURN_COMPLETED", "TURN_FAILED"} for event in events[:-1]):
            raise EvaluationEvidenceInvalid
        if terminal_outcome == "failed":
            if [event.event_kind for event in events] != ["USER_INPUT", "TURN_FAILED"]:
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
            decoded = json.loads(value) if isinstance(value, str) else value
        except json.JSONDecodeError as exception:
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
            producer = payload.get("producer")
            if (
                not self._bounded_string(tool, 64)
                or payload.get("outcome") != "deny_with_feedback"
                or (
                    producer is not None
                    and (
                        not isinstance(producer, str)
                        or producer not in TOOL_BOUNDARY_FAILURE_REASONS
                    )
                )
            ):
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
            target_version = payload.get("targetVersion")
            if (
                not self._bounded_string(pending_action_id, 36)
                or payload.get("actionType") != "REFUND_REQUEST"
                or not self._bounded_string(payload.get("argumentCommitment"), 64)
                or type(target_version) is not int
                or target_version < 1
                or not self._bounded_string(payload.get("expiresAt"), 27)
            ):
                raise EvaluationEvidenceInvalid
            outcome = "prepared"
            reference = str(pending_action_id)
        elif event_type in {"ACTION_DECLINED", "ACTION_EXPIRED"}:
            pending_action_id = payload.get("pendingActionId")
            expected = "declined" if event_type == "ACTION_DECLINED" else "expired"
            if (
                not self._bounded_string(pending_action_id, 36)
                or payload.get("outcome") != expected
            ):
                raise EvaluationEvidenceInvalid
            outcome = expected
            reference = str(pending_action_id)
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
        rows: tuple[tuple[object, ...], ...],
        trace_id: str,
        turn_id: str,
        session_id: str,
        subject: str,
        sandbox_id: str,
        conversation_id: str,
        terminal_outcome: TerminalOutcome,
    ) -> None:
        has_action_event = any(
            row[5] in {"ACTION_PREPARED", "ACTION_DECLINED", "ACTION_EXPIRED"} for row in rows
        )
        cursor.execute(
            "SELECT pending_action_id FROM pending_action_reference "
            "WHERE source_turn_id = %s LIMIT 2",
            (turn_id,),
        )
        source_references = cursor.fetchall()
        cursor.execute(
            "SELECT pending_action_id FROM pending_action_reference "
            "WHERE resolution_turn_id = %s LIMIT 2",
            (turn_id,),
        )
        resolution_references = cursor.fetchall()
        if terminal_outcome not in _ACTION_TERMINAL_OUTCOMES:
            if has_action_event or source_references or resolution_references:
                raise EvaluationEvidenceInvalid
            return
        if terminal_outcome == "action_clarification":
            if has_action_event or source_references or resolution_references:
                raise EvaluationEvidenceInvalid
            return
        if terminal_outcome == "action_pending" and (
            len(source_references) != 1 or resolution_references
        ):
            raise EvaluationEvidenceInvalid
        if terminal_outcome in {"action_declined", "action_expired"} and (
            source_references or len(resolution_references) != 1
        ):
            raise EvaluationEvidenceInvalid
        event_rows = [tuple(row[:7]) for row in rows]
        if terminal_outcome == "action_pending":
            cursor.execute(
                "SELECT pending_action_id, source_turn_id, source_trace_id, conversation_id, "
                "session_id, user_subject, sandbox_id, action_type, argument_commitment, "
                "order_id, target_version, amount_minor, currency, state, expires_at, resolved_at, "
                "resolution_turn_id, resolution_trace_id "
                "FROM pending_action_reference WHERE source_turn_id = %s LIMIT 2",
                (turn_id,),
            )
            pending_rows = cursor.fetchall()
            if len(pending_rows) != 1:
                raise EvaluationEvidenceInvalid
            pending, state = self._validated_pending_reference(
                cursor,
                pending_rows[0],
                expected_turn_id=turn_id,
                expected_trace_id=trace_id,
                expected_conversation_id=conversation_id,
                expected_session_id=session_id,
                expected_subject=subject,
                expected_sandbox_id=sandbox_id,
            )
            self._validate_pending_events(event_rows, pending, pending_rows[0][14])
            if state not in {"PENDING", "CONFIRMING"}:
                self._validate_pending_resolution(cursor, pending=pending, state=state)
            return
        try:
            action_payload = strict_json_object(str(rows[1][6]).encode("utf-8"))
        except (ActionJsonError, IndexError, TypeError) as exception:
            raise EvaluationEvidenceInvalid from exception
        pending_action_id = action_payload.get("pendingActionId")
        if not isinstance(pending_action_id, str):
            raise EvaluationEvidenceInvalid
        expected_state = "DECLINED" if terminal_outcome == "action_declined" else "EXPIRED"
        try:
            validate_resolved_action_events(
                event_rows,
                expected_trace_id=trace_id,
                expected_session_id=session_id,
                expected_user_subject=subject,
                pending_action_id=pending_action_id,
                outcome=terminal_outcome,  # type: ignore[arg-type]
            )
        except ActionEvidenceError as exception:
            raise EvaluationEvidenceInvalid from exception
        cursor.execute(
            "SELECT pending_action_id, source_turn_id, source_trace_id, conversation_id, "
            "session_id, user_subject, sandbox_id, action_type, argument_commitment, "
            "order_id, target_version, amount_minor, currency, state, expires_at, resolved_at, "
            "resolution_turn_id, resolution_trace_id "
            "FROM pending_action_reference WHERE pending_action_id = %s LIMIT 2",
            (pending_action_id,),
        )
        pending_rows = cursor.fetchall()
        if len(pending_rows) != 1:
            raise EvaluationEvidenceInvalid
        pending, state = self._validated_pending_reference(
            cursor,
            pending_rows[0],
            expected_session_id=session_id,
            expected_subject=subject,
            expected_sandbox_id=sandbox_id,
        )
        if state != expected_state or pending_rows[0][15] is None:
            raise EvaluationEvidenceInvalid
        if pending.resolution_turn_id != turn_id or pending.resolution_trace_id != trace_id:
            raise EvaluationEvidenceInvalid
        cursor.execute(ACTION_TURN_EVENTS_SQL, (pending.source_turn_id,))
        self._validate_pending_events(cursor.fetchall(), pending, pending_rows[0][14])
        self._validate_pending_resolution(cursor, pending=pending, state=state)

    def _validated_pending_reference(
        self,
        cursor: pymysql.cursors.Cursor,
        row: tuple[object, ...],
        *,
        expected_turn_id: str | None = None,
        expected_trace_id: str | None = None,
        expected_conversation_id: str | None = None,
        expected_session_id: str,
        expected_subject: str,
        expected_sandbox_id: str,
    ) -> tuple[PendingActionReference, str]:
        cursor.execute(PENDING_ACTION_SOURCE_TURN_SQL, (row[1],))
        try:
            pending, state, _ = validate_pending_action_reference(
                tuple(row),
                cursor.fetchall(),
                expected_turn_id=expected_turn_id,
                expected_trace_id=expected_trace_id,
                expected_conversation_id=expected_conversation_id,
                expected_session_id=expected_session_id,
                expected_user_subject=expected_subject,
                expected_sandbox_id=expected_sandbox_id,
            )
        except ActionEvidenceError as exception:
            raise EvaluationEvidenceInvalid from exception
        return pending, state

    @staticmethod
    def _validate_pending_resolution(
        cursor: pymysql.cursors.Cursor,
        *,
        pending: PendingActionReference,
        state: str,
    ) -> None:
        cursor.execute(PENDING_ACTION_RESOLUTION_TURN_SQL, (pending.resolution_turn_id,))
        turn_rows = cursor.fetchall()
        cursor.execute(ACTION_TURN_EVENTS_SQL, (pending.resolution_turn_id,))
        event_rows = cursor.fetchall()
        try:
            validate_pending_action_resolution(pending, state, turn_rows, event_rows)
        except ActionEvidenceError as exception:
            raise EvaluationEvidenceInvalid from exception

    @staticmethod
    def _validate_pending_events(
        rows: tuple[tuple[object, ...], ...] | list[tuple[object, ...]],
        pending: PendingActionReference,
        persisted_expiry: object,
    ) -> None:
        if not isinstance(persisted_expiry, datetime):
            raise EvaluationEvidenceInvalid
        expiry = MysqlEvaluationEvidenceStore._utc_timestamp(persisted_expiry)
        if expiry != pending.expires_at:
            raise EvaluationEvidenceInvalid
        try:
            validate_pending_action_events(
                rows,
                expected_trace_id=pending.source_trace_id,
                expected_session_id=pending.session_id,
                expected_user_subject=pending.user_subject,
                pending_action_id=pending.pending_action_id,
                action_type=pending.action_type,
                argument_commitment=pending.argument_commitment,
                target_version=pending.target_version,
                expires_at=expiry,
            )
        except ActionEvidenceError as exception:
            raise EvaluationEvidenceInvalid from exception

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
