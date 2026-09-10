package io.citybuddy.commerce.seckill;

import java.time.Instant;

public record SeckillOffer(
    String activityId,
    String productId,
    String name,
    long unitPriceMinor,
    String currency,
    Instant startsAt,
    Instant endsAt,
    long activityVersion) {}
