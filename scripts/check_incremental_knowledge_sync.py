"""Real Broker/Elasticsearch convergence evidence for CB-111."""

from __future__ import annotations

import argparse
import json
import time
from copy import deepcopy
from typing import Any, cast
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from citybuddy_indexer import (
    DeliveryAction,
    IndexerSettings,
    RocketMqKnowledgeConsumer,
    create_worker,
)
from citybuddy_indexer.incremental import EVENT_TAG, RESERVED_SANDBOX_PROPERTY
from rocketmq.v5.client import ClientConfiguration, Credentials  # type: ignore[import-untyped]
from rocketmq.v5.model import Message  # type: ignore[import-untyped]
from rocketmq.v5.producer import Producer  # type: ignore[import-untyped]


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


def settings(args: argparse.Namespace, elasticsearch_url: str) -> IndexerSettings:
    return IndexerSettings(
        environment="integration",
        rocketmq_endpoints=args.endpoints,
        rocketmq_topic=args.topic,
        rocketmq_consumer_group=args.group,
        elasticsearch_url=elasticsearch_url,
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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoints", required=True)
    parser.add_argument("--topic", required=True)
    parser.add_argument("--group", required=True)
    parser.add_argument("--elasticsearch-url", required=True)
    parser.add_argument("--drop-proxy-url", required=True)
    parser.add_argument("--index", required=True)
    args = parser.parse_args()

    configuration = ClientConfiguration(args.endpoints, Credentials(), request_timeout=5)
    producer: Any = Producer(configuration, [args.topic])
    producer.startup()
    baseline = request(
        args.elasticsearch_url, "GET", f"/{args.index}/_doc/faq-store-hours%3Ageneral"
    )
    alias_before = request(args.elasticsearch_url, "GET", "/_alias/knowledge_docs_read")
    consumer = RocketMqKnowledgeConsumer(create_worker(settings(args, args.elasticsearch_url)))
    consumer.startup()
    try:
        first = event(1)
        send(producer, args.topic, encode(first), event_id=cast(str, first["eventId"]))
        consume_expected(consumer, DeliveryAction.ACK, "applied")
        assert document(args.elasticsearch_url, args.index)["source_version"] == 1

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

        conflict = event(2, answer="Conflicting content at the same version.")
        send(producer, args.topic, encode(conflict), event_id=cast(str, conflict["eventId"]))
        consume_expected(consumer, DeliveryAction.ACK, "conflicting_source_version")
        second_content = second["content"]
        assert isinstance(second_content, dict)
        assert document(args.elasticsearch_url, args.index)["content"] == second_content["answer"]

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

        restored = event(4)
        send(producer, args.topic, encode(restored), event_id=cast(str, restored["eventId"]))
        consume_expected(consumer, DeliveryAction.ACK, "applied")

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

    indeterminate = event(8)
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
                encode(event(9)).replace(b'"sourceVersion":9', b'"sourceVersion":' + b"1" * 5000),
                {},
                "invalid_payload",
            ),
            (
                encode(
                    {
                        **event(9),
                        "content": {"question": "public", "answer": "\ud800"},
                    }
                ),
                {},
                "invalid_values",
            ),
            (
                encode({**event(9), "privateCustomer": "must-not-pass"}),
                {},
                "invalid_envelope",
            ),
            (
                encode(event(9)),
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
    assert final_document["source_version"] == 8
    assert final_document["published"] is True and final_document["deleted"] is False
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
                "finalSourceVersion": 8,
                "restartRedelivery": True,
                "tombstoneFence": True,
                "transportIndeterminateRecovery": True,
            },
            separators=(",", ":"),
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
