import json
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import UTC, datetime

import httpx
import pytest
from citybuddy_agent.actions import (
    ActionEvidenceError,
    ActionJsonError,
    ActionReceiptPayload,
    ConfirmationDecision,
    PendingActionPayload,
    PendingActionReference,
    action_argument_commitment,
    bounded_http_post,
    canonical_action_timestamp,
    confirmation_decision,
    strict_json_object,
    validate_completed_action_events,
    validate_pending_action_events,
    validate_pending_action_reference,
    validate_pending_action_resolution,
    validate_resolved_action_events,
)
from citybuddy_agent.evaluation import EvaluationEvidenceInvalid, MysqlEvaluationEvidenceStore
from pydantic import ValidationError


class CountingStream(httpx.SyncByteStream):
    def __init__(self, chunks: tuple[bytes, ...]) -> None:
        self.chunks = chunks
        self.yielded = 0

    def __iter__(self) -> Iterator[bytes]:
        for chunk in self.chunks:
            self.yielded += 1
            yield chunk


class ReferenceInventoryCursor:
    def __init__(
        self,
        source_references: tuple[tuple[object, ...], ...] = (),
        resolution_references: tuple[tuple[object, ...], ...] = (),
        confirmation_references: tuple[tuple[object, ...], ...] = (),
        receipt_projections: tuple[tuple[object, ...], ...] = (),
    ) -> None:
        self.source_references = source_references
        self.resolution_references = resolution_references
        self.confirmation_references = confirmation_references
        self.receipt_projections = receipt_projections
        self.query = ""

    def execute(self, query: str, parameters: tuple[object, ...]) -> None:
        del parameters
        self.query = query

    def fetchall(self) -> tuple[tuple[object, ...], ...]:
        if "WHERE source_turn_id = %s LIMIT 2" in self.query:
            return self.source_references
        if "WHERE resolution_turn_id = %s LIMIT 2" in self.query:
            return self.resolution_references
        if "FROM pending_action_reference" in self.query:
            assert "WHERE confirmation_turn_id = %s LIMIT 2" in self.query
            return self.confirmation_references
        assert "FROM action_receipt_projection" in self.query
        return self.receipt_projections


class ActionTruthInventoryCursor:
    def __init__(
        self,
        events: tuple[tuple[object, ...], ...],
        references: tuple[tuple[object, ...], ...],
    ) -> None:
        self.events = events
        self.references = references
        self.query = ""
        self.queries: list[str] = []

    def execute(self, query: str, parameters: tuple[object, ...]) -> None:
        del parameters
        self.query = query
        self.queries.append(query)

    def fetchall(self) -> tuple[tuple[object, ...], ...]:
        if "FROM support_event" in self.query:
            return self.events
        if "FROM pending_action_reference" in self.query:
            return self.references
        assert "FROM action_receipt_projection" in self.query
        return ()


@pytest.mark.parametrize(
    ("events", "references", "expected"),
    [
        ((("USER_INPUT",), ("ACTION_PREPARED",)), (), True),
        ((("USER_INPUT",), ("ACTION_DECLINED",)), (), True),
        ((("USER_INPUT",),), (("00000000-0000-0000-0000-000000000121",),), True),
        ((("USER_INPUT",), ("TURN_COMPLETED",)), (), False),
    ],
)
def test_evaluation_action_scope_is_enumerated_before_terminal_outcome_validation(
    events: tuple[tuple[object, ...], ...],
    references: tuple[tuple[object, ...], ...],
    expected: bool,
) -> None:
    cursor = ActionTruthInventoryCursor(events, references)

    assert (
        MysqlEvaluationEvidenceStore._action_truth_present(  # noqa: SLF001
            cursor,  # type: ignore[arg-type]
            "00000000-0000-0000-0000-000000000122",
        )
        is expected
    )
    assert any(
        "source_turn_id = %s OR resolution_turn_id = %s" in query for query in cursor.queries
    )


