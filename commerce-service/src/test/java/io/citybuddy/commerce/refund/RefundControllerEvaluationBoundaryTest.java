package io.citybuddy.commerce.refund;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.citybuddy.commerce.catalog.CatalogException;
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
  void refundStatusKeepsTheOwnerOnlyClosedHttpBoundary() throws Exception {
    DirectUserAuthorizer authorizer = mock(DirectUserAuthorizer.class);
    RefundService service = mock(RefundService.class);
    MockMvc http = http(authorizer, service);
    String refundId = "10000000-0000-0000-0000-000000000001";
    String hiddenRefundId = "10000000-0000-0000-0000-000000000002";
    var principal = new DirectUserAuthorizer.DirectPrincipal("owner-1", null, null);
    when(authorizer.authorize("Bearer owner", null, "refund:create")).thenReturn(principal);
    when(service.status("owner-1", refundId))
        .thenReturn(
            new RefundResult(
                refundId,
                "20000000-0000-0000-0000-000000000001",
                "STANDARD",
                "30000000-0000-0000-0000-000000000001",
                100,
                100,
                0,
                "AUD",
                "REQUESTED",
                1,
                null,
                false));
    when(service.status("owner-1", "not-a-refund"))
        .thenThrow(new RefundException(400, "VALIDATION", "Refund id is invalid"));
    when(service.status("owner-1", hiddenRefundId))
        .thenThrow(new RefundException(404, "NOT_FOUND", "Refund is missing or not owned"));
    when(authorizer.authorize(null, null, "refund:create"))
        .thenThrow(new CatalogException(401, "Direct-user authorization failed"));
    when(authorizer.authorize("Bearer no-permission", null, "refund:create"))
        .thenThrow(new CatalogException(403, "Missing permission"));
    when(authorizer.authorize("Bearer unavailable", null, "refund:create"))
        .thenThrow(
            new IdentityVerificationUnavailableException(
                new IllegalStateException("controlled JWKS outage")));

    http.perform(get("/api/refunds/{refundId}", refundId).header("Authorization", "Bearer owner"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"refundId\":\"" + refundId + "\",\"state\":\"REQUESTED\"}"));
    verify(service).status("owner-1", refundId);
    http.perform(
            get("/api/refunds/{refundId}", "not-a-refund").header("Authorization", "Bearer owner"))
        .andExpect(status().isBadRequest());
    http.perform(get("/api/refunds/{refundId}", refundId)).andExpect(status().isUnauthorized());
    http.perform(
            get("/api/refunds/{refundId}", refundId)
                .header("Authorization", "Bearer no-permission"))
        .andExpect(status().isForbidden());
    http.perform(
            get("/api/refunds/{refundId}", hiddenRefundId).header("Authorization", "Bearer owner"))
        .andExpect(status().isNotFound());
    http.perform(
            get("/api/refunds/{refundId}", refundId).header("Authorization", "Bearer unavailable"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void identityDependencyFailureUsesTheClosedRefundUnavailableContract() throws Exception {
    DirectUserAuthorizer authorizer = mock(DirectUserAuthorizer.class);
    doThrow(
            new IdentityVerificationUnavailableException(
                new IllegalStateException("controlled JWKS outage")))
        .when(authorizer)
        .authorize(any(), any(), eq("refund:create"));
    RefundService service = mock(RefundService.class);
    MockMvc http = http(authorizer, service);

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
    MockMvc http = http(identity.authorizer(), service);

    for (String sandboxHeader : new String[] {null, identity.sandboxId(), "wrong-sandbox"}) {
      var request =
          post("/api/orders/order-1/refunds")
              .header("Authorization", identity.authorization())
              .header("Idempotency-Key", "evaluation-proof")
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"amountMinor\":100,\"reason\":\"evaluation proof\"}");
      if (sandboxHeader != null) {
        request.header("X-Eval-Sandbox-Id", sandboxHeader);
      }
      http.perform(request)
          .andExpect(status().isUnauthorized())
          .andExpect(
              content()
                  .json(
                      "{\"category\":\"AUTHENTICATION\","
                          + "\"message\":\"Direct-user refund authorization failed\"}"));
    }
    verifyNoInteractions(service);
  }

  private static MockMvc http(DirectUserAuthorizer authorizer, RefundService service) {
    return MockMvcBuilders.standaloneSetup(
            new RefundController(
                authorizer,
                new RefundProperties(null, 0, 0, Duration.ZERO),
                service,
                new RefundRequestParser(new com.fasterxml.jackson.databind.ObjectMapper())))
        .setControllerAdvice(new RefundExceptionHandler())
        .build();
  }
}
