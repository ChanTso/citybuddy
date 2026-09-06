package io.citybuddy.commerce.shopping;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.identity.OboProperties;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ShoppingOrderControllerTest {
  private final ShoppingOrderService service = mock(ShoppingOrderService.class);
  private RSAKey key;
  private MockMvc mvc;

  @BeforeEach
  void setup() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var pair = generator.generateKeyPair();
    key =
        new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey((RSAPrivateKey) pair.getPrivate())
            .algorithm(JWSAlgorithm.RS256)
            .keyID("shopping-test")
            .build();
    var authorizer =
        new OboAuthorizer(
            new OboProperties("https://identity.citybuddy.test", "unused", null, null),
            () -> new JWKSet(key.toPublicJWK()).toString(),
            Clock.systemUTC(),
            true);
    mvc =
        MockMvcBuilders.standaloneSetup(new ShoppingOrderController(authorizer, service))
            .setControllerAdvice(new ShoppingOrderExceptionHandler())
            .build();
  }

  @Test
  void callerComesFromSignedIdentityAndMissingOrdersAreNotFound() throws Exception {
    when(service.list("buyer", 20)).thenReturn(List.of());
    when(service.find("buyer", "missing")).thenReturn(Optional.empty());
    String token =
        token("shopping-agent", "shopping:orders:read", "shop-session", null, "agent_obo");
    mvc.perform(
            get("/internal/shopping/orders")
                .header("Authorization", "Bearer " + token)
                .header("X-Shopping-Session-Id", "shop-session")
                .param("userSubject", "other")
                .content("{\"userSubject\":\"other\"}"))
        .andExpect(status().isOk());
    verify(service).list("buyer", 20);
    mvc.perform(
            get("/internal/shopping/orders/missing")
                .header("Authorization", "Bearer " + token)
                .header("X-Shopping-Session-Id", "shop-session"))
        .andExpect(status().isNotFound());
  }

  @Test
  void rejectsWrongActorScopeSessionAndDirectTokens() throws Exception {
    for (String token :
        List.of(
            token("agent-service", "shopping:orders:read", "shop-session", null, "agent_obo"),
            token("merchant-agent", "shopping:orders:read", "shop-session", null, "agent_obo"),
            token("shopping-agent", "catalog:read", "shop-session", null, "agent_obo"),
            token("shopping-agent", "shopping:orders:read", "other-session", null, "agent_obo"),
            token("shopping-agent", "shopping:orders:read", "shop-session", null, "direct_user"))) {
      mvc.perform(
              get("/internal/shopping/orders")
                  .header("Authorization", "Bearer " + token)
                  .header("X-Shopping-Session-Id", "shop-session"))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(service);
  }

  @Test
  void rejectsEvaluationHeadersAndTokensEvenWithEvaluationVerifier() throws Exception {
    String production =
        token("shopping-agent", "shopping:orders:read", "shop-session", null, "agent_obo");
    String sandbox =
        token("shopping-agent", "shopping:orders:read", "shop-session", "sandbox", "agent_obo");
    for (String token : List.of(production, sandbox)) {
      mvc.perform(
              get("/internal/shopping/orders")
                  .header("Authorization", "Bearer " + token)
                  .header("X-Shopping-Session-Id", "shop-session")
                  .header("X-Eval-Sandbox-Id", "sandbox"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            get("/internal/shopping/orders")
                .header("Authorization", "Bearer " + sandbox)
                .header("X-Shopping-Session-Id", "shop-session"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void requiresAValidSharedSessionAndBoundedLimitBeforeReading() throws Exception {
    String token =
        token("shopping-agent", "shopping:orders:read", "shop-session", null, "agent_obo");
    mvc.perform(get("/internal/shopping/orders")).andExpect(status().isForbidden());
    for (String session : List.of("", "bad session", "x".repeat(65))) {
      mvc.perform(
              get("/internal/shopping/orders")
                  .header("Authorization", "Bearer " + token)
                  .header("X-Shopping-Session-Id", session))
          .andExpect(status().isForbidden());
    }
    for (String limit : List.of("0", "51", "-1", "1.5")) {
      mvc.perform(
              get("/internal/shopping/orders")
                  .param("limit", limit)
                  .header("Authorization", "Bearer " + token)
                  .header("X-Shopping-Session-Id", "shop-session"))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(service);
  }

  private String token(String actor, String scope, String session, String sandbox, String type)
      throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience("commerce-service")
            .subject("buyer")
            .claim("user_id", "buyer")
            .claim("session", session)
            .claim("scope", scope)
            .claim("token_type", type)
            .claim("act", Map.of("azp", actor))
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .jwtID(UUID.randomUUID().toString());
    if (sandbox != null) {
      claims.claim("sandbox", sandbox);
    }
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims.build());
    jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
    return jwt.serialize();
  }
}
