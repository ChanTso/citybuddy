package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SeckillTimeoutPropertiesTest {
  @Test
  void defaultsToBoundedDispatchAndDeliveryPolicies() {
    SeckillTimeoutProperties properties =
        new SeckillTimeoutProperties(
            "proxy:8081", "timeouts", "timeout-consumer", null, null, null, null, null, null);

    assertThat(properties.receiveAwait()).isEqualTo(Duration.ofSeconds(2));
    assertThat(properties.receiveInvisibleDuration()).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.receiveBatchSize()).isEqualTo(16);
    assertThat(properties.dispatchBatchSize()).isEqualTo(32);
    assertThat(properties.maximumDispatchAttempts()).isEqualTo(5);
    assertThat(properties.maximumDeliveryAttempts()).isEqualTo(3);
  }

  @Test
  void rejectsMissingBrokerIdentityAndUnboundedPolicies() {
    assertThatThrownBy(
            () ->
                new SeckillTimeoutProperties(
                    null, "timeouts", "timeout-consumer", null, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("RocketMQ endpoints is required");
    assertThatThrownBy(
            () ->
                new SeckillTimeoutProperties(
                    "proxy:8081",
                    "timeouts",
                    "timeout-consumer",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(30),
                    16,
                    32,
                    0,
                    3))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Maximum dispatch attempts");
  }
}
