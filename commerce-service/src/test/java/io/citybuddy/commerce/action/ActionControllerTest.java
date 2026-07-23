package io.citybuddy.commerce.action;

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
  private OboAuthorizer authorizer;
  private ActionService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    authorizer = mock(OboAuthorizer.class);
    service = mock(ActionService.class);
    ActionProperties properties =
        new ActionProperties("refund:create", Duration.ofMinutes(15), 3, 1);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ActionController(authorizer, service, properties, new ObjectMapper()))
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
                ORDER,
                500,
                "AUD",
                "PREPARED",
                Instant.parse("2026-07-23T00:15:00Z"),
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
        .andExpect(jsonPath("$.state").value("PREPARED"))
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
  void rejectsUnknownOrCallerSelectedFieldsBeforePersistence() throws Exception {
    when(authorizer.authorize(anyString(), any(OboAuthorizer.AuthorizationRequest.class)))
        .thenReturn(new OboAuthorizer.OboPrincipal("user-1", "session-1", "refund:create", null));

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
                      "orderId":"%s","amountMinor":500,"currency":"AUD","userSubject":"other"
                    }}
                    """
                        .formatted(ORDER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.category").value("VALIDATION"));

    verifyNoInteractions(service);

    mvc.perform(
            post("/internal/tools/actions/prepare")
                .header("Authorization", "Bearer signed-obo")
                .header("X-Support-Session-Id", "session-1")
                .header("X-Agent-Trace-Id", "trace-1")
                .header("X-Agent-Turn-Id", TURN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(new byte[] {(byte) 0xc3, (byte) 0x28}))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.category").value("VALIDATION"));

    mvc.perform(
            post("/internal/tools/actions/prepare")
                .header("Authorization", "Bearer signed-obo")
                .header("X-Support-Session-Id", "session-1")
                .header("X-Agent-Trace-Id", "trace-1")
                .header("X-Agent-Turn-Id", TURN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.category").value("VALIDATION"));

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
                      "orderId":"%s","amountMinor":500,"amountMinor":600,"currency":"AUD"
                    }}
                    """
                        .formatted(ORDER)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.category").value("VALIDATION"));

    mvc.perform(
            post("/internal/tools/actions/prepare")
                .header("Authorization", "Bearer signed-obo")
                .header("X-Support-Session-Id", "session-1")
                .header("X-Agent-Trace-Id", "trace-1")
                .header("X-Agent-Turn-Id", TURN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(2049)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.category").value("VALIDATION"));

    verifyNoInteractions(service);
  }

  @Test
  void rejectsNonEmptyConfirmBodyAndBoundsOboFailures() throws Exception {
    when(authorizer.authorize(anyString(), any(OboAuthorizer.AuthorizationRequest.class)))
        .thenReturn(new OboAuthorizer.OboPrincipal("user-1", "session-1", "refund:create", null));
    mvc.perform(
            post("/internal/tools/actions/{id}/confirm", ACTION)
                .header("Authorization", "Bearer signed-obo")
                .header("X-Support-Session-Id", "session-1")
                .header("X-Agent-Trace-Id", "trace-1")
                .header("X-Agent-Turn-Id", TURN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"success\":true}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);

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
}
