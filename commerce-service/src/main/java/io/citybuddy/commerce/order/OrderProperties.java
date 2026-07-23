package io.citybuddy.commerce.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.orders")
public record OrderProperties(
    String requiredPermission,
    int maximumQuantity,
    int maximumConcurrencyAttempts,
    int lockWaitTimeoutSeconds) {

  public OrderProperties {
    requiredPermission =
        requiredPermission == null || requiredPermission.isBlank()
            ? "order:create"
            : requiredPermission;
    maximumQuantity = maximumQuantity == 0 ? 100 : maximumQuantity;
    maximumConcurrencyAttempts = maximumConcurrencyAttempts == 0 ? 3 : maximumConcurrencyAttempts;
    lockWaitTimeoutSeconds = lockWaitTimeoutSeconds == 0 ? 1 : lockWaitTimeoutSeconds;
    if (maximumQuantity < 1) {
      throw new IllegalArgumentException("maximumQuantity must be positive");
    }
    if (maximumConcurrencyAttempts < 1 || maximumConcurrencyAttempts > 10) {
      throw new IllegalArgumentException("maximumConcurrencyAttempts must be between 1 and 10");
    }
    if (lockWaitTimeoutSeconds < 1 || lockWaitTimeoutSeconds > 60) {
      throw new IllegalArgumentException("lockWaitTimeoutSeconds must be between 1 and 60");
    }
  }
}
