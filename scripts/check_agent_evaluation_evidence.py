#!/usr/bin/env python3
"""Validate one bounded public agent-evaluation evidence response."""

from __future__ import annotations

import argparse
import json
import uuid
from datetime import datetime
from pathlib import Path

ROOT_KEYS = {
    "schemaVersion",
    "traceId",
    "sessionId",
    "turnId",
    "terminalOutcome",
    "events",
    "retrieval",
    "feedback",
}
EVENT_KEYS = {
    "sequence",
    "eventKind",
    "outcome",
    "reference",
    "attempt",
    "attemptLimit",
    "context",
    "routing",
    "occurredAt",
}
CONTEXT_KEYS = {
    "policyVersion",
    "tokenEstimator",
    "tokenBudget",
    "tokenWatermark",
    "candidateTokens",
    "includedTokens",
    "loadedTurnCount",
    "includedTurnIds",
    "omittedLoadedTurnCount",
    "olderTurnsAvailable",
}
ROUTING_KEYS = {
    "refundContext",
    "refundContextSource",
    "chitchat",
    "toolProfile",
    "sessionPropagationEnabled",
}
SOURCE_KEYS = {"rank", "sourceId", "chunkId", "sourceVersion", "docType"}
MODELED_TERMINAL_OUTCOMES = {
    "completed",
    "budget_exhausted",
    "provider_denied",
    "retrieval_denied",
    "action_pending",
}


def canonical_uuid(value: object) -> bool:
    if not isinstance(value, str):
        return False
    try:
        return str(uuid.UUID(value)) == value
    except (AttributeError, TypeError, ValueError):
        return False


RETRIEVAL_KEYS = {
    "outcome",
    "reason",
    "indexVersion",
    "calibrationVersion",
    "candidateCount",
    "evidenceCount",
    "sources",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path)
    parser.add_argument("--trace", required=True)
    parser.add_argument("--session", required=True)
    parser.add_argument("--outcome", required=True)
    parser.add_argument("--require-event", action="append", default=[])
    parser.add_argument("--forbid-marker", action="append", default=[])
    parser.add_argument("--retrieval-outcome", choices=("SUFFICIENT", "INSUFFICIENT"))
    parser.add_argument("--feedback-count", type=int, default=0)
    return parser.parse_args()


def require_keys(payload: dict[str, object], allowed: set[str], required: set[str]) -> None:
    if not required <= payload.keys() or not payload.keys() <= allowed:
        raise SystemExit(f"Unexpected keys: {sorted(payload)}")


def require_rfc3339(value: object) -> None:
    if not isinstance(value, str):
        raise SystemExit("Evidence timestamp must be a string")
    try:
        timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exception:
        raise SystemExit("Evidence timestamp is not RFC 3339") from exception
    if timestamp.tzinfo is None or timestamp.utcoffset() is None:
        raise SystemExit("Evidence timestamp must carry an explicit UTC offset")


