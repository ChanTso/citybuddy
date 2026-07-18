package io.citybuddy.commerce.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
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
}
