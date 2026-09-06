package io.citybuddy.commerce.merchant;

import static org.assertj.core.api.Assertions.assertThat;
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
import io.citybuddy.commerce.shopping.ShoppingOrderModels.OrderView;
import io.citybuddy.commerce.shopping.ShoppingOrderModels.ProductSnapshot;
import io.citybuddy.commerce.shopping.ShoppingOrderModels.RefundFacts;
import io.citybuddy.commerce.shopping.ShoppingOrderRepository;
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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MerchantOrderControllerTest {
  private static final String ORDERS = "/internal/merchant/orders";
  private final ShoppingOrderRepository repository = mock(ShoppingOrderRepository.class);
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
            .keyID("merchant-orders-test")
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
                new MerchantOrderController(obo, new MerchantOrderService(repository)))
            .setControllerAdvice(new MerchantExceptionHandler())
            .build();
  }

  @Test
  void signedMerchantReceivesOriginalOrderFactsAtTheDefaultLimitWithoutCaching() throws Exception {
    OrderView order =
        new OrderView(
            "STANDARD",
            "order-one",
            "UNPAID",
            1,
            Instant.parse("2026-09-07T00:00:00Z"),
            null,
            new ProductSnapshot("sku-one", "Historical product", 1299, "CNY", 2, 2598, 4L),
            null,
            new RefundFacts(0, List.of()),
            null);
    when(repository.listForMerchant(6)).thenReturn(List.of(order));

    mvc.perform(authorized(get(ORDERS)).header("If-None-Match", "\"old-read\""))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().doesNotExist("ETag"))
        .andExpect(jsonPath("$[0].orderId").value("order-one"))
        .andExpect(jsonPath("$[0].status").value("UNPAID"))
        .andExpect(jsonPath("$[0].product.totalPriceMinor").value(2598))
        .andExpect(jsonPath("$[0].product.quantity").value(2))
        .andExpect(jsonPath("$[0].product.productVersion").value(4))
        .andExpect(jsonPath("$[0].payment").isEmpty())
        .andExpect(jsonPath("$[0].fulfillment").isEmpty())
        .andExpect(jsonPath("$[0].user_subject").doesNotExist());
    verify(repository).listForMerchant(6);
  }

  @Test
  void explicitLimitsReachTheRepositoryAndInvalidLimitsDoNot() throws Exception {
    when(repository.listForMerchant(1)).thenReturn(List.of());
    when(repository.listForMerchant(50)).thenReturn(List.of());
    mvc.perform(authorized(get(ORDERS)).param("limit", "1")).andExpect(status().isOk());
    mvc.perform(authorized(get(ORDERS)).param("limit", "50")).andExpect(status().isOk());
    verify(repository).listForMerchant(1);
    verify(repository).listForMerchant(50);
  }

  @Test
  void unknownDuplicateEmptyMalformedAndOutOfRangeQueriesAreRejectedBeforeReading()
      throws Exception {
    for (String limit : List.of("", "0", "-1", "51", "6.0", "abc", "2147483648")) {
      mvc.perform(authorized(get(ORDERS)).param("limit", limit))
          .andExpect(status().isBadRequest())
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.category").value("VALIDATION"));
    }
    for (String parameter : List.of("owner", "userSubject", "status", "offset", "asOf")) {
      mvc.perform(authorized(get(ORDERS)).param(parameter, "other"))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(authorized(get(ORDERS)).param("limit", "6", "50"))
        .andExpect(status().isBadRequest());
    mvc.perform(authorized(get(ORDERS)).param("limit", "6", "6"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(repository);
  }

  @Test
  void wrongActorScopeSessionDirectAndEvaluationTokensCannotReadStoreOrders() throws Exception {
    mvc.perform(get(ORDERS)).andExpect(status().isForbidden());
    for (String actor : List.of("agent-service", "shopping-agent")) {
      mvc.perform(
              identity(
                  get(ORDERS), token(true, "merchant:read", actor, "session", null), "session"))
          .andExpect(status().isForbidden());
    }
    for (String scope : List.of("merchant:change:read", "shopping:orders:read", "merchant:*")) {
      mvc.perform(
              identity(
                  get(ORDERS), token(true, scope, "merchant-agent", "session", null), "session"))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            identity(
                get(ORDERS),
                token(true, "merchant:read", "merchant-agent", "other-session", null),
                "session"))
        .andExpect(status().isForbidden());
    mvc.perform(identity(get(ORDERS), token(false, "merchant:read", null, null, null), "session"))
        .andExpect(status().isForbidden());
    mvc.perform(
            identity(
                get(ORDERS),
                token(true, "merchant:read", "merchant-agent", "session", "sandbox"),
                "session"))
        .andExpect(status().isForbidden());
    mvc.perform(authorized(get(ORDERS)).header("X-Eval-Sandbox-Id", ""))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    verifyNoInteractions(repository);
  }

  @Test
  void duplicateOrMalformedIdentityHeadersCannotReachTheRepository() throws Exception {
    String token = token(true, "merchant:read", "merchant-agent", "session", null);
    for (String repeated : List.of("session", "other")) {
      mvc.perform(identity(get(ORDERS), token, "session").header("X-Merchant-Session-Id", repeated))
          .andExpect(status().isForbidden());
    }
    mvc.perform(identity(get(ORDERS), token, "session").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    mvc.perform(identity(get(ORDERS), token, " ")).andExpect(status().isForbidden());
    mvc.perform(identity(get(ORDERS), token, "s".repeat(129))).andExpect(status().isForbidden());
    mvc.perform(identity(get(ORDERS), "x".repeat(16384), "session"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(ORDERS)
                .header("X-Merchant-Session-Id", "session")
                .header("Authorization", "Basic value"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(repository);
  }

  @Test
  void merchantReadConfigurationDoesNotDependOnTheBuyerOrdersFlag() {
    new ApplicationContextRunner()
        .withUserConfiguration(MerchantOrderConfiguration.class)
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withPropertyValues("citybuddy.merchant.enabled=true", "citybuddy.orders.enabled=false")
        .run(context -> assertThat(context).hasSingleBean(MerchantOrderService.class));
    new ApplicationContextRunner()
        .withUserConfiguration(MerchantOrderConfiguration.class)
        .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
        .withPropertyValues("citybuddy.merchant.enabled=false", "citybuddy.orders.enabled=true")
        .run(context -> assertThat(context).doesNotHaveBean(MerchantOrderService.class));
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
