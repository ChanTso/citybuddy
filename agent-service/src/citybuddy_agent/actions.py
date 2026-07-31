"""Bounded action documents and agent-owned PendingAction references."""

from __future__ import annotations

import hashlib
import json
import math
import unicodedata
import uuid
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import Enum
from typing import Literal, cast

import httpx
from pydantic import BaseModel, ConfigDict, Field, field_validator

MAX_ACTION_JSON_BYTES = 4096
MAX_ACTION_JSON_DEPTH = 16
MAX_ACTION_JSON_VALUES = 256
MAX_ACTION_SOURCE_TURN_EVENTS = 48
MAX_ACTION_AMOUNT_MINOR = 9_223_372_036_854_775_807
MAX_ACTION_PENDING_TTL_SECONDS = 86_400
ACTION_SCOPE = "refund:create"


class ActionJsonError(ValueError):
    """An untrusted action document is not one bounded strict JSON object."""


@dataclass(frozen=True)
class BoundedHttpResponse:
    status_code: int
    content: bytes


def bounded_http_post(
    url: str,
    *,
    headers: Mapping[str, str],
    json: object,
    timeout: float,
) -> BoundedHttpResponse:
    """Read an untrusted action response without first materializing an unbounded body."""
    with httpx.stream("POST", url, headers=headers, json=json, timeout=timeout) as response:
        content = bytearray()
        for chunk in response.iter_bytes():
            if len(content) + len(chunk) > MAX_ACTION_JSON_BYTES:
                raise ActionJsonError("Action response is oversized")
            content.extend(chunk)
        return BoundedHttpResponse(response.status_code, bytes(content))


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ActionJsonError("Duplicate JSON object key")
        result[key] = value
    return result


def _preflight_json(text: str) -> None:
    """Bound nesting and values before json.loads constructs containers."""
    stack: list[str] = []
    values = 0
    expecting_value = True
    in_string = False
    escaped = False
    index = 0
    while index < len(text):
        character = text[index]
        if in_string:
            if escaped:
                if character == "u":
                    digits = text[index + 1 : index + 5]
                    if len(digits) != 4 or any(
                        value not in "0123456789abcdefABCDEF" for value in digits
                    ):
                        raise ActionJsonError("Invalid JSON Unicode escape")
                    index += 4
                elif character not in '"\\/bfnrt':
                    raise ActionJsonError("Invalid JSON escape")
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
                if expecting_value:
                    values += 1
                    expecting_value = False
            elif ord(character) < 0x20:
                raise ActionJsonError("Invalid JSON control character")
            index += 1
            continue
        if character.isspace():
            index += 1
            continue
        if character == '"':
            in_string = True
            index += 1
            continue
        if character in "[{":
            values += 1
            stack.append(character)
            if len(stack) > MAX_ACTION_JSON_DEPTH:
                raise ActionJsonError("Action JSON is too deeply nested")
            expecting_value = True
        elif character in "]}":
            if not stack or (character == "]") != (stack[-1] == "["):
                raise ActionJsonError("Action JSON delimiters are inconsistent")
            stack.pop()
            expecting_value = False
        elif character == ":":
            expecting_value = True
        elif character == ",":
            expecting_value = True
        else:
            end = index
            while end < len(text) and text[end] not in " \t\r\n,]}":
                end += 1
            if end == index:
                raise ActionJsonError("Action JSON token is invalid")
            values += 1
            expecting_value = False
            index = end
            if values > MAX_ACTION_JSON_VALUES:
                raise ActionJsonError("Action JSON has too many values")
            continue
        if values > MAX_ACTION_JSON_VALUES:
            raise ActionJsonError("Action JSON has too many values")
        index += 1
    if in_string or escaped or stack:
        raise ActionJsonError("Action JSON is incomplete")


def _reject_invalid_unicode(value: object) -> None:
    stack = [value]
    while stack:
        current = stack.pop()
        if isinstance(current, dict):
            stack.extend(current.keys())
            stack.extend(current.values())
        elif isinstance(current, list):
            stack.extend(current)
        elif isinstance(current, str):
            try:
                current.encode("utf-8", errors="strict")
            except UnicodeError as exception:
                raise ActionJsonError("Action JSON contains invalid Unicode") from exception


