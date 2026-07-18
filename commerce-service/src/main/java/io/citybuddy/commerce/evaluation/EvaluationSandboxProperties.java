package io.citybuddy.commerce.evaluation;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.evaluation")
public record EvaluationSandboxProperties(
    String managementClientId,
    String managementClientSecret,
    String authBaseUrl,
    String authClientId,
    String authClientSecret,
    String identityIssuer,
    String userAudience,
    String jwksUrl,
    Duration jwksCacheTtl,
    Duration clockSkew,
    Duration provisioningTimeout,
    Duration authExpirySafety,
    Duration cleanupRetry,
    Duration janitorInterval,
    int maxCleanupAttempts,
    int janitorBatchSize) {

  public EvaluationSandboxProperties {
    provisioningTimeout = defaultDuration(provisioningTimeout, Duration.ofSeconds(30));
    jwksCacheTtl = defaultDuration(jwksCacheTtl, Duration.ofMinutes(5));
    clockSkew = defaultDuration(clockSkew, Duration.ofSeconds(30));
    authExpirySafety = defaultDuration(authExpirySafety, Duration.ofSeconds(10));
    cleanupRetry = defaultDuration(cleanupRetry, Duration.ofSeconds(5));
    janitorInterval = defaultDuration(janitorInterval, Duration.ofSeconds(5));
    maxCleanupAttempts = maxCleanupAttempts == 0 ? 3 : maxCleanupAttempts;
    janitorBatchSize = janitorBatchSize == 0 ? 10 : janitorBatchSize;
    requireText(managementClientId, "managementClientId");
    requireText(managementClientSecret, "managementClientSecret");
    requireText(authBaseUrl, "authBaseUrl");
    requireText(authClientId, "authClientId");
    requireText(authClientSecret, "authClientSecret");
    requireText(identityIssuer, "identityIssuer");
    requireText(userAudience, "userAudience");
    requireText(jwksUrl, "jwksUrl");
    requireRange(jwksCacheTtl, Duration.ofSeconds(1), Duration.ofMinutes(30), "jwksCacheTtl");
    requireRange(clockSkew, Duration.ZERO, Duration.ofMinutes(1), "clockSkew");
    requireRange(
        provisioningTimeout, Duration.ofSeconds(5), Duration.ofSeconds(45), "provisioningTimeout");
    requireRange(
        authExpirySafety, Duration.ofSeconds(1), Duration.ofMinutes(1), "authExpirySafety");
    requireRange(cleanupRetry, Duration.ofSeconds(1), Duration.ofMinutes(1), "cleanupRetry");
    requireRange(janitorInterval, Duration.ofSeconds(1), Duration.ofMinutes(1), "janitorInterval");
    if (maxCleanupAttempts < 1 || maxCleanupAttempts > 5) {
      throw new IllegalArgumentException("maxCleanupAttempts must be between 1 and 5");
    }
    if (janitorBatchSize < 1 || janitorBatchSize > 50) {
      throw new IllegalArgumentException("janitorBatchSize must be between 1 and 50");
    }
  }

  private static Duration defaultDuration(Duration value, Duration fallback) {
    return value == null ? fallback : value;
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static void requireRange(
      Duration value, Duration minimum, Duration maximum, String name) {
    if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
      throw new IllegalArgumentException(name + " is outside the bounded range");
    }
  }
}
