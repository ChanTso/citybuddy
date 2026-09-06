package io.citybuddy.commerce.merchant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.merchant.enabled", havingValue = "true")
public class MerchantMarketingConfiguration {
  @Bean
  MerchantMarketingService merchantMarketingService(MerchantMarketingRepository repository) {
    return new MerchantMarketingService(repository);
  }
}
