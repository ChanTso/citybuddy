package io.citybuddy.commerce.shopping;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import io.citybuddy.commerce.shopping.ShoppingPreferencesModels.Preferences;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ShoppingPreferencesControllerTest {
  private final ShoppingPreferencesRepository repository =
      mock(ShoppingPreferencesRepository.class);
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
            .keyID("profile-test")
            .build();
    var authorizer =
        new OboAuthorizer(
            new OboProperties("https://identity.citybuddy.test", "unused", null, null),
            () -> new JWKSet(key.toPublicJWK()).toString(),
            Clock.systemUTC(),
            true);
    mvc =
        MockMvcBuilders.standaloneSetup(new ShoppingPreferencesController(authorizer, repository))
            .setControllerAdvice(new ShoppingPreferencesExceptionHandler())
            .build();
  }

  @Test
  void exactSignedOwnerSelectsProfileAndResponseCannotBeConditionallyHidden() throws Exception {
    when(repository.find("buyer"))
        .thenReturn(new Preferences("buyer", "买家", "MEMBER", "上海市徐汇区", Map.of("color", "neutral")));
    mvc.perform(
            authorized(get("/internal/shopping/preferences"), "shopping:profile:read")
                .header("If-None-Match", "\"old\"")
                .queryParam("userId", "other"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().doesNotExist("ETag"))
        .andExpect(jsonPath("$.userId").value("buyer"))
        .andExpect(jsonPath("$.preferences.color").value("neutral"))
        .andExpect(jsonPath("$.contactEmail").doesNotExist());
    verify(repository).find("buyer");
    verifyNoMoreInteractions(repository);
  }

  @Test
  void deniesWrongActorScopeSessionAndTokenTypeBeforeReadingProfiles() throws Exception {
    for (String signed :
        List.of(
            token("agent-service", "shopping:profile:read", "shop-session", null, "agent_obo"),
            token("merchant-agent", "shopping:profile:read", "shop-session", null, "agent_obo"),
            token("shopping-agent", "shopping:orders:read", "shop-session", null, "agent_obo"),
            token("shopping-agent", "shopping:profile:*", "shop-session", null, "agent_obo"),
            token("shopping-agent", "shopping:profile:read", "other-session", null, "agent_obo"),
            token(
                "shopping-agent", "shopping:profile:read", "shop-session", null, "direct_user"))) {
      mvc.perform(
              get("/internal/shopping/preferences")
                  .header("Authorization", "Bearer " + signed)
                  .header("X-Shopping-Session-Id", "shop-session"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(get("/internal/shopping/preferences")).andExpect(status().isForbidden());
    verifyNoInteractions(repository);
  }

  @Test
  void rejectsEvaluationEvenWithAnEvaluationEnabledVerifierAndRejectsInvalidSession()
      throws Exception {
    mvc.perform(
            authorized(get("/internal/shopping/preferences"), "shopping:profile:read")
                .header("X-Eval-Sandbox-Id", "sandbox"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/internal/shopping/preferences")
                .header(
                    "Authorization",
                    "Bearer "
                        + token(
                            "shopping-agent",
                            "shopping:profile:read",
                            "shop-session",
                            "sandbox",
                            "eval_agent_obo"))
                .header("X-Shopping-Session-Id", "shop-session"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/internal/shopping/preferences")
                .header(
                    "Authorization",
                    "Bearer "
                        + token("shopping-agent", "shopping:profile:read", "!", null, "agent_obo"))
                .header("X-Shopping-Session-Id", "!"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(repository);
  }

  private MockHttpServletRequestBuilder authorized(
      MockHttpServletRequestBuilder request, String scope) throws Exception {
    return request
        .header(
            "Authorization",
            "Bearer " + token("shopping-agent", scope, "shop-session", null, "agent_obo"))
        .header("X-Shopping-Session-Id", "shop-session");
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
