package io.citybuddy.commerce.refund;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
                new RefundController(identity.authorizer(), new RefundProperties(null), service))
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
