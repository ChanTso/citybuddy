#!/usr/bin/env python3
"""Seed deterministic completed-turn history for the warm-history benchmark."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Literal, cast

import pymysql
from citybuddy_agent.agent_control import (
    MAX_ASSISTANT_MESSAGE_CHARACTERS,
    MAX_USER_MESSAGE_CHARACTERS,
    SESSION_CONTEXT_MAX_TURNS,
    SESSION_CONTEXT_TOKEN_BUDGET,
    ContextWindow,
    ConversationHistory,
    ConversationTurn,
    ModelRouter,
    ProviderRoute,
    RuleRouter,
    SessionContextPolicy,
)

CaseName = Literal["empty", "one-short", "max-count", "high-pressure"]

CASE_NAMES: tuple[CaseName, ...] = ("empty", "one-short", "max-count", "high-pressure")
CURRENT_MESSAGE = "hello, can you tell me about delivery times"
FORMAT_VERSION = "citybuddy-agent-warm-history-fixture-v1"
TOKEN_ESTIMATOR = "utf8-bytes-v1"
_FIXTURE_NAMESPACE = uuid.UUID("f6f69f3c-e7c7-5f16-9ee7-b88d9d2ae821")
_FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
_SETUP_NONCE = re.compile(r"^[0-9a-f]{32}$")
_BENCH_SUBJECT = re.compile(r"^bench-user-[0-9]+$")
_UNSAFE_TEXT_FRAGMENTS = (
    "refund",
    "退款",
    "identity",
    "身份",
    "authoriz",
    "authoris",
    "授权",
    "authenticat",
    "confirm",
    "确认",
    "credential",
    "password",
    "permission",
    "system:",
    "ignore previous",
    "instruction",
)


@dataclass(frozen=True)
class FixturePlan:
    case_name: CaseName
    persisted_turns: tuple[ConversationTurn, ...]
    query_candidates: tuple[ConversationTurn, ...]
    loaded_history: ConversationHistory
    context_window: ContextWindow
    tool_profile: str


def parse_case(value: str) -> CaseName:
    if value not in CASE_NAMES:
        expected = ", ".join(CASE_NAMES)
        raise ValueError(f"Unknown warm-history case {value!r}; expected one of: {expected}")
    return cast(CaseName, value)


def persisted_turn_count(case_name: CaseName) -> int:
    if case_name == "empty":
        return 0
    if case_name == "one-short":
        return 1
    return SESSION_CONTEXT_MAX_TURNS + 1


def _fill_ascii(prefix: str, filler: str, length: int) -> str:
    if not prefix.isascii() or not filler.isascii() or not filler:
        raise ValueError("Warm-history fixture text must use non-empty ASCII components")
    if len(prefix) > length:
        raise ValueError("Warm-history fixture prefix exceeds its field limit")
    repeats = (length - len(prefix) + len(filler) - 1) // len(filler)
    value = (prefix + filler * repeats)[:length]
    if len(value) != length or not value.isascii():
        raise RuntimeError("Warm-history fixture text did not reach its exact ASCII bound")
    return value


def history_text(case_name: CaseName, sequence: int) -> tuple[str, str]:
    if not 1 <= sequence <= persisted_turn_count(case_name):
        raise ValueError("Warm-history turn sequence is outside its case")
    if case_name == "high-pressure":
        user_text = _fill_ascii(
            f"Earlier parcel timing detail {sequence:02d}. ",
            "Ordinary parcel timing detail. ",
            MAX_USER_MESSAGE_CHARACTERS,
        )
        assistant_text = _fill_ascii(
            f"Neutral parcel timing note {sequence:02d}. ",
            "General parcel timing note. ",
            MAX_ASSISTANT_MESSAGE_CHARACTERS,
        )
    else:
        user_text = f"Earlier parcel note {sequence:02d}."
        assistant_text = f"Neutral reply {sequence:02d}."
    validate_safe_text(user_text)
    validate_safe_text(assistant_text)
    return user_text, assistant_text


def validate_safe_text(value: str) -> None:
    normalized = value.casefold()
    unsafe = next((fragment for fragment in _UNSAFE_TEXT_FRAGMENTS if fragment in normalized), None)
    if unsafe is not None:
        raise ValueError(f"Warm-history fixture text contains forbidden fragment {unsafe!r}")


def _fixture_uuid(session_id: str, case_name: CaseName, kind: str, sequence: int) -> str:
    return str(uuid.uuid5(_FIXTURE_NAMESPACE, f"{session_id}:{case_name}:{kind}:{sequence}"))


def build_persisted_turns(case_name: CaseName, session_id: str) -> tuple[ConversationTurn, ...]:
    if not session_id:
        raise ValueError("Warm-history fixture requires a session id")
    turns: list[ConversationTurn] = []
    for sequence in range(1, persisted_turn_count(case_name) + 1):
        user_text, assistant_text = history_text(case_name, sequence)
        turns.append(
            ConversationTurn(
                turn_id=_fixture_uuid(session_id, case_name, "turn", sequence),
                turn_sequence=sequence,
                user_text=user_text,
                assistant_text=assistant_text,
            )
        )
    return tuple(turns)


def select_loader_history(
    persisted_turns: tuple[ConversationTurn, ...],
) -> tuple[tuple[ConversationTurn, ...], ConversationHistory]:
    query_candidates = persisted_turns[-(SESSION_CONTEXT_MAX_TURNS + 1) :]
    older_turns_available = len(query_candidates) > SESSION_CONTEXT_MAX_TURNS
    loaded = query_candidates[-SESSION_CONTEXT_MAX_TURNS:]
    return query_candidates, ConversationHistory(loaded, older_turns_available)


def build_fixture_plan(case_name: CaseName, session_id: str = "fixture-session") -> FixturePlan:
    validate_safe_text(CURRENT_MESSAGE)
    persisted_turns = build_persisted_turns(case_name, session_id)
    query_candidates, loaded_history = select_loader_history(persisted_turns)
    context_window = SessionContextPolicy().select(loaded_history)
    prior_task_context: tuple[str, ...] = ()
    if context_window.turns:
        latest = context_window.turns[-1]
        prior_task_context = (latest.user_text, latest.assistant_text)
    signals = RuleRouter().signals(CURRENT_MESSAGE, prior_task_context)
    tool_profile = (
        ModelRouter((ProviderRoute("fixture", "fixture"),), 16).plan(signals).tool_profile
    )
    if tool_profile != "read":
        raise ValueError("Warm-history fixture changed the fixed delivery request route")
    return FixturePlan(
        case_name=case_name,
        persisted_turns=persisted_turns,
        query_candidates=query_candidates,
        loaded_history=loaded_history,
        context_window=context_window,
        tool_profile=tool_profile,
    )


def select_target_session_ids(pool_path: Path, count: int) -> tuple[str, ...]:
    if count < 1:
        raise ValueError("Warm-history session count must be a positive integer")
    try:
        decoded: object = json.loads(pool_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        raise ValueError("Cannot read the agent benchmark pool") from exception
    if not isinstance(decoded, list) or len(decoded) < count:
        raise ValueError(f"Agent benchmark pool must contain at least {count} entries")
    session_ids: list[str] = []
    for entry in decoded[:count]:
        if not isinstance(entry, dict) or not isinstance(entry.get("sessionId"), str):
            raise ValueError("Agent benchmark pool contains an invalid session entry")
        session_id = cast(str, entry["sessionId"])
        if not session_id:
            raise ValueError("Agent benchmark pool contains an empty session id")
        session_ids.append(session_id)
    if len(set(session_ids)) != len(session_ids):
        raise ValueError("Agent benchmark pool repeats a target session id")
    return tuple(session_ids)


def _seed_session(
    cursor: pymysql.cursors.Cursor,
    *,
    session_id: str,
    plan: FixturePlan,
    fixture_timestamp: datetime,
) -> None:
    cursor.execute(
        "SELECT conversation.conversation_id, conversation.user_subject, "
        "conversation.state, conversation.next_turn_sequence "
        "FROM support_conversation conversation "
        "WHERE conversation.session_id = %s FOR UPDATE",
        (session_id,),
    )
    rows = cursor.fetchall()
    if len(rows) != 1:
        raise RuntimeError("Warm-history target has no unique conversation")
    (
        conversation_id,
        conversation_subject,
        state,
        next_sequence,
    ) = rows[0]
    cursor.execute(
        "SELECT user_subject, sandbox_id FROM support_session WHERE session_id = %s",
        (session_id,),
    )
    session_row = cursor.fetchone()
    if session_row is None:
        raise RuntimeError("Warm-history target has no support session")
    session_subject, sandbox_id = session_row
    if (
        not isinstance(conversation_id, str)
        or not isinstance(conversation_subject, str)
        or conversation_subject != session_subject
        or _BENCH_SUBJECT.fullmatch(conversation_subject) is None
        or sandbox_id is not None
        or state != "ACTIVE"
        or type(next_sequence) is not int
        or next_sequence != 0
    ):
        raise RuntimeError("Warm-history target is not an empty active benchmark conversation")
    cursor.execute(
        "SELECT COUNT(*) FROM support_turn WHERE conversation_id = %s",
        (conversation_id,),
    )
    count_row = cursor.fetchone()
    if count_row is None or type(count_row[0]) is not int or count_row[0] != 0:
        raise RuntimeError("Warm-history target conversation already contains turns")

    inserted: list[tuple[object, ...]] = []
    for turn in plan.persisted_turns:
        inserted.append(
            (
                turn.turn_id,
                conversation_id,
                session_id,
                conversation_subject,
                _fixture_uuid(session_id, plan.case_name, "trace", turn.turn_sequence),
                turn.turn_sequence,
                f"warm-history-{plan.case_name}-{turn.turn_sequence:02d}",
                hashlib.sha256(turn.user_text.encode("utf-8")).hexdigest(),
                turn.user_text,
                turn.assistant_text,
                fixture_timestamp,
                fixture_timestamp,
            )
        )
    if inserted:
        cursor.executemany(
            "INSERT INTO support_turn "
            "(turn_id, conversation_id, session_id, user_subject, trace_id, turn_sequence, "
            "correlation_key, request_fingerprint, input_text, response_text, outcome, state, "
            "processing_deadline_at, created_at, completed_at) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'completed', "
            "'COMPLETED', NULL, %s, %s)",
            inserted,
        )
        if cursor.rowcount != len(inserted):
            raise RuntimeError("Warm-history fixture did not insert every planned turn")
        cursor.execute(
            "UPDATE support_conversation SET next_turn_sequence = %s "
            "WHERE conversation_id = %s AND state = 'ACTIVE' AND next_turn_sequence = 0",
            (len(inserted), conversation_id),
        )
        if cursor.rowcount != 1:
            raise RuntimeError("Warm-history fixture could not advance the conversation sequence")


def seed_fixture(
    *,
    host: str,
    port: int,
    password: str,
    session_ids: tuple[str, ...],
    plan: FixturePlan,
    fixture_timestamp: datetime,
) -> None:
    connection = pymysql.connect(
        host=host,
        port=port,
        user="agent_app",
        password=password,
        database="cs_db",
        charset="utf8mb4",
        autocommit=False,
        connect_timeout=3,
        read_timeout=3,
        write_timeout=3,
    )
    try:
        with connection.cursor() as cursor:
            for session_id in session_ids:
                session_plan = build_fixture_plan(plan.case_name, session_id)
                _seed_session(
                    cursor,
                    session_id=session_id,
                    plan=session_plan,
                    fixture_timestamp=fixture_timestamp,
                )
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def _utc_text(value: datetime) -> str:
    return value.astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


def fixture_document(
    *,
    plan: FixturePlan,
    citybuddy_commit: str,
    setup_nonce: str,
    target_session_count: int,
    started_at: datetime,
    completed_at: datetime,
) -> dict[str, object]:
    window = plan.context_window
    persisted = len(plan.persisted_turns)
    first_sequence = plan.persisted_turns[0].turn_sequence if plan.persisted_turns else None
    last_sequence = plan.persisted_turns[-1].turn_sequence if plan.persisted_turns else None
    return {
        "formatVersion": FORMAT_VERSION,
        "citybuddyCommit": citybuddy_commit,
        "setupNonce": setup_nonce,
        "case": plan.case_name,
        "fixtureSetupWindowUtc": {
            "startedAt": _utc_text(started_at),
            "completedAt": _utc_text(completed_at),
        },
        "targetSessionCount": target_session_count,
        "history": {
            "persistedTurnCount": persisted,
            "candidateTurnCount": len(plan.query_candidates),
            "loadedTurnCount": window.loaded_turn_count,
            "includedTurnCount": len(window.turns),
            "olderTurnsAvailable": window.older_turns_available,
            "tokenEstimator": TOKEN_ESTIMATOR,
            "tokenBudget": SESSION_CONTEXT_TOKEN_BUDGET,
            "tokenWatermark": window.pressure,
            "candidateTokens": window.candidate_tokens,
            "includedTokens": window.included_tokens,
            "omittedLoadedTurnCount": window.loaded_turn_count - len(window.turns),
            "trimAction": "omit-oldest-whole-turns" if window.pressure == "high" else "none",
        },
        "sessionBoundary": {
            "count": target_session_count,
            "distinctCount": target_session_count,
            "minimumPersistedTurnCount": persisted,
            "maximumPersistedTurnCount": persisted,
            "firstTurnSequence": first_sequence,
            "lastTurnSequence": last_sequence,
        },
    }


def _positive_integer(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exception:
        raise argparse.ArgumentTypeError("expected a positive integer") from exception
    if parsed < 1:
        raise argparse.ArgumentTypeError("expected a positive integer")
    return parsed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--case", dest="case_name", choices=CASE_NAMES, required=True)
    parser.add_argument("--sessions", type=_positive_integer, required=True)
    parser.add_argument("--pool", type=Path, required=True)
    parser.add_argument("--mysql-host", required=True)
    parser.add_argument("--mysql-port", type=_positive_integer, required=True)
    parser.add_argument("--mysql-password", required=True)
    parser.add_argument("--citybuddy-commit", required=True)
    parser.add_argument("--setup-nonce", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    case_name = parse_case(args.case_name)
    if _FULL_SHA.fullmatch(args.citybuddy_commit) is None:
        parser.error("--citybuddy-commit must be a full lowercase 40-character SHA")
    if _SETUP_NONCE.fullmatch(args.setup_nonce) is None:
        parser.error("--setup-nonce must be a lowercase 32-character hex value")
    if args.out.exists():
        parser.error(f"refusing to overwrite warm-history fixture output: {args.out}")
    if not args.out.parent.is_dir():
        parser.error(f"warm-history fixture output directory does not exist: {args.out.parent}")
    session_ids = select_target_session_ids(args.pool, args.sessions)
    plan = build_fixture_plan(case_name)

    started_at = datetime.now(UTC)
    seed_fixture(
        host=args.mysql_host,
        port=args.mysql_port,
        password=args.mysql_password,
        session_ids=session_ids,
        plan=plan,
        fixture_timestamp=started_at.replace(tzinfo=None),
    )
    completed_at = datetime.now(UTC)
    document = fixture_document(
        plan=plan,
        citybuddy_commit=args.citybuddy_commit,
        setup_nonce=args.setup_nonce,
        target_session_count=len(session_ids),
        started_at=started_at,
        completed_at=completed_at,
    )
    temporary = args.out.with_suffix(args.out.suffix + ".tmp")
    temporary.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    temporary.replace(args.out)


if __name__ == "__main__":
    main()
