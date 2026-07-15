package io.citybuddy.commerce.seckill;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.seckill.reservation")
public record SeckillReservationProperties(
    Duration reservationTtl,
    Duration decisionMarkerTtl,
    Duration plannedBrokerTransactionTimeout,
    Duration plannedBrokerCheckInterval,
    Integer plannedBrokerMaximumChecks,
    Duration plannedBrokerSafetyMargin,
    Duration rebuildLockTtl) {

  private static final long MAX_REDIS_TTL_MILLIS = 99_999_999_999_999L;

  public SeckillReservationProperties {
    reservationTtl = defaulted(reservationTtl, Duration.ofMinutes(15));
    decisionMarkerTtl = defaulted(decisionMarkerTtl, Duration.ofMinutes(15));
    plannedBrokerTransactionTimeout =
        defaulted(plannedBrokerTransactionTimeout, Duration.ofMinutes(1));
    plannedBrokerCheckInterval = defaulted(plannedBrokerCheckInterval, Duration.ofSeconds(15));
    plannedBrokerMaximumChecks =
        plannedBrokerMaximumChecks == null ? 5 : plannedBrokerMaximumChecks;
    plannedBrokerSafetyMargin = defaulted(plannedBrokerSafetyMargin, Duration.ofSeconds(30));
    rebuildLockTtl = defaulted(rebuildLockTtl, Duration.ofSeconds(30));

    requireRedisMillisecond(reservationTtl, "Reservation TTL");
    requireRedisMillisecond(decisionMarkerTtl, "Decision-marker TTL");
    requireRedisMillisecond(plannedBrokerTransactionTimeout, "Planned broker transaction timeout");
    requireRedisMillisecond(plannedBrokerCheckInterval, "Planned broker check interval");
    requireRedisMillisecond(plannedBrokerSafetyMargin, "Planned broker safety margin");
    requireRedisMillisecond(rebuildLockTtl, "Rebuild lock TTL");
    if (plannedBrokerMaximumChecks < 1) {
      throw new IllegalArgumentException("Planned broker maximum checks must be positive");
    }

    Duration requiredCoverage =
        brokerCoverage(
            plannedBrokerTransactionTimeout,
            plannedBrokerCheckInterval,
            plannedBrokerMaximumChecks,
            plannedBrokerSafetyMargin);
    if (reservationTtl.compareTo(requiredCoverage) < 0
        || decisionMarkerTtl.compareTo(requiredCoverage) < 0) {
      throw new IllegalArgumentException(
          "Reservation and decision-marker TTLs must cover the planned broker window");
    }
  }

  public Duration minimumPlannedBrokerCoverage() {
    return brokerCoverage(
        plannedBrokerTransactionTimeout,
        plannedBrokerCheckInterval,
        plannedBrokerMaximumChecks,
        plannedBrokerSafetyMargin);
  }

  private static Duration defaulted(Duration value, Duration defaultValue) {
    return value == null ? defaultValue : value;
  }

  private static void requireRedisMillisecond(Duration value, String label) {
    final long milliseconds;
    try {
      milliseconds = value.toMillis();
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException(label + " exceeds the Redis millisecond range", exception);
    }
    if (milliseconds < 1) {
      throw new IllegalArgumentException(label + " must be at least one millisecond");
    }
    if (milliseconds > MAX_REDIS_TTL_MILLIS) {
      throw new IllegalArgumentException(label + " exceeds the safe Redis TTL range");
    }
  }

  private static Duration brokerCoverage(
      Duration transactionTimeout,
      Duration checkInterval,
      int maximumChecks,
      Duration safetyMargin) {
    try {
      return transactionTimeout.plus(checkInterval.multipliedBy(maximumChecks)).plus(safetyMargin);
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("Planned broker window is too large", exception);
    }
  }
}
