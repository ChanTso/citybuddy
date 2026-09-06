package io.citybuddy.commerce.merchant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import io.citybuddy.commerce.merchant.MerchantListingModels.Page;
import io.citybuddy.commerce.merchant.MerchantListingModels.Window;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
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

class MerchantListingControllerTest {
  private static final String LISTINGS = "/internal/merchant/listings";
  private static final String ALERTS = "/internal/merchant/inventory-alerts";
  private static final Instant AS_OF = Instant.parse("2026-09-07T01:00:00Z");
  private final MerchantListingService service = mock(MerchantListingService.class);
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
            .keyID("listing-test")
            .build();
    var obo =
        new OboAuthorizer(
            new OboProperties(
                "https://identity.citybuddy.test", "unused", Duration.ZERO, Duration.ofMinutes(1)),
            () -> new JWKSet(key.toPublicJWK()).toString(),
            Clock.systemUTC(),
            true);
    mvc =
        MockMvcBuilders.standaloneSetup(new MerchantListingController(obo, service))
            .setControllerAdvice(new MerchantExceptionHandler())
            .build();
  }

  @Test
  void signedMerchantReadsAreBoundedAndUncachedAndUnknownDetailRemains404() throws Exception {
    Window window =
        new Window(
            Instant.parse("2026-08-07T16:00:00Z"),
            Instant.parse("2026-09-06T16:00:00Z"),
            "Asia/Shanghai");
    when(service.search(any(), eq(AS_OF))).thenReturn(new Page<>(List.of(), null, window));
    when(service.alerts(20, 0, null)).thenReturn(new Page<>(List.of(), null, window));
    when(service.get("missing", null))
        .thenThrow(new MerchantException(404, "NOT_FOUND", "Listing does not exist"));
    mvc.perform(
            authorized(get(LISTINGS))
                .param("asOf", AS_OF.toString())
                .param("limit", "50")
                .param("offset", "100"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.window.timeZone").value("Asia/Shanghai"));
    mvc.perform(authorized(get(ALERTS)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(authorized(get(LISTINGS + "/missing")))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Cache-Control", "no-store"));
    verify(service).alerts(20, 0, null);
    verify(service).get("missing", null);
  }

  @Test
  void allReadRoutesRejectWrongActorScopeSessionDirectAndEvaluationIdentity() throws Exception {
    for (String path : List.of(LISTINGS, LISTINGS + "/sku", ALERTS)) {
      mvc.perform(get(path)).andExpect(status().isForbidden());
      for (String actor : List.of("agent-service", "shopping-agent")) {
        mvc.perform(
                identity(
                    get(path), token(true, "merchant:read", actor, "session", null), "session"))
            .andExpect(status().isForbidden());
      }
      mvc.perform(
              identity(
                  get(path),
                  token(true, "merchant:change:read", "merchant-agent", "session", null),
                  "session"))
          .andExpect(status().isForbidden());
      mvc.perform(
              identity(
                  get(path),
                  token(true, "merchant:read", "merchant-agent", "other-session", null),
                  "session"))
          .andExpect(status().isForbidden());
      mvc.perform(identity(get(path), token(false, "merchant:read", null, null, null), "session"))
          .andExpect(status().isForbidden());
      mvc.perform(
              identity(
                  get(path),
                  token(true, "merchant:read", "merchant-agent", "session", "sandbox"),
                  "session"))
          .andExpect(status().isForbidden());
      mvc.perform(authorized(get(path)).header("X-Eval-Sandbox-Id", "sandbox"))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(service);
  }

  @Test
  void duplicateOrUnknownParametersAndIdentityHeadersCannotChangeTheRequestedScope()
      throws Exception {
    for (String path : List.of(LISTINGS, LISTINGS + "/sku", ALERTS)) {
      mvc.perform(authorized(get(path)).param("owner", "other")).andExpect(status().isBadRequest());
      mvc.perform(
              authorized(get(path))
                  .param("asOf", AS_OF.toString(), AS_OF.plusSeconds(1).toString()))
          .andExpect(status().isBadRequest());
      mvc.perform(authorized(get(path)).header("X-Merchant-Session-Id", "other"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(authorized(get(LISTINGS)).param("limit", "51")).andExpect(status().isBadRequest());
    mvc.perform(authorized(get(LISTINGS)).param("sort", "price_asc"))
        .andExpect(status().isBadRequest());
    mvc.perform(authorized(get(LISTINGS)).param("offset", "-1")).andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request)
      throws Exception {
    return identity(
        request, token(true, "merchant:read", "merchant-agent", "session", null), "session");
  }

  private static MockHttpServletRequestBuilder identity(
      MockHttpServletRequestBuilder request, String token, String session) {
    return request
        .header("Authorization", "Bearer " + token)
        .header("X-Merchant-Session-Id", session);
  }

  private String token(boolean obo, String scope, String actor, String session, String sandbox)
      throws Exception {
    Instant now = Instant.now();
    var claims =
        new JWTClaimsSet.Builder()
            .issuer("https://identity.citybuddy.test")
            .audience(obo ? "commerce-service" : "citybuddy-web")
            .subject("operator")
            .claim("token_type", obo ? "agent_obo" : "direct_user")
            .issueTime(Date.from(now))
            .notBeforeTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(120)))
            .jwtID(UUID.randomUUID().toString());
    if (obo) {
      claims
          .claim("user_id", "operator")
          .claim("session", session)
          .claim("scope", scope)
          .claim("act", Map.of("azp", actor));
    } else {
      claims.claim("principal_state", "ACTIVE").claim("permissions", List.of(scope));
    }
    if (sandbox != null) {
      claims.claim("sandbox", sandbox);
    }
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims.build());
    jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
    return jwt.serialize();
  }
}
