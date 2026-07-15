package io.citybuddy.commerce.seckill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.seckill.enabled", havingValue = "true")
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
  SeckillActivityService seckillActivityService(
      SeckillActivityRepository repository,
      SeckillProjectionStore projectionStore,
      PlatformTransactionManager transactionManager) {
    TransactionTemplate committedTransaction = new TransactionTemplate(transactionManager);
    committedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new SeckillActivityService(repository, projectionStore, committedTransaction);
  }
}
