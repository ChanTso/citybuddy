package io.citybuddy.commerce.catalog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.catalog")
public record CatalogProperties(
    String issuer,
    String userAudience,
    String jwksUrl,
    Duration jwksCacheTtl,
    Duration clockSkew,
    String requiredPermission,
    Duration cacheTtl,
    Duration cacheJitter,
    Duration nullTtl,
    Duration mutexTtl,
    String rocketmqEndpoints,
    String rocketmqTopic,
    String rocketmqConsumerGroup) {

  public CatalogProperties {
    jwksCacheTtl = jwksCacheTtl == null ? Duration.ofMinutes(5) : jwksCacheTtl;
    clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
    requiredPermission =
        requiredPermission == null || requiredPermission.isBlank()
            ? "catalog:read"
            : requiredPermission;
    cacheTtl = cacheTtl == null ? Duration.ofMinutes(5) : cacheTtl;
    cacheJitter = cacheJitter == null ? Duration.ofSeconds(60) : cacheJitter;
    nullTtl = nullTtl == null ? Duration.ofSeconds(20) : nullTtl;
    mutexTtl = mutexTtl == null ? Duration.ofSeconds(10) : mutexTtl;
    requirePositive(jwksCacheTtl, "jwksCacheTtl");
    requirePositive(cacheTtl, "cacheTtl");
    requirePositive(nullTtl, "nullTtl");
    requirePositive(mutexTtl, "mutexTtl");
    if (cacheJitter.isNegative() || cacheJitter.compareTo(cacheTtl) >= 0) {
      throw new IllegalArgumentException(
          "cacheJitter must be non-negative and shorter than cacheTtl");
    }
    requireText(issuer, "issuer");
    requireText(userAudience, "userAudience");
    requireText(jwksUrl, "jwksUrl");
    requireText(rocketmqEndpoints, "rocketmqEndpoints");
    requireText(rocketmqTopic, "rocketmqTopic");
    requireText(rocketmqConsumerGroup, "rocketmqConsumerGroup");
  }

  private static void requirePositive(Duration value, String name) {
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }
}
