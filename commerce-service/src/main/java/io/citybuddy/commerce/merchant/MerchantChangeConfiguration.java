package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.catalog.ProductPublicationService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.merchant.enabled", havingValue = "true")
public class MerchantChangeConfiguration {
  @Bean
  MerchantChangeRepository merchantChangeRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    return new MerchantChangeRepository(jdbc, mapper);
  }

  @Bean
  MerchantProductOperations merchantProductOperations(
      JdbcTemplate jdbc, ObjectMapper mapper, ProductPublicationService publication) {
    return new MerchantProductOperations(jdbc, mapper, publication);
  }

  @Bean
  MerchantChangeService merchantChangeService(
      MerchantChangeRepository repository,
      MerchantService prices,
      MerchantProductOperations products,
      ObjectMapper mapper,
      @Qualifier("catalogClock") Clock clock) {
    return new MerchantChangeService(repository, prices, products, mapper, clock);
  }
}
