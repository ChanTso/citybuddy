package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MockPaymentPropertiesTest {
  @Test
  void defaultsSafePermissionAndTimeBounds() {
    MockPaymentProperties properties =
        new MockPaymentProperties(
            null, "callback-key", "not-a-secret-not-a-secret-not-a-secret", null, null, 0);

    assertThat(properties.requiredPermission()).isEqualTo("payment:create");
    assertThat(properties.callbackMaximumAge()).isEqualTo(Duration.ofMinutes(5));
    assertThat(properties.callbackClockSkew()).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.lockWaitTimeoutSeconds()).isEqualTo(1);
  }

  @Test
  void rejectsWeakOrInvalidCallbackConfiguration() {
    assertThatThrownBy(
            () -> new MockPaymentProperties(null, "key", "short", null, Duration.ZERO, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MockPaymentProperties(
                    null,
                    "key",
                    "not-a-secret-not-a-secret-not-a-secret",
                    Duration.ZERO,
                    Duration.ZERO,
                    1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MockPaymentProperties(
                    null,
                    "key",
                    "not-a-secret-not-a-secret-not-a-secret",
                    Duration.ofMinutes(1),
                    Duration.ofSeconds(-1),
                    1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new MockPaymentProperties(
                    null,
                    "key",
                    "not-a-secret-not-a-secret-not-a-secret",
                    Duration.ofMinutes(1),
                    Duration.ZERO,
                    61))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
