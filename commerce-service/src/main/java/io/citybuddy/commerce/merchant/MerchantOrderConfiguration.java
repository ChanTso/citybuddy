package io.citybuddy.commerce.merchant;

import io.citybuddy.commerce.shopping.ShoppingOrderRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.merchant.enabled", havingValue = "true")
public class MerchantOrderConfiguration {
  @Bean
  MerchantOrderService merchantOrderService(JdbcTemplate jdbc) {
    return new MerchantOrderService(new ShoppingOrderRepository(jdbc));
  }
}
