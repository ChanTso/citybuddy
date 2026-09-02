package io.citybuddy.commerce.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OrderMetricsTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(OrderMetricsConfiguration.class, OrderMetricsController.class);

  @Test
  void metricsRegistryAndEndpointAreDisabledByDefault() {
    contextRunner.run(
        context -> {
          assertThat(context.getBeansOfType(OrderStockRaceMetrics.class)).hasSize(1);
          assertThat(context.getBeansOfType(PrometheusMeterRegistry.class)).isEmpty();
          assertThat(context.getBeansOfType(OrderMetricsController.class)).isEmpty();
        });
  }

  @Test
  void explicitOptInCreatesTheDedicatedRegistryAndEndpoint() {
    contextRunner
        .withPropertyValues("citybuddy.metrics.enabled=true")
        .run(
            context -> {
              assertThat(context.getBeansOfType(OrderStockRaceMetrics.class)).hasSize(1);
              assertThat(context.getBeansOfType(PrometheusMeterRegistry.class)).hasSize(1);
              assertThat(context.getBeansOfType(OrderMetricsController.class)).hasSize(1);
            });
  }

  @Test
  void endpointExportsOnlyTheUnlabelledCommerceCounter() throws Exception {
    PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    OrderStockRaceMetrics metrics = OrderStockRaceMetrics.instrumented(registry);
    metrics.recordMiss();
    metrics.recordMiss();
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new OrderMetricsController(registry)).build();

    String body =
        mvc.perform(get("/internal/metrics/prometheus"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body)
        .contains("citybuddy_commerce_order_stock_races_total 2.0")
        .doesNotContain("citybuddy_commerce_order_stock_races_total{")
        .doesNotContain("jvm_")
        .doesNotContain("process_");
  }

  @Test
  void internalMetricsEndpointIsNotPartOfTheStaticOpenApiContract() throws Exception {
    JsonNode openApi;
    try (InputStream input = getClass().getResourceAsStream("/openapi.json")) {
      assertThat(input).isNotNull();
      openApi = new ObjectMapper().readTree(input);
    }

    assertThat(openApi.path("paths").has("/internal/metrics/prometheus")).isFalse();
  }
}
