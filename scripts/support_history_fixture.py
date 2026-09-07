#!/usr/bin/env python3
"""Exercise the retained MySQL store and seed explicitly historical reader fixtures.

No model or tool is invoked here. Historical events are test input for the read API;
transaction, replay and action-reference behavior comes from MysqlConversationStore.
"""

from __future__ import annotations

import argparse
import json
import os
from dataclasses import dataclass
from pathlib import Path

import pymysql
from citybuddy_agent.actions import PendingActionPayload, canonical_action_timestamp
from citybuddy_agent.conversation import (
    ConversationIntegrityError,
    ConversationOwnershipError,
    CorrelationConflictError,
    MysqlConversationStore,
    TurnFailedError,
    TurnStart,
)
from citybuddy_agent.history_types import AgentEvent


@dataclass
class Settings:
    mysql_host: str
    mysql_port: int
    mysql_password: str
    attempt_budget: int = 8


def historical_events(profile: str, outcome: str) -> tuple[AgentEvent, ...]:
    events: list[AgentEvent] = []
    if profile == "tool":
        events.extend(
            (
                AgentEvent(
                    "CONTEXT_WINDOW",
                    {
                        "policyVersion": "session-context-v1",
                        "tokenEstimator": "utf8-bytes-v1",
                        "tokenBudget": 6144,
                        "tokenWatermark": "low",
                        "candidateTokens": 0,
                        "includedTokens": 0,
                        "loadedTurnCount": 0,
                        "includedTurnIds": [],
                        "omittedLoadedTurnCount": 0,
                        "olderTurnsAvailable": False,
                    },
                ),
                AgentEvent(
                    "ROUTING_DECISION",
                    {
                        "tier": "standard",
                        "attemptLimit": 8,
                        "signals": {
                            "refundContext": False,
                            "refundContextSource": "none",
                            "chitchat": False,
                        },
                        "toolProfile": "read",
                        "sessionPropagationEnabled": True,
                    },
                ),
                AgentEvent("BUDGET_CHARGED", {"attempt": 1, "limit": 8, "kind": "model_http"}),
                AgentEvent("MODEL_OUTCOME", {"result": "ok"}),
                AgentEvent("TOOL_LIFECYCLE", {"tool": "catalog.product.get", "state": "requested"}),
                AgentEvent("TOOL_LIFECYCLE", {"tool": "catalog.product.get", "state": "succeeded"}),
            )
        )
    elif profile == "provider":
        events.append(AgentEvent("MODEL_OUTCOME", {"result": "denied"}))
    events.append(AgentEvent("AGENT_OUTCOME", {"outcome": outcome}))
    return tuple(events)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=(
            "begin",
            "complete",
            "replay",
            "decline",
            "expire",
            "clarify",
            "reject",
            "pending",
            "claim",
            "current-pending",
        ),
    )
    parser.add_argument("--session", required=True)
    parser.add_argument("--subject", required=True)
    parser.add_argument("--sandbox", default="")
    parser.add_argument("--key", required=True)
    parser.add_argument("--message", default="historical fixture")
    parser.add_argument("--profile", choices=("basic", "tool", "provider"), default="basic")
    parser.add_argument("--start-file", type=Path)
    parser.add_argument("--pending-file", type=Path)
    parser.add_argument(
        "--expect",
        choices=("success", "integrity", "ownership", "conflict", "failed", "database"),
        default="success",
    )
    args = parser.parse_args()
    store = MysqlConversationStore(
        Settings(
            os.environ.get("MYSQL_HOST", "127.0.0.1"),
            int(os.environ["MYSQL_PORT"]),
            os.environ["MYSQL_AGENT_APP_PASSWORD"],
        )
    )
    identity = {
        "session_id": args.session,
        "subject": args.subject,
        "sandbox_id": args.sandbox or None,
    }
    turn = {**identity, "correlation_key": args.key, "message": args.message}
    start = None
    expected = {
        "integrity": ConversationIntegrityError,
        "ownership": ConversationOwnershipError,
        "conflict": CorrelationConflictError,
        "failed": TurnFailedError,
        "database": pymysql.MySQLError,
    }
    try:
        if args.command == "current-pending":
            current = store.current_pending_action(**identity)
            if args.expect != "success":
                raise AssertionError(f"Expected {args.expect} rejection")
            print(json.dumps({"state": current[1] if current is not None else None}))
            return
        if args.command == "replay":
            result = store.replay_turn(**turn)
            if result is None:
                raise AssertionError("Expected a persisted historical turn")
        else:
            pending = None
            if args.command in {"decline", "expire", "reject", "claim"}:
                current = store.current_pending_action(**identity)
                if current is None:
                    raise AssertionError("Expected a persisted pending action")
                pending = current[0]
                if args.command == "claim":
                    store.claim_action_confirmation(pending=pending)
                    print(json.dumps({"state": "CONFIRMING"}))
                    return
            if args.start_file:
                value = json.loads(args.start_file.read_text())
                start = TurnStart(value["conversationId"], value["traceId"], value["turnId"])
            else:
                start = store.begin_turn(**turn)
            if args.command == "begin":
                if args.expect != "success":
                    raise AssertionError("Expected store rejection")
                print(
                    json.dumps(
                        {
                            "conversationId": start.conversation_id,
                            "traceId": start.trace_id,
                            "turnId": start.turn_id,
                        }
                    )
                )
                return
            if start.replay is not None:
                result = start.replay
            elif args.command == "decline":
                assert pending is not None
                result = store.complete_action_decline(
                    start=start, pending=pending, response_text="Historical decline"
                )
            elif args.command == "expire":
                assert pending is not None
                result = store.complete_action_expired(
                    start=start, pending=pending, response_text="Historical expiry"
                )
            elif args.command == "reject":
                assert pending is not None
                result = store.complete_action_rejected(
                    start=start, pending=pending, response_text="Historical rejection"
                )
            else:
                payload = None
                outcome = "provider_denied" if args.profile == "provider" else "completed"
                if args.command == "clarify":
                    outcome = "action_clarification"
                events = historical_events(args.profile, outcome)
                if args.command == "pending":
                    if args.pending_file is None:
                        raise AssertionError("Preparation requires an explicit fixture response")
                    payload = PendingActionPayload.model_validate_json(
                        args.pending_file.read_text()
                    )
                    outcome = "action_pending"
                    events = (
                        AgentEvent(
                            "ACTION_PREPARED",
                            {
                                "pendingActionId": payload.pending_action_id,
                                "actionType": payload.action_type,
                                "argumentCommitment": payload.argument_commitment,
                                "targetVersion": payload.target_version,
                                "expiresAt": canonical_action_timestamp(payload.expires_at),
                            },
                        ),
                        AgentEvent("AGENT_OUTCOME", {"outcome": outcome}),
                    )
                result = store.complete_turn(
                    start=start,
                    response_text="Historical response",
                    outcome=outcome,
                    events=events,
                    pending_action=payload,
                )
        if args.expect != "success":
            raise AssertionError(f"Expected {args.expect} rejection")
    except tuple(expected.values()) as error:
        if args.expect == "success" or not isinstance(error, expected[args.expect]):
            raise
        if args.expect == "database" and start is not None:
            store.fail_turn(start=start, failure_code="controlled_store_failure")
        print(json.dumps({"expectedFailure": args.expect}))
        return
    print(
        json.dumps(
            {
                "conversationId": result.conversation_id,
                "traceId": result.trace_id,
                "turnId": result.turn_id,
                "outcome": result.outcome,
                "reply": result.response_text,
                "receiptId": result.receipt_id,
            },
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
