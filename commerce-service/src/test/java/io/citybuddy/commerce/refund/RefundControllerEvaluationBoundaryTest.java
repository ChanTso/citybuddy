package io.citybuddy.commerce.refund;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import io.citybuddy.commerce.evaluation.EvaluationDirectTokenFixture;
import io.citybuddy.commerce.identity.IdentityVerificationUnavailableException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RefundControllerEvaluationBoundaryTest {
  @Test
  void identityDependencyFailureUsesTheClosedRefundUnavailableContract() throws Exception {
    DirectUserAuthorizer authorizer = mock(DirectUserAuthorizer.class);
    doThrow(
            new IdentityVerificationUnavailableException(
                new IllegalStateException("controlled JWKS outage")))
        .when(authorizer)
        .authorize(any(), any(), eq("refund:create"));
    RefundService service = mock(RefundService.class);
    MockMvc http =
        MockMvcBuilders.standaloneSetup(
                new RefundController(
                    authorizer,
                    new RefundProperties(null, 0, 0, Duration.ZERO),
                    service,
                    new RefundRequestParser(new com.fasterxml.jackson.databind.ObjectMapper())))
            .setControllerAdvice(new RefundExceptionHandler())
            .build();

    http.perform(
            post("/api/orders/order-1/refunds")
                .header("Authorization", "Bearer controlled")
                .header("Idempotency-Key", "identity-unavailable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountMinor\":100,\"currency\":\"AUD\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            content()
                .json(
                    "{\"category\":\"UNAVAILABLE\","
                        + "\"message\":\"Refund service is unavailable\"}"));
    verifyNoInteractions(service);
  }

  @Test
  void realEvaluationTokenIsRejectedByTheProductionOnlyRefundEntry() throws Exception {
    var identity = EvaluationDirectTokenFixture.create("refund:create");
    RefundService service = mock(RefundService.class);
    MockMvc http =
        MockMvcBuilders.standaloneSetup(
                new RefundController(
                    identity.authorizer(),
                    new RefundProperties(null, 0, 0, Duration.ZERO),
                    service,
                    new RefundRequestParser(new com.fasterxml.jackson.databind.ObjectMapper())))
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
}
