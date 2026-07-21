"""Fail-closed version-aware FAQ acceleration over Support Redis."""

from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from typing import Protocol, cast
from uuid import UUID

from redis import Redis
from redis.exceptions import RedisError

from .knowledge import (
    RRF_CONSTANT,
    KnowledgeSearchOutput,
    KnowledgeSearchResult,
    PublicKnowledgeMetadata,
)

FAQ_CACHE_SCHEMA = "cb112-v1"
FAQ_CACHE_PREFIX = "cb:faq:v1:"
QUERY_MAPPING_TTL_MS = 300_000
MAX_QUERY_CODE_UNITS = 512
MAX_QUERY_BYTES = 2_048
MAX_SOURCE_VERSION = 9_223_372_036_854_775_807

_SOURCE_ID = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")
_INDEX_VERSION = re.compile(r"^knowledge_docs_v[1-9][0-9]*$")
_MAPPING_FIELDS = {"schema", "query_hash", "source_id", "source_version"}
_STATE_FIELDS = {
    "schema",
    "source_id",
    "source_version",
    "tombstone",
    "commitment",
    "index_version",
    "event_id",
    "occurred_time",
}
_ANSWER_FIELDS = {
    "schema",
    "source_id",
    "source_version",
    "doc_type",
    "chunk_id",
    "title",
    "answer",
    "index_version",
    "commitment",
    "public_category",
    "public_language",
}

_LOOKUP_SCRIPT = r"""
local mapping = KEYS[1]
local prefix = ARGV[1]
local expected_hash = ARGV[2]

if redis.call('EXISTS', mapping) == 0 then
  return {}
end
if redis.call('HLEN', mapping) ~= 4 then
  redis.call('DEL', mapping)
  return {}
end
local schema = redis.call('HGET', mapping, 'schema')
local query_hash = redis.call('HGET', mapping, 'query_hash')
local source_id = redis.call('HGET', mapping, 'source_id')
local source_version = redis.call('HGET', mapping, 'source_version')
if schema ~= ARGV[3] or query_hash ~= expected_hash or not source_id or not source_version then
  redis.call('DEL', mapping)
  return {}
end

local state = prefix .. 'state:' .. source_id
local answer = prefix .. 'answer:' .. source_id .. ':' .. source_version
if redis.call('HLEN', state) ~= 8 or redis.call('HLEN', answer) ~= 11 then
  redis.call('DEL', mapping)
  return {}
end
if redis.call('HGET', state, 'schema') ~= ARGV[3]
  or redis.call('HGET', state, 'source_id') ~= source_id
  or redis.call('HGET', state, 'source_version') ~= source_version
  or redis.call('HGET', state, 'tombstone') ~= '0'
  or redis.call('HGET', answer, 'schema') ~= ARGV[3]
  or redis.call('HGET', answer, 'source_id') ~= source_id
  or redis.call('HGET', answer, 'source_version') ~= source_version
  or redis.call('HGET', answer, 'commitment') ~= redis.call('HGET', state, 'commitment') then
  redis.call('DEL', mapping)
  return {}
end
local mapping_ttl = redis.call('PTTL', mapping)
local state_ttl = redis.call('PTTL', state)
local answer_ttl = redis.call('PTTL', answer)
if mapping_ttl <= 0 or state_ttl <= 0 or answer_ttl <= 0
  or mapping_ttl > state_ttl or mapping_ttl > answer_ttl then
  redis.call('DEL', mapping)
  return {}
end
return {redis.call('HGETALL', state), redis.call('HGETALL', answer)}
"""

