"""Monotonic FAQ answer/current-version projection in Support Redis."""

from __future__ import annotations

import hashlib
import json
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
local incoming_title = ARGV[12]
local incoming_answer = ARGV[13]
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

if not valid_version(source_version) then
  return {'retry', 'input_source_version_invalid'}
end
if tombstone ~= '0' and tombstone ~= '1' then
  return {'retry', 'input_tombstone_invalid'}
end
if not lease_ms or lease_ms <= 0 then
  return {'retry', 'input_lease_invalid'}
end
if not preparation_ttl or preparation_ttl <= lease_ms then
  return {'retry', 'input_preparation_ttl_invalid'}
end
if not ready_ttl or ready_ttl <= 0 then
  return {'retry', 'input_ready_ttl_invalid'}
end

local state_exists = redis.call('EXISTS', state) == 1
local repair_detail = false
if state_exists and redis.call('TYPE', state).ok ~= 'hash' then
  if not authority_confirmed then
    return {'authority_required', 'state_type_invalid'}
  end
  redis.call('DEL', state)
  state_exists = false
  repair_detail = 'state_type_invalid_repaired'
end
local current_version = redis.call('HGET', state, 'source_version')
local ready = redis.call('HGET', state, 'ready')
local replay = false
if state_exists and not current_version then
  if ready == '1' and not authority_confirmed then
    return {'authority_required', 'ready_state_source_version_missing'}
  end
  repair_detail = 'preparation_state_source_version_repaired'
end
if current_version then
  local state_ttl = redis.call('PTTL', state)
  local invalid_detail = false
  if not valid_version(current_version) then
    invalid_detail = 'state_source_version_invalid'
  elseif redis.call('HGET', state, 'schema') ~= schema then
    invalid_detail = 'state_schema_invalid'
  elseif redis.call('HGET', state, 'source_id') ~= source_id then
    invalid_detail = 'state_source_id_invalid'
  elseif redis.call('HGET', state, 'tombstone') == false then
    invalid_detail = 'state_tombstone_missing'
  elseif redis.call('HGET', state, 'commitment') == false then
    invalid_detail = 'state_commitment_missing'
  elseif redis.call('HGET', state, 'index_version') == false then
    invalid_detail = 'state_index_version_missing'
  elseif redis.call('HGET', state, 'event_id') == false then
    invalid_detail = 'state_event_id_missing'
  elseif redis.call('HGET', state, 'occurred_time') == false then
    invalid_detail = 'state_occurred_time_missing'
  elseif ready == false then
    invalid_detail = 'state_ready_missing'
  elseif redis.call('HGET', state, 'cache_commitment') == false then
    invalid_detail = 'state_cache_commitment_missing'
  elseif redis.call('HGET', state, 'lease_deadline_ms') == false then
    invalid_detail = 'state_lease_deadline_missing'
  elseif redis.call('HLEN', state) ~= 11 then
    invalid_detail = 'state_field_set_invalid'
  elseif state_ttl <= 0 then
    invalid_detail = 'state_ttl_invalid'
  end
  if invalid_detail then
    if ready == '1' and not authority_confirmed then
      return {'authority_required', invalid_detail}
    end
    current_version = false
    repair_detail = invalid_detail .. '_repaired'
  end
