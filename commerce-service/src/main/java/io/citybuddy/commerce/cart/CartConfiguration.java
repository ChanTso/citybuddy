package io.citybuddy.commerce.cart;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.mysql.BoundedMySqlTransactions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.orders.enabled", havingValue = "true")
public class CartConfiguration {
  @Bean
  CartRepository cartRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    return new CartRepository(jdbc, mapper);
  }

  @Bean
  CartService cartService(
      CartRepository repository, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
    return new CartService(
        repository,
        new BoundedMySqlTransactions(jdbc, new TransactionTemplate(transactionManager), 1));
  }
}
