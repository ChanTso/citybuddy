"""Monotonic FAQ answer/current-version projection in Support Redis."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass

from redis import Redis
from redis.exceptions import RedisError

from .incremental import (
    FaqKnowledgeEvent,
    KnowledgeSyncConflict,
    KnowledgeSyncError,
    ProjectionOutcome,
)

FAQ_CACHE_SCHEMA = "cb112-v1"
FAQ_CACHE_PREFIX = "cb:faq:v1:"
FAQ_ENTRY_TTL_MS = 900_000
FAQ_PREPARATION_LEASE_MS = 60_000

_PREPARE_SCRIPT = r"""
local state = KEYS[1]
local answer_prefix = KEYS[2]
local schema = ARGV[1]
local source_id = ARGV[2]
local source_version = ARGV[3]
local tombstone = ARGV[4]
local commitment = ARGV[5]
local event_id = ARGV[6]
local occurred_time = ARGV[7]
local lease_ms = tonumber(ARGV[8])
local redis_time = redis.call('TIME')
local now_ms = tonumber(redis_time[1]) * 1000 + math.floor(tonumber(redis_time[2]) / 1000)
local lease_deadline_ms = tostring(now_ms + lease_ms)

local function valid_version(value)
  if not value or not string.match(value, '^[1-9][0-9]*$') or string.len(value) > 19 then
    return false
  end
  if string.len(value) == 19 and value > '9223372036854775807' then
    return false
  end
  return true
end

local function compare_version(left, right)
  if string.len(left) < string.len(right) then return -1 end
  if string.len(left) > string.len(right) then return 1 end
  if left < right then return -1 end
  if left > right then return 1 end
  return 0
end

if not valid_version(source_version) or (tombstone ~= '0' and tombstone ~= '1') then
  return {'error', 'invalid_input'}
end

local current_version = redis.call('HGET', state, 'source_version')
local replay = false
if current_version then
  if redis.call('HLEN', state) ~= 11 or not valid_version(current_version)
    or redis.call('HGET', state, 'schema') ~= schema
    or redis.call('HGET', state, 'source_id') ~= source_id then
    return {'error', 'malformed_state'}
  end
  local ready = redis.call('HGET', state, 'ready')
  if ready == '0' then
    local current_deadline = redis.call('HGET', state, 'lease_deadline_ms')
    if not current_deadline or not string.match(current_deadline, '^[1-9][0-9]*$') then
      return {'error', 'malformed_state'}
    end
    if current_version == source_version
      and redis.call('HGET', state, 'tombstone') == tombstone
      and redis.call('HGET', state, 'commitment') == commitment
      and redis.call('HGET', state, 'event_id') == event_id
      and redis.call('HGET', state, 'occurred_time') == occurred_time
      and redis.call('HGET', state, 'index_version') == ''
      and redis.call('HGET', state, 'cache_commitment') == '' then
      redis.call('HSET', state, 'lease_deadline_ms', lease_deadline_ms)
      redis.call('PERSIST', state)
      return {'prepared', source_version}
    end
    if tonumber(current_deadline) > now_ms then
      return {'busy', current_version}
    end
  end
  if ready ~= '1' and ready ~= '0' then
    return {'error', 'malformed_state'}
  end
  if ready == '1' then
    if redis.call('HGET', state, 'lease_deadline_ms') ~= '' then
      return {'error', 'malformed_state'}
    end
    local comparison = compare_version(current_version, source_version)
    if comparison > 0 then
      return {'stale', current_version}
    end
    if comparison == 0 then
      if redis.call('HGET', state, 'schema') ~= schema
        or redis.call('HGET', state, 'source_id') ~= source_id
        or redis.call('HGET', state, 'tombstone') ~= tombstone
        or redis.call('HGET', state, 'commitment') ~= commitment
        or redis.call('HGET', state, 'event_id') ~= event_id
        or redis.call('HGET', state, 'occurred_time') ~= occurred_time then
        return {'error', 'conflicting_source_version'}
      end
      replay = true
    end
    redis.call('DEL', answer_prefix .. current_version)
  end
end

redis.call('DEL', state)
redis.call('HSET', state,
  'schema', schema,
  'source_id', source_id,
  'source_version', source_version,
  'tombstone', tombstone,
  'commitment', commitment,
  'index_version', '',
  'event_id', event_id,
  'occurred_time', occurred_time,
  'ready', '0',
  'cache_commitment', '',
  'lease_deadline_ms', lease_deadline_ms)
