"""Production-only RocketMQ FAQ synchronization worker."""

from __future__ import annotations

import json
import time
from collections.abc import Mapping
from dataclasses import dataclass
from enum import Enum
from typing import Any, Protocol, cast

from .faq_cache import RedisFaqCacheProjection
from .incremental import (
    EVENT_TAG,
    RESERVED_SANDBOX_PROPERTY,
    ElasticsearchKnowledgeProjection,
    FaqKnowledgeEvent,
    KnowledgeEventError,
    KnowledgeSyncConflict,
    KnowledgeSyncError,
    ProjectionOutcome,
)
from .knowledge import KNOWLEDGE_ALIAS


@dataclass(frozen=True)
class IndexerSettings:
    service_name: str = "knowledge-indexer"
    environment: str = "development"
    rocketmq_endpoints: str = ""
    rocketmq_topic: str = ""
    rocketmq_consumer_group: str = ""
    elasticsearch_url: str = ""
    support_redis_url: str = ""
    knowledge_alias: str = KNOWLEDGE_ALIAS
    invisible_seconds: int = 30

    def validate_runtime(self) -> None:
        if (
            not self.rocketmq_endpoints.strip()
            or not self.rocketmq_topic.strip()
            or not self.rocketmq_consumer_group.strip()
            or not self.elasticsearch_url.startswith(("http://", "https://"))
            or not self.support_redis_url.startswith(("redis://", "rediss://"))
            or self.knowledge_alias != KNOWLEDGE_ALIAS
            or self.invisible_seconds < 10
            or self.invisible_seconds > 300
        ):
            raise ValueError("Knowledge indexer runtime configuration is incomplete")


class DeliveryAction(str, Enum):
    ACK = "ack"
    RETRY = "retry"


@dataclass(frozen=True)
class DeliveryResult:
    action: DeliveryAction
    code: str
    event_id: str | None = None


class KnowledgeProjection(Protocol):
    def apply(self, event: FaqKnowledgeEvent) -> ProjectionOutcome: ...


@dataclass(frozen=True)
class VersionedKnowledgeProjection:
    elasticsearch: ElasticsearchKnowledgeProjection
    faq_cache: RedisFaqCacheProjection

    def apply(self, event: FaqKnowledgeEvent) -> ProjectionOutcome:
        outcome, index_version = self.elasticsearch.apply_with_index(event)
        if outcome is ProjectionOutcome.STALE:
            return outcome
        self.faq_cache.apply(event, index_version)
        return outcome


@dataclass(frozen=True)
class IndexerWorker:
    settings: IndexerSettings
    projection: KnowledgeProjection | None = None

    def handle(self, body: object, properties: object = None) -> DeliveryResult:
        if _has_reserved_sandbox(properties):
            return DeliveryResult(DeliveryAction.ACK, "reserved_evaluation_context")
        if not isinstance(body, bytes):
            return DeliveryResult(DeliveryAction.ACK, "invalid_payload")
        try:
            event = FaqKnowledgeEvent.from_bytes(body)
        except KnowledgeEventError as error:
            return DeliveryResult(DeliveryAction.ACK, error.code)
        if self.projection is None:
            return DeliveryResult(DeliveryAction.RETRY, "projection_not_configured", event.event_id)
        try:
            outcome = self.projection.apply(event)
        except KnowledgeSyncConflict as error:
            return DeliveryResult(DeliveryAction.ACK, error.code, event.event_id)
        except KnowledgeSyncError as error:
            return DeliveryResult(DeliveryAction.RETRY, error.code, event.event_id)
        return DeliveryResult(DeliveryAction.ACK, outcome.value, event.event_id)


def _has_reserved_sandbox(properties: object) -> bool:
    return isinstance(properties, Mapping) and RESERVED_SANDBOX_PROPERTY in properties


def create_worker(settings: IndexerSettings | None = None) -> IndexerWorker:
    """Construct a deterministic worker; runtime projection requires explicit settings."""
    configured = settings or IndexerSettings()
    projection: KnowledgeProjection | None = None
    if configured.elasticsearch_url:
        configured.validate_runtime()
        projection = VersionedKnowledgeProjection(
            ElasticsearchKnowledgeProjection(
                configured.elasticsearch_url, alias=configured.knowledge_alias
            ),
            RedisFaqCacheProjection.from_url(configured.support_redis_url),
        )
    return IndexerWorker(settings=configured, projection=projection)


class RocketMqKnowledgeConsumer:
    def __init__(self, worker: IndexerWorker) -> None:
        # The SDK configures a rotating file logger at import time. Keep that side effect at
        # the actual runtime boundary so validation, bootstrap, and unit tests remain pure.
        from rocketmq.v5.client import (  # type: ignore[import-untyped]
            ClientConfiguration,
            Credentials,
        )
        from rocketmq.v5.consumer.simple import (  # type: ignore[import-untyped]
            SimpleConsumer,
        )
        from rocketmq.v5.model import FilterExpression  # type: ignore[import-untyped]

        worker.settings.validate_runtime()
        self._worker = worker
        configuration = ClientConfiguration(
            worker.settings.rocketmq_endpoints,
            Credentials(),
            request_timeout=5,
        )
        self._consumer: Any = SimpleConsumer(
            configuration,
            worker.settings.rocketmq_consumer_group,
            {worker.settings.rocketmq_topic: FilterExpression(EVENT_TAG)},
            await_duration=1,
        )
        self._started = False

    def startup(self) -> None:
        self._consumer.startup()
        self._started = True

    def shutdown(self) -> None:
        if self._started:
            self._consumer.shutdown()
            self._started = False

    def poll_once(self, timeout_seconds: float = 5.0) -> DeliveryResult | None:
        message = self.receive_one(timeout_seconds)
        if message is None:
            return None
        result = self.process(message)
        if result.action is DeliveryAction.ACK:
            self.ack(message)
        return result

    def receive_one(self, timeout_seconds: float = 5.0) -> object | None:
        if not self._started or timeout_seconds <= 0:
            raise ValueError("Knowledge consumer is not ready")
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            messages = self._consumer.receive(1, self._worker.settings.invisible_seconds)
            if not messages:
                continue
            return cast(object, messages[0])
        return None

    def process(self, message: object) -> DeliveryResult:
        if not self._started:
            raise ValueError("Knowledge consumer is not ready")
        result = self._worker.handle(
            getattr(message, "body", None), getattr(message, "properties", None)
        )
        _log_delivery(message, result)
        return result

    def ack(self, message: object) -> None:
        if not self._started:
            raise ValueError("Knowledge consumer is not ready")
        self._consumer.ack(message)

    def run_forever(self) -> None:
        self.startup()
        try:
            while True:
                self.poll_once()
        finally:
            self.shutdown()


def _log_delivery(message: object, result: DeliveryResult) -> None:
    print(
        json.dumps(
            {
                "action": result.action.value,
                "code": result.code,
                "deliveryAttempt": getattr(message, "delivery_attempt", None),
                "event": "knowledge-sync-delivery",
                "eventId": result.event_id,
                "messageId": str(getattr(message, "message_id", "")),
            },
            separators=(",", ":"),
            sort_keys=True,
        ),
        flush=True,
    )
