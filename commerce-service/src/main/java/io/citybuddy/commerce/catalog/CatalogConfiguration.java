package io.citybuddy.commerce.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.identity.JwksLoader;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.catalog.enabled", havingValue = "true")
@EnableConfigurationProperties(CatalogProperties.class)
@EnableScheduling
public class CatalogConfiguration {

  @Bean
  Clock catalogClock() {
    return Clock.systemUTC();
  }

  @Bean
  JwksLoader catalogJwksLoader(CatalogProperties properties, RestClient.Builder builder) {
    RestClient client = builder.baseUrl(properties.jwksUrl()).build();
    return () -> client.get().retrieve().body(String.class);
  }

  @Bean
  DirectUserAuthorizer directUserAuthorizer(
      CatalogProperties properties,
      @Qualifier("catalogJwksLoader") JwksLoader loader,
      @Qualifier("catalogClock") Clock clock) {
    return new DirectUserAuthorizer(properties, loader, clock);
  }

  @Bean
  ProductRepository productRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    return new ProductRepository(jdbcTemplate, objectMapper);
  }

  @Bean
  ProductCache productCache(
      StringRedisTemplate redis, ObjectMapper objectMapper, CatalogProperties properties) {
    return new ProductCache(redis, objectMapper, properties);
  }

  @Bean
  ProductCatalogService productCatalogService(
      ProductRepository repository, ProductCache productCache) {
    return new ProductCatalogService(repository, productCache);
  }

  @Bean
  ProductPublicationService productPublicationService(
      ProductRepository repository, ProductCache productCache) {
    return new ProductPublicationService(repository, productCache);
  }

  @Bean(destroyMethod = "close")
  RocketMqCatalogMessaging rocketMqCatalogMessaging(CatalogProperties properties) throws Exception {
    return new RocketMqCatalogMessaging(properties);
  }

  @Bean
  CatalogOutboxPublisher catalogOutboxPublisher(
      ProductRepository repository, RocketMqCatalogMessaging messaging) {
    return new CatalogOutboxPublisher(repository, messaging);
  }

  @Bean
  ProductInvalidationHandler productInvalidationHandler(
      ProductRepository repository, ProductCache productCache) {
    return new ProductInvalidationHandler(repository, productCache);
  }

  @Bean
  CatalogEventWorker catalogEventWorker(
      CatalogOutboxPublisher publisher,
      RocketMqCatalogMessaging messaging,
      ProductInvalidationHandler handler) {
    return new CatalogEventWorker(publisher, messaging, handler);
  }
}
