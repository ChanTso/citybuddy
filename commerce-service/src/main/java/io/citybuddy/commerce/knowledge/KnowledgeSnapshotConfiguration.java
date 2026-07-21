package io.citybuddy.commerce.knowledge;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "citybuddy.knowledge-snapshot.enabled", havingValue = "true")
@EnableConfigurationProperties(KnowledgeSnapshotProperties.class)
public class KnowledgeSnapshotConfiguration {
  @Bean
  KnowledgeSnapshotAuthenticator knowledgeSnapshotAuthenticator(
      KnowledgeSnapshotProperties properties) {
    return new KnowledgeSnapshotAuthenticator(properties);
  }

  @Bean
  KnowledgeSnapshotRepository knowledgeSnapshotRepository(JdbcTemplate jdbcTemplate) {
    return new KnowledgeSnapshotRepository(jdbcTemplate);
  }

  @Bean
  KnowledgeSnapshotService knowledgeSnapshotService(
      KnowledgeSnapshotRepository repository, KnowledgeSnapshotProperties properties) {
    return new KnowledgeSnapshotService(repository, properties, Clock.systemUTC());
  }
}