@pytest.mark.parametrize("outcome", ["completed", "action_clarification"])
@pytest.mark.parametrize("reference_kind", ["source", "resolution"])
def test_evaluation_rejects_action_reference_on_non_owning_turn(
    outcome: str, reference_kind: str
) -> None:
    store = object.__new__(MysqlEvaluationEvidenceStore)
    references = (("00000000-0000-0000-0000-000000000121",),)
    cursor = ReferenceInventoryCursor(
        source_references=references if reference_kind == "source" else (),
        resolution_references=references if reference_kind == "resolution" else (),
    )

    with pytest.raises(EvaluationEvidenceInvalid):
        store._validate_action_truth(  # noqa: SLF001
            cursor,  # type: ignore[arg-type]
            rows=(
                (
                    "00000000-0000-0000-0000-000000000122",
                    "00000000-0000-0000-0000-000000000123",
                    "session-1",
                    "user-1",
                    1,
                    "USER_INPUT",
                    '{"accepted":true}',
                ),
            ),
            trace_id="00000000-0000-0000-0000-000000000123",
            turn_id="00000000-0000-0000-0000-000000000124",
            session_id="session-1",
            subject="user-1",
            sandbox_id="sandbox-1",
            conversation_id="00000000-0000-0000-0000-000000000125",
            terminal_outcome=outcome,  # type: ignore[arg-type]
        )


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


@pytest.mark.parametrize(
    "payload",
    [
        b"",
        b"\xff",
        b"[]",
        b'{"value":NaN}',
        b'{"value":Infinity}',
        b'{"key":1,"key":2}',
        json.dumps({"root": [[[[[[[[[[[[[[[[[0]]]]]]]]]]]]]]]]]}).encode(),
        json.dumps({"root": list(range(256))}).encode(),
        b'{"value":"\\uZZZZ"}',
        b'{"value":"\xed\xa0\x80"}',
        b"{" + b" " * 4096 + b"}",
    ],
)
def test_strict_action_decoder_is_total_and_bounded(payload: bytes) -> None:
    with pytest.raises(ActionJsonError):
        strict_json_object(payload)


def test_pending_action_schema_is_closed_and_canonical() -> None:
    document: dict[str, object] = {
        "pendingActionId": "00000000-0000-0000-0000-000000000121",
        "actionType": "REFUND_REQUEST",
        "userSubject": "user-1",
        "supportSessionId": "session-1",
        "traceId": "00000000-0000-0000-0000-000000000123",
        "turnId": "00000000-0000-0000-0000-000000000122",
        "requiredScope": "refund:create",
        "sandboxId": "sandbox-1",
        "orderId": "00000000-0000-0000-0000-000000000040",
        "targetVersion": 1,
        "amountMinor": 400,
        "currency": "CNY",
        "state": "PREPARED",
        "expiresAt": "2026-07-28T04:00:00.123456Z",
        "replayed": False,
    }
    pending = PendingActionPayload.model_validate(strict_json_object(json.dumps(document).encode()))
    assert pending.expires_at == datetime(2026, 7, 28, 4, 0, 0, 123456, tzinfo=UTC)
    for key, value in (
        ("unknown", "forbidden"),
        ("amountMinor", True),
        ("expiresAt", "2026-07-28T04:00:00Z"),
        ("targetVersion", True),
        ("state", "CONSUMED"),
    ):
        damaged = dict(document)
        damaged[key] = value
        with pytest.raises(ValidationError):
            PendingActionPayload.model_validate(strict_json_object(json.dumps(damaged).encode()))


