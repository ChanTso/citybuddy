package io.citybuddy.commerce.seckill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class SeckillProjectionStore {
  static final String KEY_PREFIX = "commerce:seckill:activity:";

  private static final DefaultRedisScript<Long> PUBLISH_SCRIPT =
      new DefaultRedisScript<>(
          """
          local MAX_JSON_INTEGER = 99999999999999
          local current = redis.call('GET', KEYS[1])
          if not current then
            redis.call('SET', KEYS[1], ARGV[1])
            return 1
          end
          local decoded, projection = pcall(cjson.decode, current)
          if not decoded or type(projection) ~= 'table' then
            return -1
          end
          local current_version = tonumber(projection.projectionVersion)
          local incoming_version = tonumber(ARGV[2])
          local current_remaining = tonumber(projection.remainingQuota)
          if not current_version or current_version < 1 or current_version > MAX_JSON_INTEGER
              or not incoming_version or incoming_version < 1
              or incoming_version > MAX_JSON_INTEGER
              or not current_remaining or current_remaining < 0
              or current_remaining > MAX_JSON_INTEGER then
            return -1
          end
          if current_version > incoming_version then
            return 0
          end
          if current_version == incoming_version then
            local incoming_ok, incoming = pcall(cjson.decode, ARGV[1])
            if not incoming_ok or type(incoming) ~= 'table' then
              return -1
            end
            local incoming_remaining = tonumber(incoming.remainingQuota)
            if not incoming_remaining or incoming_remaining < 0
                or incoming_remaining > MAX_JSON_INTEGER then
              return -1
            end
            if projection.activityId == incoming.activityId
                and projection.projectionVersion == incoming.projectionVersion
                and projection.startsAt == incoming.startsAt
                and projection.endsAt == incoming.endsAt
                and projection.state == incoming.state
                and projection.remainingQuota == incoming.remainingQuota then
              return 2
            end
            return -2
          end
          redis.call('SET', KEYS[1], ARGV[1])
          return 1
          """,
          Long.class);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  public SeckillProjectionStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  public PublishResult publish(SeckillActivity activity) {
    return publish(activity, activity.allocatedQuota());
  }

  public PublishResult publish(SeckillActivity activity, long remainingQuota) {
    SeckillLuaNumber.requirePositiveExact(
        activity.projectionVersion(), "Seckill projection version");
    SeckillLuaNumber.requirePositiveExact(activity.allocatedQuota(), "Allocated quota");
    SeckillLuaNumber.requireNonNegativeExact(remainingQuota, "Remaining seckill quota");
    if (remainingQuota > activity.allocatedQuota()) {
      throw new IllegalArgumentException("Remaining seckill quota is invalid");
    }
    SeckillProjection projection = SeckillProjection.from(activity, remainingQuota);
    String payload = serialize(projection);
    final Long result;
    try {
      result =
          redis.execute(
              PUBLISH_SCRIPT,
              List.of(key(activity.activityId())),
              payload,
              Long.toString(activity.projectionVersion()));
    } catch (RuntimeException exception) {
      throw new ProjectionWriteException("Seckill projection write failed", exception);
    }
    if (result == null) {
      throw new ProjectionWriteException("Seckill projection write returned no result");
    }
    return switch (result.intValue()) {
      case 1 -> PublishResult.APPLIED;
      case 2 -> PublishResult.IDEMPOTENT;
      case 0 -> PublishResult.STALE_REJECTED;
      case -1 -> throw new ProjectionWriteException("Existing seckill projection is malformed");
      case -2 ->
          throw new ProjectionWriteException(
              "Existing seckill projection conflicts at the same version");
      default ->
          throw new ProjectionWriteException("Seckill projection write returned an unknown result");
    };
  }

  public String key(String activityId) {
    return KEY_PREFIX + activityId;
  }

  private String serialize(SeckillProjection projection) {
    try {
      return objectMapper.writeValueAsString(projection);
    } catch (JsonProcessingException exception) {
      throw new ProjectionWriteException("Seckill projection serialization failed", exception);
    }
  }

  public enum PublishResult {
    APPLIED,
    IDEMPOTENT,
    STALE_REJECTED
  }

  public static final class ProjectionWriteException extends RuntimeException {
    ProjectionWriteException(String message) {
      super(message);
    }

    ProjectionWriteException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
