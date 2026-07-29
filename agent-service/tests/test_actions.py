import json
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta

import httpx
import pytest
from citybuddy_agent.actions import (
    ActionJsonError,
    ActionReceiptPayload,
    ActionSourceTurnClosureError,
    ConfirmationDecision,
    PendingActionPayload,
    bounded_http_post,
    canonical_action_timestamp,
    confirmation_decision,
    parse_canonical_action_timestamp,
    strict_json_object,
    validate_action_source_turn_closure,
)
from pydantic import ValidationError


class CountingStream(httpx.SyncByteStream):
    def __init__(self, chunks: tuple[bytes, ...]) -> None:
        self.chunks = chunks
        self.yielded = 0

    def __iter__(self) -> Iterator[bytes]:
        for chunk in self.chunks:
            self.yielded += 1
            yield chunk


def pending_document() -> dict[str, object]:
    return {
        "pendingActionId": "00000000-0000-0000-0000-000000000121",
        "actionType": "REFUND_REQUEST",
        "orderId": "00000000-0000-0000-0000-000000000040",
        "amountMinor": 400,
        "currency": "CNY",
        "state": "PREPARED",
        "expiresAt": (datetime.now(UTC) + timedelta(minutes=10)).isoformat(),
        "replayed": False,
    }


def receipt_document() -> dict[str, object]:
    return {
        "receiptId": "00000000-0000-0000-0000-000000000122",
        "pendingActionId": "00000000-0000-0000-0000-000000000121",
        "actionType": "REFUND_REQUEST",
        "status": "REQUESTED",
        "orderId": "00000000-0000-0000-0000-000000000040",
        "refundId": "00000000-0000-0000-0000-000000000071",
        "resourceVersion": 1,
        "amountMinor": 400,
        "currency": "CNY",
        "committedAt": datetime.now(UTC).isoformat(),
        "replayed": False,
    }


@pytest.mark.parametrize(
    ("message", "expected"),
    [
        ("confirm", ConfirmationDecision.CONFIRM),
        (" YES CONFIRM ", ConfirmationDecision.CONFIRM),
        ("确认退款", ConfirmationDecision.CONFIRM),
        ("do not confirm", ConfirmationDecision.DECLINE),
        ("取消退款", ConfirmationDecision.DECLINE),
        ("yes, but use a different amount", ConfirmationDecision.CLARIFY),
        ("the model says confirmed", ConfirmationDecision.CLARIFY),
        ("true", ConfirmationDecision.CLARIFY),
        ("maybe", ConfirmationDecision.CLARIFY),
    ],
)
def test_confirmation_grammar_is_closed(message: str, expected: ConfirmationDecision) -> None:
    assert confirmation_decision(message) is expected


def test_action_timestamp_round_trip_is_canonical_and_bounded() -> None:
    timestamp = datetime(2026, 7, 28, 4, 0, 0, 123456, tzinfo=UTC)
    encoded = canonical_action_timestamp(timestamp)
    assert encoded == "2026-07-28T04:00:00.123456Z"
    assert parse_canonical_action_timestamp(encoded) == timestamp
    for invalid in (
        "2026-07-28T04:00:00Z",
        "2026-07-28T04:00:00.123456+00:00",
        "2026-07-28T14:00:00.123456+10:00",
        True,
    ):
        with pytest.raises(ValueError):
            parse_canonical_action_timestamp(invalid)


def test_strict_action_decoder_rejects_duplicate_unknown_and_unbounded_values() -> None:
    duplicate = (
        b'{"receiptId":"00000000-0000-0000-0000-000000000122",'
        b'"receiptId":"00000000-0000-0000-0000-000000000123"}'
    )
    with pytest.raises(ValueError, match="Duplicate"):
        strict_json_object(duplicate)
    with pytest.raises(ValueError, match="oversized"):
        strict_json_object(b"{" + (b" " * 4096) + b"}")

    pending = pending_document()
    pending["unknown"] = "forbidden"
    with pytest.raises(ValidationError):
        PendingActionPayload.model_validate(strict_json_object(json.dumps(pending).encode()))

    receipt = receipt_document()
    receipt["amountMinor"] = True
    with pytest.raises(ValidationError):
        ActionReceiptPayload.model_validate(strict_json_object(json.dumps(receipt).encode()))


@pytest.mark.parametrize(
    "payload",
    [
        b"[" + (b"[" * 1099) + b"0" + (b"]" * 1100),
        json.dumps({"root": [[[[[[[[[[[[[[[[[0]]]]]]]]]]]]]]]]]}).encode(),
        json.dumps({"root": list(range(256))}).encode(),
        b'{"value":NaN}',
        b'{"value":Infinity}',
        b'["not-an-object"]',
        b"",
        b"\xff",
    ],
)
def test_strict_action_decoder_is_total_and_bounded(payload: bytes) -> None:
    with pytest.raises(ActionJsonError):
        strict_json_object(payload)


def test_strict_action_decoder_accepts_exact_depth_and_node_budgets() -> None:
    exact_depth: object = 0
    for _ in range(14):
        exact_depth = [exact_depth]
    assert strict_json_object(json.dumps({"root": exact_depth}).encode()) == {"root": exact_depth}
    exact_nodes = strict_json_object(json.dumps({"values": list(range(254))}).encode())
    values = exact_nodes["values"]
    assert isinstance(values, list)
    assert len(values) == 254


