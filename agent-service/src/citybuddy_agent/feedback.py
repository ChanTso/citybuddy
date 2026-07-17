"""Append-only feedback bound to durable support-session and trace truth."""

from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass
from typing import Literal, Protocol

import pymysql


class FeedbackConnectionSettings(Protocol):
    mysql_host: str
    mysql_port: int
    mysql_password: str


@dataclass(frozen=True)
class FeedbackRecord:
    feedback_id: str
    trace_id: str
    rating: Literal["POSITIVE", "NEGATIVE"]


class FeedbackOwnershipError(Exception):
    """The session and trace are not owned by the authenticated subject."""


class FeedbackConflictError(Exception):
    """An idempotency key was reused for a different feedback intent."""


class FeedbackStore(Protocol):
    def append(
        self,
        *,
        session_id: str,
        subject: str,
        trace_id: str,
        idempotency_key: str,
        rating: Literal["POSITIVE", "NEGATIVE"],
        comment: str | None,
    ) -> FeedbackRecord: ...


class MysqlFeedbackStore:
    """Serialize per session, re-read ownership, and insert one immutable signal."""

    def __init__(self, settings: FeedbackConnectionSettings) -> None:
        self._settings = settings

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
        fingerprint = hashlib.sha256(
            json.dumps(
                {"comment": comment, "rating": rating},
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            ).encode("utf-8")
        ).hexdigest()
        with self._connect() as connection:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT user_subject, state FROM support_conversation "
                        "WHERE session_id = %s FOR UPDATE",
                        (session_id,),
                    )
                    conversation = cursor.fetchone()
                    if (
                        conversation is None
                        or conversation[0] != subject
                        or conversation[1] != "ACTIVE"
                    ):
                        raise FeedbackOwnershipError
                    # Locking the conversation serializes absent feedback keys per
                    # session while preserving support_session's read-only grant.
                    cursor.execute(
                        "SELECT user_subject, sandbox_id FROM support_session "
                        "WHERE session_id = %s",
                        (session_id,),
                    )
                    session = cursor.fetchone()
                    if session is None or session[0] != subject or session[1] is not None:
                        raise FeedbackOwnershipError
                    cursor.execute(
                        "SELECT user_subject, session_id, state FROM support_turn "
                        "WHERE trace_id = %s",
                        (trace_id,),
                    )
                    turn = cursor.fetchone()
                    if (
                        turn is None
                        or turn[0] != subject
                        or turn[1] != session_id
                        or turn[2] != "COMPLETED"
                    ):
                        raise FeedbackOwnershipError
                    cursor.execute(
                        "SELECT feedback_id, request_fingerprint, trace_id, rating "
                        "FROM support_feedback WHERE session_id = %s "
                        "AND idempotency_key = %s",
                        (session_id, idempotency_key),
                    )
                    existing = cursor.fetchone()
                    if existing is not None:
                        if existing[1] != fingerprint or existing[2] != trace_id:
                            raise FeedbackConflictError
                        connection.commit()
                        return FeedbackRecord(str(existing[0]), str(existing[2]), existing[3])
                    feedback_id = str(uuid.uuid4())
                    cursor.execute(
                        "INSERT INTO support_feedback "
                        "(feedback_id, session_id, user_subject, trace_id, idempotency_key, "
                        "request_fingerprint, rating, comment_text) "
                        "VALUES (%s, %s, %s, %s, %s, %s, %s, %s)",
                        (
                            feedback_id,
                            session_id,
                            subject,
                            trace_id,
                            idempotency_key,
                            fingerprint,
                            rating,
                            comment,
                        ),
                    )
                connection.commit()
            except Exception:
                connection.rollback()
                raise
        return FeedbackRecord(feedback_id, trace_id, rating)

    def _connect(self) -> pymysql.Connection[pymysql.cursors.Cursor]:
        return pymysql.connect(
            host=self._settings.mysql_host,
            port=self._settings.mysql_port,
            user="agent_app",
            password=self._settings.mysql_password,
            database="cs_db",
            autocommit=False,
        )
