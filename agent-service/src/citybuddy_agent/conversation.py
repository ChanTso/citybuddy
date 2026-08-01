"""Durable support turn reservation, terminal result, and ordered evidence."""

from __future__ import annotations

import hashlib
import json
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Literal, Protocol, cast

import pymysql

from .actions import (
    ACTION_TURN_EVENTS_SQL,
    PENDING_ACTION_RESOLUTION_TURN_SQL,
    PENDING_ACTION_SOURCE_TURN_SQL,
    ActionEvidenceError,
    ActionJsonError,
    ActionReceiptPayload,
    PendingActionPayload,
    PendingActionReference,
    StoredActionReceipt,
    canonical_action_timestamp,
    strict_json_object,
    validate_completed_action_events,
    validate_pending_action_events,
    validate_pending_action_reference,
    validate_pending_action_resolution,
    validate_resolved_action_events,
)
from .agent_control import TOOL_BOUNDARY_FAILURE_REASONS, AgentEvent
from .retrieval import RetrievalDecision, RetrievalEvidence


class MysqlConnectionSettings(Protocol):
    mysql_host: str
    mysql_port: int
    mysql_password: str
    attempt_budget: int


@dataclass(frozen=True)
class ConversationResult:
    conversation_id: str
    trace_id: str
    turn_id: str
    response_text: str
    outcome: str
    retrieval_evidence: tuple[RetrievalEvidence, ...] = ()
    action_receipt: StoredActionReceipt | None = None

    def __post_init__(self) -> None:
        if (self.outcome == "action_completed") != (self.action_receipt is not None):
            raise RuntimeError("Durable action outcome and ActionReceipt projection disagree")


@dataclass(frozen=True)
class TurnStart:
    conversation_id: str
    trace_id: str
    turn_id: str
    replay: ConversationResult | None = None
    confirmation_pending_id: str | None = None


ActionReferenceState = Literal["PENDING", "CONFIRMING", "DECLINED", "EXPIRED", "CONFIRMED"]


@dataclass(frozen=True)
class ActionReferenceSnapshot:
    reference: PendingActionReference
    state: ActionReferenceState


class ConversationOwnershipError(Exception):
    """The requested support session is not owned by the authenticated subject."""


class CorrelationConflictError(Exception):
    """A correlation key was reused for a different validated request."""


class TurnInProgressError(Exception):
    """Another request already owns execution for this durable turn."""


class TurnFailedError(Exception):
    """The durable turn previously ended in a non-permissive internal failure."""


class ConversationIntegrityError(RuntimeError):
    """Stored conversation/action evidence is incomplete or contradictory."""


class ActionArbitrationConflictError(Exception):
    """A decline or expiry lost the durable local arbitration."""


class ConversationStore(Protocol):
    def replay_turn(
        self,
        *,
        session_id: str,
        subject: str,
        sandbox_id: str | None,
        correlation_key: str,
        message: str,
    ) -> ConversationResult | None: ...

    def begin_turn(
        self,
        *,
        session_id: str,
        subject: str,
        sandbox_id: str | None,
        correlation_key: str,
        message: str,
    ) -> TurnStart: ...

    def begin_or_resume_confirmation_turn(
        self,
        *,
        session_id: str,
        subject: str,
        sandbox_id: str | None,
        correlation_key: str,
        message: str,
        pending: PendingActionReference,
    ) -> TurnStart: ...

    def complete_turn(
        self,
        *,
        start: TurnStart,
        response_text: str,
        outcome: str,
        events: tuple[AgentEvent, ...],
        retrieval_decision: RetrievalDecision | None = None,
        pending_action: PendingActionPayload | None = None,
    ) -> ConversationResult: ...

    def current_pending_action(
        self, *, session_id: str, subject: str, sandbox_id: str | None
    ) -> PendingActionReference | None: ...

    def current_action_reference(
        self, *, session_id: str, subject: str, sandbox_id: str | None
    ) -> ActionReferenceSnapshot | None: ...

    def complete_action_decline(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        response_text: str,
    ) -> ConversationResult: ...

    def complete_action_expired(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        response_text: str,
    ) -> ConversationResult: ...

    def complete_action_receipt(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        receipt: ActionReceiptPayload,
        response_text: str,
        events: tuple[AgentEvent, ...] = (),
    ) -> ConversationResult: ...

    def fail_turn(self, *, start: TurnStart, failure_code: str) -> None: ...


