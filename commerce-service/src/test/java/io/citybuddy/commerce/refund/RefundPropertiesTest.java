package io.citybuddy.commerce.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RefundPropertiesTest {
  @Test
  void defaultsAreBoundedAndIndependentFromMockPaymentConfiguration() {
    RefundProperties properties = new RefundProperties(null, 0, 0, null);

    assertThat(properties.requiredPermission()).isEqualTo("refund:create");
    assertThat(properties.lockWaitTimeoutSeconds()).isEqualTo(1);
    assertThat(properties.maximumObservationAttempts()).isEqualTo(2);
    assertThat(properties.observationBackoff()).isEqualTo(Duration.ofMillis(25));
  }

  @Test
  void rejectsUnboundedPhysicalAndObservationConfiguration() {
    assertThatThrownBy(() -> new RefundProperties(null, 61, 2, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RefundProperties(null, 1, 11, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RefundProperties(null, 1, 2, Duration.ofSeconds(2)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
