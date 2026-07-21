#!/usr/bin/env python3
"""Real Support Redis evidence for the CB-112 two-level FAQ cache."""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any, cast

from citybuddy_agent.faq_cache import RedisFaqCache, normalized_query_hash
from citybuddy_indexer.faq_cache import (
    FAQ_CACHE_PREFIX,
    FAQ_PREPARATION_LEASE_MS,
    FAQ_PREPARATION_TTL_MS,
    FAQ_PREPARATION_TTL_SAFETY_MS,
    RedisFaqCacheProjection,
)
from citybuddy_indexer.incremental import (
    FaqKnowledgeEvent,
    KnowledgeSyncConflict,
    KnowledgeSyncError,
    ProjectionOutcome,
)
from redis import Redis
from redis.exceptions import NoPermissionError, ResponseError


def parse_env(path: Path) -> dict[str, str]:
    return dict(
        line.split("=", maxsplit=1)
        for line in path.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
    )


def event(
    version: int,
    *,
    tombstone: bool = False,
    event_id: str | None = None,
    question: str = "How do refunds work?",
    answer: str = "Refunds follow the published local policy.",
) -> FaqKnowledgeEvent:
    payload = {
        "eventId": event_id or f"00000000-0000-4000-8000-{version:012d}",
        "sourceId": "faq-refund",
        "sourceType": "faq",
        "sourceVersion": version,
        "publicationState": "PUBLISHED",
        "tombstone": tombstone,
        "occurredTime": f"2026-07-21T12:00:{version:02d}Z",
        "content": {"question": question, "answer": answer},
    }
    return FaqKnowledgeEvent.from_bytes(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    )


def require_denied(operation: Any) -> None:
    try:
        operation()
    except NoPermissionError:
        return
    except ResponseError as error:
        if "NOPERM" not in str(error):
            raise AssertionError(f"unexpected Redis rejection: {error}") from error
        return
    raise AssertionError("forbidden Redis operation unexpectedly succeeded")


def local_url(user: str, password: str, port: int) -> str:
    return f"redis://{user}:{password}@127.0.0.1:{port}/0"