def strict_json_object(payload: bytes) -> dict[str, object]:
    try:
        if type(payload) is not bytes or not payload or len(payload) > MAX_ACTION_JSON_BYTES:
            raise ActionJsonError("Action JSON is empty or oversized")
        text = payload.decode("utf-8", errors="strict")
        _preflight_json(text)
        decoded = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=lambda value: (_ for _ in ()).throw(
                ActionJsonError(f"Invalid JSON constant: {value}")
            ),
        )
        if not isinstance(decoded, dict):
            raise ActionJsonError("Action JSON root must be an object")
        _reject_invalid_unicode(decoded)
        nodes = 0
        stack: list[tuple[object, int]] = [(decoded, 1)]
        while stack:
            value, depth = stack.pop()
            nodes += 1
            if nodes > MAX_ACTION_JSON_VALUES or depth > MAX_ACTION_JSON_DEPTH:
                raise ActionJsonError("Action JSON exceeds structural bounds")
            if isinstance(value, dict):
                stack.extend((child, depth + 1) for child in value.values())
            elif isinstance(value, list):
                stack.extend((child, depth + 1) for child in value)
            elif value is None or type(value) in {bool, int, str}:
                continue
            elif type(value) is float and math.isfinite(value):
                continue
            else:
                raise ActionJsonError("Action JSON contains an invalid value")
        return decoded
    except ActionJsonError:
        raise
    except (UnicodeError, json.JSONDecodeError, TypeError, ValueError, RecursionError) as exception:
        raise ActionJsonError("Action JSON is invalid") from exception


class RefundActionArguments(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True, strict=True, frozen=True)

    order_id: str = Field(alias="orderId", min_length=36, max_length=36)
    amount_minor: int = Field(alias="amountMinor", ge=1, le=MAX_ACTION_AMOUNT_MINOR)
    currency: str = Field(pattern=r"^[A-Z]{3}$")

    @field_validator("order_id")
    @classmethod
    def canonical_uuid(cls, value: str) -> str:
        if str(uuid.UUID(value)) != value:
            raise ValueError("orderId must be a canonical UUID")
        return value


class PreparedActionResponse(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True, strict=True, frozen=True)

    pending_action_id: str = Field(alias="pendingActionId", min_length=36, max_length=36)
    action_type: str = Field(alias="actionType", min_length=1, max_length=32)
    user_subject: str = Field(alias="userSubject", min_length=1, max_length=190)
    support_session_id: str = Field(alias="supportSessionId", min_length=1, max_length=64)
    trace_id: str = Field(alias="traceId", min_length=36, max_length=36)
    turn_id: str = Field(alias="turnId", min_length=36, max_length=36)
    required_scope: str = Field(alias="requiredScope", min_length=1, max_length=64)
    sandbox_id: str | None = Field(alias="sandboxId", min_length=1, max_length=64)
    order_id: str = Field(alias="orderId", min_length=36, max_length=36)
    target_version: int = Field(alias="targetVersion")
    amount_minor: int = Field(alias="amountMinor", ge=1, le=MAX_ACTION_AMOUNT_MINOR)
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    state: str = Field(min_length=1, max_length=16)
    expires_at: datetime = Field(alias="expiresAt")
    replayed: bool

    @field_validator("pending_action_id", "trace_id", "turn_id", "order_id")
    @classmethod
    def canonical_uuid(cls, value: str) -> str:
        if str(uuid.UUID(value)) != value:
            raise ValueError("Action identity must be a canonical UUID")
        return value

    @field_validator("expires_at", mode="before")
    @classmethod
    def canonical_expiry(cls, value: object) -> datetime:
        if not isinstance(value, str) or len(value) != 27:
            raise ValueError("Action expiry must use canonical UTC microseconds")
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exception:
            raise ValueError("Action expiry must be ISO-8601") from exception
        if canonical_action_timestamp(parsed) != value:
            raise ValueError("Action expiry must use canonical UTC microseconds")
        return parsed.astimezone(UTC)

    @property
    def argument_commitment(self) -> str:
        return action_argument_commitment(
            self.action_type, self.order_id, self.amount_minor, self.currency
        )


