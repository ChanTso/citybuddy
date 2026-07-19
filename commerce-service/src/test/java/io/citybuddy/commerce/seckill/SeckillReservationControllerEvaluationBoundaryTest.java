package io.citybuddy.commerce.seckill;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.citybuddy.commerce.evaluation.EvaluationDirectTokenFixture;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SeckillReservationControllerEvaluationBoundaryTest {
  @Test
  void realEvaluationTokenIsRejectedByTheProductionOnlyReservationEntry() throws Exception {
    var identity = EvaluationDirectTokenFixture.create("seckill:reserve");
    SeckillTransactionCoordinator coordinator = mock(SeckillTransactionCoordinator.class);
    SeckillOrderProperties properties =
        new SeckillOrderProperties(
            "seckill:reserve",
            "localhost:8081",
            "seckill-orders",
            "seckill-orders-consumer",
            Duration.ofMinutes(15),
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            1);
    MockMvc http =
        MockMvcBuilders.standaloneSetup(
                new SeckillReservationController(identity.authorizer(), properties, coordinator))
            .setControllerAdvice(new SeckillRequestExceptionHandler())
            .build();

    http.perform(
            post("/api/seckill/activities/activity-1/reservations")
                .header("Authorization", identity.authorization())
                .header("X-Eval-Sandbox-Id", identity.sandboxId())
                .header("Idempotency-Key", "evaluation-proof")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1,\"expectedActivityVersion\":1}"))
        .andExpect(status().isUnauthorized())
        .andExpect(
            content()
                .json(
                    "{\"category\":\"AUTHENTICATION\","
                        + "\"message\":\"Direct-user reservation authorization failed\"}"));
    verifyNoInteractions(coordinator);
  }
}
