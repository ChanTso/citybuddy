package io.citybuddy.commerce.retail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.shopping.ShoppingPreferencesRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.catalog.enabled", havingValue = "true")
public class RetailFactsConfiguration {
  @Bean
  RetailPolicyRepository retailPolicyRepository(JdbcTemplate jdbc) {
    return new RetailPolicyRepository(jdbc);
  }

  @Bean
  RetailFulfillmentRepository retailFulfillmentRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    return new RetailFulfillmentRepository(jdbc, mapper);
  }

  @Bean
  RetailFulfillmentService retailFulfillmentService(
      RetailFulfillmentRepository repository, ShoppingPreferencesRepository preferences) {
    return new RetailFulfillmentService(repository, preferences, Clock.systemUTC());
  }
}