redis.call('PERSIST', state)
if replay then
  return {'replay_prepared', source_version}
end
return {'prepared', source_version}
"""

_FINALIZE_SCRIPT = r"""
local state = KEYS[1]
local answer = KEYS[2]
local schema = ARGV[1]
local source_id = ARGV[2]
local source_version = ARGV[3]
local tombstone = ARGV[4]
local commitment = ARGV[5]
local index_version = ARGV[6]
local event_id = ARGV[7]
local title = ARGV[8]
local content = ARGV[9]
local ttl = tonumber(ARGV[10])
local cache_commitment = ARGV[12]

local function valid_version(value)
  if not value or not string.match(value, '^[1-9][0-9]*$') or string.len(value) > 19 then
    return false
  end
  if string.len(value) == 19 and value > '9223372036854775807' then
    return false
  end
  return true
end

local function compare_version(left, right)
  if string.len(left) < string.len(right) then return -1 end
  if string.len(left) > string.len(right) then return 1 end
  if left < right then return -1 end
  if left > right then return 1 end
  return 0
end

if not valid_version(source_version) or (tombstone ~= '0' and tombstone ~= '1') then
  return {'error', 'invalid_input'}
end

local current_version = redis.call('HGET', state, 'source_version')
if not current_version then
  return {'retry', 'cache_preparation_missing'}
end
if redis.call('HLEN', state) ~= 11 or not valid_version(current_version) then
  return {'error', 'malformed_state'}
end
if redis.call('HGET', state, 'ready') ~= '0' then
  return {'retry', 'cache_preparation_missing'}
end
if current_version ~= source_version
  or redis.call('HGET', state, 'schema') ~= schema
  or redis.call('HGET', state, 'source_id') ~= source_id
  or redis.call('HGET', state, 'tombstone') ~= tombstone
  or redis.call('HGET', state, 'commitment') ~= commitment
  or redis.call('HGET', state, 'index_version') ~= ''
  or redis.call('HGET', state, 'event_id') ~= event_id
  or redis.call('HGET', state, 'occurred_time') ~= ARGV[11]
  or redis.call('HGET', state, 'cache_commitment') ~= ''
  or not string.match(redis.call('HGET', state, 'lease_deadline_ms') or '', '^[1-9][0-9]*$') then
  return {'retry', 'cache_source_busy'}
end

redis.call('DEL', state)
redis.call('HSET', state,
  'schema', schema,
  'source_id', source_id,
  'source_version', source_version,
  'tombstone', tombstone,
  'commitment', commitment,
  'index_version', index_version,
  'event_id', event_id,
  'occurred_time', ARGV[11],
  'ready', '1',
  'cache_commitment', cache_commitment,
  'lease_deadline_ms', '')

if tombstone == '1' then
  redis.call('DEL', answer)
else
  redis.call('DEL', answer)
  redis.call('HSET', answer,
    'schema', schema,
    'source_id', source_id,
    'source_version', source_version,
    'doc_type', 'faq',
    'chunk_id', 'answer',
    'title', title,
    'answer', content,
    'index_version', index_version,
    'commitment', commitment,
    'cache_commitment', cache_commitment,
    'public_category', 'faq',
    'public_language', 'und')
  redis.call('PEXPIRE', answer, ttl)
end
redis.call('PEXPIRE', state, ttl)
return {'applied', source_version}
"""

_ABORT_SCRIPT = r"""
local state = KEYS[1]
if redis.call('HLEN', state) == 11
  and redis.call('HGET', state, 'schema') == ARGV[1]
  and redis.call('HGET', state, 'source_id') == ARGV[2]
  and redis.call('HGET', state, 'source_version') == ARGV[3]
  and redis.call('HGET', state, 'tombstone') == ARGV[4]
  and redis.call('HGET', state, 'commitment') == ARGV[5]
  and redis.call('HGET', state, 'index_version') == ''
  and redis.call('HGET', state, 'event_id') == ARGV[6]
  and redis.call('HGET', state, 'occurred_time') == ARGV[7]
  and redis.call('HGET', state, 'ready') == '0'
  and redis.call('HGET', state, 'cache_commitment') == ''
  and string.match(redis.call('HGET', state, 'lease_deadline_ms') or '', '^[1-9][0-9]*$') then
  redis.call('DEL', state)
  return 1
