package io.citybuddy.commerce.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.knowledge-snapshot")
public record KnowledgeSnapshotProperties(
    String clientId, String clientSecret, int maximumRecords) {
  public KnowledgeSnapshotProperties {
    if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
      throw new IllegalArgumentException("Knowledge snapshot credential is required");
    }
    maximumRecords = maximumRecords == 0 ? 1000 : maximumRecords;
    if (maximumRecords < 1 || maximumRecords > 1000) {
      throw new IllegalArgumentException("Knowledge snapshot record bound is invalid");
    }
  }
}
