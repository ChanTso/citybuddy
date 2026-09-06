package io.citybuddy.commerce.checkout;

import io.citybuddy.commerce.cart.CartRepository;
import io.citybuddy.commerce.order.BatchOrderService;
import io.citybuddy.commerce.order.OrderProperties;
import io.citybuddy.commerce.order.OrderRepository;
import io.citybuddy.commerce.shopping.ShoppingOrderRepository;
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
public class CheckoutConfiguration {
  @Bean
  CheckoutRepository checkoutRepository(JdbcTemplate jdbc) {
    return new CheckoutRepository(jdbc);
  }

  @Bean
  BatchOrderService batchOrderService(
      OrderRepository orders,
      CartRepository carts,
      CheckoutRepository checkouts,
      ShoppingOrderRepository shoppingOrders,
      PlatformTransactionManager transactionManager,
      OrderProperties properties) {
    return new BatchOrderService(
        orders,
        carts,
        checkouts,
        shoppingOrders,
        new TransactionTemplate(transactionManager),
        properties);
  }
}