def test_action_receipt_schema_is_closed_strict_and_complete() -> None:
    document: dict[str, object] = {
        "receiptId": "00000000-0000-0000-0000-000000000211",
        "pendingActionId": "00000000-0000-0000-0000-000000000121",
        "actionType": "REFUND_REQUEST",
        "status": "REQUESTED",
        "orderId": "00000000-0000-0000-0000-000000000040",
        "refundId": "00000000-0000-0000-0000-000000000071",
        "resourceVersion": 1,
        "amountMinor": 400,
        "currency": "CNY",
        "committedAt": "2026-08-01T01:02:03.123456Z",
        "replayed": False,
    }
    receipt = ActionReceiptPayload.model_validate(strict_json_object(json.dumps(document).encode()))
    assert receipt.committed_at == datetime(2026, 8, 1, 1, 2, 3, 123456, tzinfo=UTC)
    for key in document:
        missing = dict(document)
        del missing[key]
        with pytest.raises(ValidationError):
            ActionReceiptPayload.model_validate(strict_json_object(json.dumps(missing).encode()))
        wrong_type = dict(document)
        wrong_type[key] = None
        with pytest.raises(ValidationError):
            ActionReceiptPayload.model_validate(strict_json_object(json.dumps(wrong_type).encode()))
    unknown = {**document, "unknown": "forbidden"}
    with pytest.raises(ValidationError):
        ActionReceiptPayload.model_validate(strict_json_object(json.dumps(unknown).encode()))


def test_completed_action_event_closure_requires_receipt_and_terminal_suffix() -> None:
    receipt = ActionReceiptPayload.model_validate(
        {
            "receiptId": "00000000-0000-0000-0000-000000000211",
            "pendingActionId": "00000000-0000-0000-0000-000000000121",
            "actionType": "REFUND_REQUEST",
            "status": "REQUESTED",
            "orderId": "00000000-0000-0000-0000-000000000040",
            "refundId": "00000000-0000-0000-0000-000000000071",
            "resourceVersion": 1,
            "amountMinor": 400,
            "currency": "CNY",
            "committedAt": "2026-08-01T01:02:03.123456Z",
            "replayed": False,
        }
    )
    trace_id = "00000000-0000-0000-0000-000000000301"

    def event(sequence: int, event_type: str, payload: object) -> tuple[object, ...]:
        return (
            f"00000000-0000-0000-0000-{sequence:012d}",
            trace_id,
            "session-1",
            "user-1",
            sequence,
            event_type,
            json.dumps(payload),
        )

    rows = [
        event(1, "USER_INPUT", {"accepted": True}),
        event(
            2,
            "ACTION_RECEIPT",
            {
                "receiptId": receipt.receipt_id,
                "pendingActionId": receipt.pending_action_id,
                "status": receipt.status,
                "receiptCommitment": receipt.receipt_commitment,
            },
        ),
        event(3, "AGENT_OUTCOME", {"outcome": "action_completed"}),
        event(4, "ASSISTANT_RESPONSE", {"outcome": "action_completed"}),
        event(5, "TURN_COMPLETED", {"outcome": "action_completed"}),
    ]
    assert (
        len(
            validate_completed_action_events(
                rows,
                expected_trace_id=trace_id,
                expected_session_id="session-1",
                expected_user_subject="user-1",
                receipt=receipt,
            )
        )
        == 5
    )
    for index in range(len(rows)):
        damaged = list(rows)
        del damaged[index]
        with pytest.raises(ActionEvidenceError):
            validate_completed_action_events(
                damaged,
                expected_trace_id=trace_id,
                expected_session_id="session-1",
                expected_user_subject="user-1",
                receipt=receipt,
            )
    duplicate_receipt = [*rows[:2], rows[1], *rows[2:]]
    with pytest.raises(ActionEvidenceError):
        validate_completed_action_events(
            duplicate_receipt,
            expected_trace_id=trace_id,
            expected_session_id="session-1",
            expected_user_subject="user-1",
            receipt=receipt,
        )