def normal(port: int, values: dict[str, str]) -> dict[str, object]:
    admin = Redis(
        host="127.0.0.1",
        port=port,
        password=values["REDIS_SUPPORT_PASSWORD"],
        decode_responses=True,
    )
    agent_client = Redis.from_url(
        local_url("agent_cache", values["REDIS_AGENT_CACHE_PASSWORD"], port),
        decode_responses=True,
    )
    indexer_client = Redis.from_url(
        local_url("knowledge_indexer", values["REDIS_INDEXER_CACHE_PASSWORD"], port),
        decode_responses=True,
    )
    admin.flushdb()
    cache = RedisFaqCache(local_url("agent_cache", values["REDIS_AGENT_CACHE_PASSWORD"], port))
    projection = RedisFaqCacheProjection(indexer_client)
    raw_query = "  HOW\u3000do Refunds work?  "

    assert projection.apply(event(1), "knowledge_docs_v1") is ProjectionOutcome.APPLIED
    assert cache.lookup(raw_query) is None
    assert cache.populate_mapping(raw_query, "faq-refund", 1)
    hit = cache.lookup("how do refunds work?")
    assert hit is not None
    assert hit.index_version == "knowledge_docs_v1"
    assert hit.results[0].source_id == "faq-refund"
    assert hit.results[0].source_version == 1
    assert hit.results[0].excerpt == "Refunds follow the published local policy."

    query_hash = normalized_query_hash(raw_query)
    assert query_hash is not None
    mapping_key = f"{FAQ_CACHE_PREFIX}query:{query_hash}"
    state_key = f"{FAQ_CACHE_PREFIX}state:faq-refund"
    answer_v1 = f"{FAQ_CACHE_PREFIX}answer:faq-refund:1"
    mapping_ttl = cast(int, admin.pttl(mapping_key))
    state_ttl = cast(int, admin.pttl(state_key))
    answer_ttl = cast(int, admin.pttl(answer_v1))
    assert 0 < mapping_ttl <= 300_000
    assert 0 < state_ttl <= 900_000
    assert 0 < answer_ttl <= 900_000
    assert mapping_ttl <= state_ttl and mapping_ttl <= answer_ttl

    assert projection.apply(event(2), "knowledge_docs_v1") is ProjectionOutcome.APPLIED
    assert cache.lookup(raw_query) is None
    assert not admin.exists(answer_v1)
    assert cache.populate_mapping(raw_query, "faq-refund", 2)
    assert cache.lookup(raw_query) is not None
    assert projection.apply(event(2), "knowledge_docs_v1") is ProjectionOutcome.REPLAYED
    assert projection.apply(event(1), "knowledge_docs_v1") is ProjectionOutcome.STALE
    assert admin.hget(state_key, "source_version") == "2"

    try:
        projection.apply(
            event(2, event_id="99999999-9999-4999-8999-999999999999"),
            "knowledge_docs_v1",
        )
    except KnowledgeSyncConflict as error:
        assert str(error) == "conflicting_source_version"
    else:
        raise AssertionError("equal-version conflict overwrote cache state")

    assert (
        projection.apply(event(3, tombstone=True), "knowledge_docs_v1") is ProjectionOutcome.APPLIED
    )
    assert cache.lookup(raw_query) is None
    assert admin.hget(state_key, "tombstone") == "1"
    assert projection.apply(event(2), "knowledge_docs_v1") is ProjectionOutcome.STALE
    assert admin.hget(state_key, "source_version") == "3"

    assert projection.apply(event(4), "knowledge_docs_v1") is ProjectionOutcome.APPLIED
    assert cache.populate_mapping(raw_query, "faq-refund", 4)
    answer_v4 = f"{FAQ_CACHE_PREFIX}answer:faq-refund:4"
    assert projection.prepare(event(5)) is None
    assert admin.hget(state_key, "source_version") == "5"
    assert admin.hget(state_key, "ready") == "0"
    lease_deadline_ms = cast(str, admin.hget(state_key, "lease_deadline_ms"))
    assert lease_deadline_ms.isdecimal()
    redis_time = cast(tuple[int, int], admin.time())
    now_ms = redis_time[0] * 1000 + redis_time[1] // 1000
    lease_remaining_ms = int(lease_deadline_ms) - now_ms
    preparation_ttl = cast(int, admin.pttl(state_key))
    assert 0 < lease_remaining_ms <= FAQ_PREPARATION_LEASE_MS
    assert lease_remaining_ms < preparation_ttl <= FAQ_PREPARATION_TTL_MS
    assert preparation_ttl - lease_remaining_ms >= FAQ_PREPARATION_TTL_SAFETY_MS - 1_000
    assert cache.lookup(raw_query) is None
    maxmemory_config = cast(dict[str, str], admin.config_get("maxmemory"))
    memory_info = cast(dict[str, int], admin.info("memory"))
    stats_info = cast(dict[str, int], admin.info("stats"))
    original_maxmemory = int(maxmemory_config["maxmemory"])
    used_memory = memory_info["used_memory"]
    evicted_before = stats_info["evicted_keys"]
    admin.config_set("maxmemory", used_memory + 262_144)
    try:
        for index in range(512):
            admin.set(f"cb112:eviction:{index}", "x" * 4096, px=60_000)
            current_stats = cast(dict[str, int], admin.info("stats"))
            if current_stats["evicted_keys"] > evicted_before:
                break
        else:
            raise AssertionError("controlled volatile-lfu pressure did not evict an eligible key")
        assert cache.lookup(raw_query) is None
    finally:
        admin.config_set("maxmemory", original_maxmemory)
        for key in admin.scan_iter(match="cb112:eviction:*"):
            admin.delete(key)
    if not admin.exists(state_key):
        assert projection.prepare(event(5)) is None
    try:
        projection.prepare(event(6))
    except KnowledgeSyncError as error:
        assert str(error) == "cache_source_busy"
    else:
        raise AssertionError("a concurrent source transition overwrote the in-flight state")
    projection.abort(event(6))
    assert admin.hget(state_key, "source_version") == "5"
    admin.hset(state_key, "lease_deadline_ms", "1")
    assert projection.prepare(event(6)) is None
    assert admin.hget(state_key, "source_version") == "6"
    projection.abort(event(5))
    assert admin.hget(state_key, "source_version") == "6"
    projection.abort(event(6))
    assert not admin.exists(state_key)
    assert projection.prepare(event(5)) is None
    projection.abort(event(5))
    assert not admin.exists(state_key)
    assert not cache.populate_mapping(raw_query, "faq-refund", 4)
    assert projection.apply(event(4), "knowledge_docs_v1") is ProjectionOutcome.APPLIED
    assert cache.populate_mapping(raw_query, "faq-refund", 4)
    assert cache.lookup(raw_query) is not None
    assert projection.prepare(event(5)) is None
    admin.pexpire(state_key, 50)
    time.sleep(0.1)
    assert not admin.exists(state_key)
    assert cache.lookup(raw_query) is None
    try:
        projection.finalize(event(5), "knowledge_docs_v1")
    except KnowledgeSyncError as error:
        assert str(error) == "cache_preparation_missing"
    else:
        raise AssertionError("a lost preparation state permanently acknowledged finalization")
    assert projection.apply(event(4), "knowledge_docs_v1") is ProjectionOutcome.APPLIED
    assert cache.populate_mapping(raw_query, "faq-refund", 4)
    admin.delete(answer_v4)
    assert cache.lookup(raw_query) is None
    assert not admin.exists(mapping_key)
    assert projection.apply(event(4), "knowledge_docs_v1") is ProjectionOutcome.REPLAYED
    assert cache.populate_mapping(raw_query, "faq-refund", 4)
    admin.hset(mapping_key, "unexpected", "field")
    assert cache.lookup(raw_query) is None
    assert not admin.exists(mapping_key)

    for field, replacement, original in (
        ("title", "tampered title", "How do refunds work?"),
        ("answer", "tampered answer", "Refunds follow the published local policy."),
        ("index_version", "knowledge_docs_v9", "knowledge_docs_v1"),
    ):
        assert cache.populate_mapping(raw_query, "faq-refund", 4)
        admin.hset(answer_v4, field, replacement)
        assert cache.lookup(raw_query) is None
        admin.hset(answer_v4, field, original)
    assert cache.populate_mapping(raw_query, "faq-refund", 4)
    admin.hset(state_key, "occurred_time", "2026-07-21T12:00:05Z")
    assert cache.lookup(raw_query) is None
    admin.hset(state_key, "occurred_time", "2026-07-21T12:00:04Z")
    assert cache.populate_mapping(raw_query, "faq-refund", 4)
    admin.hset(state_key, "index_version", "knowledge_docs_v9")
    admin.hset(answer_v4, "index_version", "knowledge_docs_v9")
    assert cache.lookup(raw_query) is None
    admin.hset(state_key, "index_version", "knowledge_docs_v1")
    admin.hset(answer_v4, "index_version", "knowledge_docs_v1")
    assert cache.populate_mapping(raw_query, "faq-refund", 4)
    admin.hset(answer_v4, "unexpected", "field")
    assert cache.lookup(raw_query) is None
    admin.hdel(answer_v4, "unexpected")

    assert cache.populate_mapping(raw_query, "faq-refund", 4)
    admin.pexpire(mapping_key, 600_000)
    admin.pexpire(state_key, 1_800_000)
    admin.pexpire(answer_v4, 1_800_000)
    assert cache.lookup(raw_query) is None
    assert not admin.exists(mapping_key)

    bad_cache = RedisFaqCache(local_url("agent_cache", "wrong-password", port))
    assert bad_cache.lookup(raw_query) is None
    assert not bad_cache.populate_mapping(raw_query, "faq-refund", 4)
    bad_projection = RedisFaqCacheProjection.from_url(
        local_url("knowledge_indexer", "wrong-password", port)
    )
    try:
        bad_projection.apply(event(5), "knowledge_docs_v1")
    except KnowledgeSyncError as error:
        assert str(error) == "support_cache_unavailable"
    else:
        raise AssertionError("invalid cache credentials unexpectedly mutated state")

    require_denied(lambda: agent_client.hset(state_key, mapping={"source_version": "999"}))
    require_denied(lambda: agent_client.config_get("requirepass"))
    require_denied(lambda: indexer_client.hgetall(mapping_key))
    require_denied(lambda: indexer_client.set("unrelated:key", "value"))

    all_values: list[str] = []
    for key in admin.scan_iter(match=f"{FAQ_CACHE_PREFIX}*"):
        all_values.extend(cast(dict[str, str], admin.hgetall(key)).values())
    assert raw_query.strip() not in all_values
    for secret_name in (
        "REDIS_SUPPORT_PASSWORD",
        "REDIS_AGENT_CACHE_PASSWORD",
        "REDIS_INDEXER_CACHE_PASSWORD",
    ):
        assert values[secret_name] not in all_values
    state = cast(dict[str, str], admin.hgetall(state_key))
    assert set(state) == {
        "schema",
        "source_id",
        "source_version",
        "tombstone",
        "commitment",
        "index_version",
        "event_id",
        "occurred_time",
        "ready",
        "cache_commitment",
        "lease_deadline_ms",
    }
    return {
        "aclDenials": 4,
        "concurrentSourceSerialization": True,
        "currentVersion": int(state["source_version"]),
        "expiredPreparationTakeover": True,
        "inFlightStateEvictionSafe": True,
        "preparationPhysicalExpiry": True,
        "normalizationStable": True,
        "rawQueryStored": False,
        "tombstoneFenced": True,
        "ttlBounds": "mapping<=300000;state,answer<=900000",
    }