class PendingActionPayload(PreparedActionResponse):
    action_type: Literal["REFUND_REQUEST"] = Field(alias="actionType")
    required_scope: Literal["refund:create"] = Field(alias="requiredScope")
    target_version: int = Field(alias="targetVersion", ge=1)
    state: Literal["PREPARED"]


class ActionReceiptPayload(BaseModel):
    """Complete bounded projection of the authoritative Commerce ActionReceipt."""

    model_config = ConfigDict(extra="forbid", populate_by_name=True, strict=True, frozen=True)

    receipt_id: str = Field(alias="receiptId", min_length=36, max_length=36)
    pending_action_id: str = Field(alias="pendingActionId", min_length=36, max_length=36)
    action_type: Literal["REFUND_REQUEST"] = Field(alias="actionType")
    status: Literal["REQUESTED"]
    order_id: str = Field(alias="orderId", min_length=36, max_length=36)
    refund_id: str = Field(alias="refundId", min_length=36, max_length=36)
    resource_version: Literal[1] = Field(alias="resourceVersion")
    amount_minor: int = Field(alias="amountMinor", ge=1, le=MAX_ACTION_AMOUNT_MINOR)
    currency: str = Field(pattern=r"^[A-Z]{3}$")
    committed_at: datetime = Field(alias="committedAt")
    replayed: bool

    @field_validator("receipt_id", "pending_action_id", "order_id", "refund_id")
    @classmethod
    def canonical_uuid(cls, value: str) -> str:
        if str(uuid.UUID(value)) != value:
            raise ValueError("Receipt identity must be a canonical UUID")
        return value

    @field_validator("committed_at", mode="before")
    @classmethod
    def canonical_committed_at(cls, value: object) -> datetime:
        if not isinstance(value, str) or len(value) != 27:
            raise ValueError("Receipt timestamp must use canonical UTC microseconds")
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exception:
            raise ValueError("Receipt timestamp must be ISO-8601") from exception
        if canonical_action_timestamp(parsed) != value:
            raise ValueError("Receipt timestamp must use canonical UTC microseconds")
        return parsed.astimezone(UTC)

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
                canonical_action_timestamp(self.committed_at),
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
    target_version: int
    amount_minor: int
    currency: str
    expires_at: datetime
    confirmation_turn_id: str | None = None
    confirmation_trace_id: str | None = None
    resolution_turn_id: str | None = None
    resolution_trace_id: str | None = None


@dataclass(frozen=True)
class StoredActionReceipt:
    receipt: ActionReceiptPayload
    source_turn_id: str
    confirmation_turn_id: str


class ActionEvidenceError(ValueError):
    """Stored action event/reference/turn truth is incomplete or contradictory."""


@dataclass(frozen=True)
class ActionEvidenceEvent:
    event_id: str
    trace_id: str
    session_id: str
    user_subject: str
    sequence: int
    event_type: str
    payload: dict[str, object]


ACTION_TURN_EVENTS_SQL = (
    "SELECT event_id, trace_id, session_id, user_subject, sequence, event_type, "
    "IF(OCTET_LENGTH(payload_json) <= 4096, payload_json, NULL) "
    "FROM support_event WHERE turn_id = %s ORDER BY sequence LIMIT 49"
)
PENDING_ACTION_SOURCE_TURN_SQL = (
    "SELECT turn_id, trace_id, conversation_id, session_id, user_subject, state, outcome "
    "FROM support_turn WHERE turn_id = %s LIMIT 2"
)
PENDING_ACTION_RESOLUTION_TURN_SQL = (
    "SELECT turn_id, trace_id, conversation_id, session_id, user_subject, state, outcome "
    "FROM support_turn WHERE turn_id = %s LIMIT 2"
)


def _canonical_uuid(value: object) -> str:
    if not isinstance(value, str) or str(uuid.UUID(value)) != value:
        raise ActionEvidenceError("Action identity is not a canonical UUID")
    return value


