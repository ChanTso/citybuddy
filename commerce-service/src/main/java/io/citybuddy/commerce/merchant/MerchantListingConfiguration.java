package io.citybuddy.commerce.merchant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.merchant.enabled", havingValue = "true")
public class MerchantListingConfiguration {
  @Bean
  MerchantListingRepository merchantListingRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    return new MerchantListingRepository(jdbc, mapper);
  }

  @Bean
  MerchantListingService merchantListingService(
      MerchantListingRepository repository, @Qualifier("catalogClock") Clock clock) {
    return new MerchantListingService(repository, clock);
  }
}
