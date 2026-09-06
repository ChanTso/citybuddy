package io.citybuddy.commerce.retail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.catalog.enabled", havingValue = "true")
public class RetailCatalogConfiguration {
  @Bean
  RetailCatalogRepository retailCatalogRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    return new RetailCatalogRepository(jdbc, mapper);
  }

  @Bean
  RetailCatalogService retailCatalogService(RetailCatalogRepository repository) {
    return new RetailCatalogService(repository);
  }
}
