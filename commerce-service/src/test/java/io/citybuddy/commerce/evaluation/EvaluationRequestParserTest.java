package io.citybuddy.commerce.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;

class EvaluationRequestParserTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void acceptsOnlyTheBoundedDocumentedFixtureShape() throws Exception {
    EvaluationResetRequest request =
        EvaluationRequestParser.parseReset(
            mapper.readTree(
                """
                {
                  "sandboxId":"sandbox-1",
                  "caseCorrelation":"case-1",
                  "ttlSeconds":300,
                  "testUserLabel":"test-user-1",
                  "products":[{
                    "productId":"product-1",
                    "name":"Tea",
                    "description":"Evaluation tea",
                    "priceMinor":500,
                    "currency":"CNY",
                    "stockQuantity":10,
                    "available":true
                  }]
                }
                """));

    assertThat(request.sandboxId()).isEqualTo("sandbox-1");
    assertThat(request.products()).hasSize(1);
    assertThat(EvaluationRequestParser.fixtureDigest(request.products())).hasSize(64);
    assertThat(EvaluationRequestParser.fixtureDigest(request.products()))
        .isEqualTo(EvaluationRequestParser.fixtureDigest(List.copyOf(request.products())));
  }

  @Test
  void rejectsUnknownFieldsDuplicateProductsAndNonIntegralNumbers() throws Exception {
    String validProduct =
        """
        {"productId":"product-1","name":"Tea","description":"Evaluation tea",
         "priceMinor":500,"currency":"CNY","stockQuantity":10,"available":true}
        """;
    assertThatThrownBy(
            () ->
                EvaluationRequestParser.parseReset(
                    mapper.readTree(
                        """
                        {"sandboxId":"sandbox-1","caseCorrelation":"case-1","ttlSeconds":300,
                         "testUserLabel":"test-user-1","products":[],"scope":"*"}
                        """)))
        .isInstanceOf(EvaluationSandboxException.class);
    assertThatThrownBy(
            () ->
                EvaluationRequestParser.parseReset(
                    mapper.readTree(
                        """
                        {"sandboxId":"sandbox-1","caseCorrelation":"case-1","ttlSeconds":300,
                         "testUserLabel":"test-user-1","products":[%s,%s]}
                        """
                            .formatted(validProduct, validProduct))))
        .isInstanceOf(EvaluationSandboxException.class);
    assertThatThrownBy(
            () ->
                EvaluationRequestParser.parseReset(
                    mapper.readTree(
                        """
                        {"sandboxId":"sandbox-1","caseCorrelation":"case-1","ttlSeconds":300.0,
                         "testUserLabel":"test-user-1","products":[%s]}
                        """
                            .formatted(validProduct))))
        .isInstanceOf(EvaluationSandboxException.class);
  }

  @Test
  void paymentOrderFixtureIsExactBoundedAndPartOfTheResetIntent() throws Exception {
    EvaluationResetRequest request =
        EvaluationRequestParser.parseReset(
            mapper.readTree(
                """
                {
                  "sandboxId":"sandbox-pay",
                  "caseCorrelation":"case-pay",
                  "ttlSeconds":300,
                  "testUserLabel":"test-user-pay",
                  "products":[{
                    "productId":"product-pay",
                    "name":"Tea",
                    "description":"Evaluation tea",
                    "priceMinor":500,
                    "currency":"CNY",
                    "stockQuantity":10,
                    "available":true
                  }],
                  "paymentOrder":{
                    "orderId":"00000000-0000-0000-0000-000000000105",
                    "productId":"product-pay",
                    "quantity":2
                  }
                }
                """));

    assertThat(request.paymentOrder().quantity()).isEqualTo(2);
    assertThat(request.paymentOrder().ownerTestUserLabel()).isNull();
    assertThat(EvaluationRequestParser.fixtureDigest(request.products(), request.paymentOrder()))
        .isNotEqualTo(EvaluationRequestParser.fixtureDigest(request.products()));

    for (String invalidPayment :
        List.of(
            "{\"orderId\":\"not-a-uuid\",\"productId\":\"product-pay\",\"quantity\":2}",
            "{\"orderId\":\"00000000-0000-0000-0000-000000000105\",\"productId\":\"missing\",\"quantity\":2}",
            "{\"orderId\":\"00000000-0000-0000-0000-000000000105\",\"productId\":\"product-pay\",\"quantity\":101}",
            "{\"orderId\":\"00000000-0000-0000-0000-000000000105\",\"productId\":\"product-pay\",\"quantity\":2,\"owner\":\"caller\"}")) {
      String body =
          """
          {"sandboxId":"sandbox-pay","caseCorrelation":"case-pay","ttlSeconds":300,
           "testUserLabel":"test-user-pay","products":[{"productId":"product-pay",
           "name":"Tea","description":"Evaluation tea","priceMinor":500,
           "currency":"CNY","stockQuantity":10,"available":true}],"paymentOrder":%s}
          """
              .formatted(invalidPayment);
      assertThatThrownBy(() -> EvaluationRequestParser.parseReset(mapper.readTree(body)))
          .isInstanceOf(EvaluationSandboxException.class);
    }
  }

  @Test
  void paymentOrderCanNameOneDistinctBoundedOwnerAndIncludesItInTheResetIntent() throws Exception {
    EvaluationResetRequest request =
        EvaluationRequestParser.parseReset(
            mapper.readTree(
                """
                {
                  "sandboxId":"sandbox-pay",
                  "caseCorrelation":"case-pay",
                  "ttlSeconds":300,
                  "testUserLabel":"test-user-pay",
                  "products":[{
                    "productId":"product-pay",
                    "name":"Tea",
                    "description":"Evaluation tea",
                    "priceMinor":500,
                    "currency":"CNY",
                    "stockQuantity":10,
                    "available":true
                  }],
                  "paymentOrder":{
                    "orderId":"00000000-0000-0000-0000-000000000105",
                    "productId":"product-pay",
                    "quantity":2,
                    "ownerTestUserLabel":"payment-owner"
                  }
                }
                """));

    assertThat(request.paymentOrder().ownerTestUserLabel()).isEqualTo("payment-owner");
    EvaluationResetRequest.PaymentOrderFixture primaryOwned =
        new EvaluationResetRequest.PaymentOrderFixture(
            request.paymentOrder().orderId(),
            request.paymentOrder().productId(),
            request.paymentOrder().quantity(),
            null);
    assertThat(EvaluationRequestParser.fixtureDigest(request.products(), request.paymentOrder()))
        .isNotEqualTo(EvaluationRequestParser.fixtureDigest(request.products(), primaryOwned));
  }

  @Test
  void paymentOrderOwnerMustDifferFromThePrimaryAndUseTheBoundedIdGrammar() {
    for (String invalidOwner :
        List.of("test-user-pay", "-payment-owner", "payment owner", "x".repeat(129))) {
      String body =
          """
          {"sandboxId":"sandbox-pay","caseCorrelation":"case-pay","ttlSeconds":300,
           "testUserLabel":"test-user-pay","products":[{"productId":"product-pay",
           "name":"Tea","description":"Evaluation tea","priceMinor":500,
           "currency":"CNY","stockQuantity":10,"available":true}],
           "paymentOrder":{"orderId":"00000000-0000-0000-0000-000000000105",
           "productId":"product-pay","quantity":2,"ownerTestUserLabel":"%s"}}
          """
              .formatted(invalidOwner);

      assertThatThrownBy(() -> EvaluationRequestParser.parseReset(mapper.readTree(body)))
          .isInstanceOf(EvaluationSandboxException.class);
    }
  }

  @Test
  void resetResponseOmitsTheSecondaryHandleWhenItWasNotRequested() throws Exception {
    String primaryOnly =
        mapper.writeValueAsString(
            new EvaluationSandboxService.ResetResult("sandbox-pay", "primary-handle", null));
    String withSecondary =
        mapper.writeValueAsString(
            new EvaluationSandboxService.ResetResult(
                "sandbox-pay", "primary-handle", "payment-owner-handle"));

    assertThat(mapper.readTree(primaryOnly).has("paymentOrderOwnerTestUserHandle")).isFalse();
    assertThat(mapper.readTree(withSecondary).get("paymentOrderOwnerTestUserHandle").textValue())
        .isEqualTo("payment-owner-handle");
  }

  @Test
  void evaluationViewParametersAreExactAndBounded() {
    LinkedMultiValueMap<String, String> accepted = new LinkedMultiValueMap<>();
    accepted.add("after", "7");
    accepted.add("limit", "50");
    EvaluationViewRequestParser.AuditPageRequest page =
        EvaluationViewRequestParser.auditPage(accepted);
    assertThat(page.after()).isEqualTo(7);
    assertThat(page.limit()).isEqualTo(50);

    LinkedMultiValueMap<String, String> widened = new LinkedMultiValueMap<>();
    widened.add("sort", "trace_id");
    assertThatThrownBy(() -> EvaluationViewRequestParser.auditPage(widened))
        .isInstanceOf(EvaluationSandboxException.class);

    LinkedMultiValueMap<String, String> unbounded = new LinkedMultiValueMap<>();
    unbounded.add("limit", "51");
    assertThatThrownBy(() -> EvaluationViewRequestParser.auditPage(unbounded))
        .isInstanceOf(EvaluationSandboxException.class);

    LinkedMultiValueMap<String, String> duplicate = new LinkedMultiValueMap<>();
    duplicate.add("limit", "1");
    duplicate.add("limit", "2");
    assertThatThrownBy(() -> EvaluationViewRequestParser.auditPage(duplicate))
        .isInstanceOf(EvaluationSandboxException.class);
  }

  @Test
  void supportSessionParsersAcceptTheCompleteOpaqueLanguageWithoutChangingGenericIds()
      throws Exception {
    for (int firstSixBits = 0; firstSixBits < 64; firstSixBits++) {
      byte[] raw = new byte[32];
      raw[0] = (byte) (firstSixBits << 2);
      String session = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

      assertThat(session).hasSize(43);
      assertThat(EvaluationRequestParser.supportSession(session)).isSameAs(session);
      assertThat(EvaluationViewRequestParser.session(session)).isSameAs(session);
    }

    String leadingDash = "-" + "A".repeat(42);
    assertThatThrownBy(
            () -> EvaluationRequestParser.boundedHeader(leadingDash, 64, "Invalid sandbox"))
        .isInstanceOf(EvaluationSandboxException.class);
    assertThatThrownBy(() -> EvaluationViewRequestParser.sandbox(leadingDash))
        .isInstanceOf(EvaluationSandboxException.class);
    assertThatThrownBy(() -> EvaluationViewRequestParser.trace(leadingDash))
        .isInstanceOf(EvaluationSandboxException.class);
    assertThatThrownBy(() -> EvaluationViewRequestParser.operation(leadingDash))
        .isInstanceOf(EvaluationSandboxException.class);
    assertThatThrownBy(
            () ->
                EvaluationRequestParser.parseReset(
                    mapper.readTree(
                        """
                        {"sandboxId":"sandbox-1","caseCorrelation":"case-1","ttlSeconds":300,
                         "testUserLabel":"test-user-1","products":[{"productId":"%s",
                         "name":"Tea","description":"Evaluation tea","priceMinor":500,
                         "currency":"CNY","stockQuantity":10,"available":true}]}
                        """
                            .formatted(leadingDash))))
        .isInstanceOf(EvaluationSandboxException.class);
  }

  @Test
  void invalidSupportSessionsHaveOnePrecisePrivateReasonAndFixedMessage() {
    List<String> invalid =
        java.util.Arrays.asList(
            null,
            "",
            " ",
            " session-main",
            "session-main ",
            "session main",
            ".session-main",
            ":session-main",
            "-" + "A".repeat(41),
            "-" + "A".repeat(43),
            "_" + "A".repeat(41),
            "_" + "A".repeat(43),
            "session+main",
            "session/main",
            "session=main",
            "session\nmain",
            "session\u0000main",
            "sessіon-main",
            "A".repeat(65),
            "session-main\r\nX-Injected: true");

    for (String value : invalid) {
      assertThatThrownBy(() -> EvaluationRequestParser.supportSession(value))
          .isInstanceOfSatisfying(
              EvaluationSandboxException.class,
              exception -> {
                assertThat(exception.status()).isEqualTo(400);
                assertThat(exception.reason())
                    .isEqualTo(EvaluationRejectionReason.TOOL_SUPPORT_SESSION_INVALID);
                assertThat(exception).hasMessage("Bad request");
              });
      assertThatThrownBy(() -> EvaluationViewRequestParser.session(value))
          .isInstanceOfSatisfying(
              EvaluationSandboxException.class,
              exception ->
                  assertThat(exception.reason())
                      .isEqualTo(EvaluationRejectionReason.TOOL_SUPPORT_SESSION_INVALID));
    }
  }
}
