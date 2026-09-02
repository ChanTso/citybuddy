package io.citybuddy.commerce.order;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@FunctionalInterface
public interface OrderStockRaceMetrics {
  String METER_NAME = "citybuddy.commerce.order.stock.races";

  void recordMiss();

  static OrderStockRaceMetrics noop() {
    return () -> {};
  }

  static OrderStockRaceMetrics instrumented(MeterRegistry registry) {
    Counter counter =
        Counter.builder(METER_NAME)
            .description(
                "Conditional standard-order product decrements that matched no row after an "
                    + "orderable, sufficient-stock snapshot")
            .register(registry);
    return counter::increment;
  }
}
