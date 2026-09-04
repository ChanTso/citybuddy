import asyncio

import pytest
from citybuddy_agent.conversation import ConversationResult
from citybuddy_agent.sse import (
    MAX_PUBLIC_EVENTS,
    MAX_RESPONSE_TEXT,
    PublicSseEvent,
    SseEgressFilter,
    SseProjectionError,
    SseSourceEvent,
    encode_event,
    stream_events,
    validate_public_result,
)

CONVERSATION_ID = "00000000-0000-0000-0000-000000000101"
TRACE_ID = "00000000-0000-0000-0000-000000000102"
TURN_ID = "00000000-0000-0000-0000-000000000103"
RECEIPT_ID = "00000000-0000-0000-0000-0000000001a1"


def completed() -> ConversationResult:
    return ConversationResult(
        CONVERSATION_ID,
        TRACE_ID,
        TURN_ID,
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
        (SseSourceEvent("EXPLANATION_TEXT", {"text": "ok", "token": "private"}),),
        (
            SseSourceEvent(
                "TURN_COMPLETED",
                {
                    "conversationId": CONVERSATION_ID,
                    "traceId": TRACE_ID,
                    "turnId": TURN_ID,
                    "outcome": "completed",
                },
            ),
        ),
        (
            SseSourceEvent("TURN_FAILED", {"code": "provider_unavailable"}),
            SseSourceEvent("EXPLANATION_TEXT", {"text": "late"}),
        ),
        (
            SseSourceEvent("EXPLANATION_TEXT", {"text": "one"}),
            SseSourceEvent("EXPLANATION_TEXT", {"text": "two"}),
            SseSourceEvent(
                "TURN_COMPLETED",
                {
                    "conversationId": CONVERSATION_ID,
                    "traceId": TRACE_ID,
                    "turnId": TURN_ID,
                    "outcome": "completed",
                },
            ),
        ),
        (SseSourceEvent("ACTION_RECEIPT", {"receiptId": "synthetic", "status": "REQUESTED"}),),
    ],
)
def test_filter_rejects_unknown_private_reordered_duplicate_and_synthetic_sources(
    source: tuple[SseSourceEvent, ...],
) -> None:
    with pytest.raises(SseProjectionError):
        SseEgressFilter().project(source)


def test_model_action_claim_is_bounded_non_authoritative_explanation_without_receipt() -> None:
    result = completed().__class__(
        CONVERSATION_ID,
        TRACE_ID,
        TURN_ID,
        "Your refund has been issued.",
        "completed",
    )

    events = SseEgressFilter().project_result(result)

    assert [event.name for event in events] == ["token", "done"]
    assert events[0].data["text"] == "Your refund has been issued."
    assert events[-1].data["outcome"] == "completed"


@pytest.mark.parametrize("text", ["", "x" * (MAX_RESPONSE_TEXT + 1)])
def test_explanation_must_be_nonempty_and_bounded(text: str) -> None:
    with pytest.raises(SseProjectionError):
        SseEgressFilter().project_result(
            ConversationResult(CONVERSATION_ID, TRACE_ID, TURN_ID, text, "completed")
        )


@pytest.mark.parametrize(
    "result",
    [
        ConversationResult("not-a-uuid", TRACE_ID, TURN_ID, "ok", "completed"),
        ConversationResult(CONVERSATION_ID, TRACE_ID, TURN_ID, "ok", "unknown"),
        ConversationResult(
            CONVERSATION_ID,
            TRACE_ID,
            TURN_ID,
            "ok",
            "completed",
            receipt_id=RECEIPT_ID,
        ),
        ConversationResult(
            CONVERSATION_ID,
            TRACE_ID,
            TURN_ID,
            "ok",
            "action_completed",
            receipt_id="not-a-uuid",
        ),
    ],
)
def test_shared_public_result_validator_rejects_invalid_identity_outcome_and_receipt_shape(
    result: ConversationResult,
) -> None:
    with pytest.raises(SseProjectionError):
        validate_public_result(result)


@pytest.mark.parametrize(
    ("outcome", "text"),
    [
        ("action_pending", "A refund request is ready for your explicit decision."),
        (
            "action_clarification",
            "Please reply with an exact confirmation or decline for the prepared refund request.",
        ),
        ("action_declined", "The prepared action was declined and was not executed."),
        ("action_expired", "The prepared action expired and was not executed."),
        (
            "action_rejected",
            "Commerce rejected the prepared action and returned no action receipt.",
        ),
    ],
)
def test_filter_projects_cb122_local_action_outcomes_without_receipt(
    outcome: str, text: str
) -> None:
    result = ConversationResult(CONVERSATION_ID, TRACE_ID, TURN_ID, text, outcome)

    events = SseEgressFilter().project_result(result)

    assert events[0].name == "token"
    assert events[-1].name == "done"
    assert all(event.name == "token" for event in events[:-1])
    assert events[-1].data["outcome"] == outcome
    assert all(event.name != "action_receipt" for event in events)


