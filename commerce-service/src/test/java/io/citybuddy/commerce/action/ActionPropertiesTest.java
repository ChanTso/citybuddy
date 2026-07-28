package io.citybuddy.commerce.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ActionPropertiesTest {
  @Test
  void defaultsAreBoundedAndScopeIsExact() {
    ActionProperties properties = new ActionProperties(null, null, 0, 0, null);

    assertThat(properties.requiredScope()).isEqualTo("refund:create");
    assertThat(properties.pendingTtl()).isEqualTo(Duration.ofMinutes(15));
    assertThat(properties.lockWaitTimeoutSeconds()).isEqualTo(1);
    assertThat(properties.maximumObservationAttempts()).isEqualTo(3);
    assertThat(properties.observationBackoff()).isEqualTo(Duration.ofMillis(25));
  }

  @Test
  void rejectsUnboundedOrWildcardConfiguration() {
    assertThatThrownBy(
            () ->
                new ActionProperties(
                    "refund:*", Duration.ofMinutes(15), 1, 3, Duration.ofMillis(25)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ActionProperties(
                    "refund:create", Duration.ofSeconds(59), 1, 3, Duration.ofMillis(25)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ActionProperties(
                    "refund:create", Duration.ofMinutes(15), 61, 3, Duration.ofMillis(25)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ActionProperties(
                    "refund:create", Duration.ofMinutes(15), 1, 11, Duration.ofMillis(25)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
