"""Fail-closed projection from durable turn results to bounded public SSE."""

from __future__ import annotations

import json
import uuid
from collections.abc import AsyncGenerator, Awaitable, Callable, Mapping, Sequence
from dataclasses import dataclass

from .conversation import ConversationResult

TOKEN_CHUNK_SIZE = 64
MAX_RESPONSE_TEXT = 256
# A receipted stream carries one more frame than a plain one, and the client caps token sequences
# at four, so a committed action's response text has this much room and no more. Exceeding it
# fails the projection rather than emitting a stream the client would reject: the two public paths
# must not disagree about the same durable turn.
MAX_PUBLIC_EVENTS = MAX_RESPONSE_TEXT // TOKEN_CHUNK_SIZE + 1
MAX_RECEIPTED_RESPONSE_TEXT = (MAX_PUBLIC_EVENTS - 2) * TOKEN_CHUNK_SIZE

_PUBLIC_COMPLETED_OUTCOMES = {
    "completed",
    "retrieval_denied",
    "action_completed",
    "action_pending",
    "action_clarification",
    "action_declined",
    "action_expired",
}
_PUBLIC_OUTCOMES = _PUBLIC_COMPLETED_OUTCOMES | {"budget_exhausted", "provider_denied"}


def _is_canonical_uuid(value: object) -> bool:
    if not isinstance(value, str):
        return False
    try:
        return str(uuid.UUID(value)) == value
    except ValueError:
        return False


class SseProjectionError(Exception):
    """A durable result or source event cannot cross the public response boundary."""


def validate_public_result(result: ConversationResult) -> None:
    """Validate the durable result shared by JSON and SSE public projections."""

    if not all(
        _is_canonical_uuid(value)
        for value in (result.conversation_id, result.trace_id, result.turn_id)
    ):
        raise SseProjectionError("invalid durable result identity")
    if (
        not isinstance(result.response_text, str)
        or not result.response_text
        or len(result.response_text) > MAX_RESPONSE_TEXT
        or (
            result.outcome == "action_completed"
            and len(result.response_text) > MAX_RECEIPTED_RESPONSE_TEXT
        )
    ):
        raise SseProjectionError("invalid durable result explanation")
    if result.outcome not in _PUBLIC_OUTCOMES:
        raise SseProjectionError("unknown durable outcome")
    if (result.outcome == "action_completed") != (result.receipt_id is not None):
        raise SseProjectionError("completed action and receipt disagree")
    if result.receipt_id is not None and (not _is_canonical_uuid(result.receipt_id)):
        raise SseProjectionError("invalid durable receipt identity")


@dataclass(frozen=True)
class SseSourceEvent:
    event_type: str
    payload: Mapping[str, object]


@dataclass(frozen=True)
class PublicSseEvent:
    name: str
    data: Mapping[str, object]


class SseEgressFilter:
    """Project one validated durable result into an exact bounded SSE schema."""

    def project_result(self, result: ConversationResult) -> tuple[PublicSseEvent, ...]:
        validate_public_result(result)
        source: tuple[SseSourceEvent, ...]
        if result.outcome == "action_completed":
            # Read from the stored projection, never rebuilt from the response text: the receipt
            # the user is shown is the one the durable turn recorded.
            if result.receipt_id is None:
                raise SseProjectionError("committed action has no receipt")
            source = (
                SseSourceEvent(
                    "ACTION_RECEIPT",
                    {"receiptId": result.receipt_id, "status": "REQUESTED"},
                ),
                SseSourceEvent("EXPLANATION_TEXT", {"text": result.response_text}),
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
        elif result.outcome in _PUBLIC_COMPLETED_OUTCOMES:
            source = (
                SseSourceEvent("EXPLANATION_TEXT", {"text": result.response_text}),
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
        receipt_seen = False
        for event in source:
            if terminal:
                raise SseProjectionError("source event follows terminal")
            if event.event_type == "EXPLANATION_TEXT":
                if text_seen or set(event.payload) != {"text"}:
                    raise SseProjectionError("invalid text source")
                text = event.payload["text"]
                if (
                    not isinstance(text, str)
                    or not text
                    or len(text) > MAX_RESPONSE_TEXT
                    or (receipt_seen and len(text) > MAX_RECEIPTED_RESPONSE_TEXT)
                ):
                    raise SseProjectionError("invalid explanation source")
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
                if event.payload["outcome"] not in _PUBLIC_COMPLETED_OUTCOMES or not all(
                    _is_canonical_uuid(event.payload[name])
                    for name in ("conversationId", "traceId", "turnId")
                ):
                    raise SseProjectionError("invalid completed values")
                if (event.payload["outcome"] == "action_completed") != receipt_seen:
                    raise SseProjectionError("completed action and receipt disagree")
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
                    }
                ):
                    raise SseProjectionError("invalid failure source")
                terminal = True
                public.append(PublicSseEvent("error", {"sequence": 1, **event.payload}))
            elif event.event_type == "ACTION_RECEIPT":
                # The receipt leads the stream so clients encounter durable action state before
                # the accompanying non-authoritative explanation.
                if receipt_seen or text_seen or set(event.payload) != {"receiptId", "status"}:
                    raise SseProjectionError("invalid receipt source")
                receipt_id = event.payload["receiptId"]
                if (
                    not isinstance(receipt_id, str)
                    or not _is_canonical_uuid(receipt_id)
                    or event.payload["status"] != "REQUESTED"
                ):
                    raise SseProjectionError("invalid receipt values")
                receipt_seen = True
                public.append(
                    PublicSseEvent("action_receipt", {"sequence": len(public) + 1, **event.payload})
                )
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
