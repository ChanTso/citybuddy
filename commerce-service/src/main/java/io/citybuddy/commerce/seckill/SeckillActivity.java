package io.citybuddy.commerce.seckill;

import java.time.Instant;

public record SeckillActivity(
    String activityId,
    String productId,
    Instant startsAt,
    Instant endsAt,
    SeckillActivityState state,
    long allocatedQuota,
    long projectionVersion) {}
