package io.citybuddy.commerce.order;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OrderMetricsConfiguration {
  @Bean
  @ConditionalOnProperty(name = "citybuddy.metrics.enabled", havingValue = "true")
  PrometheusMeterRegistry commercePrometheusMeterRegistry() {
    return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
  }

  @Bean
  OrderStockRaceMetrics orderStockRaceMetrics(
      ObjectProvider<PrometheusMeterRegistry> registryProvider) {
    PrometheusMeterRegistry registry = registryProvider.getIfAvailable();
    return registry == null
        ? OrderStockRaceMetrics.noop()
        : OrderStockRaceMetrics.instrumented(registry);
  }
}
