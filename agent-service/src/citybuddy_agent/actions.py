"""Agent-owned action references and strict commerce receipt boundary."""

from __future__ import annotations

import hashlib
import json
import unicodedata
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import Enum
from typing import Any, Literal

import httpx
from pydantic import BaseModel, ConfigDict, Field, field_validator

MAX_ACTION_RESPONSE_BYTES = 4096
ACTION_SCOPE = "refund:create"


@dataclass(frozen=True)
class BoundedHttpResponse:
    status_code: int
    content: bytes


def bounded_http_post(url: str, **kwargs: Any) -> BoundedHttpResponse:
    """Read an untrusted action-boundary response with a pre-materialization cap."""
    with httpx.stream("POST", url, **kwargs) as response:
        content = bytearray()
        for chunk in response.iter_bytes():
            if len(content) + len(chunk) > MAX_ACTION_RESPONSE_BYTES:
                raise ValueError("Action response is oversized")
            content.extend(chunk)
        return BoundedHttpResponse(response.status_code, bytes(content))


def _duplicate_rejecting_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON object key")
        result[key] = value
    return result


def strict_json_object(payload: bytes) -> object:
    if not payload or len(payload) > MAX_ACTION_RESPONSE_BYTES:
        raise ValueError("Action response is empty or oversized")
    text = payload.decode("utf-8", errors="strict")
    return json.loads(
        text,
        object_pairs_hook=_duplicate_rejecting_object,
        parse_constant=lambda value: (_ for _ in ()).throw(
            ValueError(f"Invalid JSON constant: {value}")
        ),
    )


class RefundActionArguments(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True, strict=True, frozen=True)

    order_id: str = Field(alias="orderId", min_length=36, max_length=36)
    amount_minor: int = Field(alias="amountMinor", ge=1)
    currency: str = Field(pattern=r"^[A-Z]{3}$")

    @field_validator("order_id")
    @classmethod
    def valid_order_id(cls, value: str) -> str:
        if str(uuid.UUID(value)) != value:
            raise ValueError("orderId must be a canonical UUID")
        return value


class PendingActionPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True, strict=True, frozen=True)

    pending_action_id: str = Field(alias="pendingActionId", min_length=36, max_length=36)
    action_type: Literal["REFUND_REQUEST"] = Field(alias="actionType")
    order_id: str = Field(alias="orderId", min_length=36, max_length=36)
    amount_minor: int = Field(alias="amountMinor", ge=1)
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    state: Literal["PREPARED"]
    expires_at: datetime = Field(alias="expiresAt")
    replayed: bool

    @field_validator("pending_action_id", "order_id")
    @classmethod
    def valid_uuid(cls, value: str) -> str:
        if str(uuid.UUID(value)) != value:
            raise ValueError("Action identity must be a canonical UUID")
        return value

    @field_validator("expires_at", mode="before")
    @classmethod
    def parse_expiry(cls, value: object) -> datetime:
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exception:
                raise ValueError("Action timestamp must be ISO-8601") from exception
        if not isinstance(value, datetime):
            raise ValueError("Action timestamp must be a datetime")
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("Action timestamp must be timezone-aware")
        return value.astimezone(UTC)

    @property
    def argument_commitment(self) -> str:
        return action_argument_commitment(
            self.action_type, self.order_id, self.amount_minor, self.currency
        )


class ActionReceiptPayload(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True, strict=True, frozen=True)

    receipt_id: str = Field(alias="receiptId", min_length=36, max_length=36)
    pending_action_id: str = Field(alias="pendingActionId", min_length=36, max_length=36)
    action_type: Literal["REFUND_REQUEST"] = Field(alias="actionType")
    status: Literal["REQUESTED"]
    order_id: str = Field(alias="orderId", min_length=36, max_length=36)
    refund_id: str = Field(alias="refundId", min_length=36, max_length=36)
    resource_version: Literal[1] = Field(alias="resourceVersion")
    amount_minor: int = Field(alias="amountMinor", ge=1)
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    committed_at: datetime = Field(alias="committedAt")
    replayed: bool

    @field_validator("receipt_id", "pending_action_id", "order_id", "refund_id")
    @classmethod
    def valid_uuid(cls, value: str) -> str:
        if str(uuid.UUID(value)) != value:
            raise ValueError("Receipt identity must be a canonical UUID")
        return value

    @field_validator("committed_at", mode="before")
    @classmethod
    def parse_committed_at(cls, value: object) -> datetime:
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exception:
                raise ValueError("Receipt timestamp must be ISO-8601") from exception
        if not isinstance(value, datetime):
            raise ValueError("Receipt timestamp must be a datetime")
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("Receipt timestamp must be timezone-aware")
        return value.astimezone(UTC)

    @property
    def argument_commitment(self) -> str:
        return action_argument_commitment(
            self.action_type, self.order_id, self.amount_minor, self.currency
        )

    @property
    def receipt_commitment(self) -> str:
        material = "\x00".join(
            (
                self.receipt_id,
                self.pending_action_id,
                self.action_type,
                self.status,
                self.order_id,
                self.refund_id,
                str(self.resource_version),
                str(self.amount_minor),
                self.currency,
                self.committed_at.isoformat(timespec="microseconds"),
            )
        )
        return hashlib.sha256(material.encode("utf-8")).hexdigest()


def action_argument_commitment(
    action_type: str, order_id: str, amount_minor: int, currency: str
) -> str:
    material = "\x00".join((action_type, order_id, str(amount_minor), currency))
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def canonical_action_timestamp(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("Action timestamp must be timezone-aware")
    return value.astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


def parse_canonical_action_timestamp(value: object) -> datetime:
    if not isinstance(value, str) or len(value) != 27:
        raise ValueError("Action timestamp must be a bounded canonical string")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exception:
        raise ValueError("Action timestamp must be ISO-8601") from exception
    if canonical_action_timestamp(parsed) != value:
        raise ValueError("Action timestamp must use canonical UTC microseconds")
    return parsed.astimezone(UTC)


@dataclass(frozen=True)
class PendingActionReference:
    pending_action_id: str
    source_turn_id: str
    source_trace_id: str
    conversation_id: str
    session_id: str
    user_subject: str
    sandbox_id: str | None
    action_type: str
    argument_commitment: str
    order_id: str
    amount_minor: int
    currency: str
    expires_at: datetime


@dataclass(frozen=True)
class StoredActionReceipt:
    receipt: ActionReceiptPayload
    source_turn_id: str
    confirmation_turn_id: str


class ConfirmationDecision(Enum):
    CONFIRM = "CONFIRM"
    DECLINE = "DECLINE"
    CLARIFY = "CLARIFY"


_CONFIRMATIONS = frozenset(
    {
        "confirm",
        "confirm refund",
        "yes",
        "yes confirm",
        "确认",
        "确认退款",
        "是的",
        "是的确认",
    }
)
_DECLINES = frozenset(
    {
        "cancel",
        "decline",
        "do not confirm",
        "no",
        "no cancel",
        "不",
        "不确认",
        "取消",
        "取消退款",
    }
)


def confirmation_decision(message: str) -> ConfirmationDecision:
    normalized = unicodedata.normalize("NFKC", message).strip().casefold()
    normalized = " ".join(normalized.split())
    if normalized in _CONFIRMATIONS:
        return ConfirmationDecision.CONFIRM
    if normalized in _DECLINES:
        return ConfirmationDecision.DECLINE
    return ConfirmationDecision.CLARIFY
