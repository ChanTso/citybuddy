"""Fail-closed projection from durable turn results to bounded public SSE."""

from __future__ import annotations

import json
import re
from collections.abc import AsyncGenerator, Awaitable, Callable, Mapping, Sequence
from dataclasses import dataclass

from .conversation import ConversationResult

TOKEN_CHUNK_SIZE = 64
MAX_RESPONSE_TEXT = 256
MAX_PUBLIC_EVENTS = MAX_RESPONSE_TEXT // TOKEN_CHUNK_SIZE + 1

_ACTION_SUCCESS = re.compile(
    r"\b(?:order (?:was |is )?(?:placed|confirmed)|"
    r"refund (?:was |is )?(?:completed|successful|succeeded)|"
    r"payment (?:was |is )?(?:completed|successful|succeeded)|"
    r"(?:has been|was|is) refunded|paid successfully|refunded|ordered|paid)\b",
    re.IGNORECASE,
)


class SseProjectionError(Exception):
    """A source event cannot cross the public SSE boundary."""


@dataclass(frozen=True)
class SseSourceEvent:
    event_type: str
    payload: Mapping[str, object]


@dataclass(frozen=True)
class PublicSseEvent:
    name: str
    data: Mapping[str, object]


class SseEgressFilter:
    """Accept only server-constructed text and terminal truth with exact schemas."""

    def project_result(self, result: ConversationResult) -> tuple[PublicSseEvent, ...]:
        source: tuple[SseSourceEvent, ...]
        if result.outcome == "completed":
            source = (
                SseSourceEvent("SAFE_TEXT", {"text": result.response_text}),
                SseSourceEvent(
                    "TURN_COMPLETED",
                    {
                        "conversationId": result.conversation_id,
                        "traceId": result.trace_id,
                        "turnId": result.turn_id,
                        "outcome": result.outcome,
                    },
                ),
            )
        elif result.outcome in {"budget_exhausted", "provider_denied"}:
            source = (
                SseSourceEvent(
                    "TURN_FAILED",
                    {
                        "code": (
                            "attempt_budget_exhausted"
                            if result.outcome == "budget_exhausted"
                            else "provider_unavailable"
                        )
                    },
                ),
            )
        else:
            raise SseProjectionError("unknown durable outcome")
        return self.project(source)

    def project(self, source: Sequence[SseSourceEvent]) -> tuple[PublicSseEvent, ...]:
        public: list[PublicSseEvent] = []
        terminal = False
        text_seen = False
        for event in source:
            if terminal:
                raise SseProjectionError("source event follows terminal")
            if event.event_type == "SAFE_TEXT":
                if text_seen or set(event.payload) != {"text"}:
                    raise SseProjectionError("invalid text source")
                text = event.payload["text"]
                if (
                    not isinstance(text, str)
                    or not text
                    or len(text) > MAX_RESPONSE_TEXT
                    or _ACTION_SUCCESS.search(text) is not None
                ):
                    raise SseProjectionError("unsafe text source")
                text_seen = True
                for offset in range(0, len(text), TOKEN_CHUNK_SIZE):
                    public.append(
                        PublicSseEvent(
                            "token",
                            {
                                "sequence": len(public) + 1,
                                "text": text[offset : offset + TOKEN_CHUNK_SIZE],
                            },
                        )
                    )
            elif event.event_type == "TURN_COMPLETED":
                required = {"conversationId", "traceId", "turnId", "outcome"}
                if not text_seen or set(event.payload) != required:
                    raise SseProjectionError("invalid completed source")
                if event.payload["outcome"] != "completed" or not all(
                    isinstance(event.payload[name], str) and event.payload[name]
                    for name in required
                ):
                    raise SseProjectionError("invalid completed values")
                terminal = True
                public.append(
                    PublicSseEvent("done", {"sequence": len(public) + 1, **event.payload})
                )
            elif event.event_type == "TURN_FAILED":
                if (
                    text_seen
                    or set(event.payload) != {"code"}
                    or event.payload["code"]
                    not in {
                        "attempt_budget_exhausted",
                        "provider_unavailable",
                        "stream_unavailable",
                        "unsafe_output",
                    }
                ):
                    raise SseProjectionError("invalid failure source")
                terminal = True
                public.append(PublicSseEvent("error", {"sequence": 1, **event.payload}))
            elif event.event_type == "ACTION_RECEIPT":
                # CB-120/CB-121 own receipt truth. This slice reserves the public
                # schema but cannot accept or synthesize a receipt source.
                raise SseProjectionError("receipt truth is unavailable")
            else:
                raise SseProjectionError("unknown source event")
        if not terminal or not public or len(public) > MAX_PUBLIC_EVENTS:
            raise SseProjectionError("missing or unbounded terminal stream")
        return tuple(public)

    def terminal_error(self, code: str) -> tuple[PublicSseEvent, ...]:
        return self.project((SseSourceEvent("TURN_FAILED", {"code": code}),))


def encode_event(event: PublicSseEvent) -> bytes:
    schemas = {
        "token": {"sequence", "text"},
        "action_receipt": {"sequence", "receiptId", "status"},
        "done": {"sequence", "conversationId", "traceId", "turnId", "outcome"},
        "error": {"sequence", "code"},
    }
    if event.name not in schemas or set(event.data) != schemas[event.name]:
        raise SseProjectionError("invalid public event schema")
    payload = json.dumps(event.data, ensure_ascii=False, separators=(",", ":"))
    return f"event: {event.name}\ndata: {payload}\n\n".encode()


async def stream_events(
    events: Sequence[PublicSseEvent],
    is_disconnected: Callable[[], Awaitable[bool]],
) -> AsyncGenerator[bytes, None]:
    if not events or len(events) > MAX_PUBLIC_EVENTS:
        raise SseProjectionError("unbounded public stream")
    for event in events:
        if await is_disconnected():
            return
        yield encode_event(event)
