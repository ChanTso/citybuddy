package io.citybuddy.commerce.seckill;

import java.time.Instant;

public record SeckillProjection(
    String activityId,
    long projectionVersion,
    Instant startsAt,
    Instant endsAt,
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
        activity.state(),
        remainingQuota);
  }
}
