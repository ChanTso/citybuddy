package io.citybuddy.commerce.action;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.actions")
public record ActionProperties(
    String requiredScope,
    Duration pendingTtl,
    int maximumConcurrencyAttempts,
    int lockWaitTimeoutSeconds) {

  public ActionProperties {
    requiredScope =
        requiredScope == null || requiredScope.isBlank() ? "refund:create" : requiredScope;
    pendingTtl = pendingTtl == null ? Duration.ofMinutes(15) : pendingTtl;
    maximumConcurrencyAttempts = maximumConcurrencyAttempts == 0 ? 3 : maximumConcurrencyAttempts;
    lockWaitTimeoutSeconds = lockWaitTimeoutSeconds == 0 ? 1 : lockWaitTimeoutSeconds;
    if (requiredScope.contains(" ") || requiredScope.contains("*")) {
      throw new IllegalArgumentException("requiredScope must be exact");
    }
    if (pendingTtl.compareTo(Duration.ofMinutes(1)) < 0
        || pendingTtl.compareTo(Duration.ofHours(24)) > 0) {
      throw new IllegalArgumentException("pendingTtl must be between 1 minute and 24 hours");
    }
    if (maximumConcurrencyAttempts < 1 || maximumConcurrencyAttempts > 10) {
      throw new IllegalArgumentException("maximumConcurrencyAttempts must be between 1 and 10");
    }
    if (lockWaitTimeoutSeconds < 1 || lockWaitTimeoutSeconds > 60) {
      throw new IllegalArgumentException("lockWaitTimeoutSeconds must be between 1 and 60");
    }
  }
}
