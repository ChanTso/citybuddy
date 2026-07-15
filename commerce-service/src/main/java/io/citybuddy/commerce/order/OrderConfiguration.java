package io.citybuddy.commerce.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.orders.enabled", havingValue = "true")
@EnableConfigurationProperties(OrderProperties.class)
public class OrderConfiguration {
  @Bean
  OrderRepository orderRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    return new OrderRepository(jdbcTemplate, objectMapper);
  }

  @Bean
  OrderService orderService(
      OrderRepository repository,
      PlatformTransactionManager transactionManager,
      OrderProperties properties) {
    return new OrderService(repository, new TransactionTemplate(transactionManager), properties);
  }
}
