package io.citybuddy.commerce.order;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "citybuddy.metrics.enabled", havingValue = "true")
final class OrderMetricsController {
  private static final MediaType PROMETHEUS_CONTENT_TYPE =
      MediaType.parseMediaType("text/plain; version=0.0.4; charset=utf-8");

  private final PrometheusMeterRegistry registry;

  OrderMetricsController(PrometheusMeterRegistry registry) {
    this.registry = registry;
  }

  @GetMapping(value = "/internal/metrics/prometheus", produces = "text/plain")
  ResponseEntity<String> scrape() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(PROMETHEUS_CONTENT_TYPE)
        .body(registry.scrape());
  }
}
