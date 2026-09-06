package io.citybuddy.commerce.merchant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MerchantMarketingControllerTest {
  private final MerchantMarketingRepository repository = mock(MerchantMarketingRepository.class);
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
            .keyID("marketing-read-test")
            .build();
    var obo =
        new OboAuthorizer(
            new OboProperties(
                "https://identity.citybuddy.test", "unused", Duration.ZERO, Duration.ofMinutes(1)),
            () -> new JWKSet(key.toPublicJWK()).toString(),
            Clock.systemUTC(),
            true);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new MerchantMarketingController(obo, new MerchantMarketingService(repository)))
            .setControllerAdvice(new MerchantExceptionHandler())
            .build();
  }

  @Test
  void authenticatedListsRespectTheBoundedPageAndDoNotCache() throws Exception {
    when(repository.campaigns(50, 100)).thenReturn(List.of());
    when(repository.promotions(20, 0)).thenReturn(List.of());
    mvc.perform(
            authorized("/internal/merchant/campaigns").param("limit", "50").param("offset", "100"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$").isEmpty());
    mvc.perform(authorized("/internal/merchant/promotions"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    verify(repository).campaigns(50, 100);
    verify(repository).promotions(20, 0);
  }

  @Test
  void buyerSupportAndDirectTokensCannotReadMerchantPlans() throws Exception {
    mvc.perform(get("/internal/merchant/campaigns")).andExpect(status().isForbidden());
    for (String actor : List.of("shopping-agent", "agent-service", "")) {
      mvc.perform(
              get("/internal/merchant/campaigns")
                  .header(
                      "Authorization", "Bearer " + token(actor, "merchant:read", "session", false))
                  .header("X-Merchant-Session-Id", "session"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            get("/internal/merchant/promotions")
                .header(
                    "Authorization",
                    "Bearer " + token("merchant-agent", "merchant:change:read", "session", false))
                .header("X-Merchant-Session-Id", "session"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(repository);
  }

  @Test
  void sessionMismatchEvaluationAndDuplicateIdentityHeadersAreRejected() throws Exception {
    mvc.perform(
            get("/internal/merchant/campaigns")
                .header(
                    "Authorization",
                    "Bearer " + token("merchant-agent", "merchant:read", "other", false))
                .header("X-Merchant-Session-Id", "session"))
        .andExpect(status().isForbidden());
    mvc.perform(authorized("/internal/merchant/campaigns").header("X-Eval-Sandbox-Id", "sandbox"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/internal/merchant/campaigns")
                .header(
                    "Authorization",
                    "Bearer " + token("merchant-agent", "merchant:read", "session", true))
                .header("X-Merchant-Session-Id", "session"))
        .andExpect(status().isForbidden());
    mvc.perform(authorized("/internal/merchant/campaigns").header("X-Merchant-Session-Id", "other"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(repository);
  }

  @Test
  void unknownRepeatedAndOutOfBoundsQueriesCannotReachPersistence() throws Exception {
    for (String limit : List.of("0", "51", "-1", "NaN", "999999")) {
      mvc.perform(authorized("/internal/merchant/campaigns").param("limit", limit))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(authorized("/internal/merchant/promotions").param("offset", "10001"))
        .andExpect(status().isBadRequest());
    mvc.perform(authorized("/internal/merchant/promotions").param("limit", "1", "2"))
        .andExpect(status().isBadRequest());
    mvc.perform(authorized("/internal/merchant/campaigns").param("owner", "another"))
        .andExpect(status().isBadRequest());
    mvc.perform(authorized("/internal/merchant/campaigns/missing").param("limit", "1"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(repository);
  }

  @Test
  void missingDetailsReturnAnExplicitUncachedNotFound() throws Exception {
    mvc.perform(authorized("/internal/merchant/campaigns/missing"))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(authorized("/internal/merchant/promotions/missing"))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  private MockHttpServletRequestBuilder authorized(String path) throws Exception {
    return get(path)
        .header("X-Merchant-Session-Id", "session")
        .header(
            "Authorization",
            "Bearer " + token("merchant-agent", "merchant:read", "session", false));
  }

  private String token(String actor, String scope, String session, boolean evaluation)
      throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .subject("operator")
            .audience("commerce-service")
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .claim("token_type", actor.isEmpty() ? "direct_user" : "agent_obo")
            .claim("user_id", "operator")
            .expirationTime(Date.from(now.plusSeconds(60)))
            .jwtID("marketing-read")
            .claim("scope", scope)
            .claim("session", session);
    if (!actor.isEmpty()) {
      claims.claim("act", Map.of("azp", actor));
    }
    if (evaluation) {
      claims.claim("sandbox", "sandbox");
    }
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims.build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }
}