end
if current_version then
  if ready == '0' then
    local current_deadline = redis.call('HGET', state, 'lease_deadline_ms')
    local state_ttl = redis.call('PTTL', state)
    local preparation_invalid = false
    if not current_deadline or not string.match(current_deadline, '^[1-9][0-9]*$') then
      preparation_invalid = 'preparation_lease_deadline_invalid'
    elseif state_ttl > preparation_ttl then
      preparation_invalid = 'preparation_ttl_exceeds_bound'
    elseif state_ttl <= tonumber(current_deadline) - now_ms then
      preparation_invalid = 'preparation_ttl_not_beyond_lease'
    elseif redis.call('HGET', state, 'tombstone') ~= '0'
      and redis.call('HGET', state, 'tombstone') ~= '1' then
      preparation_invalid = 'preparation_tombstone_invalid'
    elseif redis.call('HGET', state, 'index_version') ~= '' then
      preparation_invalid = 'preparation_index_version_not_empty'
    elseif redis.call('HGET', state, 'cache_commitment') ~= '' then
      preparation_invalid = 'preparation_cache_commitment_not_empty'
    end
    if preparation_invalid then
      current_version = false
      repair_detail = preparation_invalid .. '_repaired'
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
    if not authority_confirmed then
      return {'authority_required', 'state_ready_invalid'}
    end
    current_version = false
    repair_detail = 'state_ready_invalid_repaired'
  end
  if current_version and ready == '1' then
    local state_ttl = redis.call('PTTL', state)
    local current_tombstone = redis.call('HGET', state, 'tombstone')
    local current_answer = answer_prefix .. current_version
    local ready_invalid = false
    if redis.call('HGET', state, 'lease_deadline_ms') ~= '' then
      ready_invalid = 'ready_state_lease_not_empty'
    elseif state_ttl > ready_ttl then
      ready_invalid = 'ready_state_ttl_exceeds_bound'
    elseif current_tombstone ~= '0' and current_tombstone ~= '1' then
      ready_invalid = 'ready_state_tombstone_invalid'
    end
    if current_tombstone == '1' then
      if redis.call('HGET', state, 'index_version') ~= '' then
        ready_invalid = 'ready_tombstone_index_version_not_empty'
      elseif redis.call('HGET', state, 'cache_commitment') ~= '' then
        ready_invalid = 'ready_tombstone_cache_commitment_not_empty'
      elseif redis.call('EXISTS', current_answer) ~= 0 then
        ready_invalid = 'ready_tombstone_answer_present'
      end
    elseif current_tombstone == '0' then
      if redis.call('HGET', state, 'index_version') == '' then
        ready_invalid = 'ready_state_index_version_empty'
      elseif redis.call('HGET', state, 'cache_commitment') == '' then
        ready_invalid = 'ready_state_cache_commitment_empty'
      elseif redis.call('EXISTS', current_answer) == 0 then
        ready_invalid = 'ready_answer_missing'
      elseif redis.call('TYPE', current_answer).ok ~= 'hash' then
        ready_invalid = 'ready_answer_type_invalid'
      elseif redis.call('HGET', current_answer, 'schema') ~= schema then
        ready_invalid = 'ready_answer_schema_invalid'
      elseif redis.call('HGET', current_answer, 'source_id') ~= source_id then
        ready_invalid = 'ready_answer_source_id_invalid'
      elseif redis.call('HGET', current_answer, 'source_version') ~= current_version then
        ready_invalid = 'ready_answer_source_version_invalid'
      elseif redis.call('HGET', current_answer, 'doc_type') ~= 'faq' then
        ready_invalid = 'ready_answer_doc_type_invalid'
      elseif redis.call('HGET', current_answer, 'chunk_id') ~= 'answer' then
        ready_invalid = 'ready_answer_chunk_id_invalid'
      elseif redis.call('HGET', current_answer, 'title') == false then
        ready_invalid = 'ready_answer_title_missing'
      elseif current_version == source_version
        and redis.call('HGET', current_answer, 'title') ~= incoming_title then
        ready_invalid = 'ready_answer_title_invalid'
      elseif redis.call('HGET', current_answer, 'answer') == false then
        ready_invalid = 'ready_answer_content_missing'
      elseif current_version == source_version
        and redis.call('HGET', current_answer, 'answer') ~= incoming_answer then
        ready_invalid = 'ready_answer_content_invalid'
      elseif redis.call('HGET', current_answer, 'index_version')
        ~= redis.call('HGET', state, 'index_version') then
        ready_invalid = 'ready_answer_index_version_invalid'
      elseif redis.call('HGET', current_answer, 'commitment')
        ~= redis.call('HGET', state, 'commitment') then
        ready_invalid = 'ready_answer_commitment_invalid'
      elseif redis.call('HGET', current_answer, 'cache_commitment')
        ~= redis.call('HGET', state, 'cache_commitment') then
        ready_invalid = 'ready_answer_cache_commitment_invalid'
      elseif redis.call('HGET', current_answer, 'public_category') ~= 'faq' then
        ready_invalid = 'ready_answer_public_category_invalid'
      elseif redis.call('HGET', current_answer, 'public_language') ~= 'und' then
        ready_invalid = 'ready_answer_public_language_invalid'
      elseif redis.call('HLEN', current_answer) ~= 12 then
        ready_invalid = 'ready_answer_field_set_invalid'
      else
        local answer_ttl = redis.call('PTTL', current_answer)
        if answer_ttl <= 0 then
          ready_invalid = 'ready_answer_ttl_invalid'
        elseif answer_ttl > ready_ttl then
          ready_invalid = 'ready_answer_ttl_exceeds_bound'
        end
      end
    end
    if ready_invalid then
      if not authority_confirmed then
        return {'authority_required', ready_invalid}
      end
      current_version = false
      repair_detail = ready_invalid .. '_repaired'
    end
  end
  if current_version and ready == '1' then
    local comparison = compare_version(current_version, source_version)
    if comparison > 0 then
      return {'stale', 'ready_state_newer'}
    end
    if comparison == 0 then
      if redis.call('HGET', state, 'schema') ~= schema
        or redis.call('HGET', state, 'source_id') ~= source_id then
        if not authority_confirmed then
          return {'authority_required', 'ready_state_identity_contradiction'}
        end
        repair_detail = 'ready_state_identity_contradiction_repaired'
      elseif redis.call('HGET', state, 'tombstone') ~= tombstone then
        if not authority_confirmed then
          return {'authority_required', 'ready_state_tombstone_contradiction'}
        end
        repair_detail = 'ready_state_tombstone_contradiction_repaired'
      elseif redis.call('HGET', state, 'commitment') ~= commitment then
        if not authority_confirmed then
          return {'authority_required', 'ready_state_commitment_contradiction'}
        end
        repair_detail = 'ready_state_commitment_contradiction_repaired'
      elseif redis.call('HGET', state, 'event_id') ~= event_id then
        if not authority_confirmed then
          return {'authority_required', 'ready_state_event_id_contradiction'}
        end
        repair_detail = 'ready_state_event_id_contradiction_repaired'
      elseif redis.call('HGET', state, 'occurred_time') ~= occurred_time then
        if not authority_confirmed then
          return {'authority_required', 'ready_state_occurred_time_contradiction'}
        end
        repair_detail = 'ready_state_occurred_time_contradiction_repaired'
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
if repair_detail and not authority_confirmed then
  return {'retry', repair_detail}
