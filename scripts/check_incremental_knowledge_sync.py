"""Real Broker/Elasticsearch convergence evidence for CB-111."""

from __future__ import annotations

import argparse
import hashlib
import json
import time
import unicodedata
from copy import deepcopy
from dataclasses import dataclass
from typing import Any, cast
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from citybuddy_indexer import (
    DeliveryAction,
    ElasticsearchKnowledgeProjection,
    FaqKnowledgeEvent,
    IndexerSettings,
    IndexerWorker,
    KnowledgeSyncError,
    ProjectionOutcome,
    RedisFaqCacheProjection,
    RocketMqKnowledgeConsumer,
    create_worker,
)
from citybuddy_indexer.incremental import EVENT_TAG, RESERVED_SANDBOX_PROPERTY
from citybuddy_indexer.worker import VersionedKnowledgeProjection
from redis import Redis
from rocketmq.v5.client import ClientConfiguration, Credentials  # type: ignore[import-untyped]
from rocketmq.v5.model import Message  # type: ignore[import-untyped]
from rocketmq.v5.producer import Producer  # type: ignore[import-untyped]

FAQ_CACHE_PREFIX = "cb:faq:v1:"
FAQ_CACHE_SCHEMA = "cb112-v1"


def request(
    base_url: str,
    method: str,
    path: str,
    payload: dict[str, object] | None = None,
    expected: tuple[int, ...] = (200, 201),
) -> dict[str, Any]:
    data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
    http_request = Request(
        f"{base_url.rstrip('/')}{path}",
        data=data,
        headers={"Content-Type": "application/json"},
        method=method,
    )
    try:
        with urlopen(http_request, timeout=10) as response:  # noqa: S310
            status = response.status
            body = response.read()
    except HTTPError as error:
        status = error.code
        body = error.read()
    if status not in expected:
        raise AssertionError(f"Elasticsearch {method} {path} returned {status}")
    if not body:
        return {}
    decoded = json.loads(body)
    if not isinstance(decoded, dict):
        raise AssertionError("Elasticsearch response was not an object")
    return cast(dict[str, Any], decoded)


def event(version: int, *, tombstone: bool = False, answer: str | None = None) -> dict[str, object]:
    return {
        "eventId": str(uuid4()),
        "sourceId": "faq-cb111-delivery",
        "sourceType": "faq",
        "sourceVersion": version,
        "publicationState": "PUBLISHED",
        "tombstone": tombstone,
        "occurredTime": f"2026-07-21T12:00:{version:02d}Z",
        "content": {
            "question": "When will my order arrive?",
            "answer": answer or f"Public delivery guidance version {version}.",
        },
    }


def encode(payload: object) -> bytes:
    return json.dumps(payload, separators=(",", ":")).encode()


def send(
    producer: Any,
    topic: str,
    body: bytes,
    *,
    event_id: str,
    properties: dict[str, str] | None = None,
) -> None:
    message = Message()
    message.topic = topic
    message.tag = EVENT_TAG
    message.keys = event_id
    message.body = body
    for key, value in (properties or {}).items():
        message.add_property(key, value)
    producer.send(message)


def settings(
    args: argparse.Namespace,
    elasticsearch_url: str,
    support_redis_url: str | None = None,
) -> IndexerSettings:
    return IndexerSettings(
        environment="integration",
        rocketmq_endpoints=args.endpoints,
        rocketmq_topic=args.topic,
        rocketmq_consumer_group=args.group,
        elasticsearch_url=elasticsearch_url,
        support_redis_url=support_redis_url or args.support_redis_url,
        invisible_seconds=10,
    )


def consume_expected(
    consumer: RocketMqKnowledgeConsumer, action: DeliveryAction, *codes: str
) -> tuple[object, object]:
    message = consumer.receive_one(20)
    if message is None:
        raise AssertionError("Expected Broker delivery was absent")
    result = consumer.process(message)
    if result.action is not action or result.code not in codes:
        raise AssertionError(f"Unexpected delivery result: {result}")
    if action is DeliveryAction.ACK:
        consumer.ack(message)
    return message, result


