package io.citybuddy.commerce.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.obo")
public record OboProperties(
    String issuer, String jwksUrl, Duration clockSkew, Duration jwksCacheTtl) {
  public OboProperties {
    clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
    jwksCacheTtl = jwksCacheTtl == null ? Duration.ofSeconds(60) : jwksCacheTtl;
  }
}
