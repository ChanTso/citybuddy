package io.citybuddy.commerce.order;

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

class OrderControllerEvaluationBoundaryTest {
  @Test
  void realEvaluationTokenIsRejectedByTheProductionOnlyOrderEntry() throws Exception {
    var identity = EvaluationDirectTokenFixture.create("order:create");
    OrderService service = mock(OrderService.class);
    MockMvc http =
        MockMvcBuilders.standaloneSetup(
                new OrderController(
                    identity.authorizer(), service, new OrderProperties(null, 0, 0)))
            .setControllerAdvice(new OrderExceptionHandler())
            .build();

    http.perform(
            post("/api/orders")
                .header("Authorization", identity.authorization())
                .header("X-Eval-Sandbox-Id", identity.sandboxId())
                .header("Idempotency-Key", "evaluation-proof")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"productId\":\"product-1\","
                        + "\"quantity\":1,\"expectedProductVersion\":1}"))
        .andExpect(status().isUnauthorized())
        .andExpect(
            content()
                .json(
                    "{\"category\":\"AUTHENTICATION\","
                        + "\"message\":\"Direct-user order authorization failed\"}"));
    verifyNoInteractions(service);
  }
}