def validate_pending_action_reference(
    row: tuple[object, ...],
    source_turn_rows: tuple[tuple[object, ...], ...] | list[tuple[object, ...]],
    *,
    expected_session_id: str,
    expected_user_subject: str,
    expected_sandbox_id: str | None,
    expected_turn_id: str | None = None,
    expected_trace_id: str | None = None,
    expected_conversation_id: str | None = None,
) -> tuple[PendingActionReference, str, datetime]:
    """Validate one reference against its independently enumerated source turn."""
    try:
        if len(row) != 20 or len(source_turn_rows) != 1 or len(source_turn_rows[0]) != 7:
            raise ActionEvidenceError("PendingAction reference cardinality is inconsistent")
        source_turn = source_turn_rows[0]
        if (
            tuple(source_turn[:5]) != tuple(row[1:6])
            or tuple(source_turn[5:]) != ("COMPLETED", "action_pending")
            or row[4] != expected_session_id
            or row[5] != expected_user_subject
            or row[6] != expected_sandbox_id
            or (expected_turn_id is not None and row[1] != expected_turn_id)
            or (expected_trace_id is not None and row[2] != expected_trace_id)
            or (expected_conversation_id is not None and row[3] != expected_conversation_id)
        ):
            raise ActionEvidenceError("PendingAction source-turn binding is inconsistent")
        pending_action_id = _canonical_uuid(row[0])
        source_turn_id = _canonical_uuid(row[1])
        source_trace_id = _canonical_uuid(row[2])
        conversation_id = _canonical_uuid(row[3])
        order_id = _canonical_uuid(row[9])
        if (
            not isinstance(row[4], str)
            or not 1 <= len(row[4]) <= 64
            or not isinstance(row[5], str)
            or not 1 <= len(row[5]) <= 190
            or (row[6] is not None and (not isinstance(row[6], str) or not 1 <= len(row[6]) <= 64))
            or row[7] != "REFUND_REQUEST"
            or not isinstance(row[8], str)
            or len(row[8]) != 64
            or any(character not in "0123456789abcdef" for character in row[8])
            or type(row[10]) is not int
            or row[10] < 1
            or type(row[11]) is not int
            or row[11] < 1
            or not isinstance(row[12], str)
            or len(row[12]) != 3
            or not row[12].isalpha()
            or row[12] != row[12].upper()
            or row[13] not in {"PENDING", "CONFIRMING", "DECLINED", "EXPIRED", "CONFIRMED"}
            or not isinstance(row[16], datetime)
            or (row[13] in {"PENDING", "CONFIRMING"}) != (row[17] is None)
            or (row[17] is not None and not isinstance(row[17], datetime))
        ):
            raise ActionEvidenceError("PendingAction reference content is invalid")
        confirmation_turn_id = None
        confirmation_trace_id = None
        if row[13] in {"CONFIRMING", "CONFIRMED"}:
            confirmation_turn_id = _canonical_uuid(row[14])
            confirmation_trace_id = _canonical_uuid(row[15])
            if confirmation_turn_id == source_turn_id:
                raise ActionEvidenceError("PendingAction confirmation turn is inconsistent")
        elif row[14] is not None or row[15] is not None:
            raise ActionEvidenceError("PendingAction confirmation binding is inconsistent")
        resolution_turn_id = None
        resolution_trace_id = None
        if row[13] in {"DECLINED", "EXPIRED"}:
            resolution_turn_id = _canonical_uuid(row[18])
            resolution_trace_id = _canonical_uuid(row[19])
            if resolution_turn_id == source_turn_id:
                raise ActionEvidenceError("PendingAction resolution turn is inconsistent")
        elif row[18] is not None or row[19] is not None:
            raise ActionEvidenceError("PendingAction resolution binding is inconsistent")
        expires_at = (
            row[16].replace(tzinfo=UTC) if row[16].tzinfo is None else row[16].astimezone(UTC)
        )
        action_type = cast(str, row[7])
        state = cast(str, row[13])
        pending = PendingActionReference(
            pending_action_id=pending_action_id,
            source_turn_id=source_turn_id,
            source_trace_id=source_trace_id,
            conversation_id=conversation_id,
            session_id=row[4],
            user_subject=row[5],
            sandbox_id=row[6],
            action_type=action_type,
            argument_commitment=row[8],
            order_id=order_id,
            target_version=row[10],
            amount_minor=row[11],
            currency=row[12],
            expires_at=expires_at,
            confirmation_turn_id=confirmation_turn_id,
            confirmation_trace_id=confirmation_trace_id,
            resolution_turn_id=resolution_turn_id,
            resolution_trace_id=resolution_trace_id,
        )
        if pending.argument_commitment != action_argument_commitment(
            pending.action_type,
            pending.order_id,
            pending.amount_minor,
            pending.currency,
        ):
            raise ActionEvidenceError("PendingAction argument commitment is inconsistent")
        return pending, state, expires_at
    except (TypeError, ValueError) as exception:
        if isinstance(exception, ActionEvidenceError):
            raise
        raise ActionEvidenceError("PendingAction reference content is invalid") from exception