_POPULATE_SCRIPT = r"""
local mapping = KEYS[1]
local schema = ARGV[1]
local query_hash = ARGV[2]
local source_id = ARGV[3]
local source_version = ARGV[4]
local max_ttl = tonumber(ARGV[5])
local prefix = ARGV[6]
local state = prefix .. 'state:' .. source_id
local answer = prefix .. 'answer:' .. source_id .. ':' .. source_version

if redis.call('HLEN', state) ~= 8 or redis.call('HLEN', answer) ~= 11 then
  return 0
end
if redis.call('HGET', state, 'schema') ~= schema
  or redis.call('HGET', state, 'source_id') ~= source_id
  or redis.call('HGET', state, 'source_version') ~= source_version
  or redis.call('HGET', state, 'tombstone') ~= '0'
  or redis.call('HGET', answer, 'schema') ~= schema
  or redis.call('HGET', answer, 'source_id') ~= source_id
  or redis.call('HGET', answer, 'source_version') ~= source_version
  or redis.call('HGET', answer, 'commitment') ~= redis.call('HGET', state, 'commitment') then
  return 0
end
local state_ttl = redis.call('PTTL', state)
local answer_ttl = redis.call('PTTL', answer)
if state_ttl <= 0 or answer_ttl <= 0 then
  return 0
end
local ttl = math.min(max_ttl, state_ttl, answer_ttl)
if ttl <= 0 then
  return 0
end
redis.call('DEL', mapping)
redis.call('HSET', mapping,
  'schema', schema,
  'query_hash', query_hash,
  'source_id', source_id,
  'source_version', source_version)
redis.call('PEXPIRE', mapping, ttl)
return 1
"""


class FaqCache(Protocol):
    def lookup(self, public_query: str) -> KnowledgeSearchOutput | None: ...

    def populate_mapping(self, public_query: str, source_id: str, source_version: int) -> bool: ...


class RedisFaqCache:
    def __init__(
        self,
        url: str,
        *,
        socket_timeout_seconds: float = 0.2,
        client: Redis | None = None,
    ) -> None:
        if not url.startswith(("redis://", "rediss://")) or socket_timeout_seconds <= 0:
            raise ValueError("FAQ cache configuration is incomplete")
        self._client = client or Redis.from_url(
            url,
            decode_responses=True,
            socket_connect_timeout=socket_timeout_seconds,
            socket_timeout=socket_timeout_seconds,
            retry_on_timeout=False,
        )

    def lookup(self, public_query: str) -> KnowledgeSearchOutput | None:
        query_hash = normalized_query_hash(public_query)
        if query_hash is None:
            return None
        mapping_key = f"{FAQ_CACHE_PREFIX}query:{query_hash}"
        try:
            raw = self._client.eval(
                _LOOKUP_SCRIPT,
                1,
                mapping_key,
                FAQ_CACHE_PREFIX,
                query_hash,
                FAQ_CACHE_SCHEMA,
            )
            snapshot = _cache_snapshot(raw)
            if snapshot is None:
                return None
            state, answer = snapshot
            return _knowledge_output(state, answer)
        except (RedisError, OSError, UnicodeError, ValueError, TypeError):
            self._delete_quietly(mapping_key)
            return None

    def populate_mapping(self, public_query: str, source_id: str, source_version: int) -> bool:
        query_hash = normalized_query_hash(public_query)
        if (
            query_hash is None
            or _SOURCE_ID.fullmatch(source_id) is None
            or type(source_version) is not int
            or source_version < 1
            or source_version > MAX_SOURCE_VERSION
        ):
            return False
        mapping_key = f"{FAQ_CACHE_PREFIX}query:{query_hash}"
        try:
            result = self._client.eval(
                _POPULATE_SCRIPT,
                1,
                mapping_key,
                FAQ_CACHE_SCHEMA,
                query_hash,
                source_id,
                str(source_version),
                str(QUERY_MAPPING_TTL_MS),
                FAQ_CACHE_PREFIX,
            )
        except (RedisError, OSError, ValueError, TypeError):
            return False
        return isinstance(result, int) and result == 1

    def _delete_quietly(self, key: str) -> None:
        try:
            self._client.delete(key)
        except (RedisError, OSError):
            return


