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
    ACTION_SOURCE_TURN_EVENTS_SQL,
    ActionJsonError,
    ActionReceiptPayload,
    ActionSourceTurnClosureError,
    PendingActionPayload,
    PendingActionReference,
    StoredActionReceipt,
    action_argument_commitment,
    canonical_action_timestamp,
    strict_json_object,
    validate_action_source_turn_closure,
)
from .agent_control import AgentEvent
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
        receipt_expected = self.outcome == "action_completed"
        if receipt_expected != (self.action_receipt is not None):
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
    """A confirmation, decline, or expiry lost the durable local arbitration."""


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

    def fail_turn(
        self,
        *,
        start: TurnStart,
        failure_code: str,
        events: tuple[AgentEvent, ...] = (),
    ) -> None: ...


class MysqlConversationStore:
    """Reserve before agent I/O and commit each terminal turn exactly once."""

    def __init__(self, settings: MysqlConnectionSettings) -> None:
        self._settings = settings
        # Every charged network attempt is bounded to at most three seconds. Persist
        # one deadline with enough fixed margin so a crashed owner can be fenced
        # without ever re-running its agent or tool work.
        self._processing_timeout_microseconds = (settings.attempt_budget * 3 + 5) * 1_000_000

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
                    cursor.execute(
                        "SELECT source_turn_id, source_trace_id, conversation_id, session_id, "
                        "user_subject, sandbox_id, action_type, argument_commitment, order_id, "
                        "amount_minor, currency, state, expires_at, confirmation_turn_id, "
                        "confirmation_trace_id "
                        "FROM pending_action_reference WHERE pending_action_id = %s FOR UPDATE",
                        (pending.pending_action_id,),
                    )
                    owner = cursor.fetchone()
                    if owner is None or tuple(owner[:11]) != (
                        pending.source_turn_id,
                        pending.source_trace_id,
                        pending.conversation_id,
                        pending.session_id,
                        pending.user_subject,
                        pending.sandbox_id,
                        pending.action_type,
                        pending.argument_commitment,
                        pending.order_id,
                        pending.amount_minor,
                        pending.currency,
                    ):
                        raise ConversationIntegrityError(
                            "PendingAction confirmation owner is inconsistent"
                        )
                    if not isinstance(owner[12], datetime):
                        raise ConversationIntegrityError("PendingAction expiry is invalid")
                    self._lock_pending_source_turn(
                        cursor,
                        pending=pending,
                        persisted_expiry=owner[12],
                    )
                    if existing is not None:
                        if (
                            existing[2] != conversation_id
                            or existing[3] != subject
                            or existing[4] != fingerprint
                        ):
                            raise CorrelationConflictError
                        if existing[5] == "COMPLETED":
                            completed_identity = (
                                conversation_id,
                                str(existing[0]),
                                str(existing[1]),
                            )
                        elif (
                            existing[5] == "PROCESSING"
                            and owner[11] == "CONFIRMING"
                            and tuple(owner[13:15]) == (str(existing[1]), str(existing[0]))
                        ):
                            cursor.execute(
                                "UPDATE support_turn SET processing_deadline_at = "
                                "DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL %s MICROSECOND) "
                                "WHERE turn_id = %s AND state = 'PROCESSING'",
                                (self._processing_timeout_microseconds, existing[1]),
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
                        if owner[11] != "PENDING" or owner[13] is not None or owner[14] is not None:
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
                        start = TurnStart(
                            conversation_id,
                            trace_id,
                            turn_id,
                            confirmation_pending_id=pending.pending_action_id,
                        )
                        self._insert_event(
                            cursor,
                            start=start,
                            session_id=session_id,
                            subject=subject,
                            sequence=1,
                            event=AgentEvent("USER_INPUT", {"accepted": True}),
                        )
                        cursor.execute(
                            "UPDATE pending_action_reference SET state = 'CONFIRMING', "
                            "confirmation_turn_id = %s, confirmation_trace_id = %s "
                            "WHERE pending_action_id = %s AND state = 'PENDING' "
                            "AND confirmation_turn_id IS NULL "
                            "AND confirmation_trace_id IS NULL",
                            (turn_id, trace_id, pending.pending_action_id),
                        )
                        if cursor.rowcount != 1:
                            raise ActionArbitrationConflictError
                        claimed = start
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
                "AND turn_record.user_subject = %s AND session.sandbox_id <=> %s",
                (session_id, correlation_key, subject, sandbox_id),
            )
            rows = cursor.fetchall()
            if not rows:
                return None
            if len(rows) != 1:
                raise RuntimeError("Durable turn correlation cardinality is inconsistent")
            row = rows[0]
            if row[2] != fingerprint:
                raise CorrelationConflictError
            if row[4] != "COMPLETED":
                return None
            if row[3] is None or row[5] is None:
                raise RuntimeError("Durable replay is not terminal")
            if row[5] == "action_pending":
                self._load_pending_action_for_turn(
                    cursor,
                    turn_id=str(row[1]),
                    trace_id=str(row[0]),
                    conversation_id=str(row[6]),
                    session_id=session_id,
                    subject=subject,
                    sandbox_id=sandbox_id,
                )
            elif row[5] in {"action_declined", "action_expired"}:
                self._validate_resolved_action_turn(
                    cursor,
                    turn_id=str(row[1]),
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
                        retrieval_evidence = self._load_retrieval_evidence(cursor, str(existing[1]))
                        action_receipt = self._load_action_receipt(cursor, str(existing[1]))
                        if (existing[5] == "action_completed") != (action_receipt is not None):
                            raise ConversationIntegrityError(
                                "ActionReceipt projection and terminal outcome disagree"
                            )
                        if existing[5] == "action_pending":
                            self._load_pending_action_for_turn(
                                cursor,
                                turn_id=str(existing[1]),
                                trace_id=str(existing[0]),
                                conversation_id=conversation_id,
                                session_id=session_id,
                                subject=subject,
                                sandbox_id=sandbox_id,
                            )
                        elif existing[5] in {"action_declined", "action_expired"}:
                            self._validate_resolved_action_turn(
                                cursor,
                                turn_id=str(existing[1]),
                                session_id=session_id,
                                subject=subject,
                                sandbox_id=sandbox_id,
                                outcome=str(existing[5]),
                            )
                        if action_receipt is not None:
                            self._validate_receipt_pending_truth(
                                cursor,
                                stored=action_receipt,
                                session_id=session_id,
                                subject=subject,
                                sandbox_id=sandbox_id,
                            )
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
                        "expiresAt": canonical_action_timestamp(pending_action.expires_at),
                    }:
                        raise RuntimeError("PendingAction reference and preparation event disagree")
                    if pending_action is not None:
                        cursor.execute(
                            "INSERT INTO pending_action_reference "
                            "(pending_action_id, source_turn_id, source_trace_id, conversation_id, "
                            "session_id, user_subject, sandbox_id, action_type, "
                            "argument_commitment, order_id, amount_minor, currency, state, "
                            "expires_at) VALUES (%s, %s, %s, %s, %s, %s, "
                            "(SELECT sandbox_id FROM support_session WHERE session_id = %s), "
                            "%s, %s, %s, %s, %s, 'PENDING', %s)",
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
            None,
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
                "SELECT turn_id, trace_id, conversation_id FROM support_turn "
                "WHERE session_id = %s AND user_subject = %s AND outcome = 'action_pending' "
                "ORDER BY turn_sequence DESC LIMIT 1",
                (session_id, subject),
            )
            turn = cursor.fetchone()
            if turn is None:
                return None
            reference, state = self._load_pending_action_for_turn(
                cursor,
                turn_id=str(turn[0]),
                trace_id=str(turn[1]),
                conversation_id=str(turn[2]),
                session_id=session_id,
                subject=subject,
                sandbox_id=sandbox_id,
            )
        return ActionReferenceSnapshot(reference, cast(ActionReferenceState, state))

    def complete_action_decline(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        response_text: str,
    ) -> ConversationResult:
        with self._connect() as connection:
            try:
                with connection.cursor() as cursor:
                    turn = self._lock_executable_turn(cursor, start)
                    pending_state = self._lock_matching_pending(cursor, pending)
                    if pending_state != "PENDING":
                        raise ActionArbitrationConflictError
                    cursor.execute(
                        "UPDATE pending_action_reference SET state = 'DECLINED', "
                        "resolved_at = CURRENT_TIMESTAMP(6) "
                        "WHERE pending_action_id = %s AND state = 'PENDING'",
                        (pending.pending_action_id,),
                    )
                    if cursor.rowcount != 1:
                        raise RuntimeError("PendingAction decline lost its state transition")
                    self._insert_event(
                        cursor,
                        start=start,
                        session_id=str(turn[0]),
                        subject=str(turn[1]),
                        sequence=2,
                        event=AgentEvent(
                            "ACTION_DECLINED",
                            {
                                "pendingActionId": pending.pending_action_id,
                                "outcome": "declined",
                            },
                        ),
                    )
                    self._finish_turn(
                        cursor,
                        start=start,
                        turn=turn,
                        response_text=response_text,
                        outcome="action_declined",
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
            "action_declined",
        )

    def complete_action_expired(
        self,
        *,
        start: TurnStart,
        pending: PendingActionReference,
        response_text: str,
    ) -> ConversationResult:
        with self._connect() as connection:
            try:
                with connection.cursor() as cursor:
                    turn = self._lock_executable_turn(cursor, start)
                    pending_state = self._lock_matching_pending(
                        cursor, pending, require_expired=True
                    )
                    if pending_state != "PENDING":
                        raise ActionArbitrationConflictError
                    cursor.execute(
                        "UPDATE pending_action_reference SET state = 'EXPIRED', "
                        "resolved_at = CURRENT_TIMESTAMP(6) "
                        "WHERE pending_action_id = %s AND state = 'PENDING'",
                        (pending.pending_action_id,),
                    )
                    if cursor.rowcount != 1:
                        raise RuntimeError("PendingAction expiry lost its state transition")
                    self._insert_event(
                        cursor,
                        start=start,
                        session_id=str(turn[0]),
                        subject=str(turn[1]),
                        sequence=2,
                        event=AgentEvent(
                            "ACTION_EXPIRED",
                            {
                                "pendingActionId": pending.pending_action_id,
                                "outcome": "expired",
                            },
                        ),
                    )
                    self._finish_turn(
                        cursor,
                        start=start,
                        turn=turn,
                        response_text=response_text,
                        outcome="action_expired",
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
            "action_expired",
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
        stored = StoredActionReceipt(receipt, pending.source_turn_id, start.turn_id)
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
                    receipt_sequence = 2 + len(events)
                    if pending_state == "CONFIRMING":
                        cursor.execute(
                            "INSERT INTO action_receipt_projection "
                            "(receipt_id, pending_action_id, source_turn_id, confirmation_turn_id, "
                            "confirmation_trace_id, session_id, user_subject, sandbox_id, "
                            "action_type, argument_commitment, status, order_id, refund_id, "
                            "resource_version, amount_minor, currency, committed_at, "
                            "receipt_commitment, published_event_sequence) "
                            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, "
                            "%s, %s, %s, %s, %s, %s)",
                            (
                                receipt.receipt_id,
                                receipt.pending_action_id,
                                pending.source_turn_id,
                                start.turn_id,
                                start.trace_id,
                                pending.session_id,
                                pending.user_subject,
                                pending.sandbox_id,
                                receipt.action_type,
                                pending.argument_commitment,
                                receipt.status,
                                receipt.order_id,
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
                    elif pending_state == "CONFIRMED":
                        raise ConversationIntegrityError(
                            "Confirmed PendingAction has a non-terminal confirmation turn"
                        )
                    else:
                        raise ConversationIntegrityError(
                            "ActionReceipt requires a claimed PendingAction"
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

    def fail_turn(
        self,
        *,
        start: TurnStart,
        failure_code: str,
        events: tuple[AgentEvent, ...] = (),
    ) -> None:
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
                        sequence=sequence,
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

    @staticmethod
    def _validate_pending_source_turn(
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
                raise ActionSourceTurnClosureError("PendingAction expiry is inconsistent")
            validate_action_source_turn_closure(
                rows,
                expected_trace_id=pending.source_trace_id,
                expected_session_id=pending.session_id,
                expected_user_subject=pending.user_subject,
                pending_action_id=pending.pending_action_id,
                action_type=pending.action_type,
                argument_commitment=pending.argument_commitment,
                expires_at=persisted_aware,
            )
        except (AttributeError, ActionSourceTurnClosureError, ValueError) as exception:
            raise ConversationIntegrityError(
                "PendingAction source turn is inconsistent"
            ) from exception

    @classmethod
    def _lock_pending_source_turn(
        cls,
        cursor: pymysql.cursors.Cursor,
        *,
        pending: PendingActionReference,
        persisted_expiry: object,
    ) -> None:
        cursor.execute(ACTION_SOURCE_TURN_EVENTS_SQL + " FOR SHARE", (pending.source_turn_id,))
        rows = cursor.fetchall()
        cls._validate_pending_source_turn(
            rows,
            pending=pending,
            persisted_expiry=persisted_expiry,
        )

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
            "amount_minor, currency, state, expires_at, confirmation_turn_id, "
            "confirmation_trace_id, resolved_at "
            "FROM pending_action_reference WHERE pending_action_id = %s FOR UPDATE",
            (pending.pending_action_id,),
        )
        row = cursor.fetchone()
        if row is None:
            raise ConversationIntegrityError("PendingAction reference disappeared")
        expected = (
            pending.source_turn_id,
            pending.source_trace_id,
            pending.conversation_id,
            pending.session_id,
            pending.user_subject,
            pending.sandbox_id,
            pending.action_type,
            pending.argument_commitment,
            pending.order_id,
            pending.amount_minor,
            pending.currency,
        )
        if tuple(row[:11]) != expected or row[11] not in {
            "PENDING",
            "CONFIRMING",
            "DECLINED",
            "EXPIRED",
            "CONFIRMED",
        }:
            raise ConversationIntegrityError("PendingAction reference is inconsistent")
        state = str(row[11])
        expires_at = row[12]
        if not isinstance(expires_at, datetime):
            raise ConversationIntegrityError("PendingAction expiry is invalid")
        cls._lock_pending_source_turn(
            cursor,
            pending=pending,
            persisted_expiry=expires_at,
        )
        aware_expiry = expires_at.replace(tzinfo=UTC) if expires_at.tzinfo is None else expires_at
        expired = aware_expiry <= datetime.now(UTC)
        confirmation_binding = tuple(row[13:15])
        resolved_at = row[15]
        if (
            state == "PENDING"
            and (confirmation_binding != (None, None) or resolved_at is not None)
            or state == "CONFIRMING"
            and (
                not all(isinstance(value, str) for value in confirmation_binding)
                or resolved_at is not None
            )
            or state == "CONFIRMED"
            and (
                not all(isinstance(value, str) for value in confirmation_binding)
                or resolved_at is None
            )
            or state in {"DECLINED", "EXPIRED"}
            and (confirmation_binding != (None, None) or resolved_at is None)
        ):
            raise ConversationIntegrityError("PendingAction arbitration state is inconsistent")
        if (
            state in {"CONFIRMING", "CONFIRMED"}
            and confirmation_turn_id is not None
            and confirmation_binding != (confirmation_turn_id, confirmation_trace_id)
        ):
            raise ActionArbitrationConflictError
        if state != "PENDING":
            return state
        if require_expired and not expired:
            raise ActionArbitrationConflictError
        if not require_expired and expired:
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
        cls._insert_event(
            cursor,
            start=start,
            session_id=str(turn[0]),
            subject=str(turn[1]),
            sequence=next_sequence,
            event=AgentEvent("AGENT_OUTCOME", {"outcome": outcome}),
        )
        cls._insert_event(
            cursor,
            start=start,
            session_id=str(turn[0]),
            subject=str(turn[1]),
            sequence=next_sequence + 1,
            event=AgentEvent("ASSISTANT_RESPONSE", {"outcome": outcome}),
        )
        cls._insert_event(
            cursor,
            start=start,
            session_id=str(turn[0]),
            subject=str(turn[1]),
            sequence=next_sequence + 2,
            event=AgentEvent("TURN_COMPLETED", {"outcome": outcome}),
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
            "order_id, amount_minor, currency, state, expires_at, resolved_at, "
            "confirmation_turn_id, confirmation_trace_id "
            "FROM pending_action_reference WHERE source_turn_id = %s LIMIT 2",
            (turn_id,),
        )
        rows = cursor.fetchall()
        if len(rows) != 1:
            raise ConversationIntegrityError("PendingAction reference cardinality is inconsistent")
        row = rows[0]
        if tuple(row[1:7]) != (
            turn_id,
            trace_id,
            conversation_id,
            session_id,
            subject,
            sandbox_id,
        ):
            raise ConversationIntegrityError("PendingAction reference ownership is inconsistent")
        state = str(row[12])
        if state not in {"PENDING", "CONFIRMING", "DECLINED", "EXPIRED", "CONFIRMED"}:
            raise ConversationIntegrityError("PendingAction reference state is inconsistent")
        expires_at = row[13]
        if not isinstance(expires_at, datetime):
            raise ConversationIntegrityError("PendingAction expiry is invalid")
        if expires_at.tzinfo is None:
            expires_at = expires_at.replace(tzinfo=UTC)
        resolved_at = row[14]
        if (state in {"PENDING", "CONFIRMING"}) != (resolved_at is None):
            raise ConversationIntegrityError("PendingAction resolution is inconsistent")
        confirmation_binding = tuple(row[15:17])
        if (
            state == "PENDING"
            and confirmation_binding != (None, None)
            or state in {"CONFIRMING", "CONFIRMED"}
            and (
                not all(isinstance(value, str) for value in confirmation_binding)
                or not all(str(uuid.UUID(str(value))) == value for value in confirmation_binding)
            )
            or state in {"DECLINED", "EXPIRED"}
            and confirmation_binding != (None, None)
        ):
            raise ConversationIntegrityError("PendingAction confirmation binding is inconsistent")
        try:
            pending_action_id = str(row[0])
            if str(uuid.UUID(pending_action_id)) != pending_action_id:
                raise ValueError("PendingAction identity is not canonical")
            reference = PendingActionReference(
                pending_action_id=pending_action_id,
                source_turn_id=turn_id,
                source_trace_id=trace_id,
                conversation_id=conversation_id,
                session_id=session_id,
                user_subject=subject,
                sandbox_id=sandbox_id,
                action_type=str(row[7]),
                argument_commitment=str(row[8]),
                order_id=str(row[9]),
                amount_minor=int(row[10]),
                currency=str(row[11]),
                expires_at=expires_at,
            )
        except (TypeError, ValueError) as exception:
            raise ConversationIntegrityError(
                "PendingAction reference content is invalid"
            ) from exception
        if (
            reference.action_type != "REFUND_REQUEST"
            or reference.argument_commitment
            != action_argument_commitment(
                reference.action_type,
                reference.order_id,
                reference.amount_minor,
                reference.currency,
            )
        ):
            raise ConversationIntegrityError("PendingAction commitment is inconsistent")
        cursor.execute(ACTION_SOURCE_TURN_EVENTS_SQL, (turn_id,))
        event_rows = cursor.fetchall()
        cls._validate_pending_source_turn(
            event_rows,
            pending=reference,
            persisted_expiry=expires_at,
        )
        cursor.execute(
            "SELECT confirmation_turn_id, confirmation_trace_id "
            "FROM action_receipt_projection "
            "WHERE pending_action_id = %s LIMIT 2",
            (reference.pending_action_id,),
        )
        projection_rows = cursor.fetchall()
        if state == "CONFIRMED":
            if len(projection_rows) != 1:
                raise ConversationIntegrityError(
                    "Confirmed PendingAction projection is inconsistent"
                )
            if (
                tuple(projection_rows[0]) != confirmation_binding
                or cls._load_action_receipt(cursor, str(projection_rows[0][0])) is None
            ):
                raise ConversationIntegrityError("Confirmed PendingAction has no ActionReceipt")
        elif projection_rows:
            raise ConversationIntegrityError("Unconfirmed PendingAction has an ActionReceipt")
        if state == "CONFIRMING":
            cursor.execute(
                "SELECT trace_id, conversation_id, session_id, user_subject, state "
                "FROM support_turn WHERE turn_id = %s LIMIT 2",
                (confirmation_binding[0],),
            )
            confirmation_rows = cursor.fetchall()
            if len(confirmation_rows) != 1 or tuple(confirmation_rows[0]) != (
                confirmation_binding[1],
                conversation_id,
                session_id,
                subject,
                "PROCESSING",
            ):
                raise ConversationIntegrityError(
                    "Confirming PendingAction turn binding is inconsistent"
                )
        return reference, state

    @staticmethod
    def _load_action_receipt(
        cursor: pymysql.cursors.Cursor, turn_id: str
    ) -> StoredActionReceipt | None:
        cursor.execute(
            "SELECT receipt_id, pending_action_id, action_type, status, order_id, "
            "refund_id, resource_version, amount_minor, currency, committed_at, "
            "source_turn_id, confirmation_turn_id, confirmation_trace_id, session_id, "
            "user_subject, argument_commitment, "
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
        committed_at = row[9]
        if not isinstance(committed_at, datetime):
            raise ConversationIntegrityError("ActionReceipt committed timestamp is invalid")
        if committed_at.tzinfo is None:
            committed_at = committed_at.replace(tzinfo=UTC)
        try:
            receipt = ActionReceiptPayload.model_validate(
                {
                    "receiptId": str(row[0]),
                    "pendingActionId": str(row[1]),
                    "actionType": str(row[2]),
                    "status": str(row[3]),
                    "orderId": str(row[4]),
                    "refundId": str(row[5]),
                    "resourceVersion": int(row[6]),
                    "amountMinor": int(row[7]),
                    "currency": str(row[8]),
                    "committedAt": committed_at,
                    "replayed": True,
                }
            )
        except (TypeError, ValueError) as exception:
            raise ConversationIntegrityError(
                "ActionReceipt projection content is invalid"
            ) from exception
        cursor.execute(
            "SELECT trace_id, session_id, user_subject, event_type, payload_json "
            "FROM support_event WHERE turn_id = %s AND sequence = %s LIMIT 2",
            (turn_id, row[17]),
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
        if (
            row[15] != receipt.argument_commitment
            or row[16] != receipt.receipt_commitment
            or tuple(event_rows[0][:3]) != tuple(row[12:15])
            or event_payload
            != {
                "receiptId": receipt.receipt_id,
                "pendingActionId": receipt.pending_action_id,
                "status": receipt.status,
                "receiptCommitment": receipt.receipt_commitment,
            }
        ):
            raise ConversationIntegrityError("ActionReceipt projection commitment is inconsistent")
        return StoredActionReceipt(receipt, str(row[10]), str(row[11]))

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
        session_id: str,
        subject: str,
        sandbox_id: str | None,
        outcome: str,
    ) -> None:
        event_type, state, event_outcome = {
            "action_declined": ("ACTION_DECLINED", "DECLINED", "declined"),
            "action_expired": ("ACTION_EXPIRED", "EXPIRED", "expired"),
        }[outcome]
        cursor.execute(ACTION_SOURCE_TURN_EVENTS_SQL, (turn_id,))
        event_rows = cursor.fetchall()
        try:
            terminal_payloads = [
                strict_json_object(str(row[6]).encode("utf-8")) for row in event_rows[-3:]
            ]
        except ActionJsonError as exception:
            raise ConversationIntegrityError("Resolved action event is invalid") from exception
        if (
            not 4 <= len(event_rows) <= 48
            or any(
                len(row) != 7 or row[2] != session_id or row[3] != subject or row[4] != sequence
                for sequence, row in enumerate(event_rows, start=1)
            )
            or [row[5] for row in event_rows[-3:]]
            != ["AGENT_OUTCOME", "ASSISTANT_RESPONSE", "TURN_COMPLETED"]
            or any(payload != {"outcome": outcome} for payload in terminal_payloads)
            or sum(row[5] in {"ACTION_DECLINED", "ACTION_EXPIRED"} for row in event_rows) != 1
            or event_rows[-4][5] != event_type
        ):
            raise ConversationIntegrityError("Resolved action event cardinality is inconsistent")
        try:
            payload = strict_json_object(str(event_rows[-4][6]).encode("utf-8"))
        except ActionJsonError as exception:
            raise ConversationIntegrityError("Resolved action event is invalid") from exception
        if not isinstance(payload, dict):
            raise ConversationIntegrityError("Resolved action event is invalid")
        pending_action_id = payload.get("pendingActionId")
        if payload != {
            "pendingActionId": pending_action_id,
            "outcome": event_outcome,
        } or not isinstance(pending_action_id, str):
            raise ConversationIntegrityError("Resolved action event is inconsistent")
        try:
            if str(uuid.UUID(pending_action_id)) != pending_action_id:
                raise ValueError
        except ValueError as exception:
            raise ConversationIntegrityError("Resolved action identity is invalid") from exception
        cursor.execute(
            "SELECT source_turn_id, source_trace_id, conversation_id, session_id, "
            "user_subject, sandbox_id, state, resolved_at "
            "FROM pending_action_reference WHERE pending_action_id = %s LIMIT 2",
            (pending_action_id,),
        )
        pending_rows = cursor.fetchall()
        if len(pending_rows) != 1:
            raise ConversationIntegrityError("Resolved PendingAction cardinality is inconsistent")
        pending_row = pending_rows[0]
        if (
            tuple(pending_row[3:7]) != (session_id, subject, sandbox_id, state)
            or pending_row[7] is None
        ):
            raise ConversationIntegrityError("Resolved PendingAction is inconsistent")
        pending, loaded_state = cls._load_pending_action_for_turn(
            cursor,
            turn_id=str(pending_row[0]),
            trace_id=str(pending_row[1]),
            conversation_id=str(pending_row[2]),
            session_id=session_id,
            subject=subject,
            sandbox_id=sandbox_id,
        )
        if pending.pending_action_id != pending_action_id or loaded_state != state:
            raise ConversationIntegrityError("Resolved action event and PendingAction disagree")

    @classmethod
    def _load_action_receipt_by_pending(
        cls, cursor: pymysql.cursors.Cursor, pending_action_id: str
    ) -> StoredActionReceipt:
        cursor.execute(
            "SELECT confirmation_turn_id FROM action_receipt_projection "
            "WHERE pending_action_id = %s",
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

    def _connect(self) -> pymysql.Connection[pymysql.cursors.Cursor]:
        return pymysql.connect(
            host=self._settings.mysql_host,
            port=self._settings.mysql_port,
            user="agent_app",
            password=self._settings.mysql_password,
            database="cs_db",
            autocommit=False,
        )