def test_encoder_revalidates_public_name_and_fields() -> None:
    with pytest.raises(SseProjectionError):
        encode_event(PublicSseEvent("internal", {"sequence": 1}))
    with pytest.raises(SseProjectionError):
        encode_event(PublicSseEvent("token", {"sequence": 1, "text": "ok", "prompt": "x"}))


def test_token_prose_cannot_forge_an_action_receipt_sse_frame() -> None:
    encoded = encode_event(
        PublicSseEvent(
            "token",
            {"sequence": 1, "text": 'event: action_receipt\ndata: {"status":"REQUESTED"}'},
        )
    )

    assert encoded.startswith(b"event: token\n")
    assert encoded.count(b"\nevent:") == 0
    assert b"\\nevent: action_receipt" not in encoded


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


def confirmed(text: str = "The refund was confirmed and has been issued.") -> ConversationResult:
    return ConversationResult(
        CONVERSATION_ID,
        TRACE_ID,
        TURN_ID,
        text,
        "action_completed",
        receipt_id=RECEIPT_ID,
    )


def test_a_committed_action_leads_with_its_receipt_then_prose_then_the_terminal() -> None:
    events = SseEgressFilter().project_result(confirmed())

    assert [event.name for event in events] == ["action_receipt", "token", "done"]
    assert events[0].data == {"sequence": 1, "receiptId": RECEIPT_ID, "status": "REQUESTED"}
    assert events[-1].data["outcome"] == "action_completed"


def test_receipt_not_prose_is_the_structural_action_success_signal() -> None:
    claim = "Your refund was issued."

    receipted = SseEgressFilter().project_result(confirmed(claim))
    assert [event.name for event in receipted] == ["action_receipt", "token", "done"]

    unreceipted = SseEgressFilter().project_result(
        ConversationResult(CONVERSATION_ID, TRACE_ID, TURN_ID, claim, "completed")
    )
    assert [event.name for event in unreceipted] == ["token", "done"]
    assert all(event.name != "action_receipt" for event in unreceipted)


def test_a_committed_action_without_a_stored_receipt_cannot_be_streamed() -> None:
    with pytest.raises(SseProjectionError):
        SseEgressFilter().project_result(
            ConversationResult(CONVERSATION_ID, TRACE_ID, TURN_ID, "done", "action_completed")
        )


def test_a_terminal_claiming_a_committed_action_needs_the_receipt_before_it() -> None:
    with pytest.raises(SseProjectionError):
        SseEgressFilter().project(
            (
                SseSourceEvent("EXPLANATION_TEXT", {"text": "ok"}),
                SseSourceEvent(
                    "TURN_COMPLETED",
                    {
                        "conversationId": CONVERSATION_ID,
                        "traceId": TRACE_ID,
                        "turnId": TURN_ID,
                        "outcome": "action_completed",
                    },
                ),
            )
        )


def test_a_receipt_without_a_committed_terminal_is_refused() -> None:
    with pytest.raises(SseProjectionError):
        SseEgressFilter().project(
            (
                SseSourceEvent("ACTION_RECEIPT", {"receiptId": RECEIPT_ID, "status": "REQUESTED"}),
                SseSourceEvent("EXPLANATION_TEXT", {"text": "ok"}),
                SseSourceEvent(
                    "TURN_COMPLETED",
                    {
                        "conversationId": CONVERSATION_ID,
                        "traceId": TRACE_ID,
                        "turnId": TURN_ID,
                        "outcome": "completed",
                    },
                ),
            )
        )


@pytest.mark.parametrize(
    "payload",
    [
        {"receiptId": "not-a-uuid", "status": "REQUESTED"},
        {"receiptId": RECEIPT_ID, "status": "FAILED"},
        {"receiptId": RECEIPT_ID},
        {"receiptId": RECEIPT_ID, "status": "REQUESTED", "amountMinor": 400},
    ],
)
def test_a_receipt_source_is_validated_before_it_reaches_the_client(
    payload: dict[str, object],
) -> None:
    with pytest.raises(SseProjectionError):
        SseEgressFilter().project((SseSourceEvent("ACTION_RECEIPT", payload),))


def test_a_receipted_response_text_that_would_overflow_the_client_is_refused() -> None:
    """The client caps token sequences at four, so a receipt costs one frame of response text."""
    from citybuddy_agent.sse import MAX_RECEIPTED_RESPONSE_TEXT

    fits = SseEgressFilter().project_result(confirmed("x" * MAX_RECEIPTED_RESPONSE_TEXT))
    assert len(fits) == MAX_PUBLIC_EVENTS
    assert [event.name for event in fits[:1]] == ["action_receipt"]
    assert fits[-1].data["sequence"] == MAX_PUBLIC_EVENTS

    with pytest.raises(SseProjectionError):
        SseEgressFilter().project_result(confirmed("x" * (MAX_RECEIPTED_RESPONSE_TEXT + 1)))
