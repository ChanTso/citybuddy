from __future__ import annotations

import hashlib
import json
from typing import cast

import pytest
from citybuddy_agent.faq_cache import (
    FAQ_CACHE_SCHEMA,
    MAX_QUERY_CODE_UNITS,
    RedisFaqCache,
    normalized_query_hash,
)
from redis import Redis
from redis.exceptions import (
    ConnectionError,
)
from redis.exceptions import (
    TimeoutError as RedisTimeoutError,
)


class FakeRedis:
    def __init__(self, result: object) -> None:
        self.result = result
        self.eval_calls: list[tuple[object, ...]] = []
        self.deleted: list[str] = []

    def eval(self, *args: object) -> object:
        self.eval_calls.append(args)
        if isinstance(self.result, Exception):
            raise self.result
        return self.result

    def delete(self, key: str) -> int:
        self.deleted.append(key)
        return 1


def commitment(
    *,
    source_id: str = "faq-refund",
    source_version: int = 7,
    title: str = "Refund policy",
    answer: str = "A bounded public answer.",
    event_id: str = "11111111-1111-4111-8111-111111111111",
    occurred_time: str = "2026-07-21T12:34:56Z",
) -> str:
    canonical = json.dumps(
        {
            "content": {"answer": answer, "question": title},
            "eventId": event_id,
            "occurredTime": occurred_time,
            "publicationState": "PUBLISHED",
            "sourceId": source_id,
            "sourceType": "faq",
            "sourceVersion": source_version,
            "tombstone": False,
        },
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode()
    return hashlib.sha256(canonical).hexdigest()


def cache_commitment(
    *, event_commitment: str | None = None, index_version: str = "knowledge_docs_v3"
) -> str:
    canonical = json.dumps(
        {
            "eventCommitment": event_commitment or commitment(),
            "indexVersion": index_version,
        },
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode()
    return hashlib.sha256(canonical).hexdigest()


def answer_hash(**overrides: str) -> list[str]:
    values = {
        "schema": FAQ_CACHE_SCHEMA,
        "source_id": "faq-refund",
        "source_version": "7",
        "doc_type": "faq",
        "chunk_id": "answer",
        "title": "Refund policy",
        "answer": "A bounded public answer.",
        "index_version": "knowledge_docs_v3",
        "commitment": commitment(),
        "cache_commitment": cache_commitment(),
        "public_category": "faq",
        "public_language": "und",
        **overrides,
    }
    return [item for pair in values.items() for item in pair]


def state_hash(**overrides: str) -> list[str]:
    values = {
        "schema": FAQ_CACHE_SCHEMA,
        "source_id": "faq-refund",
        "source_version": "7",
        "tombstone": "0",
        "commitment": commitment(),
        "index_version": "knowledge_docs_v3",
        "event_id": "11111111-1111-4111-8111-111111111111",
        "occurred_time": "2026-07-21T12:34:56Z",
        "ready": "1",
        "cache_commitment": cache_commitment(),
        "lease_deadline_ms": "",
        **overrides,
    }
    return [item for pair in values.items() for item in pair]


def snapshot(
    *, answer_overrides: dict[str, str] | None = None, state_overrides: dict[str, str] | None = None
) -> list[list[str]]:
    return [state_hash(**(state_overrides or {})), answer_hash(**(answer_overrides or {}))]


def cache_with(fake: FakeRedis) -> RedisFaqCache:
    return RedisFaqCache(
        "redis://agent_cache:secret@redis-support:6379/0", client=cast(Redis, fake)
    )


def test_normalization_is_server_owned_bounded_and_stores_no_raw_query() -> None:
    expected = normalized_query_hash("  Refund\u3000POLICY  ")

    assert expected == normalized_query_hash("refund policy")
    assert expected is not None and len(expected) == 64
    assert normalized_query_hash("refund\x00policy") is None
    assert normalized_query_hash("x" * (MAX_QUERY_CODE_UNITS + 1)) is None
    assert normalized_query_hash("\ud800") is None


def test_exact_valid_answer_hash_projects_one_bounded_faq_candidate() -> None:
    fake = FakeRedis(snapshot())

    result = cache_with(fake).lookup("Refund policy")

    assert result is not None
    assert result.index_version == "knowledge_docs_v3"
    assert result.results[0].model_dump(by_alias=True) == {
        "sourceId": "faq-refund",
        "chunkId": "answer",
        "sourceVersion": 7,
        "docType": "faq",
        "title": "Refund policy",
        "excerpt": "A bounded public answer.",
        "publicMetadata": {"category": "faq", "language": "und", "productId": None},
        "rank": 1,
        "rrfScore": pytest.approx(1 / 61),
    }
    serialized_call = repr(fake.eval_calls)
    assert "Refund policy" not in serialized_call


@pytest.mark.parametrize(
    "result",
    [
        [],
        snapshot(answer_overrides={"extra": "unknown"}),
        snapshot(answer_overrides={"source_version": "0"}),
        snapshot(answer_overrides={"doc_type": "product"}),
        snapshot(answer_overrides={"index_version": "private_index"}),
        snapshot(answer_overrides={"title": "tampered title"}),
        snapshot(answer_overrides={"answer": "tampered answer"}),
        snapshot(state_overrides={"occurred_time": "2026-07-21T12:34:57Z"}),
        snapshot(state_overrides={"index_version": "knowledge_docs_v2"}),
        snapshot(
            answer_overrides={"index_version": "knowledge_docs_v9"},
            state_overrides={"index_version": "knowledge_docs_v9"},
        ),
        snapshot(state_overrides={"ready": "0"}),
        ConnectionError("unavailable"),
        RedisTimeoutError("timeout"),
    ],
)
def test_malformed_or_unavailable_cache_is_a_miss_and_mapping_is_removed(result: object) -> None:
    fake = FakeRedis(result)

    assert cache_with(fake).lookup("Refund policy") is None
    assert len(fake.deleted) <= 1


def test_mapping_population_uses_only_hash_and_exact_source_identity() -> None:
    fake = FakeRedis(1)

    populated = cache_with(fake).populate_mapping(" Refund policy ", "faq-refund", 7)

    assert populated
    serialized_call = repr(fake.eval_calls)
    assert "Refund policy" not in serialized_call
    assert "faq-refund" in serialized_call
    assert not cache_with(FakeRedis(1)).populate_mapping("query", "FAQ/private", 7)
    assert not cache_with(FakeRedis(1)).populate_mapping("query", "faq-refund", 0)
