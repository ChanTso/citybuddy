"""Validate the exact bounded public SSE wire contract used by integration evidence."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path)
    parser.add_argument("--terminal", choices=("done", "error"), required=True)
    parser.add_argument("--expected-text")
    parser.add_argument("--error-code")
    args = parser.parse_args()

    raw = args.path.read_text(encoding="utf-8")
    blocks = raw.rstrip("\n").split("\n\n")
    if not blocks or len(blocks) > 5:
        raise ValueError("SSE stream is empty or unbounded")
    schemas = {
        "token": {"sequence", "text"},
        "action_receipt": {"sequence", "receiptId", "status"},
        "done": {"sequence", "conversationId", "traceId", "turnId", "outcome"},
        "error": {"sequence", "code"},
    }
    parsed: list[tuple[str, dict[str, object]]] = []
    for index, block in enumerate(blocks, start=1):
        lines = block.splitlines()
        if (
            len(lines) != 2
            or not lines[0].startswith("event: ")
            or not lines[1].startswith("data: ")
        ):
            raise ValueError("SSE block has an unknown wire field")
        name = lines[0][7:]
        payload = json.loads(lines[1][6:])
        if name not in schemas or not isinstance(payload, dict) or set(payload) != schemas[name]:
            raise ValueError("SSE event name or data schema is not allowlisted")
        if payload["sequence"] != index:
            raise ValueError("SSE sequence is not contiguous")
        parsed.append((name, payload))
    if parsed[-1][0] != args.terminal or any(name in {"done", "error"} for name, _ in parsed[:-1]):
        raise ValueError("SSE terminal ordering is invalid")
    text = "".join(str(payload["text"]) for name, payload in parsed if name == "token")
    if any(len(str(payload["text"])) > 64 for name, payload in parsed if name == "token"):
        raise ValueError("SSE token chunk exceeds its bound")
    if args.expected_text is not None and text != args.expected_text:
        raise ValueError("SSE text did not match the deterministic provider result")
    if args.error_code is not None and parsed[-1][1].get("code") != args.error_code:
        raise ValueError("SSE error code did not match")
    forbidden = (
        "authorization",
        "credential",
        "prompt",
        "routing_decision",
        "budget_charged",
        "circuit_outcome",
        "model_outcome",
        "tool_lifecycle",
        "sql",
        "stack",
    )
    lowered = raw.lower()
    if any(value in lowered for value in forbidden):
        raise ValueError("SSE stream exposed a private internal field")


if __name__ == "__main__":
    main()