def test_pending_reference_and_source_turn_matrix_binds_every_persisted_field() -> None:
    expiry = datetime(2026, 7, 28, 4, 0, 0, 123456, tzinfo=UTC)
    order_id = "00000000-0000-0000-0000-000000000040"
    row: tuple[object, ...] = (
        "00000000-0000-0000-0000-000000000121",
        "00000000-0000-0000-0000-000000000122",
        "00000000-0000-0000-0000-000000000123",
        "00000000-0000-0000-0000-000000000124",
        "session-1",
        "user-1",
        "sandbox-1",
        "REFUND_REQUEST",
        action_argument_commitment("REFUND_REQUEST", order_id, 400, "CNY"),
        order_id,
        1,
        400,
        "CNY",
        "PENDING",
        None,
        None,
        expiry,
        None,
        None,
        None,
    )
    source_turn: tuple[object, ...] = (
        row[1],
        row[2],
        row[3],
        row[4],
        row[5],
        "COMPLETED",
        "action_pending",
    )
    expected = {
        "expected_turn_id": str(row[1]),
        "expected_trace_id": str(row[2]),
        "expected_conversation_id": str(row[3]),
        "expected_session_id": str(row[4]),
        "expected_user_subject": str(row[5]),
        "expected_sandbox_id": str(row[6]),
    }
    pending, state, persisted_expiry = validate_pending_action_reference(
        row, [source_turn], **expected
    )
    assert pending.pending_action_id == row[0]
    assert state == "PENDING"
    assert persisted_expiry == expiry

    resolved_row = list(row)
    resolved_row[13] = "DECLINED"
    resolved_row[17] = expiry
    resolved_row[18] = "00000000-0000-0000-0000-000000000130"
    resolved_row[19] = "00000000-0000-0000-0000-000000000131"
    resolved, resolved_state, _ = validate_pending_action_reference(
        tuple(resolved_row), [source_turn], **expected
    )
    assert resolved_state == "DECLINED"
    assert resolved.resolution_turn_id == resolved_row[18]
    assert resolved.resolution_trace_id == resolved_row[19]

    damaged_values: dict[int, object] = {
        0: "not-a-uuid",
        1: "00000000-0000-0000-0000-000000000999",
        2: "00000000-0000-0000-0000-000000000999",
        3: "00000000-0000-0000-0000-000000000999",
        4: "other-session",
        5: "other-user",
        6: "other-sandbox",
        7: "OTHER_ACTION",
        8: "b" * 64,
        9: "00000000-0000-0000-0000-000000000999",
        10: True,
        11: True,
        12: "AUD",
        13: "DECLINED",
        14: "00000000-0000-0000-0000-000000000998",
        15: "00000000-0000-0000-0000-000000000997",
        16: "not-a-timestamp",
        17: expiry,
        18: "00000000-0000-0000-0000-000000000998",
        19: "00000000-0000-0000-0000-000000000997",
    }
    for index, value in damaged_values.items():
        damaged = list(row)
        damaged[index] = value
        with pytest.raises(ActionEvidenceError):
            validate_pending_action_reference(tuple(damaged), [source_turn], **expected)

    source_damage: dict[int, object] = {
        0: "00000000-0000-0000-0000-000000000999",
        1: "00000000-0000-0000-0000-000000000999",
        2: "00000000-0000-0000-0000-000000000999",
        3: "other-session",
        4: "other-user",
        5: "FAILED",
        6: "completed",
    }
    for index, value in source_damage.items():
        damaged_source = list(source_turn)
        damaged_source[index] = value
        with pytest.raises(ActionEvidenceError):
            validate_pending_action_reference(row, [tuple(damaged_source)], **expected)
    for cardinality in ([], [source_turn, source_turn]):
        with pytest.raises(ActionEvidenceError):
            validate_pending_action_reference(row, cardinality, **expected)