def document(es_url: str, index: str) -> dict[str, Any]:
    response = request(es_url, "GET", f"/{index}/_doc/faq-cb111-delivery%3Aanswer")
    source = response.get("_source")
    if not isinstance(source, dict):
        raise AssertionError("Projection document has no source")
    return cast(dict[str, Any], source)


def query_hash(value: str) -> str:
    normalized = " ".join(unicodedata.normalize("NFKC", value).casefold().split())
    return hashlib.sha256(normalized.encode()).hexdigest()


def seed_mapping(client: Redis, query: str, source_id: str, source_version: int) -> None:
    digest = query_hash(query)
    key = f"{FAQ_CACHE_PREFIX}query:{digest}"
    client.hset(
        key,
        mapping={
            "schema": FAQ_CACHE_SCHEMA,
            "query_hash": digest,
            "source_id": source_id,
            "source_version": str(source_version),
        },
    )
    client.pexpire(key, 300_000)


def mapping_is_current(client: Redis, query: str, expected_version: int) -> bool:
    digest = query_hash(query)
    mapping = cast(dict[str, str], client.hgetall(f"{FAQ_CACHE_PREFIX}query:{digest}"))
    source_id = mapping.get("source_id")
    source_version = mapping.get("source_version")
    if (
        set(mapping) != {"schema", "query_hash", "source_id", "source_version"}
        or mapping.get("schema") != FAQ_CACHE_SCHEMA
        or mapping.get("query_hash") != digest
        or not source_id
        or source_version != str(expected_version)
    ):
        return False
    state = cast(dict[str, str], client.hgetall(f"{FAQ_CACHE_PREFIX}state:{source_id}"))
    answer = cast(
        dict[str, str],
        client.hgetall(f"{FAQ_CACHE_PREFIX}answer:{source_id}:{source_version}"),
    )
    return (
        not client.exists(f"{FAQ_CACHE_PREFIX}pending:{source_id}")
        and state.get("source_version") == source_version
        and state.get("tombstone") == "0"
        and state.get("ready") == "1"
        and answer.get("source_version") == source_version
        and answer.get("commitment") == state.get("commitment")
        and answer.get("cache_commitment") == state.get("cache_commitment")
    )


