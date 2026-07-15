package io.citybuddy.commerce.identity;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.obo.enabled", havingValue = "true")
@EnableConfigurationProperties(OboProperties.class)
public class OboIdentityConfiguration {

  @Bean
  Clock oboClock() {
    return Clock.systemUTC();
  }

  @Bean
  JwksLoader oboJwksLoader(OboProperties properties, RestClient.Builder builder) {
    RestClient client = builder.baseUrl(properties.jwksUrl()).build();
    return () -> client.get().retrieve().body(String.class);
  }

  @Bean
  OboAuthorizer oboAuthorizer(OboProperties properties, JwksLoader oboJwksLoader, Clock oboClock) {
    return new OboAuthorizer(properties, oboJwksLoader, oboClock);
  }
}