def test_resolved_pending_reference_is_anchored_to_one_exact_decision_turn() -> None:
    expiry = datetime(2026, 7, 28, 4, 0, 0, 123456, tzinfo=UTC)
    resolution_turn_id = "00000000-0000-0000-0000-000000000130"
    resolution_trace_id = "00000000-0000-0000-0000-000000000131"
    pending = PendingActionReference(
        pending_action_id="00000000-0000-0000-0000-000000000121",
        source_turn_id="00000000-0000-0000-0000-000000000122",
        source_trace_id="00000000-0000-0000-0000-000000000123",
        conversation_id="00000000-0000-0000-0000-000000000124",
        session_id="session-1",
        user_subject="user-1",
        sandbox_id="sandbox-1",
        action_type="REFUND_REQUEST",
        argument_commitment="a" * 64,
        order_id="00000000-0000-0000-0000-000000000040",
        target_version=1,
        amount_minor=400,
        currency="CNY",
        expires_at=expiry,
        resolution_turn_id=resolution_turn_id,
        resolution_trace_id=resolution_trace_id,
    )

    def event(sequence: int, event_type: str, payload: object) -> tuple[object, ...]:
        return (
            f"00000000-0000-0000-0000-{sequence:012d}",
            resolution_trace_id,
            pending.session_id,
            pending.user_subject,
            sequence,
            event_type,
            json.dumps(payload),
        )

    turn = (
        resolution_turn_id,
        resolution_trace_id,
        pending.conversation_id,
        pending.session_id,
        pending.user_subject,
        "COMPLETED",
        "action_declined",
    )
    events = (
        event(1, "USER_INPUT", {"accepted": True}),
        event(
            2,
            "ACTION_DECLINED",
            {"pendingActionId": pending.pending_action_id, "outcome": "declined"},
        ),
        event(3, "AGENT_OUTCOME", {"outcome": "action_declined"}),
        event(4, "ASSISTANT_RESPONSE", {"outcome": "action_declined"}),
        event(5, "TURN_COMPLETED", {"outcome": "action_declined"}),
    )
    validate_pending_action_resolution(pending, "DECLINED", (turn,), events)

    for damaged_turn in (
        (),
        (turn, turn),
        (
            (
                resolution_turn_id,
                resolution_trace_id,
                pending.conversation_id,
                pending.session_id,
                pending.user_subject,
                "COMPLETED",
                "action_expired",
            ),
        ),
    ):
        with pytest.raises(ActionEvidenceError):
            validate_pending_action_resolution(pending, "DECLINED", damaged_turn, events)
    with pytest.raises(ActionEvidenceError):
        validate_pending_action_resolution(pending, "DECLINED", (turn,), events[:-1])


