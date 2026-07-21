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
from citybuddy_indexer.faq_cache import (
    _FINALIZE_SCRIPT,
    _PREPARE_SCRIPT,
    FAQ_ENTRY_TTL_MS,
    FAQ_PREPARATION_LEASE_MS,
    FAQ_PREPARATION_TTL_MS,
    FAQ_PREPARATION_TTL_SAFETY_MS,
    CachePreparation,
    _cache_commitment,
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


def event(
    version: int,
    *,
    tombstone: bool = False,
    answer: str | None = None,
    source_id: str = "faq-cb111-delivery",
) -> dict[str, object]:
    return {
        "eventId": str(uuid4()),
        "sourceId": source_id,
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


def document(es_url: str, index: str, *, source_id: str = "faq-cb111-delivery") -> dict[str, Any]:
    response = request(es_url, "GET", f"/{index}/_doc/{source_id}%3Aanswer")
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
        state.get("source_version") == source_version
        and state.get("tombstone") == "0"
        and state.get("ready") == "1"
        and answer.get("source_version") == source_version
        and answer.get("commitment") == state.get("commitment")
        and answer.get("cache_commitment") == state.get("cache_commitment")
    )


@dataclass(frozen=True)
class FinalizeUnavailableCache:
    delegate: RedisFaqCacheProjection

    def prepare(self, event: FaqKnowledgeEvent) -> CachePreparation:
        return self.delegate.prepare(event)

    def prepare_authoritatively(self, event: FaqKnowledgeEvent) -> CachePreparation:
        return self.delegate.prepare_authoritatively(event)

    def finalize(self, event: FaqKnowledgeEvent, index_version: str) -> ProjectionOutcome:
        del event, index_version
        raise KnowledgeSyncError("support_cache_unavailable")

    def abort(self, event: FaqKnowledgeEvent) -> None:
        self.delegate.abort(event)


PERSISTED_STATE_CLASSES = ("missing", "ready0", "ready1-live", "ready1-tombstone")
PIPELINE_PHASES = ("before-es", "after-es-before-finalize")


@dataclass
class BranchRecordingRedis:
    delegate: Redis
    observed: list[str]

    def eval(self, script: str, key_count: int, *values: str) -> object:
        raw = self.delegate.eval(script, key_count, *values)
        script_name = (
            "prepare"
            if script == _PREPARE_SCRIPT
            else "finalize"
            if script == _FINALIZE_SCRIPT
            else None
        )
        if (
            script_name is not None
            and isinstance(raw, list)
            and len(raw) == 2
            and all(isinstance(item, str) for item in raw)
        ):
            self.observed.append(f"{script_name}:{raw[0]}:{raw[1]}")
        return raw


def _state_key(sync_event: FaqKnowledgeEvent) -> str:
    return f"{FAQ_CACHE_PREFIX}state:{sync_event.source_id}"


def _answer_key(sync_event: FaqKnowledgeEvent) -> str:
    return f"{FAQ_CACHE_PREFIX}answer:{sync_event.source_id}:{sync_event.source_version}"


def _parsed_event(payload: dict[str, object]) -> FaqKnowledgeEvent:
    return FaqKnowledgeEvent.from_bytes(encode(payload))


@dataclass(frozen=True, order=True)
class LiveRedisFault:
    key_role: str
    operation: str
    field: str = ""

    @property
    def label(self) -> str:
        suffix = f":{self.field}" if self.field else ""
        return f"{self.key_role}:{self.operation}{suffix}"


def _key_for_role(sync_event: FaqKnowledgeEvent, key_role: str) -> str:
    if key_role == "state":
        return _state_key(sync_event)
    if key_role == "answer":
        return _answer_key(sync_event)
    raise AssertionError(f"unclassified live Redis key role: {key_role}")


def _role_for_key(sync_event: FaqKnowledgeEvent, key: str) -> str:
    if key == _state_key(sync_event):
        return "state"
    if key == _answer_key(sync_event):
        return "answer"
    raise AssertionError(f"unexpected FAQ cache key discovered from live Redis: {key}")


def _live_fault_inventory(
    admin: Redis, sync_event: FaqKnowledgeEvent, persisted_class: str
) -> tuple[LiveRedisFault, ...]:
    discovered: list[LiveRedisFault] = [LiveRedisFault("none", "valid")]
    keys = sorted(
        cast(
            list[str],
            list(admin.scan_iter(match=f"{FAQ_CACHE_PREFIX}*{sync_event.source_id}*")),
        )
    )
    for key in keys:
        key_role = _role_for_key(sync_event, key)
        key_type = cast(str, admin.type(key))
        if key_type != "hash":
            raise AssertionError(f"legal FAQ cache key is not a hash: {key_role}={key_type}")
        ttl = cast(int, admin.pttl(key))
        if ttl <= 0:
            raise AssertionError(f"legal FAQ cache key is not TTL-bounded: {key_role}={ttl}")
        fields = cast(dict[str, str], admin.hgetall(key))
        if not fields:
            raise AssertionError(f"legal FAQ cache key has no enumerable fields: {key_role}")
        discovered.extend(
            (
                LiveRedisFault(key_role, "delete-key"),
                LiveRedisFault(key_role, "wrong-type"),
                LiveRedisFault(key_role, "remove-ttl"),
                LiveRedisFault(key_role, "add-field"),
            )
        )
        for field in sorted(fields):
            discovered.append(LiveRedisFault(key_role, "delete-field", field))
            discovered.append(LiveRedisFault(key_role, "mutate-field", field))
    if persisted_class == "ready1-tombstone":
        discovered.append(LiveRedisFault("answer", "unexpected-key"))
    return tuple(sorted(discovered))


def _mutated_live_value(field: str, current: str, sync_event: FaqKnowledgeEvent) -> str:
    fixed = {
        "schema": "tampered-schema",
        "source_id": f"{sync_event.source_id}-tampered",
        "tombstone": "0" if current == "1" else "1",
        "commitment": "0" * 64,
        "index_version": "knowledge_docs_v9" if current != "knowledge_docs_v9" else "v1",
        "event_id": str(uuid4()),
        "occurred_time": "2026-07-21T12:59:59Z",
        "ready": "0" if current == "1" else "1",
        "cache_commitment": "f" * 64,
        "lease_deadline_ms": "invalid",
        "doc_type": "product",
        "chunk_id": "other",
        "title": "Data consistency fault title",
        "answer": "Data consistency fault answer",
        "public_category": "other",
        "public_language": "en",
    }
    if field == "source_version":
        return str(sync_event.source_version + 1)
    replacement = fixed.get(field, f"tampered-{field}")
    if replacement == current:
        replacement = f"{replacement}-other"
    return replacement


def _inject_live_fault(admin: Redis, sync_event: FaqKnowledgeEvent, fault: LiveRedisFault) -> None:
    if fault.operation == "valid":
        return
    key = _key_for_role(sync_event, fault.key_role)
    if fault.operation == "delete-key":
        admin.delete(key)
        return
    if fault.operation == "wrong-type":
        admin.delete(key)
        admin.set(key, "data-consistency-fault", px=FAQ_ENTRY_TTL_MS)
        return
    if fault.operation == "remove-ttl":
        admin.persist(key)
        return
    if fault.operation == "add-field":
        admin.hset(key, "unexpected", "field")
        return
    if fault.operation == "unexpected-key":
        admin.hset(key, mapping={"unexpected": "tombstone-answer"})
        admin.pexpire(key, FAQ_ENTRY_TTL_MS)
        return
    if fault.operation == "delete-field":
        admin.hdel(key, fault.field)
        return
    if fault.operation == "mutate-field":
        current = cast(str | None, admin.hget(key, fault.field))
        if current is None:
            raise AssertionError(f"live Redis field disappeared before injection: {fault.label}")
        admin.hset(key, fault.field, _mutated_live_value(fault.field, current, sync_event))
        return
    raise AssertionError(f"unclassified live Redis fault: {fault.label}")


def _seed_persisted_state(
    admin: Redis,
    projection: RedisFaqCacheProjection,
    sync_event: FaqKnowledgeEvent,
    persisted_class: str,
    fault: LiveRedisFault,
    expected_inventory: tuple[LiveRedisFault, ...] | None,
) -> None:
    state_key = _state_key(sync_event)
    admin.delete(state_key)
    for key in admin.scan_iter(match=f"{FAQ_CACHE_PREFIX}answer:{sync_event.source_id}:*"):
        admin.delete(key)
    if persisted_class == "missing":
        actual_inventory = _live_fault_inventory(admin, sync_event, persisted_class)
    elif persisted_class == "ready0":
        assert projection.prepare(sync_event) is CachePreparation.PREPARED
        actual_inventory = _live_fault_inventory(admin, sync_event, persisted_class)
    elif persisted_class in {"ready1-live", "ready1-tombstone"}:
        assert projection.apply(sync_event, "knowledge_docs_v1") is ProjectionOutcome.APPLIED
        actual_inventory = _live_fault_inventory(admin, sync_event, persisted_class)
    else:
        raise AssertionError(f"unclassified persisted state: {persisted_class}")
    if expected_inventory is not None and actual_inventory != expected_inventory:
        raise AssertionError(
            f"live Redis schema drifted for {persisted_class}: "
            f"expected {[item.label for item in expected_inventory]}, "
            f"got {[item.label for item in actual_inventory]}"
        )
    _inject_live_fault(admin, sync_event, fault)


@dataclass(frozen=True)
class FinalizeFenceFault:
    delegate: RedisFaqCacheProjection
    admin: Redis
    persisted_class: str
    fault: LiveRedisFault
    expected_inventory: tuple[LiveRedisFault, ...]

    def prepare(self, sync_event: FaqKnowledgeEvent) -> CachePreparation:
        return self.delegate.prepare(sync_event)

    def prepare_authoritatively(self, sync_event: FaqKnowledgeEvent) -> CachePreparation:
        return self.delegate.prepare_authoritatively(sync_event)

    def finalize(self, sync_event: FaqKnowledgeEvent, index_version: str) -> ProjectionOutcome:
        _seed_persisted_state(
            self.admin,
            self.delegate,
            sync_event,
            self.persisted_class,
            self.fault,
            self.expected_inventory,
        )
        return self.delegate.finalize(sync_event, index_version)

    def abort(self, sync_event: FaqKnowledgeEvent) -> None:
        self.delegate.abort(sync_event)


def _assert_matrix_converged(
    args: argparse.Namespace, admin: Redis, sync_event: FaqKnowledgeEvent
) -> None:
    state_key = _state_key(sync_event)
    state = cast(dict[str, str], admin.hgetall(state_key))
    expected_cache_commitment = _cache_commitment(sync_event.commitment, args.index)
    assert state == {
        "schema": FAQ_CACHE_SCHEMA,
        "source_id": sync_event.source_id,
        "source_version": str(sync_event.source_version),
        "tombstone": "1" if sync_event.tombstone else "0",
        "commitment": sync_event.commitment,
        "index_version": "" if sync_event.tombstone else args.index,
        "event_id": sync_event.event_id,
        "occurred_time": sync_event.occurred_time,
        "ready": "1",
        "cache_commitment": "" if sync_event.tombstone else expected_cache_commitment,
        "lease_deadline_ms": "",
    }
    state_ttl = cast(int, admin.pttl(state_key))
    assert 0 < state_ttl <= FAQ_ENTRY_TTL_MS
    answer_key = _answer_key(sync_event)
    if sync_event.tombstone:
        assert not admin.exists(answer_key)
    else:
        answer = cast(dict[str, str], admin.hgetall(answer_key))
        assert answer == {
            "schema": FAQ_CACHE_SCHEMA,
            "source_id": sync_event.source_id,
            "source_version": str(sync_event.source_version),
            "doc_type": "faq",
            "chunk_id": "answer",
            "title": sync_event.content.question,
            "answer": sync_event.content.answer,
            "index_version": args.index,
            "commitment": sync_event.commitment,
            "cache_commitment": expected_cache_commitment,
            "public_category": "faq",
            "public_language": "und",
        }
        answer_ttl = cast(int, admin.pttl(answer_key))
        assert 0 < answer_ttl <= FAQ_ENTRY_TTL_MS
    redis_keys = sorted(
        cast(
            list[str],
            list(admin.scan_iter(match=f"{FAQ_CACHE_PREFIX}*{sync_event.source_id}*")),
        )
    )
    expected_keys = [state_key] if sync_event.tombstone else sorted([state_key, answer_key])
    assert set(expected_keys).issubset(redis_keys)
    for residual_key in set(redis_keys) - set(expected_keys):
        assert residual_key.startswith(f"{FAQ_CACHE_PREFIX}answer:{sync_event.source_id}:")
        assert 0 < cast(int, admin.pttl(residual_key)) <= FAQ_ENTRY_TTL_MS
    es_document = document(args.elasticsearch_url, args.index, source_id=sync_event.source_id)
    assert es_document["source_id"] == sync_event.source_id
    assert es_document["source_version"] == sync_event.source_version
    assert es_document["title"] == sync_event.content.question
    assert es_document["content"] == sync_event.content.answer
    assert es_document["published"] is (not sync_event.tombstone)
    assert es_document["deleted"] is sync_event.tombstone
    assert es_document["sync_event_id"] == sync_event.event_id
    assert es_document["sync_event_commitment"] == sync_event.commitment
    assert es_document["sync_occurred_at"] == sync_event.occurred_time


def _recover_matrix_cell(
    worker: IndexerWorker,
    payload: dict[str, object],
    admin: Redis,
    sync_event: FaqKnowledgeEvent,
) -> int:
    retries = 0
    for _ in range(6):
        result = worker.handle(encode(payload))
        if result.action is DeliveryAction.ACK:
            assert result.code in {"applied", "replayed", "stale"}
            return retries
        retries += 1
        assert result.code and result.code not in {"applied", "replayed", "stale"}
        state = cast(dict[str, str], admin.hgetall(_state_key(sync_event)))
        if result.code == "owner_local_state_ahead":
            admin.pexpire(_state_key(sync_event), 50)
            time.sleep(0.1)
            continue
        if state.get("ready") == "0" and state.get("lease_deadline_ms", "").isdecimal():
            admin.hset(_state_key(sync_event), "lease_deadline_ms", "1")
    raise AssertionError("persisted-state fault did not converge within the bounded replay window")


def _exercise_invalid_input_discriminators(recorder: BranchRecordingRedis, source_id: str) -> None:
    recorder.eval(
        _PREPARE_SCRIPT,
        2,
        f"{FAQ_CACHE_PREFIX}state:{source_id}",
        f"{FAQ_CACHE_PREFIX}answer:{source_id}:",
        FAQ_CACHE_SCHEMA,
        source_id,
        "0",
        "0",
        "commitment",
        str(uuid4()),
        "2026-07-21T12:00:00Z",
        str(FAQ_PREPARATION_LEASE_MS),
        str(FAQ_PREPARATION_TTL_MS),
        str(FAQ_ENTRY_TTL_MS),
        "0",
        "question",
        "answer",
    )
    recorder.eval(
        _FINALIZE_SCRIPT,
        2,
        f"{FAQ_CACHE_PREFIX}state:{source_id}",
        f"{FAQ_CACHE_PREFIX}answer:{source_id}:0",
        FAQ_CACHE_SCHEMA,
        source_id,
        "0",
        "0",
        "commitment",
        "knowledge_docs_v1",
        str(uuid4()),
        "question",
        "answer",
        str(FAQ_ENTRY_TTL_MS),
        "2026-07-21T12:00:00Z",
        "cache-commitment",
    )


def disposition_matrix(args: argparse.Namespace, admin: Redis) -> dict[str, object]:
    observed_branches: list[str] = []
    recording_client = BranchRecordingRedis(
        Redis.from_url(args.support_redis_url, decode_responses=True), observed_branches
    )
    cache_projection = RedisFaqCacheProjection(cast(Redis, recording_client))
    elasticsearch = ElasticsearchKnowledgeProjection(args.elasticsearch_url)
    inventories: dict[str, tuple[LiveRedisFault, ...]] = {}
    for persisted_class in PERSISTED_STATE_CLASSES:
        source_id = f"faq-live-inventory-{persisted_class}"
        payload = event(
            2,
            source_id=source_id,
            tombstone=persisted_class == "ready1-tombstone",
        )
        sync_event = _parsed_event(payload)
        discovery_seed = LiveRedisFault("none", "valid")
        if persisted_class == "missing":
            inventories[persisted_class] = _live_fault_inventory(admin, sync_event, persisted_class)
        else:
            _seed_persisted_state(
                admin,
                cache_projection,
                sync_event,
                persisted_class,
                discovery_seed,
                None,
            )
            inventories[persisted_class] = _live_fault_inventory(admin, sync_event, persisted_class)
        admin.delete(_state_key(sync_event), _answer_key(sync_event))
    matrix_cells = 0
    corruption_cells = 0
    retry_cells = 0
    discriminator_cells: dict[str, set[str]] = {}
    for phase in PIPELINE_PHASES:
        for persisted_class in PERSISTED_STATE_CLASSES:
            inventory = inventories[persisted_class]
            for fault in inventory:
                source_id = f"faq-matrix-{phase[:3]}-{persisted_class}-{matrix_cells}"
                payload = event(
                    2,
                    source_id=source_id,
                    tombstone=persisted_class == "ready1-tombstone",
                )
                sync_event = _parsed_event(payload)
                normal_worker = IndexerWorker(
                    settings(args, args.elasticsearch_url),
                    VersionedKnowledgeProjection(elasticsearch, cache_projection),
                )
                cell_branch_start = len(observed_branches)
                if phase == "before-es":
                    _seed_persisted_state(
                        admin,
                        cache_projection,
                        sync_event,
                        persisted_class,
                        fault,
                        inventory,
                    )
                    first = normal_worker.handle(encode(payload))
                else:
                    fault_worker = IndexerWorker(
                        settings(args, args.elasticsearch_url),
                        VersionedKnowledgeProjection(
                            elasticsearch,
                            FinalizeFenceFault(
                                cache_projection,
                                admin,
                                persisted_class,
                                fault,
                                inventory,
                            ),
                        ),
                    )
                    first = fault_worker.handle(encode(payload))

                requires_retry = fault.operation != "valid" or (
                    phase == "after-es-before-finalize" and persisted_class != "ready0"
                )
                if (
                    phase == "before-es"
                    and fault.key_role == "state"
                    and fault.operation == "delete-key"
                ):
                    requires_retry = False
                if requires_retry:
                    assert first.action is DeliveryAction.RETRY, {
                        "fault": fault.label,
                        "persistedClass": persisted_class,
                        "phase": phase,
                        "result": first,
                    }
                    retry_cells += 1
                    if fault.operation != "valid":
                        corruption_cells += 1
                    try:
                        recovered_retries = _recover_matrix_cell(
                            normal_worker, payload, admin, sync_event
                        )
                    except AssertionError as error:
                        raise AssertionError(
                            {
                                "fault": fault.label,
                                "persistedClass": persisted_class,
                                "phase": phase,
                                "state": admin.hgetall(_state_key(sync_event)),
                            }
                        ) from error
                    retry_cells += recovered_retries
                else:
                    assert first.action is DeliveryAction.ACK and first.code in {
                        "applied",
                        "replayed",
                    }
                _assert_matrix_converged(args, admin, sync_event)
                cell_branches = set(observed_branches[cell_branch_start:])
                if not cell_branches:
                    raise AssertionError(f"matrix cell emitted no Lua discriminator: {fault.label}")
                discriminator_cells[f"{persisted_class}:{fault.label}:{phase}"] = cell_branches
                matrix_cells += 1

    _exercise_invalid_input_discriminators(recording_client, "faq-matrix-invalid-input")
    confirmed_stale_source = "faq-matrix-confirmed-stale"
    confirmed_v3_payload = event(3, source_id=confirmed_stale_source)
    confirmed_worker = IndexerWorker(
        settings(args, args.elasticsearch_url),
        VersionedKnowledgeProjection(elasticsearch, cache_projection),
    )
    assert confirmed_worker.handle(encode(confirmed_v3_payload)).action is DeliveryAction.ACK
    confirmed_v2_payload = event(2, source_id=confirmed_stale_source)
    confirmed_stale = confirmed_worker.handle(encode(confirmed_v2_payload))
    assert confirmed_stale.action is DeliveryAction.ACK and confirmed_stale.code == "stale"

    unconfirmed_source = "faq-matrix-unconfirmed-ahead"
    redis_v3 = _parsed_event(event(3, source_id=unconfirmed_source))
    es_v2_payload = event(2, source_id=unconfirmed_source)
    es_v2 = _parsed_event(es_v2_payload)
    _seed_persisted_state(
        admin,
        cache_projection,
        redis_v3,
        "ready1-live",
        LiveRedisFault("none", "valid"),
        inventories["ready1-live"],
    )
    unconfirmed_worker = IndexerWorker(
        settings(args, args.elasticsearch_url),
        VersionedKnowledgeProjection(elasticsearch, cache_projection),
    )
    unconfirmed_ahead = unconfirmed_worker.handle(encode(es_v2_payload))
    assert unconfirmed_ahead.action is DeliveryAction.RETRY
    assert unconfirmed_ahead.code == "owner_local_state_ahead"
    admin.pexpire(_state_key(redis_v3), 50)
    time.sleep(0.1)
    converged_v2 = unconfirmed_worker.handle(encode(es_v2_payload))
    assert converged_v2.action is DeliveryAction.ACK and converged_v2.code == "replayed"
    _assert_matrix_converged(args, admin, es_v2)

    # Exact regression from independent review: a format-valid version corruption must not
    # let Redis terminate a legitimate publication before Elasticsearch observes it.
    review_source = "faq-ready-version-contradiction"
    v3_payload = event(3, source_id=review_source)
    v3_event = _parsed_event(v3_payload)
    review_worker = IndexerWorker(
        settings(args, args.elasticsearch_url),
        VersionedKnowledgeProjection(elasticsearch, cache_projection),
    )
    assert review_worker.handle(encode(v3_payload)).action is DeliveryAction.ACK
    admin.hset(_state_key(v3_event), "source_version", "4")
    v4_payload = event(4, source_id=review_source)
    v4_event = _parsed_event(v4_payload)
    first_v4 = review_worker.handle(encode(v4_payload))
    assert first_v4.action is DeliveryAction.RETRY
    assert first_v4.code == "owner_local_state_repaired"
    second_v4 = review_worker.handle(encode(v4_payload))
    assert second_v4.action is DeliveryAction.ACK and second_v4.code == "replayed"
    assert (
        document(args.elasticsearch_url, args.index, source_id=review_source)["source_version"] == 4
    )
    _assert_matrix_converged(args, admin, v4_event)

    live_field_targets = {
        (persisted_class, fault.key_role, fault.field)
        for persisted_class, inventory in inventories.items()
        for fault in inventory
        if fault.operation in {"delete-field", "mutate-field"}
    }
    observed_fault_cells = {
        key for key, details in discriminator_cells.items() if details and ":valid:" not in key
    }
    expected_fault_cells = sum(
        1 for inventory in inventories.values() for fault in inventory if fault.operation != "valid"
    ) * len(PIPELINE_PHASES)
    assert len(observed_fault_cells) == expected_fault_cells

    return {
        "fenceDispositionCells": matrix_cells,
        "fieldCorruptionPhaseCells": corruption_cells,
        "liveRedisDiscriminatorCells": len(discriminator_cells),
        "liveRedisFieldTargets": len(live_field_targets),
        "liveRedisKeys": sum(
            len(
                {
                    fault.key_role
                    for fault in inventory
                    if fault.key_role != "none" and fault.operation != "unexpected-key"
                }
            )
            for inventory in inventories.values()
        ),
        "persistedStateClasses": len(PERSISTED_STATE_CLASSES),
        "pipelinePhases": len(PIPELINE_PHASES),
        "retryObservations": retry_cells,
        "uniqueLuaDiscriminators": len(set(observed_branches)),
    }


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
    parser.add_argument("--admin-redis-url", required=True)
    args = parser.parse_args()

    configuration = ClientConfiguration(args.endpoints, Credentials(), request_timeout=5)
    producer: Any = Producer(configuration, [args.topic])
    producer.startup()
    baseline = request(
        args.elasticsearch_url, "GET", f"/{args.index}/_doc/faq-store-hours%3Ageneral"
    )
    alias_before = request(args.elasticsearch_url, "GET", "/_alias/knowledge_docs_read")
    cache = Redis.from_url(args.agent_cache_url, decode_responses=True)
    admin_cache = Redis.from_url(args.admin_redis_url, decode_responses=True)
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
    current_state = cast(
        dict[str, str], cache.hgetall(f"{FAQ_CACHE_PREFIX}state:faq-cb111-delivery")
    )
    assert current_state.get("source_version") == "8"
    assert current_state.get("ready") == "0"
    lease_deadline_ms = current_state.get("lease_deadline_ms", "")
    assert lease_deadline_ms.isdecimal()
    redis_time = cast(tuple[int, int], admin_cache.time())
    now_ms = redis_time[0] * 1000 + redis_time[1] // 1000
    lease_remaining_ms = int(lease_deadline_ms) - now_ms
    preparation_ttl = cast(int, admin_cache.pttl(f"{FAQ_CACHE_PREFIX}state:faq-cb111-delivery"))
    assert 0 < lease_remaining_ms <= FAQ_PREPARATION_LEASE_MS
    assert lease_remaining_ms < preparation_ttl <= FAQ_PREPARATION_TTL_MS
    assert preparation_ttl - lease_remaining_ms >= FAQ_PREPARATION_TTL_SAFETY_MS - 1_000
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

    matrix_evidence = disposition_matrix(args, admin_cache)
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
                **matrix_evidence,
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