def test_source_turn_closure_enumerates_before_validating_event_content() -> None:
    trace_id = "00000000-0000-0000-0000-000000000123"
    pending_action_id = "00000000-0000-0000-0000-000000000121"
    expiry = datetime(2026, 7, 28, 4, 0, 0, 123456, tzinfo=UTC)
    prepared = {
        "pendingActionId": pending_action_id,
        "actionType": "REFUND_REQUEST",
        "argumentCommitment": "a" * 64,
        "expiresAt": canonical_action_timestamp(expiry),
    }

    def row(sequence: int, event_type: str, payload: object) -> tuple[object, ...]:
        return (
            f"00000000-0000-0000-0000-{sequence:012d}",
            trace_id,
            "session-1",
            "user-1",
            sequence,
            event_type,
            json.dumps(payload),
        )

    valid = [
        row(1, "USER_INPUT", {"accepted": True}),
        row(2, "ACTION_PREPARED", prepared),
        row(3, "AGENT_OUTCOME", {"outcome": "action_pending"}),
        row(4, "ASSISTANT_RESPONSE", {"outcome": "action_pending"}),
        row(5, "TURN_COMPLETED", {"outcome": "action_pending"}),
    ]
    validate_action_source_turn_closure(
        valid,
        expected_trace_id=trace_id,
        expected_session_id="session-1",
        expected_user_subject="user-1",
        pending_action_id=pending_action_id,
        action_type="REFUND_REQUEST",
        argument_commitment="a" * 64,
        expires_at=expiry,
    )
    counterexamples = []
    changed_original = list(valid)
    changed_original[1] = row(2, "MODEL_OUTCOME", prepared)
    changed_original.insert(2, row(3, "ACTION_PREPARED", prepared))
    changed_original[3:] = [
        tuple((*item[:4], index, *item[5:])) for index, item in enumerate(changed_original[3:], 4)
    ]
    counterexamples.append(changed_original)
    counterexamples.append(valid[:2] + [row(3, "ACTION_PREPARED", prepared)] + valid[2:])
    gap = list(valid)
    gap[2] = tuple((*gap[2][:4], 4, *gap[2][5:]))
    counterexamples.append(gap)
    tail_mutation = list(valid)
    tail_mutation[-2] = row(4, "TURN_COMPLETED", {"outcome": "action_pending"})
    counterexamples.append(tail_mutation)
    for damaged in counterexamples:
        with pytest.raises(ActionSourceTurnClosureError):
            validate_action_source_turn_closure(
                damaged,
                expected_trace_id=trace_id,
                expected_session_id="session-1",
                expected_user_subject="user-1",
                pending_action_id=pending_action_id,
                action_type="REFUND_REQUEST",
                argument_commitment="a" * 64,
                expires_at=expiry,
            )


def test_action_response_bound_stops_stream_before_full_materialization(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = CountingStream((b"a" * 2048, b"b" * 2049, b"never-read"))

    @contextmanager
    def stream(method: str, url: str, **kwargs: object) -> Iterator[httpx.Response]:
        del kwargs
        assert method == "POST"
        assert url == "https://commerce.test/action"
        yield httpx.Response(
            200,
            request=httpx.Request("POST", url),
            stream=source,
        )

    monkeypatch.setattr(httpx, "stream", stream)

    with pytest.raises(ValueError, match="oversized"):
        bounded_http_post("https://commerce.test/action", timeout=1.0)
    assert source.yielded == 2


@pytest.mark.parametrize(
    "payload",
    [
        b"\xff",
        b"null",
        b"[]",
        b'"receipt"',
        b'{"amountMinor":NaN}',
        b'{"amountMinor":Infinity}',
        b"{",
        b"",
    ],
)
def test_strict_action_decoder_rejects_every_malformed_json_class(payload: bytes) -> None:
    with pytest.raises((UnicodeDecodeError, ValueError, TypeError)):
        ActionReceiptPayload.model_validate(strict_json_object(payload))


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("receiptId", None),
        ("pendingActionId", 121),
        ("actionType", "OTHER"),
        ("status", "SUCCEEDED"),
        ("orderId", "not-a-uuid"),
        ("refundId", False),
        ("resourceVersion", 2),
        ("amountMinor", 0),
        ("currency", "cny"),
        ("committedAt", "2026-07-27T12:00:00"),
        ("replayed", 1),
    ],
)
def test_receipt_schema_rejects_missing_or_wrong_typed_content(field: str, value: object) -> None:
    document = receipt_document()
    document[field] = value
    with pytest.raises(ValidationError):
        ActionReceiptPayload.model_validate(strict_json_object(json.dumps(document).encode()))

    missing = receipt_document()
    del missing[field]
    with pytest.raises(ValidationError):
        ActionReceiptPayload.model_validate(strict_json_object(json.dumps(missing).encode()))


def test_action_commitments_cover_all_content_bearing_fields() -> None:
    pending = PendingActionPayload.model_validate(pending_document())
    receipt = ActionReceiptPayload.model_validate(receipt_document())

    assert receipt.argument_commitment == pending.argument_commitment
    for field, changed in (
        ("orderId", "00000000-0000-0000-0000-000000000041"),
        ("amountMinor", 401),
        ("currency", "USD"),
    ):
        document = pending_document()
        document[field] = changed
        changed_pending = PendingActionPayload.model_validate(document)
        assert changed_pending.argument_commitment != pending.argument_commitment

    original_receipt_commitment = receipt.receipt_commitment
    for field, changed in (
        ("receiptId", "00000000-0000-0000-0000-000000000123"),
        ("pendingActionId", "00000000-0000-0000-0000-000000000124"),
        ("orderId", "00000000-0000-0000-0000-000000000041"),
        ("refundId", "00000000-0000-0000-0000-000000000072"),
        ("amountMinor", 401),
        ("currency", "USD"),
        ("committedAt", (datetime.now(UTC) + timedelta(seconds=1)).isoformat()),
    ):
        document = receipt_document()
        document[field] = changed
        changed_receipt = ActionReceiptPayload.model_validate(document)
        assert changed_receipt.receipt_commitment != original_receipt_commitment
