import json
from collections.abc import Mapping
from dataclasses import dataclass

import pytest
from citybuddy_indexer import (
    DeliveryAction,
    IndexerSettings,
    IndexerWorker,
    KnowledgeSyncConflict,
    KnowledgeSyncError,
    ProjectionOutcome,
    create_worker,
)
from citybuddy_indexer.incremental import RESERVED_SANDBOX_PROPERTY, FaqKnowledgeEvent
from citybuddy_indexer.worker import VersionedKnowledgeProjection


def valid_body() -> bytes:
    return json.dumps(
        {
            "eventId": "11111111-1111-4111-8111-111111111111",
            "sourceId": "faq-delivery-window",
            "sourceType": "faq",
            "sourceVersion": 3,
            "publicationState": "PUBLISHED",
            "tombstone": False,
            "occurredTime": "2026-07-21T12:34:56Z",
            "content": {"question": "When?", "answer": "Tomorrow."},
        },
        separators=(",", ":"),
    ).encode()


@dataclass
class Projection:
    outcome: ProjectionOutcome | Exception
    observed: FaqKnowledgeEvent | None = None

    def apply(self, event: FaqKnowledgeEvent) -> ProjectionOutcome:
        self.observed = event
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome


def test_create_worker_preserves_explicit_deterministic_settings() -> None:
    settings = IndexerSettings(environment="test")

    worker = create_worker(settings)

    assert isinstance(worker, IndexerWorker)
    assert worker.settings is settings
    assert worker.settings.service_name == "knowledge-indexer"
    assert worker.settings.environment == "test"


def test_runtime_settings_are_bounded() -> None:
    valid = IndexerSettings(
        rocketmq_endpoints="broker:8081",
        rocketmq_topic="catalog",
        rocketmq_consumer_group="knowledge",
        elasticsearch_url="http://elasticsearch:9200",
        support_redis_url="redis://knowledge_indexer:secret@redis-support:6379/0",
    )

    valid.validate_runtime()
    with pytest.raises(ValueError):
        IndexerSettings(**{**valid.__dict__, "invisible_seconds": 9}).validate_runtime()
    with pytest.raises(ValueError):
        IndexerSettings(**{**valid.__dict__, "knowledge_alias": "other"}).validate_runtime()
    with pytest.raises(ValueError):
        IndexerSettings(**{**valid.__dict__, "support_redis_url": ""}).validate_runtime()


@pytest.mark.parametrize(
    ("outcome", "action", "code"),
    [
        (ProjectionOutcome.APPLIED, DeliveryAction.ACK, "applied"),
        (ProjectionOutcome.REPLAYED, DeliveryAction.ACK, "replayed"),
        (ProjectionOutcome.STALE, DeliveryAction.ACK, "stale"),
        (KnowledgeSyncConflict("conflict"), DeliveryAction.ACK, "conflict"),
        (KnowledgeSyncError("unavailable"), DeliveryAction.RETRY, "unavailable"),
    ],
)
def test_worker_acknowledgement_is_determined_by_projection_outcome(
    outcome: ProjectionOutcome | Exception, action: DeliveryAction, code: str
) -> None:
    projection = Projection(outcome)
    worker = IndexerWorker(IndexerSettings(), projection)

    result = worker.handle(valid_body())

    assert result.action is action
    assert result.code == code
    assert projection.observed is not None


@pytest.mark.parametrize(
    "body",
    [
        None,
        "text",
        b"not-json",
        b"{}",
        b"[" * 1000 + b"]" * 1000,
        valid_body().replace(b'"sourceVersion":3', b'"sourceVersion":' + b"1" * 5000),
        valid_body().replace(b'"Tomorrow."', b'"\\ud800"'),
    ],
)
def test_permanent_malformed_payload_is_acknowledged(body: object) -> None:
    projection = Projection(ProjectionOutcome.APPLIED)

    result = IndexerWorker(IndexerSettings(), projection).handle(body)

    assert result.action is DeliveryAction.ACK
    assert result.code.startswith("invalid_")
    assert projection.observed is None


