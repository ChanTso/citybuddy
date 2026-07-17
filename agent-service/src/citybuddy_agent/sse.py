"""Fail-closed projection from durable turn results to bounded public SSE."""

from __future__ import annotations

import json
import re
import unicodedata
from collections.abc import AsyncGenerator, Awaitable, Callable, Mapping, Sequence
from dataclasses import dataclass

from .conversation import ConversationResult

TOKEN_CHUNK_SIZE = 64
MAX_RESPONSE_TEXT = 256
MAX_PUBLIC_EVENTS = MAX_RESPONSE_TEXT // TOKEN_CHUNK_SIZE + 1

_SENSITIVE_ACTION_WORDS = {
    "cancel",
    "cancellation",
    "order",
    "payment",
    "purchase",
    "refund",
}
_ACTION_COMPLETION_WORDS = {
    "approved",
    "canceled",
    "cancelled",
    "complete",
    "completed",
    "completion",
    "confirmed",
    "done",
    "executed",
    "finalized",
    "ordered",
    "paid",
    "placed",
    "processed",
    "refunded",
    "succeeded",
    "success",
    "successful",
    "successfully",
}
_COMPLETED_ACTION_VERBS = {
    "canceled",
    "cancelled",
    "ordered",
    "paid",
    "purchased",
    "refunded",
}
_NEGATION_WORDS = {"cannot", "never", "no", "not"}
_NEGATION_BRIDGE_WORDS = {
    "actually",
    "already",
    "be",
    "been",
    "being",
    "fully",
    "successfully",
    "yet",
}
_SENSITIVE_ACTION_CJK = ("订单", "下单", "退款", "支付", "付款", "取消")
_ACTION_COMPLETION_CJK = (
    "成功",
    "已完成",
    "已确认",
    "已下单",
    "已退款",
    "已支付",
    "已付款",
    "已取消",
)
_NEGATION_CJK = ("没有", "尚未", "并未", "还未", "还没", "未", "没", "不")


def _is_negated(words: list[str], index: int) -> bool:
    for negation_index in range(max(0, index - 4), index):
        if words[negation_index] not in _NEGATION_WORDS:
            continue
        if all(word in _NEGATION_BRIDGE_WORDS for word in words[negation_index + 1 : index]):
            return True
    return False


def _has_unnegated_word(words: list[str], candidates: set[str]) -> bool:
    return any(
        word in candidates and not _is_negated(words, index) for index, word in enumerate(words)
    )


def _has_unnegated_cjk_completion(text: str) -> bool:
    for completion in _ACTION_COMPLETION_CJK:
        for match in re.finditer(re.escape(completion), text):
            prefix = text[max(0, match.start() - 4) : match.start()]
            if not any(prefix.endswith(negation) for negation in _NEGATION_CJK):
                return True
    return False


def _contains_unreceipted_action_claim(text: str) -> bool:
    normalized = unicodedata.normalize("NFKC", text).casefold()
    normalized = "".join(
        character for character in normalized if unicodedata.category(character) != "Cf"
    )
    normalized = re.sub(
        r"\b(?:are|could|did|does|do|had|has|have|is|should|was|were|would|will)n['’]t\b",
        " not ",
        normalized,
    ).replace("can't", " cannot ")
    words = re.findall(r"[a-z0-9]+", normalized)
    if _has_unnegated_word(words, _COMPLETED_ACTION_VERBS):
        return True
    if set(words) & _SENSITIVE_ACTION_WORDS and _has_unnegated_word(
        words, _ACTION_COMPLETION_WORDS
    ):
        return True
    return any(action in normalized for action in _SENSITIVE_ACTION_CJK) and (
        _has_unnegated_cjk_completion(normalized)
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
                    or _contains_unreceipted_action_claim(text)
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
