package io.citybuddy.commerce.seckill;

import java.time.Instant;

public record SeckillProjection(
    String activityId,
    long projectionVersion,
    Instant startsAt,
    Instant endsAt,
    long startsAtEpochMicros,
    long endsAtEpochMicros,
    SeckillActivityState state,
    long remainingQuota) {

  static SeckillProjection from(SeckillActivity activity) {
    return from(activity, activity.allocatedQuota());
  }

  static SeckillProjection from(SeckillActivity activity, long remainingQuota) {
    return new SeckillProjection(
        activity.activityId(),
        activity.projectionVersion(),
        activity.startsAt(),
        activity.endsAt(),
        epochMicros(activity.startsAt()),
        epochMicros(activity.endsAt()),
        activity.state(),
        remainingQuota);
  }

  private static long epochMicros(Instant instant) {
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
  }
}
