package io.citybuddy.commerce.refund;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.payment.MockPaymentRepository;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.refund.enabled", havingValue = "true")
@EnableConfigurationProperties(RefundProperties.class)
public class RefundConfiguration {
  @Bean
  RefundRepository refundRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    return new RefundRepository(jdbcTemplate, objectMapper);
  }

  @Bean
  RefundService refundService(
      RefundRepository refundRepository,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager,
      @Qualifier("catalogClock") Clock clock) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new RefundService(
        refundRepository, new MockPaymentRepository(jdbcTemplate), transaction, clock);
  }
}
