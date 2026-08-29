package io.citybuddy.commerce.action;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.evaluation.EvaluationSandboxAccess;
import io.citybuddy.commerce.evaluation.EvaluationSandboxProperties;
import io.citybuddy.commerce.mysql.BoundedMySqlTransactions;
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
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager,
      ActionProperties properties,
      @Qualifier("catalogClock") ObjectProvider<Clock> catalogClock,
      ObjectProvider<EvaluationSandboxAccess> sandboxAccess,
      ObjectProvider<EvaluationSandboxProperties> evaluationProperties) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    ActionTransactions transactions =
        new ActionTransactions(
            new BoundedMySqlTransactions(
                jdbcTemplate, transaction, properties.lockWaitTimeoutSeconds()),
            properties.maximumObservationAttempts(),
            properties.observationBackoff());
    return new ActionService(
        repository,
        refunds,
        transactions,
        properties,
        catalogClock.getIfAvailable(Clock::systemUTC),
        sandboxAccess,
        ownershipBindingEnabled(evaluationProperties.getIfAvailable()));
  }

  static boolean ownershipBindingEnabled(EvaluationSandboxProperties properties) {
    return properties == null || properties.actionOwnershipBindingEnabled();
  }
}
