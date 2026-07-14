"""Disposable CB-085 RocketMQ and Elasticsearch viability experiment."""

from __future__ import annotations

import argparse
import inspect
import json
import os
import platform
import threading
import time
from dataclasses import dataclass
from enum import Enum
from importlib.metadata import version
from typing import Any, cast
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

from rocketmq.v5.client import ClientConfiguration, Credentials  # type: ignore[import-untyped]
from rocketmq.v5.consumer.push import (  # type: ignore[import-untyped]
    ConsumeResult,
    MessageListener,
    PushConsumer,
)
from rocketmq.v5.consumer.simple import SimpleConsumer  # type: ignore[import-untyped]
from rocketmq.v5.model import FilterExpression, Message  # type: ignore[import-untyped]
from rocketmq.v5.producer import Producer  # type: ignore[import-untyped]

from .spike_event import SpikeEvent

SIMPLE_TAG = "cb085-simple"
PUSH_TAG = "cb085-push"
EXCLUDED_TAG = "cb085-excluded"
INDEX_MAPPING: dict[str, Any] = {
    "mappings": {
        "properties": {
            "source_id": {"type": "keyword"},
            "source_version": {"type": "long"},
            "document_type": {"type": "keyword"},
            "deleted": {"type": "boolean"},
            "content": {"type": "text", "analyzer": "ik_max_word"},
        }
    }
}


class ProjectionOutcome(str, Enum):
    APPLIED = "applied"
    OLDER_REJECTED = "older-rejected"
    DUPLICATE = "duplicate"


class ElasticsearchSpikeClient:
    def __init__(self, base_url: str) -> None:
        self._base_url = base_url.rstrip("/")

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None = None,
        expected: tuple[int, ...] = (200, 201),
    ) -> tuple[int, dict[str, Any]]:
        data = None if payload is None else json.dumps(payload).encode()
        request = Request(
            f"{self._base_url}{path}",
            data=data,
            headers={"Content-Type": "application/json"},
            method=method,
        )
        try:
            response = urlopen(request, timeout=10)  # noqa: S310
            status = response.status
            body = response.read()
        except HTTPError as error:
            status = error.code
            body = error.read()
        if status not in expected:
            raise RuntimeError(f"Elasticsearch {method} {path} returned {status}: {body.decode()}")
        if not body:
            return status, {}
        decoded = json.loads(body)
        if not isinstance(decoded, dict):
            raise RuntimeError(f"Elasticsearch {method} {path} returned a non-object response")
        return status, cast(dict[str, Any], decoded)

    def create_index(self, index: str) -> None:
        self._request("PUT", f"/{quote(index)}", INDEX_MAPPING)

    def delete_indexes(self, *indexes: str) -> None:
        joined = ",".join(quote(index) for index in indexes)
        self._request("DELETE", f"/{joined}", expected=(200, 404))

    def add_alias(self, index: str, alias: str) -> None:
        self._request(
            "POST",
            "/_aliases",
            {"actions": [{"add": {"index": index, "alias": alias}}]},
        )

    def switch_alias(self, old_index: str, new_index: str, alias: str) -> None:
        self._request(
            "POST",
            "/_aliases",
            {
                "actions": [
                    {"remove": {"index": old_index, "alias": alias}},
                    {"add": {"index": new_index, "alias": alias}},
                ]
            },
        )

    def alias_targets(self, alias: str) -> set[str]:
        _, response = self._request("GET", f"/_alias/{quote(alias)}")
        return set(response)

    def get_document(self, index: str, source_id: str) -> dict[str, Any] | None:
        status, response = self._request(
            "GET",
            f"/{quote(index)}/_doc/{quote(source_id, safe='')}",
            expected=(200, 404),
        )
        if status == 404:
            return None
        source = response.get("_source")
        if not isinstance(source, dict):
            raise RuntimeError("Elasticsearch document response omitted _source")
        return cast(dict[str, Any], source)

    def put_document(self, index: str, source_id: str, document: dict[str, Any]) -> None:
        self._request("PUT", f"/{quote(index)}/_doc/{quote(source_id, safe='')}", document)

    def refresh(self, index: str) -> None:
        self._request("POST", f"/{quote(index)}/_refresh")

    def all_documents(self, index: str) -> dict[str, dict[str, Any]]:
        _, response = self._request(
            "POST", f"/{quote(index)}/_search", {"query": {"match_all": {}}, "size": 100}
        )
        hits_block = response.get("hits")
        if not isinstance(hits_block, dict) or not isinstance(hits_block.get("hits"), list):
            raise RuntimeError("Elasticsearch search response omitted hits")
        documents: dict[str, dict[str, Any]] = {}
        for hit in hits_block["hits"]:
            if not isinstance(hit, dict) or not isinstance(hit.get("_id"), str):
                raise RuntimeError("Elasticsearch search hit omitted _id")
            source = hit.get("_source")
            if not isinstance(source, dict):
                raise RuntimeError("Elasticsearch search hit omitted _source")
            documents[hit["_id"]] = cast(dict[str, Any], source)
        return documents


