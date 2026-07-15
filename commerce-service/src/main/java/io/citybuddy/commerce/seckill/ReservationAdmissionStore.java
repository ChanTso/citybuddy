package io.citybuddy.commerce.seckill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class ReservationAdmissionStore {
  static final String RESERVATION_PREFIX = "commerce:seckill:reservation:";
  static final String DECISION_PREFIX = "commerce:seckill:decision:";
  static final String USER_PREFIX = "commerce:seckill:user:";
  static final String REBUILD_PREFIX = "commerce:seckill:rebuild:";

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

          local function same_activity(left, right)
            return left.activityId == right.activityId
              and left.projectionVersion == right.projectionVersion
              and left.startsAt == right.startsAt
              and left.endsAt == right.endsAt
              and left.state == right.state
          end

          local function same_reservation(left, right)
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
            if not existing_activity then return -11 end
            local existing_version = tonumber(existing_activity.projectionVersion)
            if not existing_version or existing_version < 1
                or existing_version > MAX_JSON_INTEGER then return -11 end
            if existing_version > incoming_activity_version then return 0 end
            if existing_version == incoming_activity_version
                and not same_activity(existing_activity, incoming_activity) then
              return -12
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
                or (state ~= 'ADMITTED' and state ~= 'REJECTED' and state ~= 'ORDERED') then
              return -12
            end
            local quantity = tonumber(incoming.quantity)
            local activity_version = tonumber(incoming.activityProjectionVersion)
            if not quantity or quantity < 1 or quantity > MAX_JSON_INTEGER
                or not activity_version or activity_version < 1
                or activity_version > MAX_JSON_INTEGER then return -12 end

            for offset = 1, 2 do
              local existing_value = redis.call('GET', KEYS[key_base + offset])
              if existing_value then
                local existing = decode(existing_value)
                if not existing then return -11 end
                local existing_version = tonumber(existing.reservationVersion)
                local existing_quantity = tonumber(existing.quantity)
                local existing_activity_version = tonumber(existing.activityProjectionVersion)
                if not existing_version or existing_version < 1
                    or existing_version > MAX_JSON_INTEGER
                    or not existing_quantity or existing_quantity < 1
                    or existing_quantity > MAX_JSON_INTEGER
                    or not existing_activity_version or existing_activity_version < 1
                    or existing_activity_version > MAX_JSON_INTEGER then return -11 end
                if existing_version > version then return 0 end
                if existing_version == version and not same_reservation(existing, incoming) then
                  return -12
                end
              end
            end

            local existing_user = redis.call('GET', KEYS[key_base])
            if (state == 'ADMITTED' or state == 'ORDERED')
                and expected_user_marker ~= reservation_id then return -12 end
            if expected_user_marker == '' then
              if existing_user then return -12 end
            elseif existing_user and existing_user ~= expected_user_marker then
              return -12
            end
          end

          local writes = {KEYS[1], ARGV[2]}
          for index = 0, count - 1 do
            local key_base = 3 + index * 3
            local argument_base = 7 + index * 5
            local payload = ARGV[argument_base]
            local state = ARGV[argument_base + 1]
            local reservation_id = ARGV[argument_base + 2]
            if state == 'ADMITTED' or state == 'ORDERED' then
              table.insert(writes, KEYS[key_base])
              table.insert(writes, reservation_id)
            end
            table.insert(writes, KEYS[key_base + 1])
            table.insert(writes, payload)
            table.insert(writes, KEYS[key_base + 2])
            table.insert(writes, payload)
          end
          redis.call('MSET', unpack(writes))

          for index = 0, count - 1 do
            local key_base = 3 + index * 3
            local argument_base = 7 + index * 5
            if ARGV[argument_base + 1] == 'ADMITTED'
                or ARGV[argument_base + 1] == 'ORDERED' then
              redis.call('PEXPIRE', KEYS[key_base], ARGV[4])
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

  public TransactionResolution transactionResolution(String reservationId) {
    final String marker;
    try {
      marker = redis.opsForValue().get(decisionKey(reservationId));
    } catch (RuntimeException exception) {
      return TransactionResolution.UNKNOWN;
    }
    if (marker == null) {
      return TransactionResolution.UNKNOWN;
    }
    try {
      var payload = objectMapper.readTree(marker);
      if (!reservationId.equals(payload.path("reservationId").asText())) {
        return TransactionResolution.UNKNOWN;
      }
      String state = payload.path("state").asText();
      String code = payload.path("decisionCode").asText();
      if (("ADMITTED".equals(state) || "ORDERED".equals(state)) && "ADMITTED".equals(code)) {
        return TransactionResolution.COMMIT;
      }
      if ("REJECTED".equals(state) && !code.isBlank() && !"ADMITTED".equals(code)) {
        return TransactionResolution.ROLLBACK;
      }
      return TransactionResolution.UNKNOWN;
    } catch (JsonProcessingException exception) {
      return TransactionResolution.UNKNOWN;
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
    var admittedUsers = new HashSet<String>();
    Map<String, String> admittedReservationByUser = new HashMap<>();
    for (SeckillReservation reservation : reservations) {
      if (reservation.state() == ReservationState.PENDING
          || reservation.decisionCode() == null
          || (reservation.state() == ReservationState.ORDERED
              ? reservation.projectionVersion() != 3 || reservation.orderId() == null
              : reservation.projectionVersion() != 2)) {
        throw new AdmissionIndeterminateException(
            "Pending reservation prevents projection rebuild");
      }
      String userHash = SeckillReservationService.sha256(reservation.userSubject());
      if ((reservation.state() == ReservationState.ADMITTED
              || reservation.state() == ReservationState.ORDERED)
          && !admittedUsers.add(userHash)) {
        throw new AdmissionIndeterminateException(
            "MySQL truth contains repeated admitted reservations for one user");
      }
      if (reservation.state() == ReservationState.ADMITTED
          || reservation.state() == ReservationState.ORDERED) {
        admittedReservationByUser.put(userHash, reservation.reservationId());
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
                  reservation.state() == ReservationState.ORDERED)));
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

  private static AdmissionDecision rejected(ReservationDecisionCode code) {
    return new AdmissionDecision(ReservationState.REJECTED, code);
  }

  private static long epochMicros(java.time.Instant instant) {
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
  }

  public record AdmissionDecision(ReservationState state, ReservationDecisionCode decisionCode) {}

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
