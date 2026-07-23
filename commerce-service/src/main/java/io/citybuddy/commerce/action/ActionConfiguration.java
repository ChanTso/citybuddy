package io.citybuddy.commerce.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.evaluation.EvaluationSandboxAccess;
import io.citybuddy.commerce.refund.RefundService;
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
@ConditionalOnProperty(name = "citybuddy.actions.enabled", havingValue = "true")
@EnableConfigurationProperties(ActionProperties.class)
public class ActionConfiguration {
  @Bean
  ActionRepository actionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    return new ActionRepository(jdbcTemplate, objectMapper);
  }

  @Bean
  ActionService actionService(
      ActionRepository repository,
      RefundService refunds,
      PlatformTransactionManager transactionManager,
      ActionProperties properties,
      @Qualifier("catalogClock") ObjectProvider<Clock> catalogClock,
      ObjectProvider<EvaluationSandboxAccess> sandboxAccess) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return new ActionService(
        repository,
        refunds,
        transaction,
        properties,
        catalogClock.getIfAvailable(Clock::systemUTC),
        sandboxAccess);
  }
}
