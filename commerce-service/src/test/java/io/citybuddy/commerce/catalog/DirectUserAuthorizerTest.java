package io.citybuddy.commerce.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DirectUserAuthorizerTest {
  private static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");

  private RSAKey signingKey;
  private CatalogProperties properties;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    signingKey =
        new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey((RSAPrivateKey) pair.getPrivate())
            .keyID("current")
            .algorithm(JWSAlgorithm.RS256)
            .build();
    properties =
        new CatalogProperties(
            "https://identity.citybuddy.test",
            "citybuddy-web",
            "http://identity.test/auth/jwks",
            Duration.ofMinutes(5),
            Duration.ofSeconds(30),
            "catalog:read",
            Duration.ofMinutes(5),
            Duration.ofSeconds(30),
            Duration.ofSeconds(20),
            Duration.ofSeconds(10),
            "localhost:8081",
            "catalog-events",
            "catalog-consumer");
  }

  @Test
  void acceptsExactDirectUserAndRejectsWrongModes() throws Exception {
    DirectUserAuthorizer authorizer = authorizer(() -> jwks(signingKey));

    assertEquals(
        "user-123",
        authorizer
            .authorize(
                "Bearer " + token("direct_user", "citybuddy-web", List.of("catalog:read")), null)
            .subject());
    assertThrows(
        CatalogException.class,
        () ->
            authorizer.authorize(
                "Bearer " + token("agent_obo", "citybuddy-web", List.of("catalog:read")), null));
    assertThrows(
        CatalogException.class,
        () ->
            authorizer.authorize(
                "Bearer " + token("direct_user", "commerce-service", List.of("catalog:read")),
                null));
    assertThrows(
        CatalogException.class,
        () ->
            authorizer.authorize(
                "Bearer "
                    + token("direct_user", "citybuddy-web", List.of("support:session:create")),
                null));
    assertThrows(
        CatalogException.class,
        () ->
            authorizer.authorize(
                "Bearer " + token("direct_user", "citybuddy-web", List.of("catalog:read")),
                "sandbox"));
  }

  @Test
  void unknownKidRefreshesOnlyOnce() throws Exception {
    AtomicInteger loads = new AtomicInteger();
    DirectUserAuthorizer authorizer =
        authorizer(
            () -> {
              loads.incrementAndGet();
              return jwks(signingKey);
            });
    authorizer.authorize(
        "Bearer " + token("direct_user", "citybuddy-web", List.of("catalog:read")), null);

    RSAKey unknown = generateKey("unknown");
    assertThrows(
        CatalogException.class,
        () ->
            authorizer.authorize(
                "Bearer " + token(unknown, "direct_user", "citybuddy-web", List.of("catalog:read")),
                null));
    assertEquals(2, loads.get());
  }

  private DirectUserAuthorizer authorizer(io.citybuddy.commerce.identity.JwksLoader loader) {
    return new DirectUserAuthorizer(properties, loader, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private String token(String type, String audience, List<String> permissions) throws Exception {
    return token(signingKey, type, audience, permissions);
  }

  private String token(RSAKey key, String type, String audience, List<String> permissions)
      throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience(audience)
            .subject("user-123")
            .claim("token_type", type)
            .claim("principal_state", "ACTIVE")
            .claim("permissions", permissions)
            .issueTime(Date.from(NOW))
            .notBeforeTime(Date.from(NOW))
            .expirationTime(Date.from(NOW.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString())
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key.toPrivateKey()));
    return jwt.serialize();
  }

  private static RSAKey generateKey(String kid) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
        .privateKey((RSAPrivateKey) pair.getPrivate())
        .keyID(kid)
        .algorithm(JWSAlgorithm.RS256)
        .build();
  }

  private static String jwks(RSAKey key) {
    return "{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}";
  }
}
