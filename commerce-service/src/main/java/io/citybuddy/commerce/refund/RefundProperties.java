package io.citybuddy.commerce.refund;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.refund")
public record RefundProperties(
    String requiredPermission,
    int lockWaitTimeoutSeconds,
    int maximumObservationAttempts,
    Duration observationBackoff) {
  public RefundProperties {
    requiredPermission =
        requiredPermission == null || requiredPermission.isBlank()
            ? "refund:create"
            : requiredPermission;
    if (requiredPermission.length() > 128
        || !requiredPermission.equals(requiredPermission.strip())) {
      throw new IllegalArgumentException("Refund permission is invalid");
    }
    lockWaitTimeoutSeconds = lockWaitTimeoutSeconds == 0 ? 1 : lockWaitTimeoutSeconds;
    maximumObservationAttempts = maximumObservationAttempts == 0 ? 2 : maximumObservationAttempts;
    observationBackoff = observationBackoff == null ? Duration.ofMillis(25) : observationBackoff;
    if (lockWaitTimeoutSeconds < 1 || lockWaitTimeoutSeconds > 60) {
      throw new IllegalArgumentException("Refund lock wait timeout must be between 1 and 60");
    }
    if (maximumObservationAttempts < 1 || maximumObservationAttempts > 10) {
      throw new IllegalArgumentException("Refund observation attempts must be between 1 and 10");
    }
    if (observationBackoff.isNegative()
        || observationBackoff.compareTo(Duration.ofSeconds(1)) > 0) {
      throw new IllegalArgumentException(
          "Refund observation backoff must be between 0 and 1 second");
    }
  }
}