def validate_pending_action_resolution(
    pending: PendingActionReference,
    state: str,
    resolution_turn_rows: tuple[tuple[object, ...], ...] | list[tuple[object, ...]],
    resolution_event_rows: tuple[tuple[object, ...], ...] | list[tuple[object, ...]],
) -> None:
    """Validate a terminal reference against its independently enumerated decision turn."""
    if state == "PENDING":
        if (
            pending.resolution_turn_id is not None
            or pending.resolution_trace_id is not None
            or resolution_turn_rows
            or resolution_event_rows
        ):
            raise ActionEvidenceError("PendingAction resolution is inconsistent")
        return
    outcome = {
        "DECLINED": "action_declined",
        "EXPIRED": "action_expired",
    }.get(state)
    if (
        outcome is None
        or pending.resolution_turn_id is None
        or pending.resolution_trace_id is None
        or len(resolution_turn_rows) != 1
        or len(resolution_turn_rows[0]) != 7
        or tuple(resolution_turn_rows[0])
        != (
            pending.resolution_turn_id,
            pending.resolution_trace_id,
            pending.conversation_id,
            pending.session_id,
            pending.user_subject,
            "COMPLETED",
            outcome,
        )
    ):
        raise ActionEvidenceError("PendingAction resolution turn is inconsistent")
    validate_resolved_action_events(
        resolution_event_rows,
        expected_trace_id=pending.resolution_trace_id,
        expected_session_id=pending.session_id,
        expected_user_subject=pending.user_subject,
        pending_action_id=pending.pending_action_id,
        outcome=cast(Literal["action_declined", "action_expired"], outcome),
    )


def validate_pending_action_events(
    rows: tuple[tuple[object, ...], ...] | list[tuple[object, ...]],
    *,
    expected_trace_id: str,
    expected_session_id: str,
    expected_user_subject: str,
    pending_action_id: str,
    action_type: str,
    argument_commitment: str,
    target_version: int,
    expires_at: datetime,
) -> tuple[ActionEvidenceEvent, ...]:
    if not 4 <= len(rows) <= MAX_ACTION_SOURCE_TURN_EVENTS:
        raise ActionEvidenceError("Action event cardinality is inconsistent")
    events: list[ActionEvidenceEvent] = []
    try:
        for expected_sequence, row in enumerate(rows, start=1):
            if (
                len(row) != 7
                or tuple(row[1:5])
                != (
                    expected_trace_id,
                    expected_session_id,
                    expected_user_subject,
                    expected_sequence,
                )
                or not all(isinstance(row[index], str) for index in (0, 1, 2, 3, 5, 6))
                or _canonical_uuid(row[0]) != row[0]
            ):
                raise ActionEvidenceError("Action event identity or sequence is inconsistent")
            events.append(
                ActionEvidenceEvent(
                    event_id=str(row[0]),
                    trace_id=str(row[1]),
                    session_id=str(row[2]),
                    user_subject=str(row[3]),
                    sequence=expected_sequence,
                    event_type=str(row[5]),
                    payload=strict_json_object(str(row[6]).encode("utf-8")),
                )
            )
    except (ActionJsonError, TypeError, ValueError) as exception:
        if isinstance(exception, ActionEvidenceError):
            raise
        raise ActionEvidenceError("Action event content is invalid") from exception
    prepared = [event for event in events if event.event_type == "ACTION_PREPARED"]
    expected_payload = {
        "pendingActionId": pending_action_id,
        "actionType": action_type,
        "argumentCommitment": argument_commitment,
        "targetVersion": target_version,
        "expiresAt": canonical_action_timestamp(expires_at),
    }
    if (
        len(prepared) != 1
        or prepared[0] is not events[-4]
        or events[0].event_type != "USER_INPUT"
        or events[0].payload != {"accepted": True}
        or prepared[0].payload != expected_payload
        or [event.event_type for event in events[-3:]]
        != ["AGENT_OUTCOME", "ASSISTANT_RESPONSE", "TURN_COMPLETED"]
        or any(event.payload != {"outcome": "action_pending"} for event in events[-3:])
        or any(
            event.event_type in {"ACTION_DECLINED", "ACTION_EXPIRED", "ACTION_RECEIPT"}
            for event in events
        )
        or any(
            event is not prepared[0] and set(expected_payload) & set(event.payload)
            for event in events
        )
    ):
        raise ActionEvidenceError("Action pending event closure is inconsistent")
    return tuple(events)


