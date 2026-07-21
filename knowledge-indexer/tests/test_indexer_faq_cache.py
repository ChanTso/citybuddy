from __future__ import annotations

from typing import cast

import pytest
from citybuddy_indexer.faq_cache import RedisFaqCacheProjection
from citybuddy_indexer.incremental import (
    FaqKnowledgeEvent,
    KnowledgeSyncConflict,
    KnowledgeSyncError,
    ProjectionOutcome,
)
from redis import Redis
from redis.exceptions import (
    ConnectionError,
)
from redis.exceptions import (
    TimeoutError as RedisTimeoutError,
)
from test_incremental import encoded, event_payload


class FakeRedis:
    def __init__(self, result: object) -> None:
        self.result = result
        self.calls: list[tuple[object, ...]] = []

    def eval(self, *args: object) -> object:
        self.calls.append(args)
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


def projection(result: object) -> tuple[RedisFaqCacheProjection, FakeRedis]:
    fake = FakeRedis(result)
    return RedisFaqCacheProjection(cast(Redis, fake)), fake


def event(*, tombstone: bool = False) -> FaqKnowledgeEvent:
    payload = event_payload()
    payload["tombstone"] = tombstone
    return FaqKnowledgeEvent.from_bytes(encoded(payload))


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (["applied", "3"], ProjectionOutcome.APPLIED),
        (["replayed", "3"], ProjectionOutcome.REPLAYED),
        (["stale", "4"], ProjectionOutcome.STALE),
    ],
)
def test_projection_returns_only_closed_monotonic_outcomes(
    raw: object, expected: ProjectionOutcome
) -> None:
    cache, fake = projection(raw)

    assert cache.apply(event(), "knowledge_docs_v1") is expected
    call = repr(fake.calls)
    assert "faq-delivery-window" in call
    assert "knowledge_docs_v1" in call
    assert "When is delivery?" in call


def test_tombstone_is_sent_as_a_closed_flag_without_answer_availability() -> None:
    cache, fake = projection(["applied", "3"])

    assert cache.apply(event(tombstone=True), "knowledge_docs_v1") is ProjectionOutcome.APPLIED
    assert "'1'" in repr(fake.calls)


def test_equal_version_conflict_never_becomes_a_retrying_overwrite() -> None:
    cache, _ = projection(["error", "conflicting_source_version"])

    with pytest.raises(KnowledgeSyncConflict, match="conflicting_source_version"):
        cache.apply(event(), "knowledge_docs_v1")


@pytest.mark.parametrize("raw", [[], ["future", "3"], ["applied"], [1, 3]])
def test_malformed_redis_result_is_retryable_unavailability(raw: object) -> None:
    cache, _ = projection(raw)

    with pytest.raises(KnowledgeSyncError, match="malformed_support_cache_response"):
        cache.apply(event(), "knowledge_docs_v1")


@pytest.mark.parametrize("error", [ConnectionError("unavailable"), RedisTimeoutError("timeout")])
def test_redis_connection_failure_is_retryable_without_ack(error: Exception) -> None:
    cache, _ = projection(error)

    with pytest.raises(KnowledgeSyncError, match="support_cache_unavailable"):
        cache.apply(event(), "knowledge_docs_v1")
