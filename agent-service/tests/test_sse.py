import asyncio

import pytest
from citybuddy_agent.conversation import ConversationResult
from citybuddy_agent.sse import (
    MAX_PUBLIC_EVENTS,
    PublicSseEvent,
    SseEgressFilter,
    SseProjectionError,
    SseSourceEvent,
    encode_event,
    stream_events,
)


def completed() -> ConversationResult:
    return ConversationResult(
        "conversation-1",
        "trace-1",
        "turn-1",
        "x" * 256,
        "completed",
    )


def test_filter_bounds_chunks_and_emits_one_ordered_terminal() -> None:
    events = SseEgressFilter().project_result(completed())

    assert len(events) == MAX_PUBLIC_EVENTS
    assert [event.name for event in events] == ["token", "token", "token", "token", "done"]
    assert [event.data["sequence"] for event in events] == [1, 2, 3, 4, 5]
    assert all(len(str(event.data["text"])) == 64 for event in events[:-1])
    assert set(events[-1].data) == {
        "sequence",
        "conversationId",
        "traceId",
        "turnId",
        "outcome",
    }


@pytest.mark.parametrize(
    "source",
    [
        (SseSourceEvent("PRIVATE_PROMPT", {"prompt": "secret"}),),
        (SseSourceEvent("SAFE_TEXT", {"text": "ok", "token": "private"}),),
        (
            SseSourceEvent(
                "TURN_COMPLETED",
                {
                    "conversationId": "c",
                    "traceId": "t",
                    "turnId": "u",
                    "outcome": "completed",
                },
            ),
        ),
        (
            SseSourceEvent("TURN_FAILED", {"code": "provider_unavailable"}),
            SseSourceEvent("SAFE_TEXT", {"text": "late"}),
        ),
        (
            SseSourceEvent("SAFE_TEXT", {"text": "one"}),
            SseSourceEvent("SAFE_TEXT", {"text": "two"}),
            SseSourceEvent(
                "TURN_COMPLETED",
                {
                    "conversationId": "c",
                    "traceId": "t",
                    "turnId": "u",
                    "outcome": "completed",
                },
            ),
        ),
        (SseSourceEvent("ACTION_RECEIPT", {"receiptId": "synthetic", "status": "SUCCEEDED"}),),
    ],
)
def test_filter_rejects_unknown_private_reordered_duplicate_and_synthetic_sources(
    source: tuple[SseSourceEvent, ...],
) -> None:
    with pytest.raises(SseProjectionError):
        SseEgressFilter().project(source)


@pytest.mark.parametrize(
    "claim",
    [
        "Your order was placed.",
        "The refund is successful.",
        "Payment succeeded.",
        "The purchase was paid successfully.",
        "Your cancellation is complete.",
        "Your order has been processed.",
        "Your refund—SUCCESSFUL.",
        "PAYMENT\nAPPROVED.",
        "Your pay​ment was finalized.",
        "It has been refunded.",
        "I cancelled it for you.",
        "I am not guessing; I refunded it.",
        "退款已完成。",
        "订单取消成功。",
    ],
)
def test_filter_withholds_action_success_language_without_receipt_truth(claim: str) -> None:
    result = completed().__class__("c", "t", "u", claim, "completed")

    with pytest.raises(SseProjectionError):
        SseEgressFilter().project_result(result)


@pytest.mark.parametrize(
    "safe_text",
    [
        "I can explain how to request a refund.",
        "Your order status is still pending.",
        "Payment options are available.",
        "Your order is not complete.",
        "The refund was never successful.",
        "It has not yet been refunded.",
        "Payment hasn't succeeded.",
        "退款尚未成功。",
        "支付没有成功。",
    ],
)
def test_filter_preserves_non_success_action_guidance(safe_text: str) -> None:
    result = completed().__class__("c", "t", "u", safe_text, "completed")

    events = SseEgressFilter().project_result(result)

    assert [event.name for event in events] == ["token", "done"]


def test_encoder_revalidates_public_name_and_fields() -> None:
    with pytest.raises(SseProjectionError):
        encode_event(PublicSseEvent("internal", {"sequence": 1}))
    with pytest.raises(SseProjectionError):
        encode_event(PublicSseEvent("token", {"sequence": 1, "text": "ok", "prompt": "x"}))


def test_disconnect_stops_finite_stream_without_post_terminal_work() -> None:
    events = SseEgressFilter().project_result(completed())
    calls = 0

    async def collect() -> list[bytes]:
        output: list[bytes] = []

        async def disconnected() -> bool:
            nonlocal calls
            calls += 1
            return calls > 1

        async for chunk in stream_events(events, disconnected):
            output.append(chunk)
        return output

    output = asyncio.run(collect())

    assert len(output) == 1
    assert output[0].startswith(b"event: token\n")
    assert calls == 2


def test_slow_consumer_and_cancellation_keep_no_queued_or_background_events() -> None:
    events = SseEgressFilter().project_result(completed())

    async def consume_one() -> tuple[bytes, int]:
        checks = 0

        async def slow_consumer_ready() -> bool:
            nonlocal checks
            checks += 1
            await asyncio.sleep(0.001)
            return False

        iterator = stream_events(events, slow_consumer_ready)
        first = await anext(iterator)
        await iterator.aclose()
        return first, checks

    first, checks = asyncio.run(consume_one())

    assert first.startswith(b"event: token\n")
    assert checks == 1
