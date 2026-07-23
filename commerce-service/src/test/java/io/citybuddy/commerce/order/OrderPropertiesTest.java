package io.citybuddy.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OrderPropertiesTest {
  @Test
  void defaultsToOneSecondLockWaitBoundary() {
    OrderProperties properties = new OrderProperties(null, 0, 0, 0);

    assertThat(properties.lockWaitTimeoutSeconds()).isEqualTo(1);
  }

  @Test
  void rejectsLockWaitBoundariesOutsideMysqlSessionRange() {
    assertThatThrownBy(() -> new OrderProperties(null, 0, 0, 61))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 1 and 60");
  }
}
