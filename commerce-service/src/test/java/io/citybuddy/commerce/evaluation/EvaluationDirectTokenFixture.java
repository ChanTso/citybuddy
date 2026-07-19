package io.citybuddy.commerce.evaluation;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
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

public final class EvaluationDirectTokenFixture {
  private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
  private static final String SUBJECT = "evaluation-user";
  private static final String SANDBOX_ID = "sandbox-production-only-proof";

  private EvaluationDirectTokenFixture() {}

  public static Fixture create(String permission) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    RSAKey key =
        new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey((RSAPrivateKey) pair.getPrivate())
            .keyID("evaluation")
            .algorithm(JWSAlgorithm.RS256)
            .build();
    DirectUserAuthorizer authorizer =
        new DirectUserAuthorizer(
            "https://identity.citybuddy.test",
            "citybuddy-web",
            Duration.ofMinutes(5),
            Duration.ofSeconds(30),
            permission,
            () -> "{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}",
            Clock.fixed(NOW, ZoneOffset.UTC),
            true);
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience("citybuddy-web")
            .subject(SUBJECT)
            .claim("token_type", "eval_direct_user")
            .claim("principal_state", "ACTIVE")
            .claim("permissions", List.of(permission))
            .claim("sandbox", SANDBOX_ID)
            .issueTime(Date.from(NOW))
            .notBeforeTime(Date.from(NOW))
            .expirationTime(Date.from(NOW.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString())
            .build();
    SignedJWT token =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    token.sign(new RSASSASigner(key.toPrivateKey()));
    String authorization = "Bearer " + token.serialize();
    DirectUserAuthorizer.DirectPrincipal principal =
        authorizer.authorizeEvaluation(authorization, SANDBOX_ID, permission);
    if (!SUBJECT.equals(principal.subject()) || !SANDBOX_ID.equals(principal.sandboxId())) {
      throw new AssertionError("Evaluation token positive control did not preserve its binding");
    }
    return new Fixture(authorizer, authorization, SANDBOX_ID);
  }

  public record Fixture(DirectUserAuthorizer authorizer, String authorization, String sandboxId) {}
}
