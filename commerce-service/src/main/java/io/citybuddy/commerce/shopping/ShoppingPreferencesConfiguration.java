package io.citybuddy.commerce.shopping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.catalog.enabled", havingValue = "true")
public class ShoppingPreferencesConfiguration {
  @Bean
  ShoppingPreferencesRepository shoppingPreferencesRepository(
      JdbcTemplate jdbc, ObjectMapper mapper) {
    return new ShoppingPreferencesRepository(jdbc, mapper);
  }
}
