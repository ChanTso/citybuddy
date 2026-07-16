package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SeckillOrderPropertiesTest {
  @Test
  void defaultsToExplicitBoundedConsumerDurations() {
    SeckillOrderProperties properties =
        new SeckillOrderProperties(
            null, "proxy:8081", "transactions", "orders", null, null, null, null);

    assertThat(properties.requiredPermission()).isEqualTo("seckill:reserve");
    assertThat(properties.unpaidTimeout()).isEqualTo(Duration.ofMinutes(15));
    assertThat(properties.receiveAwait()).isEqualTo(Duration.ofSeconds(2));
    assertThat(properties.receiveInvisibleDuration()).isEqualTo(Duration.ofSeconds(30));
    assertThat(properties.receiveBatchSize()).isEqualTo(16);
  }

  @Test
  void rejectsInvisibleDurationsOutsideRocketMqBoundary() {
    assertThatThrownBy(
            () ->
                new SeckillOrderProperties(
                    "seckill:reserve",
                    "proxy:8081",
                    "transactions",
                    "orders",
                    Duration.ofMinutes(15),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(9),
                    16))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 10 seconds and 12 hours");
  }

  @Test
  void rejectsMissingBrokerIdentityAndOversizedBatch() {
    assertThatThrownBy(
            () ->
                new SeckillOrderProperties(
                    null, null, "transactions", "orders", null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("RocketMQ endpoints is required");
    assertThatThrownBy(
            () ->
                new SeckillOrderProperties(
                    null, "proxy:8081", "transactions", "orders", null, null, null, 33))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 1 and 32");
  }
}
