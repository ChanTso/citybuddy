package io.citybuddy.commerce.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.cart.CartRepository;
import io.citybuddy.commerce.cart.CartService;
import io.citybuddy.commerce.mysql.BoundedMySqlTransactions;
import io.citybuddy.commerce.retail.RetailPolicyRepository;
import io.citybuddy.commerce.shopping.ShoppingOrderRepository;
import io.citybuddy.commerce.shopping.ShoppingPreferencesRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("evaluation")
@ConditionalOnProperty(name = "citybuddy.obo.enabled", havingValue = "true")
public class EvaluationShoppingReadConfiguration {
  @Bean
  EvaluationShoppingReadService evaluationShoppingReadService(
      JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
    // Evaluation reads do not enable the production catalog's authorizer or message workers.
    return new EvaluationShoppingReadService(
        new ShoppingOrderRepository(jdbc),
        new ShoppingPreferencesRepository(jdbc, mapper),
        new CartService(
            new CartRepository(jdbc, mapper),
            new BoundedMySqlTransactions(jdbc, new TransactionTemplate(transactionManager), 1)),
        new RetailPolicyRepository(jdbc));
  }
}
