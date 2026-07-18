#!/usr/bin/env python3
"""Validate one bounded public agent-evaluation evidence response."""

from __future__ import annotations

import argparse
import json
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
    "occurredAt",
}
SOURCE_KEYS = {"rank", "sourceId", "chunkId", "sourceVersion", "docType"}
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
    if not isinstance(events, list) or not 2 <= len(events) <= 48:
        raise SystemExit("Evidence events are outside bounds")
    kinds: list[str] = []
    for expected, event in enumerate(events, start=1):
        if not isinstance(event, dict):
            raise SystemExit("Evidence event must be an object")
        require_keys(event, EVENT_KEYS, {"sequence", "eventKind", "occurredAt"})
        if event["sequence"] != expected or not isinstance(event["eventKind"], str):
            raise SystemExit("Evidence event sequence is not contiguous")
        require_rfc3339(event["occurredAt"])
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
    elif kinds[-3:] != ["AGENT_OUTCOME", "ASSISTANT_RESPONSE", "TURN_COMPLETED"]:
        raise SystemExit("Completed evidence omitted the durable terminal sequence")
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
