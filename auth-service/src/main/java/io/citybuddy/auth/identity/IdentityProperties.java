package io.citybuddy.auth.identity;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("citybuddy.identity")
public record IdentityProperties(
    String issuer,
    String userAudience,
    String currentKid,
    String currentPrivateKeyPath,
    String currentPublicKeyPath,
    String overlapKid,
    String overlapPublicKeyPath,
    Duration directTtl,
    Duration oboTtl,
    Duration clockSkew,
    List<String> exchangeScopes) {

  public IdentityProperties {
    directTtl = directTtl == null ? Duration.ofMinutes(15) : directTtl;
    oboTtl = oboTtl == null ? Duration.ofMinutes(2) : oboTtl;
    clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
    exchangeScopes = exchangeScopes == null ? List.of() : List.copyOf(exchangeScopes);
  }
}