end
if repair_detail then
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

if not valid_version(source_version) then
  return {'retry', 'finalize_input_source_version_invalid'}
end
if tombstone ~= '0' and tombstone ~= '1' then
  return {'retry', 'finalize_input_tombstone_invalid'}
end
if not ttl or ttl <= 0 then
  return {'retry', 'finalize_input_ttl_invalid'}
end

if redis.call('EXISTS', state) == 0 then
  return {'retry', 'cache_preparation_missing'}
end
if redis.call('TYPE', state).ok ~= 'hash' then
  return {'retry', 'preparation_state_type_invalid'}
end
local current_version = redis.call('HGET', state, 'source_version')
if not current_version then
  return {'retry', 'preparation_source_version_missing'}
end
if not valid_version(current_version) then
  return {'retry', 'preparation_source_version_invalid'}
end
if redis.call('HGET', state, 'schema') ~= schema then
  return {'retry', 'preparation_schema_invalid'}
end
if redis.call('HGET', state, 'source_id') ~= source_id then
  return {'retry', 'preparation_source_id_invalid'}
end
local current_tombstone = redis.call('HGET', state, 'tombstone')
if current_tombstone == false then
  return {'retry', 'preparation_tombstone_missing'}
end
if current_tombstone ~= '0' and current_tombstone ~= '1' then
  return {'retry', 'preparation_tombstone_invalid'}
end
if redis.call('HGET', state, 'commitment') == false then
  return {'retry', 'preparation_commitment_missing'}
end
if redis.call('HGET', state, 'index_version') == false then
  return {'retry', 'preparation_index_version_missing'}
end
if redis.call('HGET', state, 'index_version') ~= '' then
  return {'retry', 'preparation_index_version_not_empty'}
end
if redis.call('HGET', state, 'event_id') == false then
  return {'retry', 'preparation_event_id_missing'}
end
if redis.call('HGET', state, 'occurred_time') == false then
  return {'retry', 'preparation_occurred_time_missing'}
end
local current_ready = redis.call('HGET', state, 'ready')
if current_ready == false then
  return {'retry', 'preparation_ready_missing'}
end
if current_ready ~= '0' then
  return {'retry', 'preparation_not_ready'}
end
if redis.call('HGET', state, 'cache_commitment') == false then
  return {'retry', 'preparation_cache_commitment_missing'}
end
if redis.call('HGET', state, 'cache_commitment') ~= '' then
  return {'retry', 'preparation_cache_commitment_not_empty'}
end
local current_deadline = redis.call('HGET', state, 'lease_deadline_ms')
if current_deadline == false then
  return {'retry', 'preparation_lease_deadline_missing'}
end
if not string.match(current_deadline, '^[1-9][0-9]*$') then
  return {'retry', 'preparation_lease_deadline_invalid'}
end
if redis.call('HLEN', state) ~= 11 then
  return {'retry', 'preparation_field_set_invalid'}
end
if redis.call('PTTL', state) <= 0 then
  return {'retry', 'preparation_ttl_invalid'}
end
if current_version ~= source_version then
  return {'retry', 'preparation_owner_source_version_mismatch'}
end
if current_tombstone ~= tombstone then
  return {'retry', 'preparation_owner_tombstone_mismatch'}
end
if redis.call('HGET', state, 'commitment') ~= commitment then
  return {'retry', 'preparation_owner_commitment_mismatch'}
end
if redis.call('HGET', state, 'event_id') ~= event_id then
  return {'retry', 'preparation_owner_event_id_mismatch'}
end
if redis.call('HGET', state, 'occurred_time') ~= ARGV[11] then
  return {'retry', 'preparation_owner_occurred_time_mismatch'}
end

local ready_index_version = index_version
local ready_cache_commitment = cache_commitment
if tombstone == '1' then
  ready_index_version = ''
  ready_cache_commitment = ''
end
redis.call('DEL', state)
redis.call('HSET', state,
  'schema', schema,
  'source_id', source_id,
  'source_version', source_version,
  'tombstone', tombstone,
  'commitment', commitment,
  'index_version', ready_index_version,
  'event_id', event_id,
  'occurred_time', ARGV[11],
  'ready', '1',
  'cache_commitment', ready_cache_commitment,
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
            event.content.question,
            event.content.answer,
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
