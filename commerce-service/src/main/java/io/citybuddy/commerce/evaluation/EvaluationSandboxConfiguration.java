package io.citybuddy.commerce.evaluation;

import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@Profile("evaluation")
@EnableScheduling
@EnableConfigurationProperties(EvaluationSandboxProperties.class)
public class EvaluationSandboxConfiguration {

  @Bean
  Clock evaluationSandboxClock() {
    return Clock.systemUTC();
  }

  @Bean
  EvaluationManagementAuthenticator evaluationManagementAuthenticator(
      EvaluationSandboxProperties properties) {
    return new EvaluationManagementAuthenticator(properties);
  }

  @Bean
  @ConditionalOnMissingBean(DirectUserAuthorizer.class)
  DirectUserAuthorizer evaluationDirectUserAuthorizer(
      EvaluationSandboxProperties properties,
      RestClient.Builder builder,
      Clock evaluationSandboxClock) {
    RestClient jwks = builder.baseUrl(properties.jwksUrl()).build();
    return new DirectUserAuthorizer(
        properties.identityIssuer(),
        properties.userAudience(),
        properties.jwksCacheTtl(),
        properties.clockSkew(),
        "support:chat",
        () -> jwks.get().retrieve().body(String.class),
        evaluationSandboxClock,
        true);
  }

  @Bean
  EvaluationIdentityClient evaluationIdentityClient(
      EvaluationSandboxProperties properties, RestClient.Builder builder) {
    return new HttpEvaluationIdentityClient(
        builder.baseUrl(properties.authBaseUrl()).build(), properties);
  }

  @Bean
  EvaluationSandboxRepository evaluationSandboxRepository(JdbcTemplate jdbcTemplate) {
    return new EvaluationSandboxRepository(jdbcTemplate);
  }

  @Bean
  EvaluationViewRepository evaluationViewRepository(JdbcTemplate jdbcTemplate) {
    return new EvaluationViewRepository(jdbcTemplate);
  }

  @Bean
  EvaluationViewService evaluationViewService(
      EvaluationViewRepository repository,
      EvaluationSandboxProperties properties,
      Clock evaluationSandboxClock) {
    return new EvaluationViewService(repository, properties, evaluationSandboxClock);
  }

  @Bean
  EvaluationCommerceAuditService evaluationCommerceAuditService(
      JdbcTemplate jdbcTemplate, Clock evaluationSandboxClock) {
    return new EvaluationCommerceAuditService(jdbcTemplate, evaluationSandboxClock);
  }

  @Bean
  EvaluationSandboxCleanupWorker evaluationSandboxCleanupWorker(
      EvaluationSandboxRepository repository,
      EvaluationIdentityClient identity,
      EvaluationSandboxProperties properties,
      Clock evaluationSandboxClock) {
    return new EvaluationSandboxCleanupWorker(
        repository, identity, properties, evaluationSandboxClock);
  }

  @Bean
  EvaluationSandboxService evaluationSandboxService(
      EvaluationSandboxRepository repository,
      EvaluationIdentityClient identity,
      EvaluationSandboxCleanupWorker cleanup,
      EvaluationSandboxProperties properties,
      Clock evaluationSandboxClock) {
    return new EvaluationSandboxService(
        repository, identity, cleanup, properties, evaluationSandboxClock);
  }

  @Bean
  EvaluationSandboxAccess evaluationSandboxAccess(
      EvaluationSandboxRepository repository, Clock evaluationSandboxClock) {
    return new EvaluationSandboxAccess(repository, evaluationSandboxClock);
  }
}
