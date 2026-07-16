package io.citybuddy.commerce.seckill;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.seckill.order.enabled", havingValue = "true")
@EnableConfigurationProperties(SeckillOrderProperties.class)
@EnableScheduling
public class SeckillOrderConfiguration {
  @Bean
  SeckillOrderRepository seckillOrderRepository(JdbcTemplate jdbcTemplate) {
    return new SeckillOrderRepository(jdbcTemplate);
  }

  @Bean
  SeckillOrderService seckillOrderService(
      SeckillReservationRepository reservations,
      SeckillActivityRepository activities,
      SeckillOrderRepository orders,
      SeckillOrderProperties properties,
      PlatformTransactionManager transactionManager,
      Clock seckillClock) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new SeckillOrderService(
        reservations, activities, orders, properties, transaction, seckillClock);
  }

  @Bean(destroyMethod = "close")
  RocketMqSeckillTransactions rocketMqSeckillTransactions(
      ObjectMapper objectMapper,
      SeckillOrderProperties properties,
      ReservationAdmissionStore admissionStore)
      throws Exception {
    return new RocketMqSeckillTransactions(objectMapper, properties, admissionStore);
  }

  @Bean
  SeckillTransactionCoordinator seckillTransactionCoordinator(
      SeckillReservationService reservations, RocketMqSeckillTransactions messaging) {
    return new SeckillTransactionCoordinator(reservations, messaging);
  }

  @Bean
  SeckillOrderWorker seckillOrderWorker(
      RocketMqSeckillTransactions messaging, SeckillOrderService orders) {
    return new SeckillOrderWorker(messaging, orders);
  }

  @Bean
  SeckillTransactionResolutionWorker seckillTransactionResolutionWorker(
      SeckillReservationService reservations) {
    return new SeckillTransactionResolutionWorker(reservations);
  }
}
