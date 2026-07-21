from __future__ import annotations

from typing import cast

import pytest
from citybuddy_indexer.faq_cache import CachePreparation, RedisFaqCacheProjection
from citybuddy_indexer.incremental import (
    FaqKnowledgeEvent,
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
    def __init__(self, results: list[object]) -> None:
        self.results = results
        self.calls: list[tuple[object, ...]] = []

    def eval(self, *args: object) -> object:
        self.calls.append(args)
        result = self.results.pop(0)
        if isinstance(result, Exception):
            raise result
        return result


def projection(*results: object) -> tuple[RedisFaqCacheProjection, FakeRedis]:
    fake = FakeRedis(list(results))
    return RedisFaqCacheProjection(cast(Redis, fake)), fake


def event(*, tombstone: bool = False) -> FaqKnowledgeEvent:
    payload = event_payload()
    payload["tombstone"] = tombstone
    return FaqKnowledgeEvent.from_bytes(encoded(payload))


@pytest.mark.parametrize(
    ("results", "expected"),
    [
        (
            [["prepared", "new_preparation"], ["applied", "ready_state_applied"]],
            ProjectionOutcome.APPLIED,
        ),
        (
            [["replay_prepared", "ready_replay_prepared"], ["applied", "ready_state_applied"]],
            ProjectionOutcome.REPLAYED,
        ),
        ([["stale", "ready_state_newer"]], ProjectionOutcome.STALE),
    ],
)
def test_projection_returns_only_closed_monotonic_outcomes(
    results: list[object], expected: ProjectionOutcome
) -> None:
    cache, fake = projection(*results)

    assert cache.apply(event(), "knowledge_docs_v1") is expected
    call = repr(fake.calls)
    assert "faq-delivery-window" in call
    assert ("knowledge_docs_v1" in call) is (expected is not ProjectionOutcome.STALE)
    assert ("When is delivery?" in call) is (expected is not ProjectionOutcome.STALE)


def test_tombstone_is_sent_as_a_closed_flag_without_answer_availability() -> None:
    cache, fake = projection(["prepared", "3"], ["applied", "3"])

    assert cache.apply(event(tombstone=True), "knowledge_docs_v1") is ProjectionOutcome.APPLIED
    assert "'1'" in repr(fake.calls)


def test_owner_local_equal_version_contradiction_requires_authoritative_projection() -> None:
    cache, _ = projection(["authority_required", "ready_state_contradiction"])

    with pytest.raises(KnowledgeSyncError, match="authoritative_projection_required"):
        cache.apply(event(), "knowledge_docs_v1")


def test_unknown_conflict_result_is_not_a_permanent_cache_decision() -> None:
    cache, _ = projection(["conflict", "conflicting_source_version"])

    with pytest.raises(KnowledgeSyncError, match="malformed_support_cache_response"):
        cache.apply(event(), "knowledge_docs_v1")


def test_authoritative_prepare_uses_the_explicit_authority_mode() -> None:
    cache, fake = projection(["prepared", "authoritative_repair_prepared"])

    assert cache.prepare_authoritatively(event()) is CachePreparation.PREPARED
    assert fake.calls[0][-1] == "1"


@pytest.mark.parametrize(
    "result",
    [
        ["retry", "malformed_state"],
        ["retry", "cache_preparation_missing"],
        ["error", "unexpected_cache_failure"],
    ],
)
def test_integrity_and_unknown_cache_failures_never_become_permanent_conflicts(
    result: list[str],
) -> None:
    cache, _ = projection(result)

    with pytest.raises(KnowledgeSyncError, match=result[1]):
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