def apply_event(
    elasticsearch: ElasticsearchSpikeClient, index: str, event: SpikeEvent
) -> ProjectionOutcome:
    current = elasticsearch.get_document(index, event.source_id)
    if current is not None:
        current_version = current.get("source_version")
        if not isinstance(current_version, int):
            raise RuntimeError("existing projection has no integer source_version")
        if current_version > event.source_version:
            return ProjectionOutcome.OLDER_REJECTED
        if current_version == event.source_version:
            return ProjectionOutcome.DUPLICATE
    elasticsearch.put_document(index, event.source_id, event.projection())
    return ProjectionOutcome.APPLIED


def rebuild_and_switch(
    elasticsearch: ElasticsearchSpikeClient, old_index: str, new_index: str, alias: str
) -> int:
    source_documents = elasticsearch.all_documents(old_index)
    elasticsearch.create_index(new_index)
    for source_id, document in source_documents.items():
        elasticsearch.put_document(new_index, source_id, document)
    elasticsearch.refresh(new_index)
    rebuilt_documents = elasticsearch.all_documents(new_index)
    if rebuilt_documents != source_documents:
        raise AssertionError("rebuilt index validation did not match the source index")
    elasticsearch.switch_alias(old_index, new_index, alias)
    if elasticsearch.alias_targets(alias) != {new_index}:
        raise AssertionError("atomic alias switch did not leave exactly the validated target")
    return len(rebuilt_documents)


def make_message(topic: str, tag: str, key: str, event: SpikeEvent) -> Any:
    message = Message()
    message.topic = topic
    message.tag = tag
    message.keys = key
    message.body = event.to_bytes()
    return message


def receive_one(consumer: Any, timeout_seconds: float, invisible_seconds: int) -> Any:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        messages = consumer.receive(1, invisible_seconds)
        if messages:
            return messages[0]
    raise TimeoutError(f"no matching RocketMQ message received within {timeout_seconds} seconds")


def event_from_message(message: Any) -> SpikeEvent:
    body = message.body
    if not isinstance(body, bytes):
        raise ValueError("RocketMQ message body was not bytes")
    return SpikeEvent.from_bytes(body)


def log_event(name: str, **details: object) -> None:
    print(json.dumps({"event": name, **details}, sort_keys=True), flush=True)