@dataclass(frozen=True)
class FinalizeUnavailableCache:
    delegate: RedisFaqCacheProjection

    def prepare(self, event: FaqKnowledgeEvent) -> ProjectionOutcome | None:
        return self.delegate.prepare(event)

    def finalize(self, event: FaqKnowledgeEvent, index_version: str) -> ProjectionOutcome:
        del event, index_version
        raise KnowledgeSyncError("support_cache_unavailable")

    def abort(self, event: FaqKnowledgeEvent) -> None:
        self.delegate.abort(event)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoints", required=True)
    parser.add_argument("--topic", required=True)
    parser.add_argument("--group", required=True)
    parser.add_argument("--elasticsearch-url", required=True)
    parser.add_argument("--drop-proxy-url", required=True)
    parser.add_argument("--index", required=True)
    parser.add_argument("--support-redis-url", required=True)
    parser.add_argument("--agent-cache-url", required=True)
    args = parser.parse_args()

    configuration = ClientConfiguration(args.endpoints, Credentials(), request_timeout=5)
    producer: Any = Producer(configuration, [args.topic])
    producer.startup()
    baseline = request(
        args.elasticsearch_url, "GET", f"/{args.index}/_doc/faq-store-hours%3Ageneral"
    )
    alias_before = request(args.elasticsearch_url, "GET", "/_alias/knowledge_docs_read")
    cache = Redis.from_url(args.agent_cache_url, decode_responses=True)
    cache_query = "When will my order arrive?"
    consumer = RocketMqKnowledgeConsumer(create_worker(settings(args, args.elasticsearch_url)))
    consumer.startup()
    try:
        first = event(1)
        send(producer, args.topic, encode(first), event_id=cast(str, first["eventId"]))
        consume_expected(consumer, DeliveryAction.ACK, "applied")
        assert document(args.elasticsearch_url, args.index)["source_version"] == 1
        seed_mapping(cache, cache_query, "faq-cb111-delivery", 1)
        assert mapping_is_current(cache, cache_query, 1)

        send(producer, args.topic, encode(first), event_id=cast(str, first["eventId"]))
        consume_expected(consumer, DeliveryAction.ACK, "replayed")

        second = event(2)
        send(producer, args.topic, encode(second), event_id=cast(str, second["eventId"]))
        consume_expected(consumer, DeliveryAction.ACK, "applied")
        stale = deepcopy(first)
        stale["eventId"] = str(uuid4())
        send(producer, args.topic, encode(stale), event_id=cast(str, stale["eventId"]))
        consume_expected(consumer, DeliveryAction.ACK, "stale")
        assert document(args.elasticsearch_url, args.index)["source_version"] == 2
        assert not mapping_is_current(cache, cache_query, 1)
        seed_mapping(cache, cache_query, "faq-cb111-delivery", 2)
        assert mapping_is_current(cache, cache_query, 2)

        conflict = event(2, answer="Conflicting content at the same version.")
        send(producer, args.topic, encode(conflict), event_id=cast(str, conflict["eventId"]))
        consume_expected(consumer, DeliveryAction.ACK, "conflicting_source_version")
        second_content = second["content"]
        assert isinstance(second_content, dict)
        assert document(args.elasticsearch_url, args.index)["content"] == second_content["answer"]
        assert mapping_is_current(cache, cache_query, 2)

        equal_other_event = deepcopy(second)
        equal_other_event["eventId"] = str(uuid4())
        send(
            producer,
            args.topic,
            encode(equal_other_event),
            event_id=cast(str, equal_other_event["eventId"]),
        )
        consume_expected(consumer, DeliveryAction.ACK, "conflicting_source_version")

        rebound_variants = []
        for field, replacement in (
            ("sourceId", "faq-cb111-other"),
            ("sourceVersion", 9),
            ("tombstone", True),
            ("occurredTime", "2026-07-21T12:00:09Z"),
        ):
            rebound = deepcopy(first)
            rebound[field] = replacement
            rebound_variants.append(rebound)
        for content_field, replacement in (
            ("question", "A rebound question?"),
            ("answer", "A rebound answer."),
        ):
            rebound = deepcopy(first)
            rebound_content = rebound["content"]
            assert isinstance(rebound_content, dict)
            rebound_content[content_field] = replacement
            rebound_variants.append(rebound)
        for rebound in rebound_variants:
            send(producer, args.topic, encode(rebound), event_id=cast(str, rebound["eventId"]))
            consume_expected(consumer, DeliveryAction.ACK, "conflicting_event_identity")

        deleted = event(3, tombstone=True)
        send(producer, args.topic, encode(deleted), event_id=cast(str, deleted["eventId"]))
        consume_expected(consumer, DeliveryAction.ACK, "applied")
        deleted_doc = document(args.elasticsearch_url, args.index)
        assert deleted_doc["deleted"] is True and deleted_doc["published"] is False
        assert not mapping_is_current(cache, cache_query, 2)
        send(producer, args.topic, encode(deleted), event_id=cast(str, deleted["eventId"]))
        consume_expected(consumer, DeliveryAction.ACK, "replayed")
        older_tombstone = event(2, tombstone=True)
        send(
            producer,
            args.topic,
            encode(older_tombstone),
            event_id=cast(str, older_tombstone["eventId"]),
        )
        consume_expected(consumer, DeliveryAction.ACK, "stale")
        stale_after_delete = event(2)
        send(
            producer,
            args.topic,
            encode(stale_after_delete),
            event_id=cast(str, stale_after_delete["eventId"]),
        )
        consume_expected(consumer, DeliveryAction.ACK, "stale")
        assert document(args.elasticsearch_url, args.index)["deleted"] is True
        assert not mapping_is_current(cache, cache_query, 2)

        restored = event(4)
        send(producer, args.topic, encode(restored), event_id=cast(str, restored["eventId"]))
        consume_expected(consumer, DeliveryAction.ACK, "applied")
        seed_mapping(cache, cache_query, "faq-cb111-delivery", 4)
        assert mapping_is_current(cache, cache_query, 4), {
            "mapping": cache.hgetall(f"{FAQ_CACHE_PREFIX}query:{query_hash(cache_query)}"),
            "state": cache.hgetall(f"{FAQ_CACHE_PREFIX}state:faq-cb111-delivery"),
            "answer": cache.hgetall(f"{FAQ_CACHE_PREFIX}answer:faq-cb111-delivery:4"),
        }

        interrupted = event(5)
        send(
            producer,
            args.topic,
            encode(interrupted),
            event_id=cast(str, interrupted["eventId"]),
        )
        pending_message, pending_result = consume_expected(consumer, DeliveryAction.ACK, "applied")
        del pending_message, pending_result
        # The helper acknowledged above; use the next event to prove mutation-before-ack replay.
        redelivered = event(6)
        send(
            producer,
            args.topic,
            encode(redelivered),
            event_id=cast(str, redelivered["eventId"]),
        )
        unacked = consumer.receive_one(20)
        assert unacked is not None
        result = consumer.process(unacked)
        assert result.action is DeliveryAction.ACK and result.code == "applied"
    finally:
        consumer.shutdown()

    time.sleep(11)
    restarted = RocketMqKnowledgeConsumer(create_worker(settings(args, args.elasticsearch_url)))
    restarted.startup()
    try:
        consume_expected(restarted, DeliveryAction.ACK, "replayed")

        unavailable = event(7)
        send(producer, args.topic, encode(unavailable), event_id=cast(str, unavailable["eventId"]))
    finally:
        restarted.shutdown()

    failing = RocketMqKnowledgeConsumer(create_worker(settings(args, "http://127.0.0.1:9")))
    failing.startup()
    try:
        consume_expected(failing, DeliveryAction.RETRY, "elasticsearch_unavailable")
    finally:
        failing.shutdown()
    time.sleep(11)
    recovered = RocketMqKnowledgeConsumer(create_worker(settings(args, args.elasticsearch_url)))
    recovered.startup()
    try:
        consume_expected(recovered, DeliveryAction.ACK, "applied")
    finally:
        recovered.shutdown()

    seed_mapping(cache, cache_query, "faq-cb111-delivery", 7)
    assert mapping_is_current(cache, cache_query, 7)

    cache_unavailable = event(8)
    send(
        producer,
        args.topic,
        encode(cache_unavailable),
        event_id=cast(str, cache_unavailable["eventId"]),
    )
    cache_settings = settings(args, args.elasticsearch_url)
    cache_failing = RocketMqKnowledgeConsumer(
        IndexerWorker(
            cache_settings,
            VersionedKnowledgeProjection(
                ElasticsearchKnowledgeProjection(args.elasticsearch_url),
                FinalizeUnavailableCache(RedisFaqCacheProjection.from_url(args.support_redis_url)),
            ),
        )
    )
    cache_failing.startup()
    try:
        consume_expected(cache_failing, DeliveryAction.RETRY, "support_cache_unavailable")
    finally:
        cache_failing.shutdown()
    assert not mapping_is_current(cache, cache_query, 7)
    pending_state = cast(
        dict[str, str], cache.hgetall(f"{FAQ_CACHE_PREFIX}pending:faq-cb111-delivery")
    )
    assert pending_state.get("source_version") == "8"
    current_state = cast(
        dict[str, str], cache.hgetall(f"{FAQ_CACHE_PREFIX}state:faq-cb111-delivery")
    )
    assert current_state.get("source_version") == "7"
    assert current_state.get("ready") == "1"
    time.sleep(11)
    cache_recovered = RocketMqKnowledgeConsumer(
        create_worker(settings(args, args.elasticsearch_url))
    )
    cache_recovered.startup()
    try:
        consume_expected(cache_recovered, DeliveryAction.ACK, "replayed")
    finally:
        cache_recovered.shutdown()

    indeterminate = event(9)
    send(
        producer,
        args.topic,
        encode(indeterminate),
        event_id=cast(str, indeterminate["eventId"]),
    )
    dropped = RocketMqKnowledgeConsumer(create_worker(settings(args, args.drop_proxy_url)))
    dropped.startup()
    try:
        consume_expected(dropped, DeliveryAction.RETRY, "elasticsearch_unavailable")
    finally:
        dropped.shutdown()
    time.sleep(11)
    final_consumer = RocketMqKnowledgeConsumer(
        create_worker(settings(args, args.elasticsearch_url))
    )
    final_consumer.startup()
    try:
        consume_expected(final_consumer, DeliveryAction.ACK, "replayed")

        malformed_cases: list[tuple[bytes, dict[str, str], str]] = [
            (b"not-json", {}, "invalid_payload"),
            (b"{" + b" " * 8192 + b"}", {}, "invalid_payload"),
            (b"[" * 1000 + b"]" * 1000, {}, "invalid_payload"),
            (
                encode(event(10)).replace(b'"sourceVersion":10', b'"sourceVersion":' + b"1" * 5000),
                {},
                "invalid_payload",
            ),
            (
                encode(
                    {
                        **event(10),
                        "content": {"question": "public", "answer": "\ud800"},
                    }
                ),
                {},
                "invalid_values",
            ),
            (
                encode({**event(10), "privateCustomer": "must-not-pass"}),
                {},
                "invalid_envelope",
            ),
            (
                encode(event(10)),
                {RESERVED_SANDBOX_PROPERTY: "synthetic"},
                "reserved_evaluation_context",
            ),
        ]
        for body, properties, code in malformed_cases:
            send(producer, args.topic, body, event_id=str(uuid4()), properties=properties)
            consume_expected(final_consumer, DeliveryAction.ACK, code)
        time.sleep(11)
        assert final_consumer.receive_one(2) is None
    finally:
        final_consumer.shutdown()
        producer.shutdown()

    assert request(args.elasticsearch_url, "GET", "/_alias/knowledge_docs_read") == alias_before
    assert (
        request(args.elasticsearch_url, "GET", f"/{args.index}/_doc/faq-store-hours%3Ageneral")
        == baseline
    )
    final_document = document(args.elasticsearch_url, args.index)
    assert final_document["source_version"] == 9
    assert final_document["published"] is True and final_document["deleted"] is False
    assert not mapping_is_current(cache, cache_query, 4)
    seed_mapping(cache, cache_query, "faq-cb111-delivery", 9)
    assert mapping_is_current(cache, cache_query, 9)
    search = request(
        args.elasticsearch_url,
        "POST",
        "/knowledge_docs_read/_search",
        {
            "query": {
                "bool": {
                    "filter": [
                        {"term": {"published": True}},
                        {"term": {"deleted": False}},
                        {"term": {"source_id": "faq-cb111-delivery"}},
                    ]
                }
            }
        },
    )
    assert search["hits"]["total"]["value"] == 1
    print(
        json.dumps(
            {
                "aliasUnchanged": True,
                "boundedPermanentRejections": len(malformed_cases),
                "event": "cb111-incremental-knowledge-sync-evidence",
                "finalSourceVersion": 9,
                "restartRedelivery": True,
                "supportCacheRecovery": True,
                "tombstoneFence": True,
                "transportIndeterminateRecovery": True,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
