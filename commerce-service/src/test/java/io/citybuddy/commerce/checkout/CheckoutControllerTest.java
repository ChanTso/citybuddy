package io.citybuddy.commerce.checkout;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import io.citybuddy.commerce.checkout.CheckoutModels.Command;
import io.citybuddy.commerce.checkout.CheckoutModels.Item;
import io.citybuddy.commerce.checkout.CheckoutModels.View;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.identity.OboProperties;
import io.citybuddy.commerce.order.BatchOrderService;
import io.citybuddy.commerce.order.OrderProperties;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CheckoutControllerTest {
  private static final String ITEM =
      "{\"productId\":\"sku\",\"quantity\":2,\"expectedProductVersion\":7,\"expectedUnitPriceMinor\":1250}";
  private static final String QUOTE =
      "{\"expectedCartVersion\":3,\"currency\":\"CNY\",\"items\":[" + ITEM + "]}";
  private final BatchOrderService service = mock(BatchOrderService.class);
  private RSAKey key;
  private DirectUserAuthorizer directAuthorizer;
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
            .keyID("checkout-test")
            .build();
    directAuthorizer =
        new DirectUserAuthorizer(
            "https://identity.citybuddy.test",
            "citybuddy-web",
            Duration.ofSeconds(30),
            Duration.ZERO,
            "order:create",
            () -> new JWKSet(key.toPublicJWK()).toString(),
            Clock.systemUTC(),
            true);
    var obo =
        new OboAuthorizer(
            new OboProperties("https://identity.citybuddy.test", "unused", null, null),
            () -> new JWKSet(key.toPublicJWK()).toString(),
            Clock.systemUTC(),
            true);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new CheckoutController(
                    directAuthorizer,
                    service,
                    new ObjectMapper(),
                    new OrderProperties(null, 0, 0, 0)),
                new CheckoutReadController(obo, service))
            .setControllerAdvice(new CheckoutExceptionHandler())
            .build();
  }

  @Test
  void configuredOrderPermissionReplacesTheDefaultForCheckoutToo() throws Exception {
    mvc =
        MockMvcBuilders.standaloneSetup(
                new CheckoutController(
                    directAuthorizer,
                    service,
                    new ObjectMapper(),
                    new OrderProperties("orders:approved:create", 0, 0, 0)))
            .setControllerAdvice(new CheckoutExceptionHandler())
            .build();
    mvc.perform(create(QUOTE)).andExpect(status().isForbidden());
    verifyNoInteractions(service);
    var command = new Command(3, "CNY", List.of(new Item("sku", 2, 7, 1250)));
    when(service.create("buyer", "checkout-key", command, "checkout-test")).thenReturn(view(false));
    mvc.perform(
            post("/api/shopping/checkouts")
                .contentType("application/json")
                .content(QUOTE)
                .header(
                    "Authorization",
                    "Bearer " + direct("orders:approved:create", "direct_user", null))
                .header("Idempotency-Key", "checkout-key")
                .header("X-Correlation-Id", "checkout-test"))
        .andExpect(status().isCreated());
    verify(service).create("buyer", "checkout-key", command, "checkout-test");
  }

  @Test
  void directUserApprovesExactQuoteAndReplayReturnsOk() throws Exception {
    var command = new Command(3, "CNY", List.of(new Item("sku", 2, 7, 1250)));
    when(service.create("buyer", "checkout-key", command, "checkout-test"))
        .thenReturn(view(false), view(true));
    mvc.perform(create(QUOTE))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.totalMinor").value(2500))
        .andExpect(jsonPath("$.sourceCartVersion").value(3));
    mvc.perform(create(QUOTE))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.replayed").value(true));
    verify(service, times(2)).create("buyer", "checkout-key", command, "checkout-test");
  }

  @Test
  void onlySignedDirectOrderPermissionCanCreateAndEvaluationIsNeverProductionCheckout()
      throws Exception {
    mvc.perform(post("/api/shopping/checkouts").contentType("application/json").content(QUOTE))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/shopping/checkouts")
                .contentType("application/json")
                .content(QUOTE)
                .header("Authorization", "Bearer " + direct("catalog:read", "direct_user", null)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/shopping/checkouts")
                .contentType("application/json")
                .content(QUOTE)
                .header(
                    "Authorization",
                    "Bearer " + obo("shopping-agent", "order:create", "shop-session", null)))
        .andExpect(status().isUnauthorized());
    mvc.perform(create(QUOTE).header("X-Eval-Sandbox-Id", "sandbox"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/shopping/checkouts")
                .contentType("application/json")
                .content(QUOTE)
                .header(
                    "Authorization",
                    "Bearer " + direct("order:create", "eval_direct_user", "sandbox"))
                .header("X-Eval-Sandbox-Id", "sandbox"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(service);
  }

  @Test
  void rejectsIncompleteCoercedUnknownAndDuplicateQuotes() throws Exception {
    for (String body :
        List.of(
            "null",
            "[]",
            "{}",
            QUOTE.replace("\"expectedCartVersion\":3", "\"expectedCartVersion\":\"3\""),
            QUOTE.replace("\"currency\":\"CNY\"", "\"currency\":123"),
            QUOTE.replace("\"productId\":\"sku\"", "\"productId\":123"),
            QUOTE.replace("\"quantity\":2", "\"quantity\":2.0"),
            QUOTE.replace("\"expectedProductVersion\":7", "\"expectedProductVersion\":\"7\""),
            QUOTE.replace(",\"expectedUnitPriceMinor\":1250", ""),
            QUOTE.replace(
                "\"expectedUnitPriceMinor\":1250",
                "\"expectedUnitPriceMinor\":9223372036854775808"),
            QUOTE.replace("\"quantity\":2", "\"quantity\":2,\"quantity\":3"),
            QUOTE.replace("\"quantity\":2", "\"quantity\":2,\"owner\":\"other\""),
            QUOTE.replace("\"currency\":\"CNY\"", "\"currency\":\"CNY\",\"owner\":\"other\""),
            QUOTE + " {}")) {
      mvc.perform(create(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.category").value("validation"));
    }
    verifyNoInteractions(service);
  }

  @Test
  void boundsItemsAndBytesBeforeCreatingAnOrder() throws Exception {
    String prefix = "{\"expectedCartVersion\":3,\"currency\":\"CNY\",\"items\":[";
    mvc.perform(create(prefix + "]}")).andExpect(status().isBadRequest());
    mvc.perform(create(prefix + String.join(",", Collections.nCopies(101, ITEM)) + "]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(create(" ".repeat(32769))).andExpect(status().isPayloadTooLarge());
    verifyNoInteractions(service);
  }

  @Test
  void shoppingActorReadsOnlyItsOwnersCheckoutAndCannotUseWriteOrForeignActorScope()
      throws Exception {
    when(service.find("buyer", "checkout")).thenReturn(Optional.of(view(false)));
    when(service.find("buyer", "missing")).thenReturn(Optional.empty());
    mvc.perform(
            read("checkout", obo("shopping-agent", "shopping:orders:read", "shop-session", null)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checkoutId").value("checkout"));
    mvc.perform(
            read("missing", obo("shopping-agent", "shopping:orders:read", "shop-session", null)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.category").value("not_found"));
    for (String token :
        List.of(
            obo("agent-service", "shopping:orders:read", "shop-session", null),
            obo("merchant-agent", "shopping:orders:read", "shop-session", null),
            obo("shopping-agent", "shopping:cart:write", "shop-session", null),
            obo("shopping-agent", "shopping:orders:read", "other-session", null),
            direct("order:create", "direct_user", null))) {
      mvc.perform(read("checkout", token)).andExpect(status().isForbidden());
    }
    verify(service).find("buyer", "checkout");
    verify(service).find("buyer", "missing");
    verifyNoMoreInteractions(service);
  }

  @Test
  void internalReadRejectsEvaluationAndMissingOrInvalidSession() throws Exception {
    String token = obo("shopping-agent", "shopping:orders:read", "shop-session", null);
    mvc.perform(read("checkout", token).header("X-Eval-Sandbox-Id", "sandbox"))
        .andExpect(status().isForbidden());
    mvc.perform(
            read(
                "checkout",
                obo("shopping-agent", "shopping:orders:read", "shop-session", "sandbox")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/internal/shopping/checkouts/checkout").header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/internal/shopping/checkouts/checkout")
                .header("Authorization", "Bearer " + token)
                .header("X-Shopping-Session-Id", "bad session"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void preservesClosedBusinessConflictWithoutEchoingQuoteOrIdentity() throws Exception {
    var command = new Command(3, "CNY", List.of(new Item("sku", 2, 7, 1250)));
    when(service.create("buyer", "checkout-key", command, "checkout-test"))
        .thenThrow(new CheckoutException(409, "stale_quote", "Checkout quote is stale"));
    mvc.perform(create(QUOTE))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.category").value("stale_quote"))
        .andExpect(jsonPath("$.message").value("Checkout quote is stale"))
        .andExpect(jsonPath("$.owner").doesNotExist())
        .andExpect(jsonPath("$.items").doesNotExist());
  }

  private MockHttpServletRequestBuilder create(String body) throws Exception {
    return post("/api/shopping/checkouts")
        .contentType("application/json")
        .content(body)
        .header("Authorization", "Bearer " + direct("order:create", "direct_user", null))
        .header("Idempotency-Key", "checkout-key")
        .header("X-Correlation-Id", "checkout-test");
  }

  private MockHttpServletRequestBuilder read(String id, String token) {
    return get("/internal/shopping/checkouts/" + id)
        .header("Authorization", "Bearer " + token)
        .header("X-Shopping-Session-Id", "shop-session");
  }

  private static View view(boolean replayed) {
    return new View(
        "checkout",
        3,
        "CNY",
        2500,
        Instant.parse("2026-09-06T12:00:00Z"),
        "UNPAID",
        List.of(),
        replayed);
  }

  private String direct(String permission, String type, String sandbox) throws Exception {
    var claims =
        claims()
            .audience("citybuddy-web")
            .claim("token_type", type)
            .claim("principal_state", "ACTIVE")
            .claim("permissions", List.of(permission));
    if (sandbox != null) {
      claims.claim("sandbox", sandbox);
    }
    return sign(claims);
  }

  private String obo(String actor, String scope, String session, String sandbox) throws Exception {
    var claims =
        claims()
            .audience("commerce-service")
            .claim("user_id", "buyer")
            .claim("token_type", "agent_obo")
            .claim("act", Map.of("azp", actor))
            .claim("scope", scope)
            .claim("session", session);
    if (sandbox != null) {
      claims.claim("sandbox", sandbox);
    }
    return sign(claims);
  }

  private static JWTClaimsSet.Builder claims() {
    Instant now = Instant.now();
    return new JWTClaimsSet.Builder()
        .issuer("https://identity.citybuddy.test")
        .subject("buyer")
        .issueTime(Date.from(now))
        .notBeforeTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300)))
        .jwtID(UUID.randomUUID().toString());
  }

  private String sign(JWTClaimsSet.Builder claims) throws Exception {
    var jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
            claims.build());
    jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
    return jwt.serialize();
  }
}
