package io.citybuddy.commerce.catalog;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.citybuddy.commerce.identity.JwksLoader;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DirectUserAuthorizer {
  private final CatalogProperties properties;
  private final JwksLoader loader;
  private final Clock clock;
  private volatile Map<String, RSAKey> keys = Map.of();
  private volatile Instant loadedAt;

  public DirectUserAuthorizer(CatalogProperties properties, JwksLoader loader, Clock clock) {
    this.properties = properties;
    this.loader = loader;
    this.clock = clock;
  }

  public DirectPrincipal authorize(String authorization, String evalSandboxHeader) {
    return authorize(authorization, evalSandboxHeader, properties.requiredPermission());
  }

  public DirectPrincipal authorize(
      String authorization, String evalSandboxHeader, String requiredPermission) {
    try {
      require(evalSandboxHeader == null, "Evaluation context is not enabled");
      require(authorization != null && authorization.startsWith("Bearer "), "Invalid bearer token");
      SignedJWT jwt = SignedJWT.parse(authorization.substring(7));
      require(JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()), "Wrong algorithm");
      String kid = jwt.getHeader().getKeyID();
      boolean refreshed = false;
      Instant now = clock.instant();
      if (loadedAt == null
          || Duration.between(loadedAt, now).compareTo(properties.jwksCacheTtl()) >= 0) {
        refresh();
        refreshed = true;
      }
      RSAKey key = keys.get(kid);
      if (key == null && !refreshed) {
        refresh();
        key = keys.get(kid);
      }
      require(key != null, "Unknown signing key");
      require(jwt.verify(new RSASSAVerifier(key.toRSAPublicKey())), "Invalid signature");
      JWTClaimsSet claims = jwt.getJWTClaimsSet();
      validateClaims(claims, requiredPermission);
      return new DirectPrincipal(claims.getSubject());
    } catch (ParseException | JOSEException | RuntimeException exception) {
      if (exception instanceof CatalogException catalogException) {
        throw catalogException;
      }
      throw new CatalogException(401, "Direct-user authorization failed");
    }
  }

  private void validateClaims(JWTClaimsSet claims, String requiredPermission)
      throws ParseException {
    require(properties.issuer().equals(claims.getIssuer()), "Wrong issuer");
    require(claims.getAudience().equals(List.of(properties.userAudience())), "Wrong audience");
    require("direct_user".equals(claims.getClaimAsString("token_type")), "Wrong token type");
    require("ACTIVE".equals(claims.getClaimAsString("principal_state")), "Inactive principal");
    require(hasText(claims.getSubject()), "Missing subject");
    require(claims.getClaim("act") == null, "Direct token carries actor");
    require(claims.getClaim("session") == null, "Direct token carries session");
    require(claims.getClaim("sandbox") == null, "Evaluation context is not enabled");
    require(claims.getClaim("eval_sandbox") == null, "Evaluation context is not enabled");
    require(hasText(claims.getJWTID()), "Missing token identifier");
    List<String> permissions = claims.getStringListClaim("permissions");
    if (requiredPermission == null
        || requiredPermission.isBlank()
        || permissions == null
        || !permissions.contains(requiredPermission)) {
      throw new CatalogException(403, "Missing permission");
    }
    validateTime(claims);
  }

  private void validateTime(JWTClaimsSet claims) {
    Instant now = clock.instant();
    Instant lower = now.minus(properties.clockSkew());
    Instant upper = now.plus(properties.clockSkew());
    Date expiration = claims.getExpirationTime();
    Date notBefore = claims.getNotBeforeTime();
    Date issuedAt = claims.getIssueTime();
    require(expiration != null && expiration.toInstant().isAfter(lower), "Expired token");
    require(notBefore != null && !notBefore.toInstant().isAfter(upper), "Premature token");
    require(issuedAt != null && !issuedAt.toInstant().isAfter(upper), "Future issued-at");
  }

  private synchronized void refresh() throws ParseException {
    JWKSet set = JWKSet.parse(loader.load());
    Map<String, RSAKey> loaded = new HashMap<>();
    for (JWK key : set.getKeys()) {
      if (key instanceof RSAKey rsaKey
          && !key.isPrivate()
          && JWSAlgorithm.RS256.equals(key.getAlgorithm())
          && hasText(key.getKeyID())) {
        loaded.put(key.getKeyID(), rsaKey.toPublicJWK());
      }
    }
    keys = Map.copyOf(loaded);
    loadedAt = clock.instant();
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new CatalogException(401, message);
    }
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  public record DirectPrincipal(String subject) {}
}