def validate_resolved_action_events(
    rows: tuple[tuple[object, ...], ...] | list[tuple[object, ...]],
    *,
    expected_trace_id: str,
    expected_session_id: str,
    expected_user_subject: str,
    pending_action_id: str,
    outcome: Literal["action_declined", "action_expired"],
) -> tuple[ActionEvidenceEvent, ...]:
    event_type, event_outcome = {
        "action_declined": ("ACTION_DECLINED", "declined"),
        "action_expired": ("ACTION_EXPIRED", "expired"),
    }[outcome]
    if len(rows) != 5:
        raise ActionEvidenceError("Resolved action event cardinality is inconsistent")
    events: list[ActionEvidenceEvent] = []
    try:
        for expected_sequence, row in enumerate(rows, start=1):
            if (
                len(row) != 7
                or tuple(row[1:5])
                != (
                    expected_trace_id,
                    expected_session_id,
                    expected_user_subject,
                    expected_sequence,
                )
                or not all(isinstance(row[index], str) for index in (0, 1, 2, 3, 5, 6))
                or _canonical_uuid(row[0]) != row[0]
            ):
                raise ActionEvidenceError(
                    "Resolved action event identity or sequence is inconsistent"
                )
            events.append(
                ActionEvidenceEvent(
                    event_id=str(row[0]),
                    trace_id=str(row[1]),
                    session_id=str(row[2]),
                    user_subject=str(row[3]),
                    sequence=expected_sequence,
                    event_type=str(row[5]),
                    payload=strict_json_object(str(row[6]).encode("utf-8")),
                )
            )
    except (ActionJsonError, TypeError, ValueError) as exception:
        if isinstance(exception, ActionEvidenceError):
            raise
        raise ActionEvidenceError("Resolved action event content is invalid") from exception
    if (
        events[0].event_type != "USER_INPUT"
        or events[0].payload != {"accepted": True}
        or [event.event_type for event in events[1:]]
        != [event_type, "AGENT_OUTCOME", "ASSISTANT_RESPONSE", "TURN_COMPLETED"]
        or events[1].payload != {"pendingActionId": pending_action_id, "outcome": event_outcome}
        or any(event.payload != {"outcome": outcome} for event in events[-3:])
        or any(
            event is not events[1]
            and (
                event.event_type
                in {"ACTION_PREPARED", "ACTION_DECLINED", "ACTION_EXPIRED", "ACTION_RECEIPT"}
                or "pendingActionId" in event.payload
            )
            for event in events
        )
    ):
        raise ActionEvidenceError("Resolved action event closure is inconsistent")
    return tuple(events)


class ConfirmationDecision(Enum):
    CONFIRM = "CONFIRM"
    DECLINE = "DECLINE"
    CLARIFY = "CLARIFY"


_CONFIRMATIONS = frozenset(
    {"confirm", "confirm refund", "yes", "yes confirm", "确认", "确认退款", "是的", "是的确认"}
)
_DECLINES = frozenset(
    {"cancel", "decline", "do not confirm", "no", "no cancel", "不", "不确认", "取消", "取消退款"}
)


def confirmation_decision(message: str) -> ConfirmationDecision:
    normalized = " ".join(unicodedata.normalize("NFKC", message).strip().casefold().split())
    if normalized in _CONFIRMATIONS:
        return ConfirmationDecision.CONFIRM
    if normalized in _DECLINES:
        return ConfirmationDecision.DECLINE
    return ConfirmationDecision.CLARIFY