class Properties(Mapping[str, str]):
    def __getitem__(self, key: str) -> str:
        if key != RESERVED_SANDBOX_PROPERTY:
            raise KeyError(key)
        return "synthetic"

    def __iter__(self):  # type: ignore[no-untyped-def]
        yield RESERVED_SANDBOX_PROPERTY

    def __len__(self) -> int:
        return 1


def test_reserved_evaluation_context_is_rejected_before_projection() -> None:
    projection = Projection(ProjectionOutcome.APPLIED)

    result = IndexerWorker(IndexerSettings(), projection).handle(valid_body(), Properties())

    assert result == result.__class__(DeliveryAction.ACK, "reserved_evaluation_context")
    assert projection.observed is None


def test_missing_projection_does_not_ack_a_valid_event() -> None:
    result = create_worker().handle(valid_body())

    assert result.action is DeliveryAction.RETRY
    assert result.code == "projection_not_configured"


@dataclass
class ElasticsearchProjection:
    outcome: ProjectionOutcome
    index_version: str = "knowledge_docs_v1"
    observed: FaqKnowledgeEvent | None = None

    def apply_with_index(self, event: FaqKnowledgeEvent) -> tuple[ProjectionOutcome, str]:
        self.observed = event
        return self.outcome, self.index_version


@dataclass
class CacheProjection:
    outcome: ProjectionOutcome | Exception
    prepare_error: Exception | None = None
    prepared: FaqKnowledgeEvent | None = None
    finalized: tuple[FaqKnowledgeEvent, str] | None = None
    aborted: FaqKnowledgeEvent | None = None

    def prepare(self, event: FaqKnowledgeEvent) -> ProjectionOutcome | None:
        self.prepared = event
        if self.prepare_error is not None:
            raise self.prepare_error
        return None

    def finalize(self, event: FaqKnowledgeEvent, index_version: str) -> ProjectionOutcome:
        self.finalized = (event, index_version)
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome

    def abort(self, event: FaqKnowledgeEvent) -> None:
        self.aborted = event


def test_stale_elasticsearch_delivery_is_fenced_before_es_and_not_finalized() -> None:
    cache = CacheProjection(ProjectionOutcome.APPLIED)
    projection = VersionedKnowledgeProjection(
        ElasticsearchProjection(ProjectionOutcome.STALE),  # type: ignore[arg-type]
        cache,
    )

    assert projection.apply(FaqKnowledgeEvent.from_bytes(valid_body())) is ProjectionOutcome.STALE
    assert cache.prepared is not None
    assert cache.finalized is None
    assert cache.aborted is not None


def test_elasticsearch_replay_repairs_cache_before_acknowledgement() -> None:
    cache = CacheProjection(ProjectionOutcome.APPLIED)
    projection = VersionedKnowledgeProjection(
        ElasticsearchProjection(ProjectionOutcome.REPLAYED),  # type: ignore[arg-type]
        cache,
    )

    assert (
        projection.apply(FaqKnowledgeEvent.from_bytes(valid_body())) is ProjectionOutcome.REPLAYED
    )
    assert cache.prepared is not None
    assert cache.finalized is not None and cache.finalized[1] == "knowledge_docs_v1"


def test_cache_unavailability_after_elasticsearch_apply_remains_retryable() -> None:
    cache = CacheProjection(KnowledgeSyncError("support_cache_unavailable"))
    projection = VersionedKnowledgeProjection(
        ElasticsearchProjection(ProjectionOutcome.APPLIED),  # type: ignore[arg-type]
        cache,
    )

    with pytest.raises(KnowledgeSyncError, match="support_cache_unavailable"):
        projection.apply(FaqKnowledgeEvent.from_bytes(valid_body()))


def test_cache_prepare_unavailability_prevents_elasticsearch_mutation() -> None:
    elasticsearch = ElasticsearchProjection(ProjectionOutcome.APPLIED)
    cache = CacheProjection(
        ProjectionOutcome.APPLIED,
        prepare_error=KnowledgeSyncError("support_cache_unavailable"),
    )
    projection = VersionedKnowledgeProjection(
        elasticsearch,  # type: ignore[arg-type]
        cache,
    )

    with pytest.raises(KnowledgeSyncError, match="support_cache_unavailable"):
        projection.apply(FaqKnowledgeEvent.from_bytes(valid_body()))
    assert elasticsearch.observed is None
    assert cache.finalized is None
