import json
from datetime import UTC, datetime, timedelta

import pytest
from citybuddy_agent.actions import (
    ActionReceiptPayload,
    ConfirmationDecision,
    PendingActionPayload,
    confirmation_decision,
    strict_json_object,
)
from pydantic import ValidationError


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
