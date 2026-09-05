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
public class MerchantConfiguration {
  @Bean
  MerchantRepository merchantRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    return new MerchantRepository(jdbc, mapper);
  }

  @Bean
  MerchantService merchantService(
      MerchantRepository repository,
      ProductPublicationService prices,
      ObjectMapper mapper,
      @Qualifier("catalogClock") Clock clock) {
    return new MerchantService(repository, prices, mapper, clock);
  }
}