class MysqlConversationStore:
    """Reserve before agent I/O and commit each terminal turn exactly once."""

    def __init__(self, settings: MysqlConnectionSettings) -> None:
        self._settings = settings
        # Every charged network attempt is bounded to at most three seconds. Persist
        # one deadline with enough fixed margin so a crashed owner can be fenced
        # without ever re-running its agent or tool work.
        self._processing_timeout_microseconds = (settings.attempt_budget * 3 + 5) * 1_000_000

    def replay_turn(
        self,
        *,
        session_id: str,
        subject: str,
        sandbox_id: str | None,
        correlation_key: str,
        message: str,
    ) -> ConversationResult | None:
        fingerprint = hashlib.sha256(message.encode("utf-8")).hexdigest()
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT turn_record.trace_id, turn_record.turn_id, "
                "turn_record.request_fingerprint, turn_record.response_text, "
                "turn_record.state, turn_record.outcome, conversation.conversation_id "
                "FROM support_turn turn_record "
                "JOIN support_conversation conversation "
                "ON conversation.conversation_id = turn_record.conversation_id "
                "JOIN support_session session ON session.session_id = turn_record.session_id "
                "WHERE turn_record.session_id = %s AND turn_record.correlation_key = %s "
                "AND turn_record.user_subject = %s AND session.sandbox_id <=> %s LIMIT 2",
                (session_id, correlation_key, subject, sandbox_id),
            )
            rows = cursor.fetchall()
            if not rows:
                return None
            if len(rows) != 1:
                raise ConversationIntegrityError(
                    "Durable turn correlation cardinality is inconsistent"
                )
            row = rows[0]
            if row[2] != fingerprint:
                raise CorrelationConflictError
            if row[4] != "COMPLETED":
                return None
            if row[3] is None or row[5] is None:
                raise ConversationIntegrityError("Durable replay is not terminal")
            self._validate_action_turn_if_present(
                cursor,
                turn_id=str(row[1]),
                trace_id=str(row[0]),
                conversation_id=str(row[6]),
                session_id=session_id,
                subject=subject,
                sandbox_id=sandbox_id,
                outcome=str(row[5]),
            )
            action_receipt = self._load_action_receipt(cursor, str(row[1]))
            if (row[5] == "action_completed") != (action_receipt is not None):
                raise ConversationIntegrityError(
                    "ActionReceipt projection and terminal outcome disagree"
                )
            if action_receipt is not None:
                self._validate_receipt_pending_truth(
                    cursor,
                    stored=action_receipt,
                    session_id=session_id,
                    subject=subject,
                    sandbox_id=sandbox_id,
                )
            return ConversationResult(
                str(row[6]),
                str(row[0]),
                str(row[1]),
                str(row[3]),
                str(row[5]),
                self._load_retrieval_evidence(cursor, str(row[1])),
                action_receipt,
            )

    def begin_turn(
        self,
        *,
        session_id: str,
        subject: str,
        sandbox_id: str | None,
        correlation_key: str,
        message: str,
    ) -> TurnStart:
        for attempt in range(61):
            try:
                return self._begin_turn_once(
                    session_id=session_id,
                    subject=subject,
                    sandbox_id=sandbox_id,
                    correlation_key=correlation_key,
                    message=message,
                )
            except TurnInProgressError:
                if attempt == 60:
                    raise
                time.sleep(0.05)
        raise RuntimeError("Bounded turn wait did not terminate")

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
        fingerprint = hashlib.sha256(message.encode("utf-8")).hexdigest()
        completed_identity: tuple[str, str, str] | None = None
        claimed: TurnStart | None = None
        with self._connect() as connection:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT conversation_id, user_subject, state, next_turn_sequence "
                        "FROM support_conversation WHERE session_id = %s FOR UPDATE",
                        (session_id,),
                    )
                    conversation = cursor.fetchone()
                    if (
                        conversation is None
                        or conversation[1] != subject
                        or conversation[2] != "ACTIVE"
                    ):
                        raise ConversationOwnershipError
                    conversation_id = str(conversation[0])
                    cursor.execute(
                        "SELECT user_subject, sandbox_id FROM support_session "
                        "WHERE session_id = %s",
                        (session_id,),
                    )
                    session = cursor.fetchone()
                    if session is None or tuple(session) != (subject, sandbox_id):
                        raise ConversationOwnershipError
                    cursor.execute(
                        "SELECT trace_id, turn_id, conversation_id, user_subject, "
                        "request_fingerprint, state "
                        "FROM support_turn WHERE session_id = %s AND correlation_key = %s "
                        "FOR UPDATE",
                        (session_id, correlation_key),
                    )
                    existing = cursor.fetchone()
                    existing_turn_id = str(existing[1]) if existing is not None else None
                    existing_trace_id = str(existing[0]) if existing is not None else None
                    pending_state = self._lock_matching_pending(
                        cursor,
                        pending,
                        confirmation_turn_id=existing_turn_id,
                        confirmation_trace_id=existing_trace_id,
                    )
                    if existing is not None:
                        if (
                            existing[2] != conversation_id
                            or existing[3] != subject
                            or existing[4] != fingerprint
                        ):
                            raise CorrelationConflictError
                        if existing[5] == "COMPLETED" and pending_state == "CONFIRMED":
                            completed_identity = (
                                conversation_id,
                                str(existing[0]),
                                str(existing[1]),
                            )
                        elif existing[5] == "PROCESSING" and pending_state == "CONFIRMING":
                            cursor.execute(
                                "UPDATE support_turn SET processing_deadline_at = "
                                "DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL %s MICROSECOND) "
                                "WHERE turn_id = %s AND state = 'PROCESSING'",
                                (self._processing_timeout_microseconds, existing_turn_id),
                            )
                            claimed = TurnStart(
                                conversation_id,
                                str(existing[0]),
                                str(existing[1]),
                                confirmation_pending_id=pending.pending_action_id,
                            )
                        else:
                            raise ConversationIntegrityError(
                                "Confirmation turn and PendingAction binding disagree"
                            )
                    else:
                        if pending_state != "PENDING":
                            raise ActionArbitrationConflictError
                        turn_sequence = int(conversation[3]) + 1
                        trace_id = str(uuid.uuid4())
                        turn_id = str(uuid.uuid4())
                        cursor.execute(
                            "UPDATE support_conversation SET next_turn_sequence = %s "
                            "WHERE conversation_id = %s",
                            (turn_sequence, conversation_id),
                        )
                        cursor.execute(
                            "INSERT INTO support_turn "
                            "(turn_id, conversation_id, session_id, user_subject, trace_id, "
                            "turn_sequence, correlation_key, request_fingerprint, input_text, "
                            "state, processing_deadline_at) "
                            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'PROCESSING', "
                            "DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL %s MICROSECOND))",
                            (
                                turn_id,
                                conversation_id,
                                session_id,
                                subject,
                                trace_id,
                                turn_sequence,
                                correlation_key,
                                fingerprint,
                                message,
                                self._processing_timeout_microseconds,
                            ),
                        )
                        claimed = TurnStart(
                            conversation_id,
                            trace_id,
                            turn_id,
                            confirmation_pending_id=pending.pending_action_id,
                        )
                        self._insert_event(
                            cursor,
                            start=claimed,
                            session_id=session_id,
                            subject=subject,
                            sequence=1,
                            event=AgentEvent("USER_INPUT", {"accepted": True}),
                        )
                        cursor.execute(
                            "UPDATE pending_action_reference SET state = 'CONFIRMING', "
                            "confirmation_turn_id = %s, confirmation_trace_id = %s "
                            "WHERE pending_action_id = %s AND state = 'PENDING' "
                            "AND confirmation_turn_id IS NULL AND confirmation_trace_id IS NULL",
                            (turn_id, trace_id, pending.pending_action_id),
                        )
                        if cursor.rowcount != 1:
                            raise ActionArbitrationConflictError
                connection.commit()
            except Exception:
                connection.rollback()
                raise
        if completed_identity is not None:
            replay = self.replay_turn(
                session_id=session_id,
                subject=subject,
                sandbox_id=sandbox_id,
                correlation_key=correlation_key,
                message=message,
            )
            if replay is None:
                raise ConversationIntegrityError(
                    "Completed confirmation turn has no replayable result"
                )
            return TurnStart(*completed_identity, replay=replay)
        if claimed is None:
            raise RuntimeError("Confirmation claim did not produce a durable turn")
        return claimed

    def _begin_turn_once(
        self,
        *,
        session_id: str,
        subject: str,
        sandbox_id: str | None,
        correlation_key: str,
        message: str,
    ) -> TurnStart:
        fingerprint = hashlib.sha256(message.encode("utf-8")).hexdigest()
        with self._connect() as connection:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT conversation_id, user_subject, state, next_turn_sequence "
                        "FROM support_conversation WHERE session_id = %s FOR UPDATE",
                        (session_id,),
                    )
                    conversation = cursor.fetchone()
                    if (
                        conversation is None
                        or conversation[1] != subject
                        or conversation[2] != "ACTIVE"
                    ):
                        raise ConversationOwnershipError
                    conversation_id = str(conversation[0])
                    cursor.execute(
                        "SELECT user_subject, sandbox_id FROM support_session "
                        "WHERE session_id = %s",
                        (session_id,),
                    )
                    session = cursor.fetchone()
                    if session is None or session[0] != subject or session[1] != sandbox_id:
                        raise ConversationOwnershipError
                    cursor.execute(
                        "SELECT trace_id, turn_id, request_fingerprint, response_text, "
                        "state, outcome, processing_deadline_at <= CURRENT_TIMESTAMP(6) "
                        "FROM support_turn WHERE session_id = %s AND correlation_key = %s "
                        "FOR UPDATE",
                        (session_id, correlation_key),
                    )
                    existing = cursor.fetchone()
                    if existing is not None:
                        if existing[2] != fingerprint:
                            raise CorrelationConflictError
                        if existing[4] == "PROCESSING":
                            if not existing[6]:
                                raise TurnInProgressError
                            stale_start = TurnStart(
                                conversation_id,
                                str(existing[0]),
                                str(existing[1]),
                            )
                            self._insert_event(
                                cursor,
                                start=stale_start,
                                session_id=session_id,
                                subject=subject,
                                sequence=2,
                                event=AgentEvent(
                                    "TURN_FAILED", {"code": "processing_deadline_expired"}
                                ),
                            )
                            cursor.execute(
                                "UPDATE support_turn SET state = 'FAILED', "
                                "failure_code = 'processing_deadline_expired', "
                                "processing_deadline_at = NULL, "
                                "completed_at = CURRENT_TIMESTAMP(6) WHERE turn_id = %s",
                                (stale_start.turn_id,),
                            )
                            connection.commit()
                            raise TurnFailedError
                        if existing[4] == "FAILED":
                            raise TurnFailedError
                        if existing[3] is None or existing[5] is None:
                            raise RuntimeError("Durable replay is not terminal")
                        self._validate_action_turn_if_present(
                            cursor,
                            turn_id=str(existing[1]),
                            trace_id=str(existing[0]),
                            conversation_id=conversation_id,
                            session_id=session_id,
                            subject=subject,
                            sandbox_id=sandbox_id,
                            outcome=str(existing[5]),
                        )
                        action_receipt = self._load_action_receipt(cursor, str(existing[1]))
                        if (existing[5] == "action_completed") != (action_receipt is not None):
                            raise ConversationIntegrityError(
                                "ActionReceipt projection and terminal outcome disagree"
                            )
                        if action_receipt is not None:
                            self._validate_receipt_pending_truth(
                                cursor,
                                stored=action_receipt,
                                session_id=session_id,
                                subject=subject,
                                sandbox_id=sandbox_id,
                            )
                        retrieval_evidence = self._load_retrieval_evidence(cursor, str(existing[1]))
                        result = ConversationResult(
                            conversation_id,
                            str(existing[0]),
                            str(existing[1]),
                            str(existing[3]),
                            str(existing[5]),
                            retrieval_evidence,
                            action_receipt,
                        )
                        connection.commit()
                        return TurnStart(
                            conversation_id=result.conversation_id,
                            trace_id=result.trace_id,
                            turn_id=result.turn_id,
                            replay=result,
                        )
                    turn_sequence = int(conversation[3]) + 1
                    trace_id = str(uuid.uuid4())
                    turn_id = str(uuid.uuid4())
                    cursor.execute(
                        "UPDATE support_conversation SET next_turn_sequence = %s "
                        "WHERE conversation_id = %s",
                        (turn_sequence, conversation_id),
                    )
                    cursor.execute(
                        "INSERT INTO support_turn "
                        "(turn_id, conversation_id, session_id, user_subject, trace_id, "
                        "turn_sequence, correlation_key, request_fingerprint, input_text, state, "
                        "processing_deadline_at) "
                        "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'PROCESSING', "
                        "DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL %s MICROSECOND))",
                        (
                            turn_id,
                            conversation_id,
                            session_id,
                            subject,
                            trace_id,
                            turn_sequence,
                            correlation_key,
                            fingerprint,
                            message,
                            self._processing_timeout_microseconds,
                        ),
                    )
                    self._insert_event(
                        cursor,
                        start=TurnStart(conversation_id, trace_id, turn_id),
                        session_id=session_id,
                        subject=subject,
                        sequence=1,
                        event=AgentEvent("USER_INPUT", {"accepted": True}),
                    )
                connection.commit()
            except Exception:
                connection.rollback()
                raise
        return TurnStart(conversation_id, trace_id, turn_id)

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
        with self._connect() as connection:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT session_id, user_subject, state, "
                        "trace_id, "
                        "processing_deadline_at > CURRENT_TIMESTAMP(6) FROM support_turn "
                        "WHERE turn_id = %s FOR UPDATE",
                        (start.turn_id,),
                    )
                    turn = cursor.fetchone()
                    if (
                        turn is None
                        or turn[2] != "PROCESSING"
                        or turn[3] != start.trace_id
                        or not turn[4]
                    ):
                        raise RuntimeError("Durable turn is not executable")
                    sequence = 2
                    for event in events:
                        self._insert_event(
                            cursor,
                            start=start,
                            session_id=str(turn[0]),
                            subject=str(turn[1]),
                            sequence=sequence,
                            event=event,
                        )
                        sequence += 1
                    if retrieval_decision is not None:
                        self._insert_retrieval_decision(
                            cursor,
                            start=start,
                            session_id=str(turn[0]),
                            subject=str(turn[1]),
                            decision=retrieval_decision,
                        )
                    if (outcome == "action_pending") != (pending_action is not None):
                        raise RuntimeError("Action pending outcome and reference disagree")
                    if outcome == "action_completed":
                        raise RuntimeError(
                            "Action completed outcome requires the receipt transaction"
                        )
                    prepared_events = [
                        event for event in events if event.event_type == "ACTION_PREPARED"
                    ]
                    if pending_action is None:
                        if prepared_events:
                            raise RuntimeError(
                                "Action preparation event has no PendingAction reference"
                            )
                    elif len(prepared_events) != 1 or prepared_events[0].payload != {
                        "pendingActionId": pending_action.pending_action_id,
                        "actionType": pending_action.action_type,
                        "argumentCommitment": pending_action.argument_commitment,
                        "targetVersion": pending_action.target_version,
                        "expiresAt": canonical_action_timestamp(pending_action.expires_at),
                    }:
                        raise RuntimeError("PendingAction reference and preparation event disagree")
                    if pending_action is not None:
                        cursor.execute(
                            "INSERT INTO pending_action_reference "
                            "(pending_action_id, source_turn_id, source_trace_id, conversation_id, "
                            "session_id, user_subject, sandbox_id, action_type, "
                            "argument_commitment, order_id, target_version, amount_minor, "
                            "currency, state, "
                            "expires_at) VALUES (%s, %s, %s, %s, %s, %s, "
                            "(SELECT sandbox_id FROM support_session WHERE session_id = %s), "
                            "%s, %s, %s, %s, %s, %s, 'PENDING', %s)",
                            (
                                pending_action.pending_action_id,
                                start.turn_id,
                                start.trace_id,
                                start.conversation_id,
                                str(turn[0]),
                                str(turn[1]),
                                str(turn[0]),
                                pending_action.action_type,
                                pending_action.argument_commitment,
                                pending_action.order_id,
                                pending_action.target_version,
                                pending_action.amount_minor,
                                pending_action.currency,
                                pending_action.expires_at,
                            ),
                        )
                    self._insert_event(
                        cursor,
                        start=start,
                        session_id=str(turn[0]),
                        subject=str(turn[1]),
                        sequence=sequence,
                        event=AgentEvent("ASSISTANT_RESPONSE", {"outcome": outcome}),
                    )
                    self._insert_event(
                        cursor,
                        start=start,
                        session_id=str(turn[0]),
                        subject=str(turn[1]),
                        sequence=sequence + 1,
                        event=AgentEvent("TURN_COMPLETED", {"outcome": outcome}),
                    )
                    cursor.execute(
                        "UPDATE support_turn SET state = 'COMPLETED', response_text = %s, "
                        "outcome = %s, processing_deadline_at = NULL, "
                        "completed_at = CURRENT_TIMESTAMP(6) WHERE turn_id = %s",
                        (response_text, outcome, start.turn_id),
                    )
                connection.commit()
            except Exception:
                connection.rollback()
                raise
        return ConversationResult(
            start.conversation_id,
            start.trace_id,
            start.turn_id,
            response_text,
            outcome,
            retrieval_decision.evidence
            if retrieval_decision is not None and outcome == "completed"
            else (),
        )

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
        with self._connect() as connection, connection.cursor() as cursor:
            cursor.execute(
                "SELECT COUNT(*) FROM support_turn turn_record "
                "LEFT JOIN pending_action_reference action_reference "
                "ON action_reference.source_turn_id = turn_record.turn_id "
                "WHERE turn_record.session_id = %s AND turn_record.user_subject = %s "
                "AND turn_record.outcome = 'action_pending' "
                "AND (action_reference.pending_action_id IS NULL "
                "OR action_reference.source_trace_id <> turn_record.trace_id "
                "OR action_reference.conversation_id <> turn_record.conversation_id "
                "OR action_reference.session_id <> turn_record.session_id "
                "OR action_reference.user_subject <> turn_record.user_subject "
                "OR NOT (action_reference.sandbox_id <=> %s))",
                (session_id, subject, sandbox_id),
            )
            missing_reference = cursor.fetchone()
            if missing_reference is None or int(missing_reference[0]) != 0:
                raise ConversationIntegrityError(
                    "PendingAction turn and reference sets are inconsistent"
                )
            cursor.execute(
                "SELECT COUNT(*), "
                "COALESCE(SUM(pending_action_reference.state IN ('PENDING', 'CONFIRMING')), 0), "
                "COALESCE(SUM(pending_action_reference.state "
                "NOT IN ('PENDING', 'CONFIRMING', 'DECLINED', 'EXPIRED', 'CONFIRMED')), 0), "
                "COALESCE(SUM("
                "(pending_action_reference.state = 'PENDING' AND ("
                "pending_action_reference.resolved_at IS NOT NULL "
                "OR pending_action_reference.confirmation_turn_id IS NOT NULL "
                "OR pending_action_reference.confirmation_trace_id IS NOT NULL "
                "OR pending_action_reference.resolution_turn_id IS NOT NULL "
                "OR pending_action_reference.resolution_trace_id IS NOT NULL)) "
                "OR (pending_action_reference.state = 'CONFIRMING' AND ("
                "pending_action_reference.resolved_at IS NOT NULL "
                "OR pending_action_reference.confirmation_turn_id IS NULL "
                "OR pending_action_reference.confirmation_trace_id IS NULL "
                "OR pending_action_reference.resolution_turn_id IS NOT NULL "
                "OR pending_action_reference.resolution_trace_id IS NOT NULL)) "
                "OR (pending_action_reference.state = 'CONFIRMED' AND ("
                "pending_action_reference.resolved_at IS NULL "
                "OR pending_action_reference.confirmation_turn_id IS NULL "
                "OR pending_action_reference.confirmation_trace_id IS NULL "
                "OR pending_action_reference.resolution_turn_id IS NOT NULL "
                "OR pending_action_reference.resolution_trace_id IS NOT NULL)) "
                "OR (pending_action_reference.state IN ('DECLINED', 'EXPIRED') AND ("
                "pending_action_reference.resolved_at IS NULL "
                "OR pending_action_reference.confirmation_turn_id IS NOT NULL "
                "OR pending_action_reference.confirmation_trace_id IS NOT NULL "
                "OR pending_action_reference.resolution_turn_id IS NULL "
                "OR pending_action_reference.resolution_trace_id IS NULL))), 0), "
                "COALESCE(SUM(source_turn.turn_id IS NULL "
                "OR source_turn.outcome <> 'action_pending' "
                "OR source_turn.trace_id <> source_trace_id "
                "OR source_turn.conversation_id <> pending_action_reference.conversation_id "
                "OR source_turn.session_id <> pending_action_reference.session_id "
                "OR source_turn.user_subject <> pending_action_reference.user_subject), 0) "
                "FROM pending_action_reference "
                "LEFT JOIN support_turn source_turn "
                "ON source_turn.turn_id = pending_action_reference.source_turn_id "
                "WHERE pending_action_reference.session_id = %s "
                "AND pending_action_reference.user_subject = %s "
                "AND pending_action_reference.sandbox_id <=> %s",
                (session_id, subject, sandbox_id),
            )
            totals = cursor.fetchone()
            if (
                totals is None
                or int(totals[2]) != 0
                or int(totals[3]) != 0
                or int(totals[4]) != 0
                or int(totals[1]) > 1
            ):
                raise ConversationIntegrityError("PendingAction session closure is inconsistent")
            if int(totals[1]) == 0:
                return None
            cursor.execute(
                "SELECT source_turn_id, source_trace_id, conversation_id "
                "FROM pending_action_reference "
                "WHERE session_id = %s AND user_subject = %s AND sandbox_id <=> %s "
                "AND state IN ('PENDING', 'CONFIRMING') LIMIT 2",
                (session_id, subject, sandbox_id),
            )
            rows = cursor.fetchall()
            if len(rows) != 1:
                raise ConversationIntegrityError(
                    "PendingAction current cardinality is inconsistent"
                )
            latest = rows[0]
            reference, state = self._load_pending_action_for_turn(
                cursor,
                turn_id=str(latest[0]),
                trace_id=str(latest[1]),
                conversation_id=str(latest[2]),
                session_id=session_id,
                subject=subject,
                sandbox_id=sandbox_id,
            )
            if state not in {"PENDING", "CONFIRMING"}:
                raise ConversationIntegrityError("PendingAction current state is inconsistent")
            return ActionReferenceSnapshot(reference, cast(ActionReferenceState, state))

    def complete_action_decline(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        response_text: str,
    ) -> ConversationResult:
        return self._complete_local_action(
            start=start,
            pending=pending,
            response_text=response_text,
            state="DECLINED",
            outcome="action_declined",
            event_type="ACTION_DECLINED",
            event_outcome="declined",
            require_expired=False,
        )

    def complete_action_expired(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        response_text: str,
    ) -> ConversationResult:
        return self._complete_local_action(
            start=start,
            pending=pending,
            response_text=response_text,
            state="EXPIRED",
            outcome="action_expired",
            event_type="ACTION_EXPIRED",
            event_outcome="expired",
            require_expired=True,
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
        if (
            receipt.pending_action_id != pending.pending_action_id
            or receipt.action_type != pending.action_type
            or receipt.order_id != pending.order_id
            or receipt.amount_minor != pending.amount_minor
            or receipt.currency != pending.currency
            or receipt.argument_commitment != pending.argument_commitment
        ):
            raise ConversationIntegrityError(
                "Commerce receipt contradicts the stored PendingAction"
            )
        stored = StoredActionReceipt(
            receipt,
            pending.source_turn_id,
            pending.source_trace_id,
            start.turn_id,
            start.trace_id,
            pending.sandbox_id,
            pending.target_version,
        )
        with self._connect() as connection:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT session_id, user_subject, state, trace_id, "
                        "processing_deadline_at > CURRENT_TIMESTAMP(6), outcome, response_text "
                        "FROM support_turn WHERE turn_id = %s FOR UPDATE",
                        (start.turn_id,),
                    )
                    turn_row = cursor.fetchone()
                    if (
                        turn_row is None
                        or turn_row[3] != start.trace_id
                        or turn_row[0] != pending.session_id
                        or turn_row[1] != pending.user_subject
                    ):
                        raise ConversationIntegrityError(
                            "Confirmation turn identity is inconsistent"
                        )
                    if turn_row[2] == "COMPLETED":
                        if turn_row[5] != "action_completed" or not isinstance(turn_row[6], str):
                            raise ConversationIntegrityError(
                                "Completed confirmation turn is inconsistent"
                            )
                        existing = self._load_action_receipt_by_pending(
                            cursor, pending.pending_action_id
                        )
                        self._validate_receipt_pending_truth(
                            cursor,
                            stored=existing,
                            session_id=pending.session_id,
                            subject=pending.user_subject,
                            sandbox_id=pending.sandbox_id,
                        )
                        if existing.receipt.receipt_commitment != receipt.receipt_commitment:
                            raise ConversationIntegrityError(
                                "Concurrent ActionReceipt replay contradicts stored truth"
                            )
                        connection.commit()
                        return ConversationResult(
                            start.conversation_id,
                            start.trace_id,
                            start.turn_id,
                            str(turn_row[6]),
                            "action_completed",
                            action_receipt=existing,
                        )
                    if turn_row[2] != "PROCESSING" or not turn_row[4]:
                        raise ConversationIntegrityError("Confirmation turn is not recoverable")
                    turn = cast(tuple[object, ...], turn_row[:5])
                    pending_state = self._lock_matching_pending(
                        cursor,
                        pending,
                        confirmation_turn_id=start.turn_id,
                        confirmation_trace_id=start.trace_id,
                    )
                    if pending_state != "CONFIRMING":
                        raise ConversationIntegrityError(
                            "ActionReceipt requires a claimed PendingAction"
                        )
                    receipt_sequence = 2 + len(events)
                    cursor.execute(
                        "INSERT INTO action_receipt_projection "
                        "(receipt_id, pending_action_id, source_turn_id, source_trace_id, "
                        "confirmation_turn_id, confirmation_trace_id, session_id, user_subject, "
                        "sandbox_id, action_type, argument_commitment, status, order_id, "
                        "target_version, refund_id, resource_version, amount_minor, currency, "
                        "committed_at, receipt_commitment, published_event_sequence) "
                        "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, "
                        "%s, %s, %s, %s, %s, %s, %s)",
                        (
                            receipt.receipt_id,
                            receipt.pending_action_id,
                            pending.source_turn_id,
                            pending.source_trace_id,
                            start.turn_id,
                            start.trace_id,
                            pending.session_id,
                            pending.user_subject,
                            pending.sandbox_id,
                            receipt.action_type,
                            pending.argument_commitment,
                            receipt.status,
                            receipt.order_id,
                            pending.target_version,
                            receipt.refund_id,
                            receipt.resource_version,
                            receipt.amount_minor,
                            receipt.currency,
                            receipt.committed_at,
                            receipt.receipt_commitment,
                            receipt_sequence,
                        ),
                    )
                    cursor.execute(
                        "UPDATE pending_action_reference SET state = 'CONFIRMED', "
                        "resolved_at = CURRENT_TIMESTAMP(6) "
                        "WHERE pending_action_id = %s AND state = 'CONFIRMING' "
                        "AND confirmation_turn_id = %s AND confirmation_trace_id = %s",
                        (pending.pending_action_id, start.turn_id, start.trace_id),
                    )
                    if cursor.rowcount != 1:
                        raise ConversationIntegrityError(
                            "PendingAction confirmation lost its state transition"
                        )
                    sequence = 2
                    for event in events:
                        self._insert_event(
                            cursor,
                            start=start,
                            session_id=str(turn[0]),
                            subject=str(turn[1]),
                            sequence=sequence,
                            event=event,
                        )
                        sequence += 1
                    self._insert_event(
                        cursor,
                        start=start,
                        session_id=str(turn[0]),
                        subject=str(turn[1]),
                        sequence=receipt_sequence,
                        event=AgentEvent(
                            "ACTION_RECEIPT",
                            {
                                "receiptId": receipt.receipt_id,
                                "pendingActionId": receipt.pending_action_id,
                                "status": receipt.status,
                                "receiptCommitment": receipt.receipt_commitment,
                            },
                        ),
                    )
                    self._finish_turn(
                        cursor,
                        start=start,
                        turn=turn,
                        response_text=response_text,
                        outcome="action_completed",
                        next_sequence=receipt_sequence + 1,
                    )
                connection.commit()
            except Exception:
                connection.rollback()
                raise
        return ConversationResult(
            start.conversation_id,
            start.trace_id,
            start.turn_id,
            response_text,
            "action_completed",
            action_receipt=stored,
        )

    def _complete_local_action(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        response_text: str,
        state: Literal["DECLINED", "EXPIRED"],
        outcome: Literal["action_declined", "action_expired"],
        event_type: Literal["ACTION_DECLINED", "ACTION_EXPIRED"],
        event_outcome: Literal["declined", "expired"],
        require_expired: bool,
    ) -> ConversationResult:
        with self._connect() as connection:
            try:
                with connection.cursor() as cursor:
                    turn = self._lock_executable_turn(cursor, start)
                    if (
                        self._lock_matching_pending(
                            cursor, pending, require_expired=require_expired
                        )
                        != "PENDING"
                    ):
                        raise ActionArbitrationConflictError
                    cursor.execute(
                        "UPDATE pending_action_reference SET state = %s, "
                        "resolved_at = CURRENT_TIMESTAMP(6), resolution_turn_id = %s, "
                        "resolution_trace_id = %s "
                        "WHERE pending_action_id = %s AND state = 'PENDING'",
                        (state, start.turn_id, start.trace_id, pending.pending_action_id),
                    )
                    if cursor.rowcount != 1:
                        raise ActionArbitrationConflictError
                    self._insert_event(
                        cursor,
                        start=start,
                        session_id=str(turn[0]),
                        subject=str(turn[1]),
                        sequence=2,
                        event=AgentEvent(
                            event_type,
                            {
                                "pendingActionId": pending.pending_action_id,
                                "outcome": event_outcome,
                            },
                        ),
                    )
                    self._finish_turn(
                        cursor,
                        start=start,
                        turn=turn,
                        response_text=response_text,
                        outcome=outcome,
                        next_sequence=3,
                    )
                connection.commit()
            except Exception:
                connection.rollback()
                raise
        return ConversationResult(
            start.conversation_id,
            start.trace_id,
            start.turn_id,
            response_text,
            outcome,
        )

    def fail_turn(self, *, start: TurnStart, failure_code: str) -> None:
        with self._connect() as connection:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT session_id, user_subject, state FROM support_turn "
                        "WHERE turn_id = %s FOR UPDATE",
                        (start.turn_id,),
                    )
                    turn = cursor.fetchone()
                    if turn is None or turn[2] != "PROCESSING":
                        return
                    self._insert_event(
                        cursor,
                        start=start,
                        session_id=str(turn[0]),
                        subject=str(turn[1]),
                        sequence=2,
                        event=AgentEvent("TURN_FAILED", {"code": failure_code}),
                    )
                    cursor.execute(
                        "UPDATE support_turn SET state = 'FAILED', failure_code = %s, "
                        "processing_deadline_at = NULL, "
                        "completed_at = CURRENT_TIMESTAMP(6) WHERE turn_id = %s",
                        (failure_code, start.turn_id),
                    )
                connection.commit()
            except Exception:
                connection.rollback()
                raise

    @staticmethod
    def _lock_executable_turn(
        cursor: pymysql.cursors.Cursor, start: TurnStart
    ) -> tuple[object, ...]:
        cursor.execute(
            "SELECT session_id, user_subject, state, trace_id, "
            "processing_deadline_at > CURRENT_TIMESTAMP(6) FROM support_turn "
            "WHERE turn_id = %s FOR UPDATE",
            (start.turn_id,),
        )
        turn = cursor.fetchone()
        if turn is None or turn[2] != "PROCESSING" or turn[3] != start.trace_id or not turn[4]:
            raise RuntimeError("Durable turn is not executable")
        return cast(tuple[object, ...], turn)

    @classmethod
    def _validate_pending_source_turn(
        cls,
        rows: tuple[tuple[object, ...], ...] | list[tuple[object, ...]],
        *,
        pending: PendingActionReference,
        persisted_expiry: object,
    ) -> None:
        if not isinstance(persisted_expiry, datetime):
            raise ConversationIntegrityError("PendingAction expiry is invalid")
        persisted_aware = (
            persisted_expiry.replace(tzinfo=UTC)
            if persisted_expiry.tzinfo is None
            else persisted_expiry.astimezone(UTC)
        )
        try:
            if persisted_aware != pending.expires_at.astimezone(UTC):
                raise ActionEvidenceError("PendingAction expiry is inconsistent")
            validate_pending_action_events(
                rows,
                expected_trace_id=pending.source_trace_id,
                expected_session_id=pending.session_id,
                expected_user_subject=pending.user_subject,
                pending_action_id=pending.pending_action_id,
                action_type=pending.action_type,
                argument_commitment=pending.argument_commitment,
                target_version=pending.target_version,
                expires_at=persisted_aware,
            )
        except (AttributeError, ActionEvidenceError, ValueError) as exception:
            raise ConversationIntegrityError(
                "PendingAction source turn is inconsistent"
            ) from exception

    @classmethod
    def _lock_matching_pending(
        cls,
        cursor: pymysql.cursors.Cursor,
        pending: PendingActionReference,
        *,
        require_expired: bool = False,
        confirmation_turn_id: str | None = None,
        confirmation_trace_id: str | None = None,
    ) -> str:
        cursor.execute(
            "SELECT source_turn_id, source_trace_id, conversation_id, session_id, "
            "user_subject, sandbox_id, action_type, argument_commitment, order_id, "
            "target_version, amount_minor, currency, state, confirmation_turn_id, "
            "confirmation_trace_id, expires_at, resolved_at, resolution_turn_id, "
            "resolution_trace_id "
            "FROM pending_action_reference WHERE pending_action_id = %s FOR UPDATE",
            (pending.pending_action_id,),
        )
        row = cursor.fetchone()
        if row is None:
            raise ConversationIntegrityError("PendingAction reference disappeared")
        if tuple(row[:12]) != (
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
        ):
            raise ConversationIntegrityError("PendingAction reference is inconsistent")
        state = str(row[12])
        confirmation_binding = tuple(row[13:15])
        expires_at = row[15]
        resolved_at = row[16]
        resolution_binding = tuple(row[17:19])
        if state not in {
            "PENDING",
            "CONFIRMING",
            "DECLINED",
            "EXPIRED",
            "CONFIRMED",
        } or not isinstance(expires_at, datetime):
            raise ConversationIntegrityError("PendingAction state is inconsistent")
        if (
            (
                state == "PENDING"
                and (
                    confirmation_binding != (None, None)
                    or resolved_at is not None
                    or resolution_binding != (None, None)
                )
            )
            or (
                state == "CONFIRMING"
                and (
                    not all(isinstance(value, str) for value in confirmation_binding)
                    or resolved_at is not None
                    or resolution_binding != (None, None)
                )
            )
            or (
                state == "CONFIRMED"
                and (
                    not all(isinstance(value, str) for value in confirmation_binding)
                    or resolved_at is None
                    or resolution_binding != (None, None)
                )
            )
            or (
                state in {"DECLINED", "EXPIRED"}
                and (
                    confirmation_binding != (None, None)
                    or resolved_at is None
                    or not all(isinstance(value, str) for value in resolution_binding)
                )
            )
        ):
            raise ConversationIntegrityError("PendingAction resolution is inconsistent")
        if (
            state in {"CONFIRMING", "CONFIRMED"}
            and confirmation_turn_id is not None
            and confirmation_binding != (confirmation_turn_id, confirmation_trace_id)
        ):
            raise ActionArbitrationConflictError
        cursor.execute(ACTION_TURN_EVENTS_SQL + " FOR SHARE", (pending.source_turn_id,))
        cls._validate_pending_source_turn(
            cursor.fetchall(),
            pending=pending,
            persisted_expiry=expires_at,
        )
        aware_expiry = (
            expires_at.replace(tzinfo=UTC)
            if expires_at.tzinfo is None
            else expires_at.astimezone(UTC)
        )
        expired = aware_expiry <= datetime.now(UTC)
        if state == "PENDING":
            if require_expired != expired:
                raise ActionArbitrationConflictError
        elif require_expired:
            raise ActionArbitrationConflictError
        return state

    @classmethod
    def _finish_turn(
        cls,
        cursor: pymysql.cursors.Cursor,
        *,
        start: TurnStart,
        turn: tuple[object, ...],
        response_text: str,
        outcome: str,
        next_sequence: int,
    ) -> None:
        for sequence, event in enumerate(
            (
                AgentEvent("AGENT_OUTCOME", {"outcome": outcome}),
                AgentEvent("ASSISTANT_RESPONSE", {"outcome": outcome}),
                AgentEvent("TURN_COMPLETED", {"outcome": outcome}),
            ),
            start=next_sequence,
        ):
            cls._insert_event(
                cursor,
                start=start,
                session_id=str(turn[0]),
                subject=str(turn[1]),
                sequence=sequence,
                event=event,
            )
        cursor.execute(
            "UPDATE support_turn SET state = 'COMPLETED', response_text = %s, "
            "outcome = %s, processing_deadline_at = NULL, "
            "completed_at = CURRENT_TIMESTAMP(6) WHERE turn_id = %s",
            (response_text, outcome, start.turn_id),
        )
        if cursor.rowcount != 1:
            raise RuntimeError("Durable action turn was not completed")

    @staticmethod
    def _insert_event(
        cursor: pymysql.cursors.Cursor,
        *,
        start: TurnStart,
        session_id: str,
        subject: str,
        sequence: int,
        event: AgentEvent,
    ) -> None:
        cursor.execute(
            "INSERT INTO support_event "
            "(event_id, turn_id, trace_id, session_id, user_subject, sequence, "
            "event_type, payload_json) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)",
            (
                str(uuid.uuid4()),
                start.turn_id,
                start.trace_id,
                session_id,
                subject,
                sequence,
                event.event_type,
                json.dumps(event.payload, separators=(",", ":"), ensure_ascii=False),
            ),
        )

    @staticmethod
    def _insert_retrieval_decision(
        cursor: pymysql.cursors.Cursor,
        *,
        start: TurnStart,
        session_id: str,
        subject: str,
        decision: RetrievalDecision,
    ) -> None:
        decision_id = str(uuid.uuid4())
        cursor.execute(
            "INSERT INTO retrieval_decision "
            "(decision_id, turn_id, trace_id, session_id, user_subject, index_version, "
            "calibration_version, sufficiency_outcome, reason_code, candidate_count, "
            "evidence_count, top_score, top_margin) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
            (
                decision_id,
                start.turn_id,
                start.trace_id,
                session_id,
                subject,
                decision.index_version,
                decision.calibration_version,
                decision.outcome,
                decision.reason,
                decision.candidate_count,
                len(decision.evidence),
                decision.top_score,
                decision.top_margin,
            ),
        )
        for evidence in decision.evidence:
            cursor.execute(
                "INSERT INTO retrieval_evidence "
                "(evidence_id, decision_id, evidence_rank, source_id, chunk_id, "
                "source_version, doc_type, title, excerpt, rerank_score) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
                (
                    str(uuid.uuid4()),
                    decision_id,
                    evidence.rank,
                    evidence.source_id,
                    evidence.chunk_id,
                    evidence.source_version,
                    evidence.doc_type,
                    evidence.title,
                    evidence.excerpt,
                    evidence.score,
                ),
            )

    @staticmethod
    def _load_retrieval_evidence(
        cursor: pymysql.cursors.Cursor, turn_id: str
    ) -> tuple[RetrievalEvidence, ...]:
        cursor.execute(
            "SELECT evidence.source_id, evidence.chunk_id, evidence.source_version, "
            "evidence.doc_type, evidence.title, evidence.excerpt, "
            "evidence.evidence_rank, evidence.rerank_score "
            "FROM retrieval_decision decision "
            "JOIN support_turn turn_record ON turn_record.turn_id = decision.turn_id "
            "JOIN retrieval_evidence evidence ON evidence.decision_id = decision.decision_id "
            "WHERE decision.turn_id = %s "
            "AND decision.sufficiency_outcome = 'SUFFICIENT' "
            "AND turn_record.outcome = 'completed' "
            "ORDER BY evidence.evidence_rank",
            (turn_id,),
        )
        rows = cursor.fetchall()
        return tuple(
            RetrievalEvidence(
                source_id=str(row[0]),
                chunk_id=str(row[1]),
                source_version=int(row[2]),
                doc_type=cast(Literal["faq", "product"], str(row[3])),
                title=str(row[4]),
                excerpt=str(row[5]),
                rank=int(row[6]),
                score=float(row[7]),
            )
            for row in rows
        )

    @classmethod
    def _validate_action_turn_if_present(
        cls,
        cursor: pymysql.cursors.Cursor,
        *,
        turn_id: str,
        trace_id: str,
        conversation_id: str,
        session_id: str,
        subject: str,
        sandbox_id: str | None,
        outcome: str,
    ) -> None:
        cursor.execute(ACTION_TURN_EVENTS_SQL, (turn_id,))
        rows = cursor.fetchall()
        action_types = [
            str(row[5])
            for row in rows
            if len(row) == 7
            and row[5] in {"ACTION_PREPARED", "ACTION_DECLINED", "ACTION_EXPIRED", "ACTION_RECEIPT"}
        ]
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
        cursor.execute(
            "SELECT pending_action_id FROM pending_action_reference "
            "WHERE confirmation_turn_id = %s LIMIT 2",
            (turn_id,),
        )
        confirmation_references = cursor.fetchall()
        action_outcomes = {
            "action_pending",
            "action_clarification",
            "action_declined",
            "action_expired",
            "action_completed",
        }
        if (
            not action_types
            and not source_references
            and not resolution_references
            and not confirmation_references
            and outcome not in action_outcomes
        ):
            return
        if outcome == "action_clarification":
            if (
                action_types
                or source_references
                or resolution_references
                or confirmation_references
            ):
                raise ConversationIntegrityError(
                    "Action clarification has contradictory action evidence"
                )
            return
        if outcome == "action_pending":
            if (
                action_types != ["ACTION_PREPARED"]
                or len(source_references) != 1
                or resolution_references
                or confirmation_references
            ):
                raise ConversationIntegrityError("PendingAction turn evidence is incomplete")
            cls._load_pending_action_for_turn(
                cursor,
                turn_id=turn_id,
                trace_id=trace_id,
                conversation_id=conversation_id,
                session_id=session_id,
                subject=subject,
                sandbox_id=sandbox_id,
            )
            return
        if outcome in {"action_declined", "action_expired"}:
            expected_event = "ACTION_DECLINED" if outcome == "action_declined" else "ACTION_EXPIRED"
            if (
                action_types != [expected_event]
                or source_references
                or len(resolution_references) != 1
                or confirmation_references
            ):
                raise ConversationIntegrityError("Resolved action turn evidence is incomplete")
            cls._validate_resolved_action_turn(
                cursor,
                turn_id=turn_id,
                trace_id=trace_id,
                session_id=session_id,
                subject=subject,
                sandbox_id=sandbox_id,
                outcome=outcome,
            )
            return
        if outcome == "action_completed":
            stored = cls._load_action_receipt(cursor, turn_id)
            if (
                action_types != ["ACTION_RECEIPT"]
                or source_references
                or resolution_references
                or len(confirmation_references) != 1
                or stored is None
            ):
                raise ConversationIntegrityError("Completed action turn evidence is incomplete")
            try:
                validate_completed_action_events(
                    rows,
                    expected_trace_id=trace_id,
                    expected_session_id=session_id,
                    expected_user_subject=subject,
                    receipt=stored.receipt,
                    tool_failure_reasons=TOOL_BOUNDARY_FAILURE_REASONS,
                )
            except ActionEvidenceError as exception:
                raise ConversationIntegrityError(
                    "Completed action turn event closure is inconsistent"
                ) from exception
            return
        raise ConversationIntegrityError("Action evidence contradicts the owning turn outcome")

    @classmethod
    def _load_pending_action_for_turn(
        cls,
        cursor: pymysql.cursors.Cursor,
        *,
        turn_id: str,
        trace_id: str,
        conversation_id: str,
        session_id: str,
        subject: str,
        sandbox_id: str | None,
    ) -> tuple[PendingActionReference, str]:
        cursor.execute(
            "SELECT pending_action_id, source_turn_id, source_trace_id, conversation_id, "
            "session_id, user_subject, sandbox_id, action_type, argument_commitment, "
            "order_id, target_version, amount_minor, currency, state, confirmation_turn_id, "
            "confirmation_trace_id, expires_at, resolved_at, resolution_turn_id, "
            "resolution_trace_id "
            "FROM pending_action_reference WHERE source_turn_id = %s LIMIT 2",
            (turn_id,),
        )
        rows = cursor.fetchall()
        if len(rows) != 1:
            raise ConversationIntegrityError("PendingAction reference cardinality is inconsistent")
        row = rows[0]
        cursor.execute(PENDING_ACTION_SOURCE_TURN_SQL, (row[1],))
        try:
            reference, state, expires_at = validate_pending_action_reference(
                tuple(row),
                cursor.fetchall(),
                expected_turn_id=turn_id,
                expected_trace_id=trace_id,
                expected_conversation_id=conversation_id,
                expected_session_id=session_id,
                expected_user_subject=subject,
                expected_sandbox_id=sandbox_id,
            )
        except ActionEvidenceError as exception:
            raise ConversationIntegrityError(
                "PendingAction source truth is inconsistent"
            ) from exception
        cursor.execute(ACTION_TURN_EVENTS_SQL, (turn_id,))
        cls._validate_pending_source_turn(
            cursor.fetchall(),
            pending=reference,
            persisted_expiry=expires_at,
        )
        if state in {"DECLINED", "EXPIRED"}:
            cls._validate_pending_resolution(cursor, pending=reference, state=state)
        elif state == "CONFIRMING":
            cursor.execute(
                "SELECT trace_id, conversation_id, session_id, user_subject, state "
                "FROM support_turn WHERE turn_id = %s LIMIT 2",
                (reference.confirmation_turn_id,),
            )
            confirmation_rows = cursor.fetchall()
            if len(confirmation_rows) != 1 or tuple(confirmation_rows[0]) != (
                reference.confirmation_trace_id,
                conversation_id,
                session_id,
                subject,
                "PROCESSING",
            ):
                raise ConversationIntegrityError(
                    "Confirming PendingAction turn binding is inconsistent"
                )
            cursor.execute(
                "SELECT receipt_id FROM action_receipt_projection "
                "WHERE pending_action_id = %s LIMIT 2",
                (reference.pending_action_id,),
            )
            if cursor.fetchall():
                raise ConversationIntegrityError(
                    "Confirming PendingAction has premature ActionReceipt truth"
                )
        elif state == "CONFIRMED":
            stored = cls._load_action_receipt_by_pending(cursor, reference.pending_action_id)
            if stored.confirmation_turn_id != reference.confirmation_turn_id:
                raise ConversationIntegrityError(
                    "Confirmed PendingAction and ActionReceipt turn disagree"
                )
        return reference, state

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
            raise ConversationIntegrityError(
                "PendingAction resolution truth is inconsistent"
            ) from exception

    @staticmethod
    def _load_action_receipt(
        cursor: pymysql.cursors.Cursor, turn_id: str
    ) -> StoredActionReceipt | None:
        cursor.execute(
            "SELECT receipt_id, pending_action_id, source_turn_id, source_trace_id, "
            "confirmation_turn_id, confirmation_trace_id, session_id, user_subject, "
            "sandbox_id, action_type, argument_commitment, status, order_id, target_version, "
            "refund_id, resource_version, amount_minor, currency, committed_at, "
            "receipt_commitment, published_event_sequence "
            "FROM action_receipt_projection WHERE confirmation_turn_id = %s LIMIT 2",
            (turn_id,),
        )
        rows = cursor.fetchall()
        if not rows:
            return None
        if len(rows) != 1:
            raise ConversationIntegrityError("ActionReceipt projection cardinality is inconsistent")
        row = rows[0]
        committed_at = row[18]
        if not isinstance(committed_at, datetime):
            raise ConversationIntegrityError("ActionReceipt committed timestamp is invalid")
        if committed_at.tzinfo is None:
            committed_at = committed_at.replace(tzinfo=UTC)
        try:
            receipt = ActionReceiptPayload.model_validate(
                {
                    "receiptId": row[0],
                    "pendingActionId": row[1],
                    "actionType": row[9],
                    "status": row[11],
                    "orderId": row[12],
                    "refundId": row[14],
                    "resourceVersion": row[15],
                    "amountMinor": row[16],
                    "currency": row[17],
                    "committedAt": canonical_action_timestamp(committed_at),
                    "replayed": True,
                }
            )
        except (TypeError, ValueError) as exception:
            raise ConversationIntegrityError(
                "ActionReceipt projection content is invalid"
            ) from exception
        if (
            type(row[13]) is not int
            or row[13] < 1
            or row[10] != receipt.argument_commitment
            or row[19] != receipt.receipt_commitment
            or not isinstance(row[20], int)
            or row[20] <= 1
        ):
            raise ConversationIntegrityError("ActionReceipt projection commitment is inconsistent")
        cursor.execute(
            "SELECT trace_id, session_id, user_subject, event_type, payload_json "
            "FROM support_event WHERE turn_id = %s AND sequence = %s LIMIT 2",
            (turn_id, row[20]),
        )
        event_rows = cursor.fetchall()
        if len(event_rows) != 1 or event_rows[0][3] != "ACTION_RECEIPT":
            raise ConversationIntegrityError("ActionReceipt event identity is inconsistent")
        try:
            event_payload = strict_json_object(str(event_rows[0][4]).encode("utf-8"))
        except ActionJsonError as exception:
            raise ConversationIntegrityError(
                "ActionReceipt event payload is invalid"
            ) from exception
        if tuple(event_rows[0][:3]) != tuple(row[5:8]) or event_payload != {
            "receiptId": receipt.receipt_id,
            "pendingActionId": receipt.pending_action_id,
            "status": receipt.status,
            "receiptCommitment": receipt.receipt_commitment,
        }:
            raise ConversationIntegrityError("ActionReceipt event commitment is inconsistent")
        if (
            not isinstance(row[3], str)
            or not isinstance(row[5], str)
            or (row[8] is not None and not isinstance(row[8], str))
        ):
            raise ConversationIntegrityError("ActionReceipt projection identity is invalid")
        return StoredActionReceipt(
            receipt,
            str(row[2]),
            row[3],
            str(row[4]),
            row[5],
            row[8],
            row[13],
        )

    @classmethod
    def _load_action_receipt_by_pending(
        cls, cursor: pymysql.cursors.Cursor, pending_action_id: str
    ) -> StoredActionReceipt:
        cursor.execute(
            "SELECT confirmation_turn_id FROM action_receipt_projection "
            "WHERE pending_action_id = %s LIMIT 2",
            (pending_action_id,),
        )
        rows = cursor.fetchall()
        if len(rows) != 1:
            raise ConversationIntegrityError("ActionReceipt projection cardinality is inconsistent")
        receipt = cls._load_action_receipt(cursor, str(rows[0][0]))
        if receipt is None:
            raise ConversationIntegrityError(
                "Confirmed PendingAction has no published ActionReceipt"
            )
        return receipt

    @classmethod
    def _validate_receipt_pending_truth(
        cls,
        cursor: pymysql.cursors.Cursor,
        *,
        stored: StoredActionReceipt,
        session_id: str,
        subject: str,
        sandbox_id: str | None,
    ) -> None:
        cursor.execute(
            "SELECT trace_id, conversation_id, session_id, user_subject, state, outcome "
            "FROM support_turn WHERE turn_id = %s LIMIT 2",
            (stored.source_turn_id,),
        )
        source_rows = cursor.fetchall()
        if len(source_rows) != 1:
            raise ConversationIntegrityError(
                "ActionReceipt source turn cardinality is inconsistent"
            )
        source = source_rows[0]
        if tuple(source[2:]) != (session_id, subject, "COMPLETED", "action_pending"):
            raise ConversationIntegrityError("ActionReceipt source turn is inconsistent")
        pending, state = cls._load_pending_action_for_turn(
            cursor,
            turn_id=stored.source_turn_id,
            trace_id=str(source[0]),
            conversation_id=str(source[1]),
            session_id=session_id,
            subject=subject,
            sandbox_id=sandbox_id,
        )
        receipt = stored.receipt
        if (
            state != "CONFIRMED"
            or stored.source_trace_id != pending.source_trace_id
            or pending.confirmation_turn_id != stored.confirmation_turn_id
            or pending.confirmation_trace_id != stored.confirmation_trace_id
            or stored.sandbox_id != sandbox_id
            or stored.sandbox_id != pending.sandbox_id
            or stored.target_version != pending.target_version
            or pending.pending_action_id != receipt.pending_action_id
            or pending.action_type != receipt.action_type
            or pending.argument_commitment != receipt.argument_commitment
            or pending.order_id != receipt.order_id
            or pending.amount_minor != receipt.amount_minor
            or pending.currency != receipt.currency
        ):
            raise ConversationIntegrityError("ActionReceipt and PendingAction truth disagree")

    @classmethod
    def _validate_resolved_action_turn(
        cls,
        cursor: pymysql.cursors.Cursor,
        *,
        turn_id: str,
        trace_id: str,
        session_id: str,
        subject: str,
        sandbox_id: str | None,
        outcome: str,
    ) -> None:
        state = {
            "action_declined": "DECLINED",
            "action_expired": "EXPIRED",
        }[outcome]
        cursor.execute(ACTION_TURN_EVENTS_SQL, (turn_id,))
        rows = cursor.fetchall()
        try:
            first_action_payload = strict_json_object(str(rows[1][6]).encode("utf-8"))
            pending_action_id = first_action_payload.get("pendingActionId")
            if not isinstance(pending_action_id, str):
                raise ActionEvidenceError("Resolved action identity is invalid")
            validate_resolved_action_events(
                rows,
                expected_trace_id=trace_id,
                expected_session_id=session_id,
                expected_user_subject=subject,
                pending_action_id=pending_action_id,
                outcome=cast(
                    Literal["action_declined", "action_expired"],
                    outcome,
                ),
            )
        except (ActionJsonError, ActionEvidenceError, IndexError, TypeError) as exception:
            raise ConversationIntegrityError(
                "Resolved action event closure is inconsistent"
            ) from exception
        cursor.execute(
            "SELECT source_turn_id, source_trace_id, conversation_id, session_id, "
            "user_subject, sandbox_id, state, resolved_at, resolution_turn_id, "
            "resolution_trace_id "
            "FROM pending_action_reference WHERE pending_action_id = %s LIMIT 2",
            (pending_action_id,),
        )
        pending_rows = cursor.fetchall()
        if (
            len(pending_rows) != 1
            or tuple(pending_rows[0][3:7]) != (session_id, subject, sandbox_id, state)
            or pending_rows[0][7] is None
            or tuple(pending_rows[0][8:10]) != (turn_id, trace_id)
        ):
            raise ConversationIntegrityError("Resolved PendingAction is inconsistent")
        pending, loaded_state = cls._load_pending_action_for_turn(
            cursor,
            turn_id=str(pending_rows[0][0]),
            trace_id=str(pending_rows[0][1]),
            conversation_id=str(pending_rows[0][2]),
            session_id=session_id,
            subject=subject,
            sandbox_id=sandbox_id,
        )
        if (
            pending.pending_action_id != pending_action_id
            or loaded_state != state
            or pending.resolution_turn_id != turn_id
            or pending.resolution_trace_id != trace_id
        ):
            raise ConversationIntegrityError("Resolved action and PendingAction disagree")

    def _connect(self) -> pymysql.Connection[pymysql.cursors.Cursor]:
        return pymysql.connect(
            host=self._settings.mysql_host,
            port=self._settings.mysql_port,
            user="agent_app",
            password=self._settings.mysql_password,
            database="cs_db",
            autocommit=False,
        )
