package io.citybuddy.commerce.seckill;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.seckill.enabled", havingValue = "true")
@EnableConfigurationProperties(SeckillReservationProperties.class)
public class SeckillConfiguration {
  @Bean
  SeckillActivityRepository seckillActivityRepository(JdbcTemplate jdbcTemplate) {
    return new SeckillActivityRepository(jdbcTemplate);
  }

  @Bean
  SeckillProjectionStore seckillProjectionStore(
      StringRedisTemplate redis, ObjectMapper objectMapper) {
    return new SeckillProjectionStore(redis, objectMapper);
  }

  @Bean
  SeckillReservationRepository seckillReservationRepository(JdbcTemplate jdbcTemplate) {
    return new SeckillReservationRepository(jdbcTemplate);
  }

  @Bean
  Clock seckillClock() {
    return Clock.systemUTC();
  }

  @Bean
  ReservationAdmissionStore reservationAdmissionStore(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      SeckillReservationProperties properties,
      Clock seckillClock) {
    return new ReservationAdmissionStore(redis, objectMapper, properties, seckillClock);
  }

  @Bean
  SeckillActivityService seckillActivityService(
      SeckillActivityRepository repository,
      SeckillReservationRepository reservationRepository,
      SeckillProjectionStore projectionStore,
      PlatformTransactionManager transactionManager) {
    TransactionTemplate committedTransaction = new TransactionTemplate(transactionManager);
    committedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new SeckillActivityService(
        repository, reservationRepository, projectionStore, committedTransaction);
  }

  @Bean
  SeckillReservationService seckillReservationService(
      SeckillReservationRepository repository,
      SeckillActivityRepository activityRepository,
      ReservationAdmissionStore admissionStore,
      PlatformTransactionManager transactionManager) {
    TransactionTemplate committedTransaction = new TransactionTemplate(transactionManager);
    committedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new SeckillReservationService(
        repository, activityRepository, admissionStore, committedTransaction);
  }
}
