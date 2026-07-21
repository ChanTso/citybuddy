#!/usr/bin/env python3
"""Real Support Redis evidence for the CB-112 two-level FAQ cache."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, cast

from citybuddy_agent.faq_cache import RedisFaqCache, normalized_query_hash
from citybuddy_indexer.faq_cache import (
    FAQ_CACHE_PREFIX,
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
    assert cache.lookup(raw_query) is None
    projection.abort(event(5))
    assert cache.populate_mapping(raw_query, "faq-refund", 4)
    assert cache.lookup(raw_query) is not None
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
    }
    return {
        "aclDenials": 4,
        "currentVersion": int(state["source_version"]),
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
