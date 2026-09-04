package io.citybuddy.commerce.seckill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class ReservationAdmissionStore {
  static final String RESERVATION_PREFIX = "commerce:seckill:reservation:";
  static final String DECISION_PREFIX = "commerce:seckill:decision:";
  static final String USER_PREFIX = "commerce:seckill:user:";
  static final String INTENT_PREFIX = "commerce:seckill:intent:";
  static final String HANDOFF_PREFIX = "commerce:seckill:handoff:";
  static final String HANDOFF_INDEX = "commerce:seckill:handoff:pending";
  static final String ACTIVITY_HANDOFF_PREFIX = "commerce:seckill:handoff-activity:";
  static final String REBUILD_PREFIX = "commerce:seckill:rebuild:";

  private static final DefaultRedisScript<String> PRE_ADMISSION_SCRIPT =
      new DefaultRedisScript<>(
          """
          local MAX_JSON_INTEGER = 99999999999999
          local MAX_LUA_INTEGER = 9007199254740991
          local HANDOFF_PREFIX = 'commerce:seckill:handoff:'

          local function decode(value)
            local ok, decoded = pcall(cjson.decode, value)
            if not ok or type(decoded) ~= 'table' then return nil end
            return decoded
          end

          local function output(reservation_id, state, code, replay, handoff_pending)
            return cjson.encode({
              reservationId = reservation_id,
              state = state,
              decisionCode = code,
              replay = replay,
              handoffPending = handoff_pending
            })
          end

          local function reservation_payload(reservation_id, state, code)
            return cjson.encode({
              reservationId = reservation_id,
              activityId = ARGV[2],
              userHash = ARGV[3],
              quantity = tonumber(ARGV[4]),
              activityProjectionVersion = tonumber(ARGV[5]),
              reservationVersion = 2,
              state = state,
              decisionCode = code,
              durableOrderCreated = false
            })
          end

          local function anchor_payload(reservation_id, state, code)
            return cjson.encode({
              reservationId = reservation_id,
              activityId = ARGV[2],
              userHash = ARGV[3],
              quantity = tonumber(ARGV[4]),
              activityProjectionVersion = tonumber(ARGV[5]),
              intentHash = ARGV[6],
              state = state,
              decisionCode = code
            })
          end

          local function reject(code)
            local reservation = reservation_payload(ARGV[1], 'REJECTED', code)
            redis.call('MSET', KEYS[2], anchor_payload(ARGV[1], 'REJECTED', code),
              KEYS[4], reservation, KEYS[5], reservation)
            redis.call('PEXPIRE', KEYS[2], ARGV[10])
            redis.call('PEXPIRE', KEYS[4], ARGV[9])
            redis.call('PEXPIRE', KEYS[5], ARGV[10])
            return output(ARGV[1], 'REJECTED', code, false, false)
          end

          local existing_value = redis.call('GET', KEYS[2])
          if existing_value then
            local existing = decode(existing_value)
            if not existing
                or existing.activityId ~= ARGV[2]
                or existing.userHash ~= ARGV[3]
                or tonumber(existing.quantity) ~= tonumber(ARGV[4])
                or tonumber(existing.activityProjectionVersion) ~= tonumber(ARGV[5])
                or existing.intentHash ~= ARGV[6]
                or type(existing.reservationId) ~= 'string' then
              return 'CONFLICT'
            end
            if existing.state == 'ADMITTED' and existing.decisionCode == 'ADMITTED' then
              local pending = redis.call('EXISTS', HANDOFF_PREFIX .. existing.reservationId) == 1
              return output(existing.reservationId, 'ADMITTED', 'ADMITTED', true, pending)
            end
            if existing.state == 'REJECTED'
                and type(existing.decisionCode) == 'string'
                and existing.decisionCode ~= 'ADMITTED' then
              return output(existing.reservationId, 'REJECTED', existing.decisionCode, true, false)
            end
            return 'PARTIAL'
          end

          if redis.call('EXISTS', KEYS[4]) == 1
              or redis.call('EXISTS', KEYS[5]) == 1
              or redis.call('EXISTS', KEYS[6]) == 1 then
            return 'PARTIAL'
          end
          if redis.call('EXISTS', KEYS[8]) == 1 then return 'REBUILDING' end

          local activity_value = redis.call('GET', KEYS[1])
          if not activity_value then return 'MISSING_ACTIVITY' end
          local activity = decode(activity_value)
          if not activity then return 'MALFORMED_ACTIVITY' end
          local projection_version = tonumber(activity.projectionVersion)
          local expected_version = tonumber(ARGV[5])
          local remaining = tonumber(activity.remainingQuota)
          local quantity = tonumber(ARGV[4])
          local now = tonumber(ARGV[8])
          local starts_at = tonumber(activity.startsAtEpochMicros)
          local ends_at = tonumber(activity.endsAtEpochMicros)
          if activity.activityId ~= ARGV[2]
              or not projection_version or projection_version < 1
              or projection_version > MAX_JSON_INTEGER
              or not expected_version or expected_version < 1
              or expected_version > MAX_JSON_INTEGER
              or not remaining or remaining < 0 or remaining > MAX_JSON_INTEGER
              or not quantity or quantity < 1 or quantity > MAX_JSON_INTEGER
              or not now or math.abs(now) > MAX_LUA_INTEGER
              or not starts_at or math.abs(starts_at) > MAX_LUA_INTEGER
              or not ends_at or math.abs(ends_at) > MAX_LUA_INTEGER
              or starts_at >= ends_at then
            return 'MALFORMED_ACTIVITY'
          end
          if projection_version ~= expected_version then return reject('STALE_VERSION') end
          if activity.state ~= 'ACTIVE' then return reject('ACTIVITY_INACTIVE') end
          if now < starts_at then return reject('NOT_OPEN') end
          if now >= ends_at then return reject('EXPIRED') end

          local existing_user = redis.call('GET', KEYS[3])
          if existing_user then return reject('DUPLICATE_USER') end
          if remaining < quantity then return reject('EXHAUSTED') end

          activity.remainingQuota = remaining - quantity
          local reservation = reservation_payload(ARGV[1], 'ADMITTED', 'ADMITTED')
          local handoff = cjson.encode({
            reservationId = ARGV[1],
            userSubject = ARGV[7],
            activityId = ARGV[2],
            idempotencyKey = ARGV[11],
            intentHash = ARGV[6],
            quantity = quantity,
            activityProjectionVersion = expected_version
          })
          redis.call('MSET', KEYS[1], cjson.encode(activity),
            KEYS[2], anchor_payload(ARGV[1], 'ADMITTED', 'ADMITTED'),
            KEYS[3], ARGV[1], KEYS[4], reservation, KEYS[5], reservation,
            KEYS[6], handoff)
          redis.call('PEXPIREAT', KEYS[3], math.ceil(ends_at / 1000))
          redis.call('ZADD', KEYS[7], ARGV[12], ARGV[1])
          redis.call('SADD', KEYS[9], ARGV[1])
          return output(ARGV[1], 'ADMITTED', 'ADMITTED', false, true)
          """,
          String.class);

  private static final DefaultRedisScript<Long> COMPLETE_HANDOFF_SCRIPT =
      new DefaultRedisScript<>(
          """
          local removed = redis.call('DEL', KEYS[1])
          redis.call('ZREM', KEYS[2], ARGV[1])
          redis.call('SREM', KEYS[3], ARGV[1])
          if removed == 1 then
            redis.call('PEXPIRE', KEYS[4], ARGV[2])
            redis.call('PEXPIRE', KEYS[6], ARGV[3])
            redis.call('PEXPIRE', KEYS[7], ARGV[2])
          end
          return removed
          """,
          Long.class);

  private static final DefaultRedisScript<String> SUSPEND_PROJECTION_SCRIPT =
      new DefaultRedisScript<>(
          """
          if redis.call('GET', KEYS[2]) ~= ARGV[1] then return '__LOCK_LOST__' end
          local current = redis.call('GET', KEYS[1])
          if not current then return '' end
          redis.call('DEL', KEYS[1])
          return current
          """,
          String.class);

  private static final DefaultRedisScript<Long> RESTORE_SUSPENDED_PROJECTION_SCRIPT =
      new DefaultRedisScript<>(
          """
          if redis.call('GET', KEYS[2]) ~= ARGV[1] then return -1 end
          if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
          redis.call('SET', KEYS[1], ARGV[2])
          return 1
          """,
          Long.class);

  private static final DefaultRedisScript<Long> ADMISSION_SCRIPT =
      new DefaultRedisScript<>(
          """
          local MAX_JSON_INTEGER = 99999999999999
          local MAX_LUA_INTEGER = 9007199254740991

          local function decode(value)
            local ok, decoded = pcall(cjson.decode, value)
            if not ok or type(decoded) ~= 'table' then
              return nil
            end
            return decoded
          end

          local function result_code(code, replay)
            if code == 'ADMITTED' then
              if replay then return 2 else return 1 end
            elseif code == 'ACTIVITY_INACTIVE' then return 10
            elseif code == 'NOT_OPEN' then return 11
            elseif code == 'EXPIRED' then return 12
            elseif code == 'STALE_VERSION' then return 13
            elseif code == 'EXHAUSTED' then return 14
            elseif code == 'DUPLICATE_USER' then return 15
            elseif code == 'TRANSACTION_TIMEOUT' then return 16
            end
            return -12
          end

          local function terminal_payload(state, code)
            return cjson.encode({
              reservationId = ARGV[1],
              activityId = ARGV[2],
              userHash = ARGV[3],
              quantity = tonumber(ARGV[4]),
              activityProjectionVersion = tonumber(ARGV[5]),
              reservationVersion = tonumber(ARGV[14]),
              state = state,
              decisionCode = code,
              durableOrderCreated = false
            })
          end

          local function reject(code)
            local payload = terminal_payload('REJECTED', code)
            redis.call('MSET', KEYS[3], payload, KEYS[4], payload)
            redis.call('PEXPIRE', KEYS[3], ARGV[12])
            redis.call('PEXPIRE', KEYS[4], ARGV[13])
            return result_code(code, false)
          end

          local existing_decision = redis.call('GET', KEYS[4])
          if existing_decision then
            local decision = decode(existing_decision)
            if not decision
                or decision.reservationId ~= ARGV[1]
                or decision.activityId ~= ARGV[2]
                or decision.userHash ~= ARGV[3]
                or tonumber(decision.quantity) ~= tonumber(ARGV[4])
                or tonumber(decision.activityProjectionVersion) ~= tonumber(ARGV[5])
                or tonumber(decision.reservationVersion) ~= tonumber(ARGV[14]) then
              return -12
            end
            local existing_reservation = redis.call('GET', KEYS[3])
            local projected = existing_reservation and decode(existing_reservation) or nil
            if not projected
                or projected.reservationId ~= decision.reservationId
                or projected.activityId ~= decision.activityId
                or projected.userHash ~= decision.userHash
                or projected.quantity ~= decision.quantity
                or projected.activityProjectionVersion ~= decision.activityProjectionVersion
                or projected.reservationVersion ~= decision.reservationVersion
                or projected.state ~= decision.state
                or projected.decisionCode ~= decision.decisionCode then
              return -13
            end
            if decision.decisionCode == 'ADMITTED'
                and redis.call('GET', KEYS[2]) ~= ARGV[1] then
              return -13
            end
            return result_code(decision.decisionCode, true)
          end

          if redis.call('EXISTS', KEYS[3]) == 1 then
            return -13
          end
          if redis.call('EXISTS', KEYS[5]) == 1 then
            return -14
          end

          local current = redis.call('GET', KEYS[1])
          if not current then
            return -10
          end
          local activity = decode(current)
          if not activity then
            return -11
          end
          if activity.activityId ~= ARGV[2]
              or activity.startsAt ~= ARGV[7]
              or activity.endsAt ~= ARGV[8] then
            return -12
          end
          local current_version = tonumber(activity.projectionVersion)
          local expected_version = tonumber(ARGV[5])
          local mysql_version = tonumber(ARGV[6])
          if not current_version or current_version < 1
              or current_version > MAX_JSON_INTEGER
              or not expected_version or expected_version < 1
              or expected_version > MAX_JSON_INTEGER
              or not mysql_version or mysql_version < 1
              or mysql_version > MAX_JSON_INTEGER then
            return -11
          end
          if expected_version ~= mysql_version then
            return reject('STALE_VERSION')
          end
          if current_version ~= mysql_version then return -15 end
          if activity.state ~= 'ACTIVE' then
            return reject('ACTIVITY_INACTIVE')
          end

          local now = tonumber(ARGV[11])
          local starts_at = tonumber(ARGV[9])
          local ends_at = tonumber(ARGV[10])
          if not now or math.abs(now) > MAX_LUA_INTEGER
              or not starts_at or math.abs(starts_at) > MAX_LUA_INTEGER
              or not ends_at or math.abs(ends_at) > MAX_LUA_INTEGER
              or starts_at >= ends_at then
            return -12
          end
          if now < starts_at then
            return reject('NOT_OPEN')
          end
          if now >= ends_at then
            return reject('EXPIRED')
          end

          local existing_user = redis.call('GET', KEYS[2])
          if existing_user then
            if existing_user == ARGV[1] then
              return -13
            end
            return reject('DUPLICATE_USER')
          end

          local remaining = tonumber(activity.remainingQuota)
          local quantity = tonumber(ARGV[4])
          if not remaining or not quantity or remaining < 0
              or remaining > MAX_JSON_INTEGER or quantity < 1
              or quantity > MAX_JSON_INTEGER then
            return -11
          end
          if remaining < quantity then
            return reject('EXHAUSTED')
          end

          activity.remainingQuota = remaining - quantity
          local activity_payload = cjson.encode(activity)
          local admitted_payload = terminal_payload('ADMITTED', 'ADMITTED')
          redis.call(
            'MSET',
            KEYS[1], activity_payload,
            KEYS[2], ARGV[1],
            KEYS[3], admitted_payload,
            KEYS[4], admitted_payload
          )
          redis.call('PEXPIRE', KEYS[2], ARGV[12])
          redis.call('PEXPIRE', KEYS[3], ARGV[12])
          redis.call('PEXPIRE', KEYS[4], ARGV[13])
          return 1
          """,
          Long.class);

  private static final DefaultRedisScript<Long> DEADLINE_SCRIPT =
      new DefaultRedisScript<>(
          """
          local function decode(value)
            local ok, decoded = pcall(cjson.decode, value)
            if not ok or type(decoded) ~= 'table' then return nil end
            return decoded
          end

          local function same(left, right)
            return left.reservationId == right.reservationId
              and left.activityId == right.activityId
              and left.userHash == right.userHash
              and left.quantity == right.quantity
              and left.activityProjectionVersion == right.activityProjectionVersion
              and left.reservationVersion == right.reservationVersion
              and left.state == right.state
              and left.decisionCode == right.decisionCode
              and left.durableOrderCreated == right.durableOrderCreated
          end

          local incoming = decode(ARGV[1])
          if not incoming
              or incoming.state ~= 'REJECTED'
              or incoming.decisionCode ~= 'TRANSACTION_TIMEOUT'
              or incoming.reservationVersion ~= 2
              or incoming.durableOrderCreated ~= false then
            return -12
          end

          local existing_decision_value = redis.call('GET', KEYS[2])
          if existing_decision_value then
            local existing_decision = decode(existing_decision_value)
            local existing_reservation_value = redis.call('GET', KEYS[1])
            local existing_reservation =
              existing_reservation_value and decode(existing_reservation_value) or nil
            if not existing_decision or not existing_reservation
                or not same(existing_decision, existing_reservation)
                or existing_decision.reservationId ~= incoming.reservationId
                or existing_decision.activityId ~= incoming.activityId
                or existing_decision.userHash ~= incoming.userHash
                or existing_decision.quantity ~= incoming.quantity
                or existing_decision.activityProjectionVersion
                    ~= incoming.activityProjectionVersion
                or existing_decision.reservationVersion ~= incoming.reservationVersion then
              return -13
            end
            return 2
          end

          if redis.call('EXISTS', KEYS[1]) == 1 then return -13 end
          redis.call('MSET', KEYS[1], ARGV[1], KEYS[2], ARGV[1])
          redis.call('PEXPIRE', KEYS[1], ARGV[2])
          redis.call('PEXPIRE', KEYS[2], ARGV[3])
          return 1
          """,
          Long.class);

  private static final DefaultRedisScript<Long> RELEASE_LOCK_SCRIPT =
      new DefaultRedisScript<>(
          """
          if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
          end
          return 0
          """,
          Long.class);

  private static final DefaultRedisScript<Long> REBUILD_SCRIPT =
      new DefaultRedisScript<>(
          """
          local MAX_JSON_INTEGER = 99999999999999

          local function decode(value)
            local ok, decoded = pcall(cjson.decode, value)
            if not ok or type(decoded) ~= 'table' then return nil end
            return decoded
          end

          if redis.call('GET', KEYS[2]) ~= ARGV[1] then return -20 end
          local incoming_activity = decode(ARGV[2])
          if not incoming_activity then return -11 end
          local incoming_activity_version = tonumber(ARGV[3])
          if not incoming_activity_version
              or incoming_activity_version < 1
              or incoming_activity_version > MAX_JSON_INTEGER
              or tonumber(incoming_activity.projectionVersion) ~= incoming_activity_version then
            return -12
          end
          local incoming_remaining = tonumber(incoming_activity.remainingQuota)
          if not incoming_remaining or incoming_remaining < 0
              or incoming_remaining > MAX_JSON_INTEGER then return -12 end

          local existing_activity_value = redis.call('GET', KEYS[1])
          if existing_activity_value then
            local existing_activity = decode(existing_activity_value)
            if existing_activity then
              local existing_version = tonumber(existing_activity.projectionVersion)
              if existing_version and existing_version >= 1
                  and existing_version <= MAX_JSON_INTEGER
                  and existing_version > incoming_activity_version then return 0 end
            end
          end

          local count = tonumber(ARGV[6])
          if not count or count < 0 then return -12 end
          for index = 0, count - 1 do
            local key_base = 3 + index * 3
            local argument_base = 7 + index * 5
            local incoming = decode(ARGV[argument_base])
            local state = ARGV[argument_base + 1]
            local reservation_id = ARGV[argument_base + 2]
            local version = tonumber(ARGV[argument_base + 3])
            local expected_user_marker = ARGV[argument_base + 4]
            if not incoming
                or incoming.reservationId ~= reservation_id
                or tonumber(incoming.reservationVersion) ~= version
                or not version or version < 1 or version > MAX_JSON_INTEGER
                or (state ~= 'ADMITTED' and state ~= 'REJECTED'
                  and state ~= 'ORDERED' and state ~= 'CANCELLED'
                  and state ~= 'UNFULFILLED') then
              return -12
            end
            local quantity = tonumber(incoming.quantity)
            local activity_version = tonumber(incoming.activityProjectionVersion)
            if not quantity or quantity < 1 or quantity > MAX_JSON_INTEGER
                or not activity_version or activity_version < 1
                or activity_version > MAX_JSON_INTEGER then return -12 end

          end

          redis.call('SET', KEYS[1], ARGV[2])
          for index = 0, count - 1 do
            local key_base = 3 + index * 3
            local argument_base = 7 + index * 5
            local payload = ARGV[argument_base]
            local expected_user_marker = ARGV[argument_base + 4]
            if expected_user_marker == '' then
              redis.call('DEL', KEYS[key_base])
            else
              redis.call('SET', KEYS[key_base], expected_user_marker)
            end
            redis.call('MSET', KEYS[key_base + 1], payload, KEYS[key_base + 2], payload)
          end

          for index = 0, count - 1 do
            local key_base = 3 + index * 3
            local argument_base = 7 + index * 5
            if ARGV[argument_base + 4] ~= '' then
              redis.call('PEXPIREAT', KEYS[key_base],
                math.ceil(tonumber(incoming_activity.endsAtEpochMicros) / 1000))
            end
            redis.call('PEXPIRE', KEYS[key_base + 1], ARGV[4])
            redis.call('PEXPIRE', KEYS[key_base + 2], ARGV[5])
          end
          redis.call('DEL', KEYS[2])
          return 1
          """,
          Long.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final SeckillReservationProperties properties;
  private final Clock clock;

  public ReservationAdmissionStore(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      SeckillReservationProperties properties,
      Clock clock) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.clock = clock;
  }

  public PreAdmission preAdmit(AdmissionHandoff candidate, String userHash) {
    validateHandoff(candidate);
    if (userHash == null || !userHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Reservation user hash is invalid");
    }
    String anchorKey = intentKey(candidate.activityId(), userHash, candidate.idempotencyKey());
    List<String> keys =
        List.of(
            activityKey(candidate.activityId()),
            anchorKey,
            userKey(candidate.activityId(), userHash),
            reservationKey(candidate.reservationId()),
            decisionKey(candidate.reservationId()),
            handoffKey(candidate.reservationId()),
            HANDOFF_INDEX,
            rebuildKey(candidate.activityId()),
            activityHandoffKey(candidate.activityId()));
    final String result;
    try {
      long dueAtMillis =
          Math.addExact(clock.millis(), properties.brokerTransactionTimeout().toMillis());
      result =
          redis.execute(
              PRE_ADMISSION_SCRIPT,
              keys,
              candidate.reservationId(),
              candidate.activityId(),
              userHash,
              Integer.toString(candidate.quantity()),
              Long.toString(candidate.activityProjectionVersion()),
              candidate.intentHash(),
              candidate.userSubject(),
              Long.toString(epochMicros(clock.instant())),
              Long.toString(properties.reservationTtl().toMillis()),
              Long.toString(properties.decisionMarkerTtl().toMillis()),
              candidate.idempotencyKey(),
              Long.toString(dueAtMillis));
    } catch (RuntimeException exception) {
      throw new AdmissionIndeterminateException(
          "Redis-first admission execution failed", exception);
    }
    if (result == null) {
      throw new AdmissionIndeterminateException("Redis-first admission returned no result");
    }
    return parsePreAdmission(result, candidate);
  }

  public List<AdmissionHandoff> dueHandoffs(int limit) {
    if (limit < 1 || limit > 1_000) {
      throw new IllegalArgumentException("Admission handoff batch size is invalid");
    }
    final Set<String> reservationIds;
    try {
      reservationIds = redis.opsForZSet().rangeByScore(HANDOFF_INDEX, 0, clock.millis(), 0, limit);
    } catch (RuntimeException exception) {
      throw new AdmissionIndeterminateException("Admission handoff scan failed", exception);
    }
    if (reservationIds == null || reservationIds.isEmpty()) {
      return List.of();
    }
    List<AdmissionHandoff> handoffs = new ArrayList<>();
    for (String reservationId : reservationIds) {
      final String value;
      try {
        value = redis.opsForValue().get(handoffKey(reservationId));
      } catch (RuntimeException exception) {
        throw new AdmissionIndeterminateException("Admission handoff read failed", exception);
      }
      if (value == null) {
        throw new AdmissionIndeterminateException("Admission handoff index is partial");
      }
      try {
        AdmissionHandoff handoff = objectMapper.readValue(value, AdmissionHandoff.class);
        validateHandoff(handoff);
        if (!reservationId.equals(handoff.reservationId())) {
          throw new AdmissionIndeterminateException("Admission handoff identity conflicts");
        }
        handoffs.add(handoff);
      } catch (JsonProcessingException exception) {
        throw new AdmissionIndeterminateException("Admission handoff is unreadable", exception);
      }
    }
    return List.copyOf(handoffs);
  }

  public Optional<AdmissionProjection> readOwned(String reservationId, String userHash) {
    final String value;
    final Boolean pending;
    try {
      value = redis.opsForValue().get(reservationKey(reservationId));
      pending = redis.hasKey(handoffKey(reservationId));
    } catch (RuntimeException exception) {
      throw new AdmissionIndeterminateException("Reservation projection read failed", exception);
    }
    if (value == null) {
      return Optional.empty();
    }
    try {
      var payload = objectMapper.readTree(value);
      if (!reservationId.equals(payload.path("reservationId").asText())
          || !userHash.equals(payload.path("userHash").asText())
          || !payload.path("quantity").canConvertToInt()
          || payload.path("quantity").intValue() < 1
          || !payload.path("activityProjectionVersion").canConvertToLong()
          || payload.path("activityProjectionVersion").longValue() < 1
          || payload.path("reservationVersion").longValue() != 2) {
        return Optional.empty();
      }
      ReservationState state = ReservationState.valueOf(payload.path("state").asText());
      ReservationDecisionCode code =
          ReservationDecisionCode.valueOf(payload.path("decisionCode").asText());
      if ((state == ReservationState.ADMITTED && code != ReservationDecisionCode.ADMITTED)
          || (state == ReservationState.REJECTED && code == ReservationDecisionCode.ADMITTED)
          || (state != ReservationState.ADMITTED && state != ReservationState.REJECTED)) {
        throw new AdmissionIndeterminateException("Reservation projection is malformed");
      }
      return Optional.of(
          new AdmissionProjection(
              reservationId,
              payload.path("activityId").asText(),
              payload.path("quantity").intValue(),
              payload.path("activityProjectionVersion").longValue(),
              state,
              code,
              Boolean.TRUE.equals(pending)));
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw new AdmissionIndeterminateException("Reservation projection is unreadable", exception);
    }
  }

  public boolean hasPendingHandoff(String activityId) {
    try {
      Long count = redis.opsForSet().size(activityHandoffKey(activityId));
      return count != null && count > 0;
    } catch (RuntimeException exception) {
      throw new AdmissionIndeterminateException("Activity handoff read failed", exception);
    }
  }

  public void completeHandoff(AdmissionHandoff handoff) {
    validateHandoff(handoff);
    final Long result;
    try {
      result =
          redis.execute(
              COMPLETE_HANDOFF_SCRIPT,
              List.of(
                  handoffKey(handoff.reservationId()),
                  HANDOFF_INDEX,
                  activityHandoffKey(handoff.activityId()),
                  intentKey(
                      handoff.activityId(),
                      SeckillReservationService.sha256(handoff.userSubject()),
                      handoff.idempotencyKey()),
                  userKey(
                      handoff.activityId(),
                      SeckillReservationService.sha256(handoff.userSubject())),
                  reservationKey(handoff.reservationId()),
                  decisionKey(handoff.reservationId())),
              handoff.reservationId(),
              Long.toString(properties.decisionMarkerTtl().toMillis()),
              Long.toString(properties.reservationTtl().toMillis()));
    } catch (RuntimeException exception) {
      throw new AdmissionIndeterminateException("Admission handoff completion failed", exception);
    }
    if (result == null) {
      throw new AdmissionIndeterminateException("Admission handoff completion returned no result");
    }
  }

  public String suspendProjection(String activityId, String lockToken) {
    final String result;
    try {
      result =
          redis.execute(
              SUSPEND_PROJECTION_SCRIPT,
              List.of(activityKey(activityId), rebuildKey(activityId)),
              lockToken);
    } catch (RuntimeException exception) {
      throw new AdmissionIndeterminateException("Activity projection suspension failed", exception);
    }
    if (result == null || "__LOCK_LOST__".equals(result)) {
      throw new AdmissionIndeterminateException("Reservation rebuild lock was lost");
    }
    return result;
  }

  public void restoreSuspendedProjection(
      String activityId, String lockToken, String suspendedProjection) {
    if (suspendedProjection == null || suspendedProjection.isEmpty()) {
      return;
    }
    final Long result;
    try {
      result =
          redis.execute(
              RESTORE_SUSPENDED_PROJECTION_SCRIPT,
              List.of(activityKey(activityId), rebuildKey(activityId)),
              lockToken,
              suspendedProjection);
    } catch (RuntimeException exception) {
      throw new AdmissionIndeterminateException(
          "Activity projection restoration failed", exception);
    }
    if (result == null || result < 0) {
      throw new AdmissionIndeterminateException("Reservation rebuild lock was lost");
    }
  }

  private PreAdmission parsePreAdmission(String result, AdmissionHandoff candidate) {
    if ("CONFLICT".equals(result)) {
      throw new IllegalStateException(
          "Idempotency key is bound to a conflicting reservation intent");
    }
    String message =
        switch (result) {
          case "PARTIAL" -> "Redis-first admission projection is partial";
          case "REBUILDING" -> "Activity projection rebuild is active";
          case "MISSING_ACTIVITY" -> "Activity projection is missing";
          case "MALFORMED_ACTIVITY" -> "Activity projection is malformed";
          default -> null;
        };
    if (message != null) {
      throw new AdmissionIndeterminateException(message);
    }
    try {
      var payload = objectMapper.readTree(result);
      String reservationId = payload.path("reservationId").asText();
      ReservationState state = ReservationState.valueOf(payload.path("state").asText());
      ReservationDecisionCode code =
          ReservationDecisionCode.valueOf(payload.path("decisionCode").asText());
      if (!payload.path("replay").isBoolean()
          || !payload.path("handoffPending").isBoolean()
          || (state == ReservationState.ADMITTED && code != ReservationDecisionCode.ADMITTED)
          || (state == ReservationState.REJECTED && code == ReservationDecisionCode.ADMITTED)
          || (state != ReservationState.ADMITTED && state != ReservationState.REJECTED)) {
        throw new AdmissionIndeterminateException("Redis-first admission result is malformed");
      }
      AdmissionHandoff handoff =
          new AdmissionHandoff(
              reservationId,
              candidate.userSubject(),
              candidate.activityId(),
              candidate.idempotencyKey(),
              candidate.intentHash(),
              candidate.quantity(),
              candidate.activityProjectionVersion());
      validateHandoff(handoff);
      return new PreAdmission(
          handoff,
          new AdmissionDecision(state, code),
          payload.path("replay").booleanValue(),
          payload.path("handoffPending").booleanValue());
    } catch (JsonProcessingException | IllegalArgumentException exception) {
      throw new AdmissionIndeterminateException(
          "Redis-first admission result is unreadable", exception);
    }
  }

  private static void validateHandoff(AdmissionHandoff handoff) {
    if (handoff == null
        || !hasText(handoff.reservationId(), 36)
        || !hasText(handoff.userSubject(), 128)
        || !hasText(handoff.activityId(), 64)
        || !hasText(handoff.idempotencyKey(), 128)
        || handoff.intentHash() == null
        || !handoff.intentHash().matches("[0-9a-f]{64}")
        || handoff.quantity() < 1
        || handoff.activityProjectionVersion() < 1
        || handoff.activityProjectionVersion() > SeckillLuaNumber.MAX_EXACT_INTEGER) {
      throw new IllegalArgumentException("Admission handoff is invalid");
    }
    try {
      UUID.fromString(handoff.reservationId());
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Admission handoff reservation id is invalid", exception);
    }
  }

  private static boolean hasText(String value, int maximumLength) {
    return value != null
        && !value.isBlank()
        && value.length() <= maximumLength
        && value.equals(value.strip());
  }

  public AdmissionDecision decide(
      SeckillReservation reservation, SeckillActivity activity, String userHash) {
    SeckillLuaNumber.requirePositiveExact(activity.allocatedQuota(), "Allocated quota");
    SeckillLuaNumber.requirePositiveExact(
        activity.projectionVersion(), "MySQL activity projection version");
    SeckillLuaNumber.requirePositiveExact(
        reservation.activityProjectionVersion(), "Reservation activity projection version");
    List<String> keys =
        List.of(
            activityKey(activity.activityId()),
            userKey(activity.activityId(), userHash),
            reservationKey(reservation.reservationId()),
            decisionKey(reservation.reservationId()),
            rebuildKey(activity.activityId()));
    Long result;
    try {
      result =
          redis.execute(
              ADMISSION_SCRIPT,
              keys,
              reservation.reservationId(),
              activity.activityId(),
              userHash,
              Integer.toString(reservation.quantity()),
              Long.toString(reservation.activityProjectionVersion()),
              Long.toString(activity.projectionVersion()),
              activity.startsAt().toString(),
              activity.endsAt().toString(),
              Long.toString(epochMicros(activity.startsAt())),
              Long.toString(epochMicros(activity.endsAt())),
              Long.toString(epochMicros(clock.instant())),
              Long.toString(properties.reservationTtl().toMillis()),
              Long.toString(properties.decisionMarkerTtl().toMillis()),
              "2");
    } catch (RuntimeException exception) {
      throw new AdmissionIndeterminateException("Seckill admission execution failed", exception);
    }
    if (result == null) {
      throw new AdmissionIndeterminateException("Seckill admission returned no result");
    }
    return switch (result.intValue()) {
      case 1, 2 ->
          new AdmissionDecision(ReservationState.ADMITTED, ReservationDecisionCode.ADMITTED);
      case 10 -> rejected(ReservationDecisionCode.ACTIVITY_INACTIVE);
      case 11 -> rejected(ReservationDecisionCode.NOT_OPEN);
      case 12 -> rejected(ReservationDecisionCode.EXPIRED);
      case 13 -> rejected(ReservationDecisionCode.STALE_VERSION);
      case 14 -> rejected(ReservationDecisionCode.EXHAUSTED);
      case 15 -> rejected(ReservationDecisionCode.DUPLICATE_USER);
      case 16 -> rejected(ReservationDecisionCode.TRANSACTION_TIMEOUT);
      case -10 -> throw new AdmissionIndeterminateException("Activity projection is missing");
      case -11 -> throw new AdmissionIndeterminateException("Activity projection is malformed");
      case -12 -> throw new AdmissionIndeterminateException("Admission projection conflicts");
      case -13 -> throw new AdmissionIndeterminateException("Admission projection is partial");
      case -14 ->
          throw new AdmissionIndeterminateException("Activity projection rebuild is active");
      case -15 ->
          throw new AdmissionIndeterminateException(
              "Activity projection version differs from MySQL truth");
      default ->
          throw new AdmissionIndeterminateException("Seckill admission returned an unknown result");
    };
  }

  public AdmissionDecision resolveDeadline(SeckillReservation reservation, String userHash) {
    SeckillLuaNumber.requirePositiveExact(
        reservation.activityProjectionVersion(), "Reservation activity projection version");
    String timeoutProjection =
        json(
            new ReservationProjection(
                reservation.reservationId(),
                reservation.activityId(),
                userHash,
                reservation.quantity(),
                reservation.activityProjectionVersion(),
                2,
                ReservationState.REJECTED,
                ReservationDecisionCode.TRANSACTION_TIMEOUT,
                false));
    final Long result;
    try {
      result =
          redis.execute(
              DEADLINE_SCRIPT,
              List.of(
                  reservationKey(reservation.reservationId()),
                  decisionKey(reservation.reservationId())),
              timeoutProjection,
              Long.toString(properties.reservationTtl().toMillis()),
              Long.toString(properties.decisionMarkerTtl().toMillis()));
    } catch (RuntimeException exception) {
      throw new AdmissionIndeterminateException(
          "Transaction deadline resolution failed", exception);
    }
    if (result == null) {
      throw new AdmissionIndeterminateException(
          "Transaction deadline resolution returned no result");
    }
    if (result == -12) {
      throw new AdmissionIndeterminateException("Transaction deadline projection is malformed");
    }
    if (result == -13) {
      throw new AdmissionIndeterminateException("Transaction deadline projection conflicts");
    }
    if (result != 1 && result != 2) {
      throw new AdmissionIndeterminateException(
          "Transaction deadline resolution returned an unknown result");
    }
    return readDurableDecision(reservation, userHash);
  }

  private AdmissionDecision readDurableDecision(
      SeckillReservation reservation, String expectedUserHash) {
    final String marker;
    try {
      marker = redis.opsForValue().get(decisionKey(reservation.reservationId()));
    } catch (RuntimeException exception) {
      throw new AdmissionIndeterminateException("Durable decision read failed", exception);
    }
    if (marker == null) {
      throw new AdmissionIndeterminateException("Durable decision is missing");
    }
    try {
      var payload = objectMapper.readTree(marker);
      if (!reservation.reservationId().equals(payload.path("reservationId").asText())
          || !reservation.activityId().equals(payload.path("activityId").asText())
          || !expectedUserHash.equals(payload.path("userHash").asText())
          || reservation.quantity() != payload.path("quantity").asInt()
          || reservation.activityProjectionVersion()
              != payload.path("activityProjectionVersion").asLong()
          || payload.path("reservationVersion").asLong() != 2) {
        throw new AdmissionIndeterminateException("Durable decision conflicts with MySQL truth");
      }
      String state = payload.path("state").asText();
      String code = payload.path("decisionCode").asText();
      if ("ADMITTED".equals(state) && "ADMITTED".equals(code)) {
        return new AdmissionDecision(ReservationState.ADMITTED, ReservationDecisionCode.ADMITTED);
      }
      if ("REJECTED".equals(state) && !code.isBlank() && !"ADMITTED".equals(code)) {
        try {
          return rejected(ReservationDecisionCode.valueOf(code));
        } catch (IllegalArgumentException exception) {
          throw new AdmissionIndeterminateException("Durable decision code is unknown", exception);
        }
      }
      throw new AdmissionIndeterminateException("Durable decision is malformed");
    } catch (JsonProcessingException exception) {
      throw new AdmissionIndeterminateException("Durable decision is unreadable", exception);
    }
  }

  public String acquireRebuild(String activityId) {
    String token = UUID.randomUUID().toString();
    final Boolean acquired;
    try {
      acquired =
          redis
              .opsForValue()
              .setIfAbsent(rebuildKey(activityId), token, properties.rebuildLockTtl());
    } catch (RuntimeException exception) {
      throw new AdmissionIndeterminateException("Reservation rebuild lock failed", exception);
    }
    if (!Boolean.TRUE.equals(acquired)) {
      throw new AdmissionIndeterminateException("Reservation rebuild is already active");
    }
    return token;
  }

  public void releaseRebuild(String activityId, String token) {
    try {
      redis.execute(RELEASE_LOCK_SCRIPT, List.of(rebuildKey(activityId)), token);
    } catch (RuntimeException exception) {
      throw new AdmissionIndeterminateException(
          "Reservation rebuild lock release failed", exception);
    }
  }

  public RebuildResult rebuild(
      SeckillActivity activity,
      List<SeckillReservation> reservations,
      long remainingQuota,
      String lockToken) {
    SeckillLuaNumber.requirePositiveExact(activity.allocatedQuota(), "Allocated quota");
    SeckillLuaNumber.requirePositiveExact(
        activity.projectionVersion(), "MySQL activity projection version");
    SeckillLuaNumber.requireNonNegativeExact(remainingQuota, "Remaining seckill quota");
    if (remainingQuota > activity.allocatedQuota()) {
      throw new IllegalArgumentException("Reservation rebuild quota is invalid");
    }
    List<String> keys = new ArrayList<>();
    keys.add(activityKey(activity.activityId()));
    keys.add(rebuildKey(activity.activityId()));
    List<String> arguments = new ArrayList<>();
    arguments.add(lockToken);
    arguments.add(json(SeckillProjection.from(activity, remainingQuota)));
    arguments.add(Long.toString(activity.projectionVersion()));
    arguments.add(Long.toString(properties.reservationTtl().toMillis()));
    arguments.add(Long.toString(properties.decisionMarkerTtl().toMillis()));
    arguments.add(Integer.toString(reservations.size()));
    Map<String, String> admittedReservationByUser = new HashMap<>();
    for (SeckillReservation reservation : reservations) {
      if (reservation.state() == ReservationState.PENDING
          || reservation.decisionCode() == null
          || !hasValidTerminalShape(reservation)) {
        throw new AdmissionIndeterminateException(
            "Pending reservation prevents projection rebuild");
      }
      String userHash = SeckillReservationService.sha256(reservation.userSubject());
      if (SeckillReservationService.blocksAnotherAdmission(reservation.state())) {
        admittedReservationByUser.putIfAbsent(userHash, reservation.reservationId());
      }
    }
    for (SeckillReservation reservation : reservations) {
      String userHash = SeckillReservationService.sha256(reservation.userSubject());
      SeckillLuaNumber.requirePositiveExact(
          reservation.activityProjectionVersion(), "Reservation activity projection version");
      keys.add(userKey(activity.activityId(), userHash));
      keys.add(reservationKey(reservation.reservationId()));
      keys.add(decisionKey(reservation.reservationId()));
      arguments.add(
          json(
              new ReservationProjection(
                  reservation.reservationId(),
                  reservation.activityId(),
                  userHash,
                  reservation.quantity(),
                  reservation.activityProjectionVersion(),
                  reservation.projectionVersion(),
                  reservation.state(),
                  reservation.decisionCode(),
                  reservation.state() == ReservationState.ORDERED
                      || reservation.state() == ReservationState.CANCELLED)));
      arguments.add(reservation.state().name());
      arguments.add(reservation.reservationId());
      arguments.add(Long.toString(reservation.projectionVersion()));
      arguments.add(admittedReservationByUser.getOrDefault(userHash, ""));
    }

    final Long result;
    try {
      result = redis.execute(REBUILD_SCRIPT, keys, arguments.toArray());
    } catch (RuntimeException exception) {
      throw new AdmissionIndeterminateException("Reservation projection rebuild failed", exception);
    }
    if (result == null) {
      throw new AdmissionIndeterminateException(
          "Reservation projection rebuild returned no result");
    }
    return switch (result.intValue()) {
      case 1 -> RebuildResult.APPLIED;
      case 0 -> RebuildResult.STALE_REJECTED;
      case -11 -> throw new AdmissionIndeterminateException("Rebuild projection is malformed");
      case -12 -> throw new AdmissionIndeterminateException("Rebuild projection conflicts");
      case -20 -> throw new AdmissionIndeterminateException("Reservation rebuild lock was lost");
      default ->
          throw new AdmissionIndeterminateException(
              "Reservation projection rebuild returned an unknown result");
    };
  }

  public String activityKey(String activityId) {
    return SeckillProjectionStore.KEY_PREFIX + activityId;
  }

  public String reservationKey(String reservationId) {
    return RESERVATION_PREFIX + reservationId;
  }

  public String decisionKey(String reservationId) {
    return DECISION_PREFIX + reservationId;
  }

  public String userKey(String activityId, String userHash) {
    return USER_PREFIX + activityId + ":" + userHash;
  }

  public String intentKey(String activityId, String userHash, String idempotencyKey) {
    String canonical =
        activityId.length()
            + ":"
            + activityId
            + ":"
            + userHash.length()
            + ":"
            + userHash
            + ":"
            + idempotencyKey.length()
            + ":"
            + idempotencyKey;
    return INTENT_PREFIX + SeckillReservationService.sha256(canonical);
  }

  public String handoffKey(String reservationId) {
    return HANDOFF_PREFIX + reservationId;
  }

  public String activityHandoffKey(String activityId) {
    return ACTIVITY_HANDOFF_PREFIX + activityId;
  }

  public String rebuildKey(String activityId) {
    return REBUILD_PREFIX + activityId;
  }

  String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new AdmissionIndeterminateException(
          "Reservation projection serialization failed", exception);
    }
  }

  private static boolean hasValidTerminalShape(SeckillReservation reservation) {
    return switch (reservation.state()) {
      case ADMITTED, REJECTED ->
          reservation.projectionVersion() == 2 && reservation.orderId() == null;
      case ORDERED -> reservation.projectionVersion() == 3 && reservation.orderId() != null;
      case UNFULFILLED -> reservation.projectionVersion() == 3 && reservation.orderId() == null;
      case CANCELLED -> reservation.projectionVersion() == 4 && reservation.orderId() != null;
      case PENDING -> false;
    };
  }

  private static AdmissionDecision rejected(ReservationDecisionCode code) {
    return new AdmissionDecision(ReservationState.REJECTED, code);
  }

  private static long epochMicros(java.time.Instant instant) {
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
  }

  public record AdmissionDecision(ReservationState state, ReservationDecisionCode decisionCode) {}

  public record AdmissionHandoff(
      String reservationId,
      String userSubject,
      String activityId,
      String idempotencyKey,
      String intentHash,
      int quantity,
      long activityProjectionVersion) {}

  public record PreAdmission(
      AdmissionHandoff handoff,
      AdmissionDecision decision,
      boolean replay,
      boolean handoffPending) {}

  public record AdmissionProjection(
      String reservationId,
      String activityId,
      int quantity,
      long activityProjectionVersion,
      ReservationState state,
      ReservationDecisionCode decisionCode,
      boolean handoffPending) {}

  public enum RebuildResult {
    APPLIED,
    STALE_REJECTED
  }

  private record ReservationProjection(
      String reservationId,
      String activityId,
      String userHash,
      int quantity,
      long activityProjectionVersion,
      long reservationVersion,
      ReservationState state,
      ReservationDecisionCode decisionCode,
      boolean durableOrderCreated) {}

  public static final class AdmissionIndeterminateException extends RuntimeException {
    AdmissionIndeterminateException(String message) {
      super(message);
    }

    AdmissionIndeterminateException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
