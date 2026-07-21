"""Monotonic FAQ answer/current-version projection in Support Redis."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from enum import Enum

from redis import Redis
from redis.exceptions import RedisError

from .incremental import (
    FaqKnowledgeEvent,
    KnowledgeSyncError,
    ProjectionOutcome,
)

FAQ_CACHE_SCHEMA = "cb112-v1"
FAQ_CACHE_PREFIX = "cb:faq:v1:"
FAQ_ENTRY_TTL_MS = 900_000
FAQ_PREPARATION_LEASE_MS = 60_000
FAQ_PREPARATION_TTL_SAFETY_MS = 60_000
FAQ_PREPARATION_TTL_MS = FAQ_PREPARATION_LEASE_MS + FAQ_PREPARATION_TTL_SAFETY_MS


class CachePreparation(str, Enum):
    PREPARED = "prepared"
    REPLAY_PREPARED = "replay_prepared"
    STALE = "stale"
    AUTHORITY_REQUIRED = "authority_required"


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
local preparation_ttl = tonumber(ARGV[9])
local ready_ttl = tonumber(ARGV[10])
local authority_confirmed = ARGV[11] == '1'
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

if not valid_version(source_version) or (tombstone ~= '0' and tombstone ~= '1')
  or not lease_ms or not preparation_ttl or not ready_ttl
  or lease_ms <= 0 or preparation_ttl <= lease_ms or ready_ttl <= 0 then
  return {'retry', 'invalid_input'}
end

local state_exists = redis.call('EXISTS', state) == 1
local current_version = redis.call('HGET', state, 'source_version')
local ready = redis.call('HGET', state, 'ready')
local replay = false
local repaired = state_exists and not current_version
if repaired and ready == '1' and not authority_confirmed then
  return {'authority_required', 'ready_state_missing_version'}
end
if current_version then
  local state_ttl = redis.call('PTTL', state)
  if redis.call('HLEN', state) ~= 11 or not valid_version(current_version)
    or redis.call('HGET', state, 'schema') ~= schema
    or redis.call('HGET', state, 'source_id') ~= source_id
    or state_ttl <= 0 then
    if ready == '1' and not authority_confirmed then
      return {'authority_required', 'ready_state_invalid_identity'}
    end
    current_version = false
    repaired = true
  end
end
if current_version then
  if ready == '0' then
    local current_deadline = redis.call('HGET', state, 'lease_deadline_ms')
    local state_ttl = redis.call('PTTL', state)
    if not current_deadline or not string.match(current_deadline, '^[1-9][0-9]*$')
      or state_ttl > preparation_ttl
      or state_ttl <= tonumber(current_deadline) - now_ms
      or (redis.call('HGET', state, 'tombstone') ~= '0'
        and redis.call('HGET', state, 'tombstone') ~= '1')
      or redis.call('HGET', state, 'index_version') ~= ''
      or redis.call('HGET', state, 'cache_commitment') ~= '' then
      current_version = false
      repaired = true
    end
  end
  if current_version and ready == '0' then
    local current_deadline = redis.call('HGET', state, 'lease_deadline_ms')
    if current_version == source_version
      and redis.call('HGET', state, 'tombstone') == tombstone
      and redis.call('HGET', state, 'commitment') == commitment
      and redis.call('HGET', state, 'event_id') == event_id
      and redis.call('HGET', state, 'occurred_time') == occurred_time
      and redis.call('HGET', state, 'index_version') == ''
      and redis.call('HGET', state, 'cache_commitment') == '' then
      redis.call('HSET', state, 'lease_deadline_ms', lease_deadline_ms)
      redis.call('PEXPIRE', state, preparation_ttl)
      return {'prepared', 'current_preparation_renewed'}
    end
    if tonumber(current_deadline) > now_ms then
      return {'busy', 'active_preparation_owner'}
    end
  end
  if current_version and ready ~= '1' and ready ~= '0' then
    current_version = false
    repaired = true
  end
  if current_version and ready == '1' then
    local state_ttl = redis.call('PTTL', state)
    local current_tombstone = redis.call('HGET', state, 'tombstone')
    local current_answer = answer_prefix .. current_version
    local answer_valid = false
    if current_tombstone == '1' then
      answer_valid = redis.call('EXISTS', current_answer) == 0
    elseif current_tombstone == '0' then
      local answer_ttl = redis.call('PTTL', current_answer)
      answer_valid = redis.call('HLEN', current_answer) == 12
        and answer_ttl > 0 and answer_ttl <= ready_ttl
        and redis.call('HGET', current_answer, 'schema') == schema
        and redis.call('HGET', current_answer, 'source_id') == source_id
        and redis.call('HGET', current_answer, 'source_version') == current_version
        and redis.call('HGET', current_answer, 'doc_type') == 'faq'
        and redis.call('HGET', current_answer, 'chunk_id') == 'answer'
        and redis.call('HGET', current_answer, 'title') ~= false
        and redis.call('HGET', current_answer, 'answer') ~= false
        and redis.call('HGET', current_answer, 'index_version')
          == redis.call('HGET', state, 'index_version')
        and redis.call('HGET', current_answer, 'commitment')
          == redis.call('HGET', state, 'commitment')
        and redis.call('HGET', current_answer, 'cache_commitment')
          == redis.call('HGET', state, 'cache_commitment')
        and redis.call('HGET', current_answer, 'public_category') == 'faq'
        and redis.call('HGET', current_answer, 'public_language') == 'und'
    end
    if redis.call('HGET', state, 'lease_deadline_ms') ~= ''
      or state_ttl > ready_ttl
      or (current_tombstone ~= '0' and current_tombstone ~= '1')
      or redis.call('HGET', state, 'index_version') == ''
      or redis.call('HGET', state, 'cache_commitment') == ''
      or not answer_valid then
      if not authority_confirmed then
        return {'authority_required', 'ready_state_invalid_projection'}
      end
      current_version = false
      repaired = true
    end
  end
  if current_version and ready == '1' then
    local comparison = compare_version(current_version, source_version)
    if comparison > 0 then
      return {'stale', 'ready_state_newer'}
    end
    if comparison == 0 then
      if redis.call('HGET', state, 'schema') ~= schema
        or redis.call('HGET', state, 'source_id') ~= source_id
        or redis.call('HGET', state, 'tombstone') ~= tombstone
        or redis.call('HGET', state, 'commitment') ~= commitment
        or redis.call('HGET', state, 'event_id') ~= event_id
        or redis.call('HGET', state, 'occurred_time') ~= occurred_time then
        if not authority_confirmed then
          return {'authority_required', 'ready_state_contradiction'}
        end
        repaired = true
      else
        replay = true
      end
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
redis.call('PEXPIRE', state, preparation_ttl)
if repaired and not authority_confirmed then
  return {'retry', 'malformed_preparation_repaired'}
end
if repaired then
  return {'prepared', 'authoritative_repair_prepared'}
end
if replay then
  return {'replay_prepared', 'ready_replay_prepared'}
end
return {'prepared', 'new_preparation'}
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

if not valid_version(source_version) or (tombstone ~= '0' and tombstone ~= '1')
  or not ttl or ttl <= 0 then
  return {'retry', 'invalid_input'}
end

local current_version = redis.call('HGET', state, 'source_version')
if not current_version then
  return {'retry', 'cache_preparation_missing'}
end
if redis.call('HLEN', state) ~= 11 or not valid_version(current_version)
  or redis.call('PTTL', state) <= 0
  or redis.call('HGET', state, 'schema') ~= schema
  or redis.call('HGET', state, 'source_id') ~= source_id
  or (redis.call('HGET', state, 'tombstone') ~= '0'
    and redis.call('HGET', state, 'tombstone') ~= '1')
  or redis.call('HGET', state, 'index_version') ~= ''
  or redis.call('HGET', state, 'cache_commitment') ~= ''
  or not string.match(redis.call('HGET', state, 'lease_deadline_ms') or '', '^[1-9][0-9]*$') then
  return {'retry', 'malformed_state'}
end
if redis.call('HGET', state, 'ready') ~= '0' then
  return {'retry', 'preparation_not_ready'}
end
if current_version ~= source_version
  or redis.call('HGET', state, 'tombstone') ~= tombstone
  or redis.call('HGET', state, 'commitment') ~= commitment
  or redis.call('HGET', state, 'event_id') ~= event_id
  or redis.call('HGET', state, 'occurred_time') ~= ARGV[11]
  then
  return {'retry', 'preparation_owner_mismatch'}
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
return {'applied', 'ready_state_applied'}
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

    def prepare(self, event: FaqKnowledgeEvent) -> CachePreparation:
        return self._prepare(event, authority_confirmed=False)

    def prepare_authoritatively(self, event: FaqKnowledgeEvent) -> CachePreparation:
        return self._prepare(event, authority_confirmed=True)

    def _prepare(self, event: FaqKnowledgeEvent, *, authority_confirmed: bool) -> CachePreparation:
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
            str(FAQ_PREPARATION_TTL_MS),
            str(FAQ_ENTRY_TTL_MS),
            "1" if authority_confirmed else "0",
        )
        outcome, detail = self._closed_result(raw)
        if outcome in {"retry", "error"}:
            raise KnowledgeSyncError(detail)
        if outcome == "busy":
            raise KnowledgeSyncError("cache_source_busy")
        try:
            return CachePreparation(outcome)
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
        if outcome in {"retry", "error"}:
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
        if prepared is CachePreparation.AUTHORITY_REQUIRED:
            raise KnowledgeSyncError("authoritative_projection_required")
        if prepared is CachePreparation.STALE:
            return ProjectionOutcome.STALE
        finalized = self.finalize(event, index_version)
        if prepared is CachePreparation.REPLAY_PREPARED:
            return ProjectionOutcome.REPLAYED
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


_LUA_RETURN = re.compile(r"return\s+\{\s*'([^']+)'\s*,\s*'([^']+)'\s*\}")
_LUA_STATE_FIELD = re.compile(r"HGET',\s*state,\s*'([^']+)'\)")


def lua_result_branch_inventory() -> frozenset[str]:
    branches: set[str] = set()
    for script_name, script in (("prepare", _PREPARE_SCRIPT), ("finalize", _FINALIZE_SCRIPT)):
        total_returns = len(re.findall(r"return\s+\{", script))
        matches = _LUA_RETURN.findall(script)
        if len(matches) != total_returns:
            raise RuntimeError(f"{script_name} contains an unlabelled result branch")
        for outcome, detail in matches:
            branch = f"{script_name}:{outcome}:{detail}"
            if branch in branches:
                raise RuntimeError(f"duplicate Lua result branch: {branch}")
            branches.add(branch)
    return frozenset(branches)


def lua_state_field_inventory() -> frozenset[str]:
    return frozenset(_LUA_STATE_FIELD.findall(_PREPARE_SCRIPT + _FINALIZE_SCRIPT))
