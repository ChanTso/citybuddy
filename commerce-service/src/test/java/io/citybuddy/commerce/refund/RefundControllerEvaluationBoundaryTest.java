package io.citybuddy.commerce.refund;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import io.citybuddy.commerce.evaluation.EvaluationDirectTokenFixture;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RefundControllerEvaluationBoundaryTest {
  @Test
  void realEvaluationTokenIsRejectedByTheProductionOnlyRefundEntry() throws Exception {
    var identity = EvaluationDirectTokenFixture.create("refund:create");
    RefundService service = mock(RefundService.class);
    MockMvc http =
        MockMvcBuilders.standaloneSetup(
                new RefundController(
                    identity.authorizer(),
                    new RefundProperties(null, 1, 2, java.time.Duration.ofMillis(25)),
                    service,
                    mock(RefundRequestParser.class)))
            .setControllerAdvice(new RefundExceptionHandler())
            .build();

    http.perform(
            post("/api/orders/order-1/refunds")
                .header("Authorization", identity.authorization())
                .header("X-Eval-Sandbox-Id", identity.sandboxId())
                .header("Idempotency-Key", "evaluation-proof")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":100,\"reason\":\"evaluation proof\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(
            content()
                .json(
                    "{\"category\":\"AUTHENTICATION\","
                        + "\"message\":\"Direct-user refund authorization failed\"}"));
    verifyNoInteractions(service);
  }

  @Test
  void indeterminateReasonIsLoggedServerSideButNotExposedInThePublicBody() throws Exception {
    var identity = EvaluationDirectTokenFixture.create("refund:create");
    RefundService service = mock(RefundService.class);
    RefundRequestParser parser = mock(RefundRequestParser.class);
    DirectUserAuthorizer authorizer = mock(DirectUserAuthorizer.class);
    RefundRequest request = new RefundRequest(100L, "AUD", null);
    when(parser.parse(org.mockito.ArgumentMatchers.any())).thenReturn(request);
    when(authorizer.authorize(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.eq("refund:create")))
        .thenReturn(new DirectUserAuthorizer.DirectPrincipal("refund-owner", null));
    when(service.request(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(request)))
        .thenThrow(
            new RefundException(
                429,
                "INDETERMINATE",
                RefundRejectionReason.REFUND_CONCURRENCY_OBSERVATION_INDETERMINATE,
                "Refund truth is indeterminate; retry the same request"));
    MockMvc http =
        MockMvcBuilders.standaloneSetup(
                new RefundController(
                    authorizer,
                    new RefundProperties(null, 1, 2, java.time.Duration.ofMillis(25)),
                    service,
                    parser))
            .setControllerAdvice(new RefundExceptionHandler())
            .build();

    http.perform(
            post("/api/orders/00000000-0000-0000-0000-000000000120/refunds")
                .header("Authorization", "Bearer controlled")
                .header("Idempotency-Key", "refund-lock")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":100,\"currency\":\"AUD\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(
            content()
                .json(
                    "{\"category\":\"INDETERMINATE\","
                        + "\"message\":\"Refund truth is indeterminate; retry the same request\"}"))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(
                            "REFUND_CONCURRENCY_OBSERVATION_INDETERMINATE"))));
  }
}
