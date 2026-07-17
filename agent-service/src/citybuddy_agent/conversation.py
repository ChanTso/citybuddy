"""Durable support conversation, turn, and ordered evidence transaction."""

from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass
from typing import Protocol

import pymysql


class MysqlConnectionSettings(Protocol):
    mysql_host: str
    mysql_port: int
    mysql_password: str


@dataclass(frozen=True)
class ConversationResult:
    conversation_id: str
    trace_id: str
    turn_id: str
    response_text: str
    outcome: str


class ConversationOwnershipError(Exception):
    """The requested support session is not owned by the authenticated subject."""


class CorrelationConflictError(Exception):
    """A correlation key was reused for a different validated request."""


class ConversationStore(Protocol):
    def complete_turn(
        self,
        *,
        session_id: str,
        subject: str,
        correlation_key: str,
        message: str,
        response_text: str,
    ) -> ConversationResult: ...


class MysqlConversationStore:
    """Serialize one session's turns and commit all required truth atomically."""

    def __init__(self, settings: MysqlConnectionSettings) -> None:
        self._settings = settings

    def complete_turn(
        self,
        *,
        session_id: str,
        subject: str,
        correlation_key: str,
        message: str,
        response_text: str,
    ) -> ConversationResult:
        request_fingerprint = hashlib.sha256(message.encode("utf-8")).hexdigest()
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
                        "SELECT user_subject, sandbox_id "
                        "FROM support_session WHERE session_id = %s",
                        (session_id,),
                    )
                    session = cursor.fetchone()
                    if session is None or session[0] != subject or session[1] is not None:
                        raise ConversationOwnershipError

                    cursor.execute(
                        "SELECT trace_id, turn_id, request_fingerprint, response_text, state "
                        "FROM support_turn "
                        "WHERE session_id = %s AND correlation_key = %s",
                        (session_id, correlation_key),
                    )
                    replay = cursor.fetchone()
                    if replay is not None:
                        if replay[2] != request_fingerprint:
                            raise CorrelationConflictError
                        if replay[4] != "COMPLETED" or replay[3] is None:
                            raise RuntimeError("Durable replay is not terminal")
                        connection.commit()
                        return ConversationResult(
                            conversation_id=conversation_id,
                            trace_id=str(replay[0]),
                            turn_id=str(replay[1]),
                            response_text=str(replay[3]),
                            outcome="completed",
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
                        "turn_sequence, correlation_key, request_fingerprint, input_text, state) "
                        "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'PROCESSING')",
                        (
                            turn_id,
                            conversation_id,
                            session_id,
                            subject,
                            trace_id,
                            turn_sequence,
                            correlation_key,
                            request_fingerprint,
                            message,
                        ),
                    )
                    events = (
                        (1, "USER_INPUT", {"message": message}),
                        (2, "ASSISTANT_RESPONSE", {"message": response_text}),
                        (3, "TURN_COMPLETED", {"outcome": "completed"}),
                    )
                    for sequence, event_type, payload in events:
                        cursor.execute(
                            "INSERT INTO support_event "
                            "(event_id, turn_id, trace_id, session_id, user_subject, sequence, "
                            "event_type, payload_json) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)",
                            (
                                str(uuid.uuid4()),
                                turn_id,
                                trace_id,
                                session_id,
                                subject,
                                sequence,
                                event_type,
                                json.dumps(payload, separators=(",", ":"), ensure_ascii=False),
                            ),
                        )
                    cursor.execute(
                        "UPDATE support_turn SET state = 'COMPLETED', response_text = %s, "
                        "completed_at = CURRENT_TIMESTAMP(6) WHERE turn_id = %s",
                        (response_text, turn_id),
                    )
                connection.commit()
            except Exception:
                connection.rollback()
                raise
        return ConversationResult(
            conversation_id=conversation_id,
            trace_id=trace_id,
            turn_id=turn_id,
            response_text=response_text,
            outcome="completed",
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