def normalized_query_hash(value: str) -> str | None:
    if not isinstance(value, str):
        return None
    normalized = " ".join(unicodedata.normalize("NFKC", value).casefold().split())
    if not normalized or any(unicodedata.category(character) == "Cc" for character in normalized):
        return None
    try:
        code_units = len(normalized.encode("utf-16-le")) // 2
        encoded = normalized.encode("utf-8")
    except UnicodeEncodeError:
        return None
    if code_units > MAX_QUERY_CODE_UNITS or len(encoded) > MAX_QUERY_BYTES:
        return None
    return hashlib.sha256(encoded).hexdigest()


def _flat_hash(value: object, expected_fields: set[str]) -> dict[str, str] | None:
    if not isinstance(value, list) or not value or len(value) % 2 != 0:
        return None
    if not all(isinstance(item, str) for item in value):
        return None
    result = dict(zip(value[::2], value[1::2], strict=True))
    if len(result) != len(value) // 2 or set(result) != expected_fields:
        return None
    return cast(dict[str, str], result)


def _cache_snapshot(value: object) -> tuple[dict[str, str], dict[str, str]] | None:
    if not isinstance(value, list) or len(value) != 2:
        return None
    state = _flat_hash(value[0], _STATE_FIELDS)
    answer = _flat_hash(value[1], _ANSWER_FIELDS)
    if state is None or answer is None:
        return None
    return state, answer


def _knowledge_output(state: dict[str, str], answer: dict[str, str]) -> KnowledgeSearchOutput:
    source_id = answer["source_id"]
    source_version_text = answer["source_version"]
    index_version = answer["index_version"]
    title = answer["title"]
    content = answer["answer"]
    if (
        answer["schema"] != FAQ_CACHE_SCHEMA
        or state["schema"] != FAQ_CACHE_SCHEMA
        or state["source_id"] != source_id
        or state["source_version"] != source_version_text
        or state["tombstone"] != "0"
        or state["index_version"] != index_version
        or state["commitment"] != answer["commitment"]
        or answer["doc_type"] != "faq"
        or answer["chunk_id"] != "answer"
        or answer["public_category"] != "faq"
        or answer["public_language"] != "und"
        or _SOURCE_ID.fullmatch(source_id) is None
        or not source_version_text.isdecimal()
        or _INDEX_VERSION.fullmatch(index_version) is None
        or not _canonical_uuid(state["event_id"])
        or not state["occurred_time"].endswith("Z")
        or not _bounded_text(title, 500)
        or not _bounded_text(content, 4000)
    ):
        raise ValueError("Malformed FAQ cache entry")
    source_version = int(source_version_text)
    if source_version < 1 or source_version > MAX_SOURCE_VERSION:
        raise ValueError("Malformed FAQ cache version")
    canonical = json.dumps(
        {
            "content": {"answer": content, "question": title},
            "eventId": state["event_id"],
            "occurredTime": state["occurred_time"],
            "publicationState": "PUBLISHED",
            "sourceId": source_id,
            "sourceType": "faq",
            "sourceVersion": source_version,
            "tombstone": False,
        },
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    if not hashlib.sha256(canonical).hexdigest() == state["commitment"]:
        raise ValueError("Malformed FAQ cache commitment")
    return KnowledgeSearchOutput(
        index_version=index_version,
        results=(
            KnowledgeSearchResult(
                source_id=source_id,
                source_version=source_version,
                chunk_id="answer",
                doc_type="faq",
                title=title,
                excerpt=content[:600],
                public_metadata=PublicKnowledgeMetadata(category="faq", language="und"),
                rank=1,
                rrf_score=1 / (RRF_CONSTANT + 1),
            ),
        ),
    )


def _bounded_text(value: str, maximum: int) -> bool:
    if not value.strip():
        return False
    try:
        return len(value.encode("utf-16-le")) // 2 <= maximum
    except UnicodeEncodeError:
        return False


def _canonical_uuid(value: str) -> bool:
    try:
        return str(UUID(value)) == value
    except ValueError:
        return False
