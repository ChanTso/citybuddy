import json
from copy import deepcopy
from datetime import UTC, datetime
from uuid import UUID

import pytest
from citybuddy_indexer.incremental import (
    ElasticsearchKnowledgeProjection,
    FaqKnowledgeEvent,
    KnowledgeEventError,
    deterministic_document_embedding,
)
from citybuddy_indexer.knowledge import EMBEDDING_DIMS


def event_payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "eventId": "11111111-1111-4111-8111-111111111111",
        "sourceId": "faq-delivery-window",
        "sourceType": "faq",
        "sourceVersion": 3,
        "publicationState": "PUBLISHED",
        "tombstone": False,
        "occurredTime": "2026-07-21T12:34:56.123456789Z",
        "content": {"question": "When is delivery?", "answer": "Delivery is tomorrow."},
    }
    payload.update(overrides)
    return payload


def encoded(payload: object) -> bytes:
    return json.dumps(payload, separators=(",", ":")).encode()


def test_exact_public_envelope_is_valid_and_committed() -> None:
    event = FaqKnowledgeEvent.from_bytes(encoded(event_payload()))

    assert UUID(event.event_id).version == 4
    assert event.source_id == "faq-delivery-window"
    assert event.source_version == 3
    assert len(event.commitment) == 64
    assert event.document_id == "faq-delivery-window:answer"
    assert datetime.fromisoformat(event.occurred_time.replace("Z", "+00:00")).tzinfo == UTC


@pytest.mark.parametrize(
    "mutation",
    [
        lambda value: value.update({"private": "not-public"}),
        lambda value: value.update({"eventId": "not-a-uuid"}),
        lambda value: value.update({"sourceId": "FAQ/private"}),
        lambda value: value.update({"sourceType": "product"}),
        lambda value: value.update({"sourceVersion": True}),
        lambda value: value.update({"sourceVersion": 0}),
        lambda value: value.update({"sourceVersion": 9_223_372_036_854_775_808}),
        lambda value: value.update({"publicationState": "DRAFT"}),
        lambda value: value.update({"tombstone": 1}),
        lambda value: value.update({"occurredTime": "2026-02-30T00:00:00Z"}),
        lambda value: value.update({"occurredTime": "2026-07-21T00:00:00.1Z"}),
        lambda value: value.update({"occurredTime": "2026-07-21T00:00:00.000Z"}),
        lambda value: value.update({"occurredTime": "2026-07-21T00:00:00.123000Z"}),
        lambda value: value.update({"content": {"question": "q" * 501, "answer": "public"}}),
        lambda value: value.update({"content": {"question": "public", "answer": "a" * 4001}}),
        lambda value: value.update({"content": {"question": " ", "answer": "public"}}),
        lambda value: value.update({"content": {"question": "🛠" * 251, "answer": "public"}}),
        lambda value: value.update({"content": {"question": "public"}}),
    ],
)
def test_invalid_or_unbounded_envelope_is_rejected(mutation: object) -> None:
    payload = deepcopy(event_payload())
    mutation(payload)  # type: ignore[operator]

    with pytest.raises(KnowledgeEventError):
        FaqKnowledgeEvent.from_bytes(encoded(payload))


def test_duplicate_json_field_and_oversized_payload_are_rejected() -> None:
    duplicate = encoded(event_payload()).replace(
        b'"sourceId":"faq-delivery-window"',
        b'"sourceId":"faq-delivery-window","sourceId":"faq-other"',
    )

    with pytest.raises(KnowledgeEventError, match="invalid_payload"):
        FaqKnowledgeEvent.from_bytes(duplicate)
    with pytest.raises(KnowledgeEventError, match="invalid_payload"):
        FaqKnowledgeEvent.from_bytes(b"{" + b" " * 8192 + b"}")


@pytest.mark.parametrize(
    "payload",
    [
        b"\xff",
        b"null",
        b"[]",
        b'"text"',
        b"1",
        b"true",
        b'{"eventId":null}',
        b'{"eventId":"line\\u0000break"}',
    ],
)
def test_all_parsing_and_framing_failures_are_bounded(payload: bytes) -> None:
    with pytest.raises(KnowledgeEventError):
        FaqKnowledgeEvent.from_bytes(payload)


def test_projection_sources_are_bounded_public_and_cosine_safe() -> None:
    event = FaqKnowledgeEvent.from_bytes(encoded(event_payload()))

    public = ElasticsearchKnowledgeProjection._projection_source(event)
    marker = ElasticsearchKnowledgeProjection._marker_source(event)

    assert public["published"] is True
    assert public["deleted"] is False
    assert public["title"] == "When is delivery?"
    assert public["content"] == "Delivery is tomorrow."
    assert len(public["embedding"]) == EMBEDDING_DIMS  # type: ignore[arg-type]
    assert marker["published"] is False
    assert marker["deleted"] is True
    marker_embedding = marker["embedding"]
    assert isinstance(marker_embedding, list)
    assert sum(marker_embedding) == 1.0
    assert "sandbox" not in json.dumps(public).casefold()


def test_deterministic_embedding_is_normalized() -> None:
    first = deterministic_document_embedding("退款", "Refund guidance")
    second = deterministic_document_embedding("退款", "Refund guidance")

    assert first == second
    assert sum(component * component for component in first) == pytest.approx(1.0)
