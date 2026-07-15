package io.citybuddy.commerce.seckill;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.seckill.reservation")
public record SeckillReservationProperties(
    Duration reservationTtl,
    Duration decisionMarkerTtl,
    Duration brokerTransactionTimeout,
    Duration brokerCheckInterval,
    Integer brokerMaximumChecks,
    Duration brokerSafetyMargin,
    Duration rebuildLockTtl) {

  private static final long MAX_REDIS_TTL_MILLIS = 99_999_999_999_999L;

  public SeckillReservationProperties {
    reservationTtl = defaulted(reservationTtl, Duration.ofMinutes(15));
    decisionMarkerTtl = defaulted(decisionMarkerTtl, Duration.ofMinutes(15));
    brokerTransactionTimeout = defaulted(brokerTransactionTimeout, Duration.ofSeconds(6));
    brokerCheckInterval = defaulted(brokerCheckInterval, Duration.ofSeconds(1));
    brokerMaximumChecks = brokerMaximumChecks == null ? 3 : brokerMaximumChecks;
    brokerSafetyMargin = defaulted(brokerSafetyMargin, Duration.ofSeconds(2));
    rebuildLockTtl = defaulted(rebuildLockTtl, Duration.ofSeconds(30));

    requireRedisMillisecond(reservationTtl, "Reservation TTL");
    requireRedisMillisecond(decisionMarkerTtl, "Decision-marker TTL");
    requireRedisMillisecond(brokerTransactionTimeout, "Broker transaction timeout");
    requireRedisMillisecond(brokerCheckInterval, "Broker check interval");
    requireRedisMillisecond(brokerSafetyMargin, "Broker safety margin");
    requireRedisMillisecond(rebuildLockTtl, "Rebuild lock TTL");
    if (brokerMaximumChecks < 1) {
      throw new IllegalArgumentException("Broker maximum checks must be positive");
    }

    Duration requiredCoverage =
        brokerCoverage(
            brokerTransactionTimeout, brokerCheckInterval, brokerMaximumChecks, brokerSafetyMargin);
    if (reservationTtl.compareTo(requiredCoverage) < 0
        || decisionMarkerTtl.compareTo(requiredCoverage) < 0) {
      throw new IllegalArgumentException(
          "Reservation and decision-marker TTLs must cover the broker window");
    }
  }

  public Duration minimumBrokerCoverage() {
    return brokerCoverage(
        brokerTransactionTimeout, brokerCheckInterval, brokerMaximumChecks, brokerSafetyMargin);
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
      throw new IllegalArgumentException("Broker window is too large", exception);
    }
  }
}
