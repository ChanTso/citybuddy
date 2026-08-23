package io.citybuddy.commerce.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ActionControllerTest {
  private static final String TURN = "00000000-0000-0000-0000-000000000120";
  private static final String ORDER = "00000000-0000-0000-0000-000000000121";
  private static final String ACTION = "00000000-0000-0000-0000-000000000122";
  private static final String RECEIPT = "00000000-0000-0000-0000-000000000123";
  private static final String REFUND = "00000000-0000-0000-0000-000000000124";
  private OboAuthorizer authorizer;
  private ActionService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    authorizer = mock(OboAuthorizer.class);
    service = mock(ActionService.class);
    ActionProperties properties =
        new ActionProperties("refund:create", Duration.ofMinutes(15), 1, 3, Duration.ofMillis(25));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ActionController(authorizer, service, properties, new ObjectMapper()))
            .setControllerAdvice(new ActionExceptionHandler())
            .build();
  }

  @Test
  void preparesOnlyTheClosedRefundSchemaUnderExactOboContext() throws Exception {
    when(authorizer.authorize(anyString(), any(OboAuthorizer.AuthorizationRequest.class)))
        .thenReturn(new OboAuthorizer.OboPrincipal("user-1", "session-1", "refund:create", null));
    when(service.prepare(any(ActionRequestContext.class), any(PrepareActionCommand.class)))
        .thenReturn(
            new PendingActionView(
                ACTION,
                "REFUND_REQUEST",
                "user-1",
                "session-1",
                "trace-1",
                TURN,
                "refund:create",
                null,
                ORDER,
                7,
                500,
                "AUD",
                "PREPARED",
                Instant.parse("2026-07-27T00:15:00.123000Z"),
                false));

    mvc.perform(
            post("/internal/tools/actions/prepare")
                .header("Authorization", "Bearer signed-obo")
                .header("X-Support-Session-Id", "session-1")
                .header("X-Agent-Trace-Id", "trace-1")
                .header("X-Agent-Turn-Id", TURN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"actionType":"REFUND_REQUEST","arguments":{
                      "orderId":"%s","amountMinor":500,"currency":"AUD"
                    }}
                    """
                        .formatted(ORDER)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.pendingActionId").value(ACTION))
        .andExpect(jsonPath("$.userSubject").value("user-1"))
        .andExpect(jsonPath("$.supportSessionId").value("session-1"))
        .andExpect(jsonPath("$.traceId").value("trace-1"))
        .andExpect(jsonPath("$.turnId").value(TURN))
        .andExpect(jsonPath("$.requiredScope").value("refund:create"))
        .andExpect(jsonPath("$.sandboxId").value(nullValue()))
        .andExpect(jsonPath("$.targetVersion").value(7))
        .andExpect(jsonPath("$.expiresAt").value("2026-07-27T00:15:00.123000Z"))
        .andExpect(jsonPath("$.replayed").value(false));

    verify(authorizer)
        .authorize(
            eq("signed-obo"),
            eq(
                new OboAuthorizer.AuthorizationRequest(
                    "refund:create", null, "session-1", null, null, null)));
    verify(service)
        .prepare(
            eq(
                new ActionRequestContext(
                    "user-1", "session-1", "trace-1", TURN, null, "refund:create")),
            eq(new PrepareActionCommand("REFUND_REQUEST", ORDER, 500L, "AUD")));
  }

  @Test
  void confirmsWithCanonicalUtcMicroseconds() throws Exception {
    when(authorizer.authorize(anyString(), any(OboAuthorizer.AuthorizationRequest.class)))
        .thenReturn(new OboAuthorizer.OboPrincipal("user-1", "session-1", "refund:create", null));
    when(service.confirm(any(ActionRequestContext.class), eq(ACTION)))
        .thenReturn(
            new ActionReceiptView(
                RECEIPT,
                ACTION,
                "REFUND_REQUEST",
                "REQUESTED",
                ORDER,
                REFUND,
                1,
                500,
                "AUD",
                Instant.parse("2026-07-27T00:16:00.123000Z"),
                false));

    mvc.perform(
            post("/internal/tools/actions/{id}/confirm", ACTION)
                .header("Authorization", "Bearer signed-obo")
                .header("X-Support-Session-Id", "session-1")
                .header("X-Agent-Trace-Id", "trace-1")
                .header("X-Agent-Turn-Id", TURN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.receiptId").value(RECEIPT))
        .andExpect(jsonPath("$.committedAt").value("2026-07-27T00:16:00.123000Z"))
        .andExpect(jsonPath("$.replayed").value(false));
  }

  @Test
  void prepareViewProjectsEveryBindingFromTheDurablePendingRecord() {
    Instant created = Instant.parse("2026-07-27T00:00:00Z");
    ActionRepository.PendingActionRecord pending =
        new ActionRepository.PendingActionRecord(
            ACTION,
            "action-key",
            "pending-hash",
            "REFUND_REQUEST",
            "argument-hash",
            "durable-user",
            "durable-session",
            "durable-trace",
            TURN,
            "refund:create",
            "durable-sandbox",
            ORDER,
            "STANDARD",
            "00000000-0000-0000-0000-000000000123",
            9,
            500,
            "AUD",
            "PREPARED",
            1,
            created.plusSeconds(900),
            null,
            created);

    PendingActionView view = ActionService.pendingView(pending, true);

    assertThat(view.userSubject()).isEqualTo(pending.userSubject());
    assertThat(view.supportSessionId()).isEqualTo(pending.supportSessionId());
    assertThat(view.traceId()).isEqualTo(pending.traceId());
    assertThat(view.turnId()).isEqualTo(pending.turnId());
    assertThat(view.requiredScope()).isEqualTo(pending.requiredScope());
    assertThat(view.sandboxId()).isEqualTo(pending.sandboxId());
    assertThat(view.targetVersion()).isEqualTo(pending.targetOrderVersion());
    assertThat(view.replayed()).isTrue();
  }

  @Test
  void rejectsUnknownDuplicateMalformedAndOversizedInputsBeforePersistence() throws Exception {
    when(authorizer.authorize(anyString(), any(OboAuthorizer.AuthorizationRequest.class)))
        .thenReturn(new OboAuthorizer.OboPrincipal("user-1", "session-1", "refund:create", null));

    for (byte[] content :
        new byte[][] {
          """
          {"actionType":"REFUND_REQUEST","arguments":{
            "orderId":"00000000-0000-0000-0000-000000000121",
            "amountMinor":500,"currency":"AUD","userSubject":"other"
          }}
          """
              .getBytes(java.nio.charset.StandardCharsets.UTF_8),
          new byte[] {(byte) 0xc3, (byte) 0x28},
          "{}{}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
          """
          {"actionType":"REFUND_REQUEST","arguments":{
            "orderId":"00000000-0000-0000-0000-000000000121",
            "amountMinor":500,"amountMinor":600,"currency":"AUD"
          }}
          """
              .getBytes(java.nio.charset.StandardCharsets.UTF_8),
          " ".repeat(2049).getBytes(java.nio.charset.StandardCharsets.UTF_8)
        }) {
      mvc.perform(
              post("/internal/tools/actions/prepare")
                  .header("Authorization", "Bearer signed-obo")
                  .header("X-Support-Session-Id", "session-1")
                  .header("X-Agent-Trace-Id", "trace-1")
                  .header("X-Agent-Turn-Id", TURN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(content))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.category").value("VALIDATION"));
    }
    verifyNoInteractions(service);
  }

  @Test
  void preservesConcealmentAndDoesNotExposeServerReason() throws Exception {
    when(authorizer.authorize(anyString(), any(OboAuthorizer.AuthorizationRequest.class)))
        .thenReturn(new OboAuthorizer.OboPrincipal("user-1", "session-1", "refund:create", null));
    doThrow(
            new ActionException(
                404,
                "NOT_FOUND",
                ActionRejectionReason.ACTION_CONCEALED_NOT_FOUND,
                "PendingAction is missing or not owned"))
        .when(service)
        .confirm(any(ActionRequestContext.class), eq(ACTION));

    mvc.perform(
            post("/internal/tools/actions/{id}/confirm", ACTION)
                .header("Authorization", "Bearer signed-obo")
                .header("X-Support-Session-Id", "session-1")
                .header("X-Agent-Trace-Id", "trace-1")
                .header("X-Agent-Turn-Id", TURN))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.category").value("NOT_FOUND"))
        .andExpect(jsonPath("$.reason").doesNotExist());

    doThrow(new OboAuthorizationException("private claim detail"))
        .when(authorizer)
        .authorize(eq("direct-token"), any(OboAuthorizer.AuthorizationRequest.class));
    mvc.perform(
            post("/internal/tools/actions/prepare")
                .header("Authorization", "Bearer direct-token")
                .header("X-Support-Session-Id", "session-1")
                .header("X-Agent-Trace-Id", "trace-1")
                .header("X-Agent-Turn-Id", TURN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Forbidden"));
  }

  @Test
  void missingContextHeadersUseOnlyDocumentedClosedErrors() throws Exception {
    when(authorizer.authorize(anyString(), any(OboAuthorizer.AuthorizationRequest.class)))
        .thenReturn(new OboAuthorizer.OboPrincipal("user-1", "session-1", "refund:create", null));

    for (String route :
        new String[] {
          "/internal/tools/actions/prepare", "/internal/tools/actions/" + ACTION + "/confirm"
        }) {
      mvc.perform(
              post(route)
                  .header("Authorization", "Bearer signed-obo")
                  .header("X-Agent-Trace-Id", "trace-1")
                  .header("X-Agent-Turn-Id", TURN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(route.endsWith("prepare") ? "{}" : ""))
          .andExpect(status().isForbidden())
          .andExpect(jsonPath("$.error").value("Forbidden"))
          .andExpect(jsonPath("$.category").doesNotExist());

      mvc.perform(
              post(route)
                  .header("Authorization", "Bearer signed-obo")
                  .header("X-Support-Session-Id", "session-1")
                  .header("X-Agent-Turn-Id", TURN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(route.endsWith("prepare") ? "{}" : ""))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.category").value("VALIDATION"))
          .andExpect(jsonPath("$.message").isNotEmpty());

      mvc.perform(
              post(route)
                  .header("Authorization", "Bearer signed-obo")
                  .header("X-Support-Session-Id", "session-1")
                  .header("X-Agent-Trace-Id", "trace-1")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(route.endsWith("prepare") ? "{}" : ""))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.category").value("VALIDATION"))
          .andExpect(jsonPath("$.message").isNotEmpty());
    }
    verifyNoInteractions(service);
  }
}
