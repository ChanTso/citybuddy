package io.citybuddy.auth.identity;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.identity.enabled", havingValue = "true")
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentityConfiguration {

  @Bean
  Clock identityClock() {
    return Clock.systemUTC();
  }

  @Bean
  PasswordEncoder identityPasswordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  AuthRepository authRepository(JdbcClient jdbcClient) {
    return new AuthRepository(jdbcClient);
  }

  @Bean
  AuthKeySet authKeySet(IdentityProperties properties, Clock identityClock) {
    return new AuthKeySet(properties, identityClock);
  }

  @Bean
  @Profile("evaluation")
  EvaluationIdentityService evaluationIdentityService(
      AuthRepository repository,
      AuthKeySet keys,
      IdentityProperties properties,
      Clock identityClock) {
    return new EvaluationIdentityService(repository, keys, properties, identityClock);
  }
}
