package io.citybuddy.commerce.action;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.actions")
public record ActionProperties(
    String requiredScope,
    Duration pendingTtl,
    int lockWaitTimeoutSeconds,
    int maximumObservationAttempts,
    Duration observationBackoff) {

  public ActionProperties {
    requiredScope =
        requiredScope == null || requiredScope.isBlank() ? "refund:create" : requiredScope;
    pendingTtl = pendingTtl == null ? Duration.ofMinutes(15) : pendingTtl;
    lockWaitTimeoutSeconds = lockWaitTimeoutSeconds == 0 ? 1 : lockWaitTimeoutSeconds;
    maximumObservationAttempts = maximumObservationAttempts == 0 ? 3 : maximumObservationAttempts;
    observationBackoff = observationBackoff == null ? Duration.ofMillis(25) : observationBackoff;
    if (requiredScope.contains(" ") || requiredScope.contains("*")) {
      throw new IllegalArgumentException("requiredScope must be exact");
    }
    if (pendingTtl.compareTo(Duration.ofMinutes(1)) < 0
        || pendingTtl.compareTo(Duration.ofHours(24)) > 0) {
      throw new IllegalArgumentException("pendingTtl must be between 1 minute and 24 hours");
    }
    if (lockWaitTimeoutSeconds < 1 || lockWaitTimeoutSeconds > 60) {
      throw new IllegalArgumentException("lockWaitTimeoutSeconds must be between 1 and 60");
    }
    if (maximumObservationAttempts < 1 || maximumObservationAttempts > 10) {
      throw new IllegalArgumentException("maximumObservationAttempts must be between 1 and 10");
    }
    if (observationBackoff.isNegative()
        || observationBackoff.isZero()
        || observationBackoff.compareTo(Duration.ofSeconds(5)) > 0) {
      throw new IllegalArgumentException(
          "observationBackoff must be positive and no more than 5 seconds");
    }
  }
}
