package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SeckillReservationPropertiesTest {
  @Test
  void derivesExplicitMinimumBrokerCoverage() {
    SeckillReservationProperties properties =
        new SeckillReservationProperties(
            Duration.ofMinutes(5),
            Duration.ofMinutes(5),
            Duration.ofMinutes(1),
            Duration.ofSeconds(15),
            4,
            Duration.ofSeconds(30),
            Duration.ofSeconds(20));

    assertThat(properties.minimumBrokerCoverage()).isEqualTo(Duration.ofMinutes(2).plusSeconds(30));
  }

  @Test
  void rejectsTtlsThatCannotCoverBrokerChecks() {
    assertThatThrownBy(
            () ->
                new SeckillReservationProperties(
                    Duration.ofMinutes(2),
                    Duration.ofMinutes(3),
                    Duration.ofMinutes(1),
                    Duration.ofSeconds(15),
                    4,
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(20)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cover the broker window");
  }

  @Test
  void rejectsPositiveDurationsThatRedisWouldRoundToZeroMilliseconds() {
    assertThatThrownBy(
            () ->
                new SeckillReservationProperties(
                    Duration.ofNanos(3),
                    Duration.ofNanos(3),
                    Duration.ofNanos(1),
                    Duration.ofNanos(1),
                    1,
                    Duration.ofNanos(1),
                    Duration.ofNanos(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one millisecond");
  }

  @Test
  void rejectsDurationsThatCouldOverflowRedisAbsoluteExpiration() {
    assertThatThrownBy(
            () ->
                new SeckillReservationProperties(
                    Duration.ofMillis(Long.MAX_VALUE),
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(1),
                    Duration.ofSeconds(15),
                    4,
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(20)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("safe Redis TTL range");
  }
}
