"""Monotonic FAQ answer/current-version projection in Support Redis."""

from __future__ import annotations

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

_APPLY_SCRIPT = r"""
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
if current_version then
  if redis.call('HLEN', state) ~= 8 or not valid_version(current_version) then
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
      or redis.call('HGET', state, 'index_version') ~= index_version
      or redis.call('HGET', state, 'event_id') ~= event_id
      or redis.call('HGET', state, 'occurred_time') ~= ARGV[11] then
      return {'error', 'conflicting_source_version'}
    end
    if tombstone == '1' then
      redis.call('DEL', answer)
    else
      if redis.call('EXISTS', answer) == 0 then
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
          'public_category', 'faq',
          'public_language', 'und')
      elseif redis.call('HLEN', answer) ~= 11
        or redis.call('HGET', answer, 'schema') ~= schema
        or redis.call('HGET', answer, 'source_id') ~= source_id
        or redis.call('HGET', answer, 'source_version') ~= source_version
        or redis.call('HGET', answer, 'doc_type') ~= 'faq'
        or redis.call('HGET', answer, 'chunk_id') ~= 'answer'
        or redis.call('HGET', answer, 'title') ~= title
        or redis.call('HGET', answer, 'answer') ~= content
        or redis.call('HGET', answer, 'index_version') ~= index_version
        or redis.call('HGET', answer, 'commitment') ~= commitment
        or redis.call('HGET', answer, 'public_category') ~= 'faq'
        or redis.call('HGET', answer, 'public_language') ~= 'und' then
        return {'error', 'conflicting_cache_entry'}
      end
      redis.call('PEXPIRE', answer, ttl)
    end
    redis.call('PEXPIRE', state, ttl)
    return {'replayed', source_version}
  end
  local old_answer = KEYS[3] .. current_version
  redis.call('DEL', old_answer)
else
  redis.call('DEL', answer)
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
  'occurred_time', ARGV[11])
redis.call('PEXPIRE', state, ttl)
if tombstone == '1' then
  redis.call('DEL', answer)
else
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
    'public_category', 'faq',
    'public_language', 'und')
  redis.call('PEXPIRE', answer, ttl)
end
return {'applied', source_version}
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

    def apply(self, event: FaqKnowledgeEvent, index_version: str) -> ProjectionOutcome:
        state_key = f"{FAQ_CACHE_PREFIX}state:{event.source_id}"
        answer_prefix = f"{FAQ_CACHE_PREFIX}answer:{event.source_id}:"
        answer_key = f"{answer_prefix}{event.source_version}"
        try:
            raw = self.client.eval(
                _APPLY_SCRIPT,
                3,
                state_key,
                answer_key,
                answer_prefix,
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
            )
        except (RedisError, OSError, ValueError, TypeError) as error:
            raise KnowledgeSyncError("support_cache_unavailable") from error
        if (
            not isinstance(raw, list)
            or len(raw) != 2
            or not all(isinstance(item, str) for item in raw)
        ):
            raise KnowledgeSyncError("malformed_support_cache_response")
        outcome, detail = raw
        if outcome == "error":
            raise KnowledgeSyncConflict(detail)
        try:
            return ProjectionOutcome(outcome)
        except ValueError as error:
            raise KnowledgeSyncError("malformed_support_cache_response") from error