def test_pending_event_closure_enumerates_before_content_assertions() -> None:
    trace_id = "00000000-0000-0000-0000-000000000123"
    pending_id = "00000000-0000-0000-0000-000000000121"
    expiry = datetime(2026, 7, 28, 4, 0, 0, 123456, tzinfo=UTC)
    prepared = {
        "pendingActionId": pending_id,
        "actionType": "REFUND_REQUEST",
        "argumentCommitment": "a" * 64,
        "targetVersion": 1,
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
    validate_pending_action_events(
        valid,
        expected_trace_id=trace_id,
        expected_session_id="session-1",
        expected_user_subject="user-1",
        pending_action_id=pending_id,
        action_type="REFUND_REQUEST",
        argument_commitment="a" * 64,
        target_version=1,
        expires_at=expiry,
    )
    counterexamples = [
        [valid[0], *valid[2:]],
        valid[:2] + [row(3, "ACTION_PREPARED", prepared)] + valid[2:],
        [*valid[:2], row(4, "AGENT_OUTCOME", {"outcome": "action_pending"}), *valid[3:]],
        [valid[0], row(2, "ACTION_PREPARED", {**prepared, "expiresAt": "wrong"}), *valid[2:]],
        [valid[0], row(2, "ACTION_PREPARED", {**prepared, "targetVersion": 2}), *valid[2:]],
        [valid[0], (*valid[1][:6], None), *valid[2:]],
        [
            valid[0],
            row(2, "MODEL_OUTCOME", prepared),
            row(3, "ACTION_PREPARED", prepared),
            row(4, "AGENT_OUTCOME", {"outcome": "action_pending"}),
            row(5, "ASSISTANT_RESPONSE", {"outcome": "action_pending"}),
            row(6, "TURN_COMPLETED", {"outcome": "action_pending"}),
        ],
    ]
    for damaged in counterexamples:
        with pytest.raises(ActionEvidenceError):
            validate_pending_action_events(
                damaged,
                expected_trace_id=trace_id,
                expected_session_id="session-1",
                expected_user_subject="user-1",
                pending_action_id=pending_id,
                action_type="REFUND_REQUEST",
                argument_commitment="a" * 64,
                target_version=1,
                expires_at=expiry,
            )

    damage_by_field: dict[int, object] = {
        0: "not-a-uuid",
        1: "00000000-0000-0000-0000-000000000999",
        2: "other-session",
        3: "other-user",
        4: 99,
        5: "ACTION_RECEIPT",
        6: "{}",
    }
    for row_index in range(len(valid)):
        for field_index, value in damage_by_field.items():
            damaged = list(valid)
            damaged_row = list(damaged[row_index])
            damaged_row[field_index] = value
            damaged[row_index] = tuple(damaged_row)
            with pytest.raises(ActionEvidenceError):
                validate_pending_action_events(
                    damaged,
                    expected_trace_id=trace_id,
                    expected_session_id="session-1",
                    expected_user_subject="user-1",
                    pending_action_id=pending_id,
                    action_type="REFUND_REQUEST",
                    argument_commitment="a" * 64,
                    target_version=1,
                    expires_at=expiry,
                )


def test_resolved_action_event_closure_binds_every_row_to_owning_trace() -> None:
    trace_id = "00000000-0000-0000-0000-000000000123"
    pending_id = "00000000-0000-0000-0000-000000000121"

    def row(
        sequence: int,
        event_type: str,
        payload: object,
        *,
        trace: str = trace_id,
    ) -> tuple[object, ...]:
        return (
            f"00000000-0000-0000-0000-{sequence:012d}",
            trace,
            "session-1",
            "user-1",
            sequence,
            event_type,
            json.dumps(payload),
        )

    valid = [
        row(1, "USER_INPUT", {"accepted": True}),
        row(
            2,
            "ACTION_DECLINED",
            {"pendingActionId": pending_id, "outcome": "declined"},
        ),
        row(3, "AGENT_OUTCOME", {"outcome": "action_declined"}),
        row(4, "ASSISTANT_RESPONSE", {"outcome": "action_declined"}),
        row(5, "TURN_COMPLETED", {"outcome": "action_declined"}),
    ]
    validate_resolved_action_events(
        valid,
        expected_trace_id=trace_id,
        expected_session_id="session-1",
        expected_user_subject="user-1",
        pending_action_id=pending_id,
        outcome="action_declined",
    )

    for index in range(len(valid)):
        damaged = list(valid)
        damaged[index] = row(
            index + 1,
            str(valid[index][5]),
            json.loads(str(valid[index][6])),
            trace="00000000-0000-0000-0000-000000000999",
        )
        with pytest.raises(ActionEvidenceError):
            validate_resolved_action_events(
                damaged,
                expected_trace_id=trace_id,
                expected_session_id="session-1",
                expected_user_subject="user-1",
                pending_action_id=pending_id,
                outcome="action_declined",
            )


def test_action_response_cap_stops_stream_before_full_materialization(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = CountingStream((b"a" * 2048, b"b" * 2049, b"never-read"))

    @contextmanager
    def stream(method: str, url: str, **kwargs: object) -> Iterator[httpx.Response]:
        del kwargs
        assert method == "POST"
        yield httpx.Response(
            200,
            request=httpx.Request("POST", url),
            stream=source,
        )

    monkeypatch.setattr(httpx, "stream", stream)
    with pytest.raises(ActionJsonError, match="oversized"):
        bounded_http_post(
            "https://commerce.test/action",
            headers={"X-Test": "true"},
            json={},
            timeout=1.0,
        )
    assert source.yielded == 2
