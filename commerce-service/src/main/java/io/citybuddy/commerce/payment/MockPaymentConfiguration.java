package io.citybuddy.commerce.payment;

import io.citybuddy.commerce.evaluation.EvaluationSandboxRepository;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
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
@ConditionalOnProperty(name = "citybuddy.mock-payment.enabled", havingValue = "true")
@EnableConfigurationProperties(MockPaymentProperties.class)
public class MockPaymentConfiguration {
  @Bean
  MockPaymentRepository mockPaymentRepository(JdbcTemplate jdbcTemplate) {
    return new MockPaymentRepository(jdbcTemplate);
  }

  @Bean
  MockPaymentService mockPaymentService(
      MockPaymentRepository repository,
      PlatformTransactionManager transactionManager,
      @Qualifier("catalogClock") ObjectProvider<Clock> catalogClock,
      ObjectProvider<EvaluationSandboxRepository> sandboxes) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new MockPaymentService(
        repository,
        transaction,
        catalogClock.getIfAvailable(Clock::systemUTC),
        sandboxes.getIfAvailable());
  }

  @Bean
  MockPaymentCallbackAuthenticator mockPaymentCallbackAuthenticator(
      MockPaymentProperties properties,
      @Qualifier("catalogClock") ObjectProvider<Clock> catalogClock) {
    return new MockPaymentCallbackAuthenticator(
        properties, catalogClock.getIfAvailable(Clock::systemUTC));
  }
}
