package io.citybuddy.commerce.payment;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.mock-payment")
public record MockPaymentProperties(
    String requiredPermission,
    String callbackKeyId,
    String callbackSecret,
    Duration callbackMaximumAge,
    Duration callbackClockSkew) {
  public MockPaymentProperties {
    requiredPermission = defaultText(requiredPermission, "payment:create");
    callbackMaximumAge = callbackMaximumAge == null ? Duration.ofMinutes(5) : callbackMaximumAge;
    callbackClockSkew = callbackClockSkew == null ? Duration.ofSeconds(30) : callbackClockSkew;
    requireText(requiredPermission, 128, "Payment permission");
    requireText(callbackKeyId, 64, "Callback key id");
    requireText(callbackSecret, 512, "Callback secret");
    if (callbackSecret.length() < 32) {
      throw new IllegalArgumentException("Callback secret must contain at least 32 characters");
    }
    requirePositive(callbackMaximumAge, "Callback maximum age");
    if (callbackClockSkew.isNegative()) {
      throw new IllegalArgumentException("Callback clock skew must not be negative");
    }
  }

  private static String defaultText(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static void requireText(String value, int maximumLength, String label) {
    if (value == null
        || value.isBlank()
        || value.length() > maximumLength
        || !value.equals(value.strip())) {
      throw new IllegalArgumentException(label + " is invalid");
    }
  }

  private static void requirePositive(Duration duration, String label) {
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException(label + " must be positive");
    }
  }
}