class PushEvidenceListener(MessageListener):  # type: ignore[misc]
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self.retry_attempts: list[tuple[str, int, float]] = []
        self.retry_completed = threading.Event()
        self.long_attempts: list[tuple[str, int, float]] = []
        self.long_started = threading.Event()
        self.long_completed = threading.Event()

    def consume(self, message: Any) -> Any:
        event = event_from_message(message)
        attempt = int(message.delivery_attempt or 0)
        evidence = (str(message.message_id), attempt, time.monotonic())
        if event.source_id == "push-retry":
            with self._lock:
                self.retry_attempts.append(evidence)
                attempt_count = len(self.retry_attempts)
            log_event("push-delivery", message_id=message.message_id, delivery_attempt=attempt)
            if attempt_count == 1:
                return ConsumeResult.FAILURE
            self.retry_completed.set()
            return ConsumeResult.SUCCESS
        if event.source_id == "push-long-processing":
            with self._lock:
                self.long_attempts.append(evidence)
                attempt_count = len(self.long_attempts)
            log_event(
                "push-long-delivery",
                message_id=message.message_id,
                delivery_attempt=attempt,
                concurrent_attempt=attempt_count,
            )
            if attempt_count == 1:
                self.long_started.set()
                time.sleep(35)
                self.long_completed.set()
            return ConsumeResult.SUCCESS
        raise ValueError(f"unexpected PushConsumer spike event: {event.source_id}")


@dataclass(frozen=True)
class SpikeArguments:
    endpoints: str
    topic: str
    simple_group: str
    push_group: str
    elasticsearch_url: str
    old_index: str
    new_index: str
    alias: str
    broker_proxy_image: str
    elasticsearch_version: str


def send_event(producer: Any, args: SpikeArguments, tag: str, key: str, event: SpikeEvent) -> str:
    receipt = producer.send(make_message(args.topic, tag, key, event))
    message_id = str(receipt.message_id)
    log_event("produced", key=key, message_id=message_id, tag=tag)
    return message_id