def main() -> None:
    args = parse_args()
    raw = args.path.read_text(encoding="utf-8")
    payload = json.loads(raw)
    if not isinstance(payload, dict):
        raise SystemExit("Evidence response must be an object")
    require_keys(
        payload,
        ROOT_KEYS,
        {
            "schemaVersion",
            "traceId",
            "sessionId",
            "turnId",
            "terminalOutcome",
            "events",
            "feedback",
        },
    )
    if payload["schemaVersion"] != "agent-evidence-v1":
        raise SystemExit("Unexpected evidence schema version")
    if payload["traceId"] != args.trace or payload["sessionId"] != args.session:
        raise SystemExit("Evidence correlation mismatch")
    if payload["terminalOutcome"] != args.outcome:
        raise SystemExit("Evidence terminal outcome mismatch")
    events = payload["events"]
    if not isinstance(events, list) or not 2 <= len(events) <= 96:
        raise SystemExit("Evidence events are outside bounds")
    kinds: list[str] = []
    for expected, event in enumerate(events, start=1):
        if not isinstance(event, dict):
            raise SystemExit("Evidence event must be an object")
        require_keys(event, EVENT_KEYS, {"sequence", "eventKind", "occurredAt"})
        if event["sequence"] != expected or not isinstance(event["eventKind"], str):
            raise SystemExit("Evidence event sequence is not contiguous")
        require_rfc3339(event["occurredAt"])
        context = event.get("context")
        if event["eventKind"] == "CONTEXT_WINDOW":
            if not isinstance(context, dict):
                raise SystemExit("Context event omitted its bounded selection")
            require_keys(context, CONTEXT_KEYS, CONTEXT_KEYS)
            included = context["includedTurnIds"]
            candidate_tokens = context["candidateTokens"]
            included_tokens = context["includedTokens"]
            loaded_turn_count = context["loadedTurnCount"]
            omitted_turn_count = context["omittedLoadedTurnCount"]
            if (
                context["policyVersion"] != "session-context-v1"
                or context["tokenEstimator"] != "utf8-bytes-v1"
                or context["tokenBudget"] != 6144
                or context["tokenWatermark"] not in {"low", "guarded", "high"}
                or not isinstance(included, list)
                or len(included) > 16
                or len(set(included)) != len(included)
                or any(not canonical_uuid(turn_id) for turn_id in included)
                or type(candidate_tokens) is not int
                or not 0 <= candidate_tokens <= 272_512
                or type(included_tokens) is not int
                or not 0 <= included_tokens <= 6144
                or type(loaded_turn_count) is not int
                or not 0 <= loaded_turn_count <= 16
                or type(omitted_turn_count) is not int
                or not 0 <= omitted_turn_count <= 16
                or loaded_turn_count != len(included) + omitted_turn_count
                or included_tokens > candidate_tokens
                or not isinstance(context["olderTurnsAvailable"], bool)
            ):
                raise SystemExit("Context selection is outside its closed bounds")
        elif context is not None:
            raise SystemExit("Non-context event carried context selection")
        routing = event.get("routing")
        if routing is not None:
            if event["eventKind"] != "ROUTING_DECISION" or not isinstance(routing, dict):
                raise SystemExit("Non-routing event carried a routing decision")
            require_keys(routing, ROUTING_KEYS, ROUTING_KEYS)
            refund_context = routing["refundContext"]
            refund_context_source = routing["refundContextSource"]
            chitchat = routing["chitchat"]
            tool_profile = routing["toolProfile"]
            session_propagation_enabled = routing["sessionPropagationEnabled"]
            if (
                type(refund_context) is not bool
                or refund_context_source not in {"none", "current", "session"}
                or type(chitchat) is not bool
                or tool_profile not in {"none", "read", "all"}
                or type(session_propagation_enabled) is not bool
                or refund_context != (refund_context_source != "none")
            ):
                raise SystemExit("Routing decision is outside its closed bounds")
            if refund_context_source == "current":
                expected_tool_profile = "all"
            elif chitchat:
                expected_tool_profile = "none"
            elif refund_context and session_propagation_enabled:
                expected_tool_profile = "all"
            else:
                expected_tool_profile = "read"
            if tool_profile != expected_tool_profile:
                raise SystemExit("Routing decision conflicts with its visible tool profile")
        kinds.append(event["eventKind"])
    if kinds[0] != "USER_INPUT":
        raise SystemExit("Evidence omitted accepted-input boundary")
    expected_terminal = "TURN_FAILED" if args.outcome == "failed" else "TURN_COMPLETED"
    if kinds[-1] != expected_terminal:
        raise SystemExit("Evidence omitted terminal boundary")
    if any(kind in {"TURN_COMPLETED", "TURN_FAILED"} for kind in kinds[:-1]):
        raise SystemExit("Evidence contains an intermediate terminal boundary")
    if args.outcome == "failed":
        if kinds != ["USER_INPUT", "TURN_FAILED"]:
            raise SystemExit("Failed evidence contains an impossible lifecycle")
    else:
        if kinds[-3:] != ["AGENT_OUTCOME", "ASSISTANT_RESPONSE", "TURN_COMPLETED"]:
            raise SystemExit("Completed evidence omitted the durable terminal sequence")
        if args.outcome in MODELED_TERMINAL_OUTCOMES and (
            kinds[:3] != ["USER_INPUT", "CONTEXT_WINDOW", "ROUTING_DECISION"]
            or kinds.count("CONTEXT_WINDOW") != 1
            or kinds.count("ROUTING_DECISION") != 1
        ):
            raise SystemExit("Modeled evidence omitted its exact context and routing prefix")
    for event in events:
        if event["eventKind"] in {"AGENT_OUTCOME", "ASSISTANT_RESPONSE", "TURN_COMPLETED"}:
            if event.get("outcome") != args.outcome:
                raise SystemExit("Evidence event conflicts with terminal outcome")
    for required in args.require_event:
        if required not in kinds:
            raise SystemExit(f"Missing required event kind: {required}")
    retrieval = payload.get("retrieval")
    if args.retrieval_outcome is None:
        if retrieval is not None:
            raise SystemExit("Unexpected retrieval projection")
    else:
        if not isinstance(retrieval, dict):
            raise SystemExit("Missing retrieval projection")
        require_keys(retrieval, RETRIEVAL_KEYS, RETRIEVAL_KEYS)
        if retrieval["outcome"] != args.retrieval_outcome:
            raise SystemExit("Retrieval outcome mismatch")
        sources = retrieval["sources"]
        if not isinstance(sources, list) or len(sources) != retrieval["evidenceCount"]:
            raise SystemExit("Retrieval source count mismatch")
        for expected, source in enumerate(sources, start=1):
            if not isinstance(source, dict):
                raise SystemExit("Retrieval source must be an object")
            require_keys(source, SOURCE_KEYS, SOURCE_KEYS)
            if source["rank"] != expected:
                raise SystemExit("Retrieval ranks are not contiguous")
    feedback = payload["feedback"]
    if not isinstance(feedback, list) or len(feedback) != args.feedback_count:
        raise SystemExit("Feedback count mismatch")
    for record in feedback:
        if not isinstance(record, dict) or set(record) != {"rating", "occurredAt"}:
            raise SystemExit("Feedback projection is not closed")
        require_rfc3339(record["occurredAt"])
    for marker in args.forbid_marker:
        if marker in raw:
            raise SystemExit(f"Private marker leaked: {marker}")


if __name__ == "__main__":
    main()