end
return 0
"""


@dataclass(frozen=True)
class RedisFaqCacheProjection:
    client: Redis

    @classmethod
    def from_url(cls, url: str, *, socket_timeout_seconds: float = 1.0) -> RedisFaqCacheProjection:
        if not url.startswith(("redis://", "rediss://")) or socket_timeout_seconds <= 0:
            raise ValueError("FAQ cache projection configuration is incomplete")
        return cls(
            Redis.from_url(
                url,
                decode_responses=True,
                socket_connect_timeout=socket_timeout_seconds,
                socket_timeout=socket_timeout_seconds,
                retry_on_timeout=False,
            )
        )

    def prepare(self, event: FaqKnowledgeEvent) -> ProjectionOutcome | None:
        state_key = f"{FAQ_CACHE_PREFIX}state:{event.source_id}"
        answer_prefix = f"{FAQ_CACHE_PREFIX}answer:{event.source_id}:"
        raw = self._eval(
            _PREPARE_SCRIPT,
            2,
            state_key,
            answer_prefix,
            FAQ_CACHE_SCHEMA,
            event.source_id,
            str(event.source_version),
            "1" if event.tombstone else "0",
            event.commitment,
            event.event_id,
            event.occurred_time,
            str(FAQ_PREPARATION_LEASE_MS),
        )
        outcome, detail = self._closed_result(raw)
        if outcome == "error":
            raise KnowledgeSyncConflict(detail)
        if outcome == "busy":
            raise KnowledgeSyncError("cache_source_busy")
        if outcome == "prepared":
            return None
        if outcome == "replay_prepared":
            return ProjectionOutcome.REPLAYED
        try:
            return ProjectionOutcome(outcome)
        except ValueError as error:
            raise KnowledgeSyncError("malformed_support_cache_response") from error

    def finalize(self, event: FaqKnowledgeEvent, index_version: str) -> ProjectionOutcome:
        state_key = f"{FAQ_CACHE_PREFIX}state:{event.source_id}"
        answer_prefix = f"{FAQ_CACHE_PREFIX}answer:{event.source_id}:"
        answer_key = f"{answer_prefix}{event.source_version}"
        raw = self._eval(
            _FINALIZE_SCRIPT,
            2,
            state_key,
            answer_key,
            FAQ_CACHE_SCHEMA,
            event.source_id,
            str(event.source_version),
            "1" if event.tombstone else "0",
            event.commitment,
            index_version,
            event.event_id,
            event.content.question,
            event.content.answer,
            str(FAQ_ENTRY_TTL_MS),
            event.occurred_time,
            _cache_commitment(event.commitment, index_version),
        )
        outcome, detail = self._closed_result(raw)
        if outcome == "error":
            raise KnowledgeSyncConflict(detail)
        if outcome == "retry":
            raise KnowledgeSyncError(detail)
        try:
            return ProjectionOutcome(outcome)
        except ValueError as error:
            raise KnowledgeSyncError("malformed_support_cache_response") from error

    def abort(self, event: FaqKnowledgeEvent) -> None:
        state_key = f"{FAQ_CACHE_PREFIX}state:{event.source_id}"
        self._eval(
            _ABORT_SCRIPT,
            1,
            state_key,
            FAQ_CACHE_SCHEMA,
            event.source_id,
            str(event.source_version),
            "1" if event.tombstone else "0",
            event.commitment,
            event.event_id,
            event.occurred_time,
        )

    def apply(self, event: FaqKnowledgeEvent, index_version: str) -> ProjectionOutcome:
        prepared = self.prepare(event)
        if prepared is ProjectionOutcome.STALE:
            return prepared
        finalized = self.finalize(event, index_version)
        if prepared is ProjectionOutcome.REPLAYED:
            return prepared
        return finalized

    def _eval(self, script: str, key_count: int, *values: str) -> object:
        try:
            return self.client.eval(script, key_count, *values)
        except (RedisError, OSError, ValueError, TypeError) as error:
            raise KnowledgeSyncError("support_cache_unavailable") from error

    @staticmethod
    def _closed_result(raw: object) -> tuple[str, str]:
        if (
            not isinstance(raw, list)
            or len(raw) != 2
            or not all(isinstance(item, str) for item in raw)
        ):
            raise KnowledgeSyncError("malformed_support_cache_response")
        return raw[0], raw[1]


def _cache_commitment(event_commitment: str, index_version: str) -> str:
    canonical = json.dumps(
        {"eventCommitment": event_commitment, "indexVersion": index_version},
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()