def run_spike(args: SpikeArguments) -> None:
    elasticsearch = ElasticsearchSpikeClient(args.elasticsearch_url)
    producer: Any = None
    simple_consumer: Any = None
    push_consumer: Any = None
    producer_started = False
    simple_consumer_started = False
    push_consumer_started = False
    elasticsearch.delete_indexes(args.old_index, args.new_index)
    elasticsearch.create_index(args.old_index)
    elasticsearch.add_alias(args.old_index, args.alias)
    try:
        configuration = ClientConfiguration(args.endpoints, Credentials(), request_timeout=5)
        producer = Producer(configuration, [args.topic])
        simple_consumer = SimpleConsumer(
            configuration,
            args.simple_group,
            {args.topic: FilterExpression(SIMPLE_TAG)},
            await_duration=1,
        )
        producer.startup()
        producer_started = True
        simple_consumer.startup()
        simple_consumer_started = True
        log_event(
            "runtime",
            python=platform.python_version(),
            client=version("rocketmq-python-client"),
            endpoints=args.endpoints,
            selected_mode="SimpleConsumer",
            acknowledgement="explicit ack(message)",
            minimum_invisible_seconds=10,
            broker_proxy_image=args.broker_proxy_image,
            elasticsearch_version=args.elasticsearch_version,
            sdk_log_path=os.path.expanduser("~/logs/rocketmq_python/rocketmq_client.log"),
            sdk_log_max_bytes=100 * 1024 * 1024,
            sdk_log_backup_count=10,
            writable_home_required=True,
        )

        send_event(
            producer,
            args,
            EXCLUDED_TAG,
            "excluded",
            SpikeEvent("excluded", 1, "spike", "must not be consumed"),
        )
        filtered_id = send_event(
            producer,
            args,
            SIMPLE_TAG,
            "filtered",
            SpikeEvent("filtered", 1, "spike", "intended subscription"),
        )
        filtered = receive_one(simple_consumer, 20, 10)
        if str(filtered.message_id) != filtered_id or filtered.tag != SIMPLE_TAG:
            raise AssertionError("tag-filtered consumer received an unintended message")
        outcome = apply_event(elasticsearch, args.old_index, event_from_message(filtered))
        simple_consumer.ack(filtered)
        log_event("simple-explicit-ack", message_id=filtered_id, outcome=outcome.value)

        retry_id = send_event(
            producer,
            args,
            SIMPLE_TAG,
            "retry",
            SpikeEvent("retry", 1, "spike", "side effect before failure"),
        )
        first_delivery = receive_one(simple_consumer, 20, 10)
        if str(first_delivery.message_id) != retry_id:
            raise AssertionError(
                "controlled-failure message identity changed before first delivery"
            )
        first_time = time.monotonic()
        first_outcome = apply_event(
            elasticsearch, args.old_index, event_from_message(first_delivery)
        )
        log_event(
            "controlled-handler-failure",
            message_id=retry_id,
            delivery_attempt=first_delivery.delivery_attempt,
            outcome=first_outcome.value,
            acknowledgement="withheld",
        )
        redelivery = receive_one(simple_consumer, 30, 10)
        elapsed = time.monotonic() - first_time
        if str(redelivery.message_id) != retry_id:
            raise AssertionError("controlled failure did not redeliver the same message")
        duplicate_outcome = apply_event(
            elasticsearch, args.old_index, event_from_message(redelivery)
        )
        if duplicate_outcome is not ProjectionOutcome.DUPLICATE:
            raise AssertionError("redelivered side effect was not handled idempotently")
        simple_consumer.ack(redelivery)
        log_event(
            "bounded-redelivery-acked",
            message_id=retry_id,
            delivery_attempt=redelivery.delivery_attempt,
            elapsed_seconds=round(elapsed, 3),
            outcome=duplicate_outcome.value,
        )

        long_id = send_event(
            producer,
            args,
            SIMPLE_TAG,
            "long-processing",
            SpikeEvent("long-processing", 1, "spike", "lease extended"),
        )
        long_delivery = receive_one(simple_consumer, 20, 10)
        if str(long_delivery.message_id) != long_id:
            raise AssertionError("long-processing message identity changed")
        simple_consumer.change_invisible_duration(long_delivery, 20)
        lease_start = time.monotonic()
        time.sleep(12)
        premature = simple_consumer.receive(1, 10)
        if premature:
            raise AssertionError(
                "message was redelivered before the extended invisible duration elapsed"
            )
        lease_elapsed = time.monotonic() - lease_start
        apply_event(elasticsearch, args.old_index, event_from_message(long_delivery))
        simple_consumer.ack(long_delivery)
        log_event(
            "long-processing-acked",
            message_id=long_id,
            original_invisible_seconds=10,
            extended_invisible_seconds=20,
            observed_processing_seconds=round(lease_elapsed, 3),
            premature_redelivery=False,
        )

        ordered_events = [
            SpikeEvent("ordered", 2, "spike", "newer"),
            SpikeEvent("ordered", 1, "spike", "older"),
            SpikeEvent("ordered", 3, "spike", None, tombstone=True),
            SpikeEvent("ordered", 3, "spike", None, tombstone=True),
        ]
        expected_outcomes = [
            ProjectionOutcome.APPLIED,
            ProjectionOutcome.OLDER_REJECTED,
            ProjectionOutcome.APPLIED,
            ProjectionOutcome.DUPLICATE,
        ]
        for sequence, event in enumerate(ordered_events):
            send_event(producer, args, SIMPLE_TAG, f"ordered-{sequence}", event)
            delivery = receive_one(simple_consumer, 20, 10)
            actual = apply_event(elasticsearch, args.old_index, event_from_message(delivery))
            simple_consumer.ack(delivery)
            if actual is not expected_outcomes[sequence]:
                raise AssertionError(
                    f"projection outcome {actual.value} did not match "
                    f"{expected_outcomes[sequence].value}"
                )
            log_event(
                "projection-ordering",
                sequence=sequence,
                source_version=event.source_version,
                tombstone=event.tombstone,
                outcome=actual.value,
            )
        projected = elasticsearch.get_document(args.old_index, "ordered")
        if (
            projected is None
            or projected.get("source_version") != 3
            or projected.get("deleted") is not True
        ):
            raise AssertionError("tombstone projection did not retain the latest source version")

        listener = PushEvidenceListener()
        push_configuration = ClientConfiguration(args.endpoints, Credentials(), request_timeout=5)
        push_consumer = PushConsumer(
            push_configuration,
            args.push_group,
            listener,
            {args.topic: FilterExpression(PUSH_TAG)},
            consumption_thread_count=2,
        )
        push_consumer.startup()
        push_consumer_started = True
        push_id = send_event(
            producer,
            args,
            PUSH_TAG,
            "push-retry",
            SpikeEvent("push-retry", 1, "spike", "listener retry"),
        )
        if not listener.retry_completed.wait(60):
            raise TimeoutError("PushConsumer did not retry the failed listener within 60 seconds")
        if len(listener.retry_attempts) < 2 or {
            item[0] for item in listener.retry_attempts[:2]
        } != {push_id}:
            raise AssertionError("PushConsumer retry did not preserve message identity")
        push_signature = inspect.signature(PushConsumer).parameters
        log_event(
            "push-mode-observation",
            acknowledgement="listener return SUCCESS/FAILURE",
            attempts=len(listener.retry_attempts),
            elapsed_seconds=round(listener.retry_attempts[1][2] - listener.retry_attempts[0][2], 3),
            public_invisible_duration_parameter="invisible_duration" in push_signature,
            selected_for_worker=False,
        )

        push_long_id = send_event(
            producer,
            args,
            PUSH_TAG,
            "push-long-processing",
            SpikeEvent("push-long-processing", 1, "spike", "35 second listener"),
        )
        if not listener.long_started.wait(20):
            raise TimeoutError("PushConsumer did not begin the long-processing callback")
        if not listener.long_completed.wait(50):
            raise TimeoutError("PushConsumer long-processing callback did not finish")
        time.sleep(5)
        if not listener.long_attempts or {item[0] for item in listener.long_attempts} != {
            push_long_id
        }:
            raise AssertionError("PushConsumer long-processing evidence changed message identity")
        log_event(
            "push-long-processing-observation",
            message_id=push_long_id,
            callback_seconds=35,
            observed_attempts=len(listener.long_attempts),
            duplicate_observed=len(listener.long_attempts) > 1,
            acknowledgement="automatic after listener SUCCESS",
            public_invisible_duration_parameter="invisible_duration" in push_signature,
            selected_for_worker=False,
        )

        elasticsearch.refresh(args.old_index)
        rebuilt_count = rebuild_and_switch(
            elasticsearch, args.old_index, args.new_index, args.alias
        )
        log_event(
            "validated-atomic-alias-switch",
            source_index=args.old_index,
            target_index=args.new_index,
            alias=args.alias,
            validated_documents=rebuilt_count,
        )
        log_event(
            "viability-decision",
            decision="viable",
            selected_mode="SimpleConsumer",
            limitation=(
                "PushConsumer 5.1.1 exposes listener-result acknowledgement but no public "
                "invisible-duration constructor parameter; this observation is not a "
                "maintainer-confirmed root-cause claim"
            ),
        )
    finally:
        if push_consumer is not None and push_consumer_started:
            push_consumer.shutdown()
        if simple_consumer is not None and simple_consumer_started:
            simple_consumer.shutdown()
        if producer is not None and producer_started:
            producer.shutdown()
        elasticsearch.delete_indexes(args.old_index, args.new_index)


def parse_args() -> SpikeArguments:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoints", required=True)
    parser.add_argument("--topic", required=True)
    parser.add_argument("--simple-group", required=True)
    parser.add_argument("--push-group", required=True)
    parser.add_argument("--elasticsearch-url", required=True)
    parser.add_argument("--old-index", required=True)
    parser.add_argument("--new-index", required=True)
    parser.add_argument("--alias", required=True)
    parser.add_argument("--broker-proxy-image", required=True)
    parser.add_argument("--elasticsearch-version", required=True)
    namespace = parser.parse_args()
    return SpikeArguments(**vars(namespace))


def main() -> None:
    run_spike(parse_args())


if __name__ == "__main__":
    main()
