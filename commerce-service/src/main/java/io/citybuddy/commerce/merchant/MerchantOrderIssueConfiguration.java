package io.citybuddy.commerce.merchant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.merchant.enabled", havingValue = "true")
public class MerchantOrderIssueConfiguration {
  @Bean
  MerchantOrderIssueService merchantOrderIssueService(JdbcTemplate jdbc) {
    return new MerchantOrderIssueService(new MerchantOrderIssueRepository(jdbc));
  }
}
