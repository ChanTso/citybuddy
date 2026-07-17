"""Durable support turn reservation, terminal result, and ordered evidence."""

from __future__ import annotations

import hashlib
import json
import time
import uuid
from dataclasses import dataclass
from typing import Protocol

import pymysql

from .agent_control import AgentEvent


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


@dataclass(frozen=True)
class TurnStart:
    conversation_id: str
    trace_id: str
    turn_id: str
    replay: ConversationResult | None = None


class ConversationOwnershipError(Exception):
    """The requested support session is not owned by the authenticated subject."""


class CorrelationConflictError(Exception):
    """A correlation key was reused for a different validated request."""


class TurnInProgressError(Exception):
    """Another request already owns execution for this durable turn."""


class TurnFailedError(Exception):
    """The durable turn previously ended in a non-permissive internal failure."""


class ConversationStore(Protocol):
    def begin_turn(
        self,
        *,
        session_id: str,
        subject: str,
        correlation_key: str,
        message: str,
    ) -> TurnStart: ...

    def complete_turn(
        self,
        *,
        start: TurnStart,
        response_text: str,
        outcome: str,
        events: tuple[AgentEvent, ...],
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

    def begin_turn(
        self,
        *,
        session_id: str,
        subject: str,
        correlation_key: str,
        message: str,
    ) -> TurnStart:
        for attempt in range(61):
            try:
                return self._begin_turn_once(
                    session_id=session_id,
                    subject=subject,
                    correlation_key=correlation_key,
                    message=message,
                )
            except TurnInProgressError:
                if attempt == 60:
                    raise
                time.sleep(0.05)
        raise RuntimeError("Bounded turn wait did not terminate")

    def _begin_turn_once(
        self,
        *,
        session_id: str,
        subject: str,
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
                    if session is None or session[0] != subject or session[1] is not None:
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
                        result = ConversationResult(
                            conversation_id,
                            str(existing[0]),
                            str(existing[1]),
                            str(existing[3]),
                            str(existing[5]),
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
    ) -> ConversationResult:
        with self._connect() as connection:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT session_id, user_subject, state, "
                        "processing_deadline_at > CURRENT_TIMESTAMP(6) FROM support_turn "
                        "WHERE turn_id = %s FOR UPDATE",
                        (start.turn_id,),
                    )
                    turn = cursor.fetchone()
                    if turn is None or turn[2] != "PROCESSING" or not turn[3]:
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
            start.conversation_id, start.trace_id, start.turn_id, response_text, outcome
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

    def _connect(self) -> pymysql.Connection[pymysql.cursors.Cursor]:
        return pymysql.connect(
            host=self._settings.mysql_host,
            port=self._settings.mysql_port,
            user="agent_app",
            password=self._settings.mysql_password,
            database="cs_db",
            autocommit=False,
        )
