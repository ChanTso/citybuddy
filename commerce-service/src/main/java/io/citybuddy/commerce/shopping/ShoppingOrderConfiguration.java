package io.citybuddy.commerce.shopping;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.orders.enabled", havingValue = "true")
public class ShoppingOrderConfiguration {
  @Bean
  ShoppingOrderRepository shoppingOrderRepository(JdbcTemplate jdbc) {
    return new ShoppingOrderRepository(jdbc);
  }

  @Bean
  ShoppingOrderService shoppingOrderService(ShoppingOrderRepository repository) {
    return new ShoppingOrderService(repository);
  }
}