def outage(port: int, values: dict[str, str]) -> dict[str, object]:
    cache = RedisFaqCache(
        local_url("agent_cache", values["REDIS_AGENT_CACHE_PASSWORD"], port),
        socket_timeout_seconds=0.1,
    )
    assert cache.lookup("how do refunds work?") is None
    assert not cache.populate_mapping("how do refunds work?", "faq-refund", 4)
    return {"lookup": "miss", "population": "skipped"}


def restart(port: int, values: dict[str, str]) -> dict[str, object]:
    cache = RedisFaqCache(local_url("agent_cache", values["REDIS_AGENT_CACHE_PASSWORD"], port))
    projection = RedisFaqCacheProjection.from_url(
        local_url("knowledge_indexer", values["REDIS_INDEXER_CACHE_PASSWORD"], port)
    )
    assert cache.lookup("how do refunds work?") is None
    assert projection.apply(event(4), "knowledge_docs_v1") is ProjectionOutcome.APPLIED
    assert cache.populate_mapping("how do refunds work?", "faq-refund", 4)
    hit = cache.lookup("how do refunds work?")
    assert hit is not None and hit.results[0].source_version == 4
    return {"coldMiss": True, "replayedProjection": True, "recoveredHit": True}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--redis-port", required=True, type=int)
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--mode", choices=("normal", "outage", "restart"), default="normal")
    args = parser.parse_args()
    values = parse_env(Path(args.env_file))
    if args.mode == "normal":
        evidence = normal(args.redis_port, values)
    elif args.mode == "restart":
        evidence = restart(args.redis_port, values)
    else:
        evidence = outage(args.redis_port, values)
    print(
        json.dumps(
            {"event": "cb112-faq-cache-evidence", "mode": args.mode, **evidence},
            separators=(",", ":"),
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
