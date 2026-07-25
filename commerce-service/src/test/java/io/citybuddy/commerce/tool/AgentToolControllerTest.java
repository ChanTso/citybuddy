package io.citybuddy.commerce.tool;

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

import io.citybuddy.commerce.evaluation.EvaluationRejectionReason;
import io.citybuddy.commerce.evaluation.EvaluationSandboxException;
import io.citybuddy.commerce.identity.IdentityVerificationUnavailableException;
import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

@ExtendWith(OutputCaptureExtension.class)
class AgentToolControllerTest {
  private OboAuthorizer authorizer;
  private JdbcTemplate jdbc;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    authorizer = mock(OboAuthorizer.class);
    jdbc = mock(JdbcTemplate.class);
    mvc = MockMvcBuilders.standaloneSetup(new AgentToolController(authorizer, jdbc)).build();
  }

  @Test
  @SuppressWarnings("unchecked")
  void returnsOnlyTheBoundedPublishedProductView() throws Exception {
    when(authorizer.authorize(anyString(), any(OboAuthorizer.AuthorizationRequest.class)))
        .thenReturn(new OboAuthorizer.OboPrincipal("user-1", "session-1", "catalog:read", null));
    when(jdbc.query(anyString(), any(RowMapper.class), eq("product-1")))
        .thenReturn(
            List.of(
                Map.of(
                    "productId",
                    "product-1",
                    "name",
                    "Tea",
                    "priceMinor",
                    500L,
                    "currency",
                    "CNY",
                    "available",
                    true,
                    "publicationVersion",
                    2L)));

    mvc.perform(
            post("/internal/tools/catalog.product.get")
                .header("Authorization", "Bearer signed-obo")
                .header("X-Support-Session-Id", "session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"product-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.productId").value("product-1"))
        .andExpect(jsonPath("$.description").doesNotExist())
        .andExpect(jsonPath("$.stockQuantity").doesNotExist());

    verify(authorizer)
        .authorize(
            eq("signed-obo"),
            eq(
                new OboAuthorizer.AuthorizationRequest(
                    "catalog:read", null, "session-1", null, null, null)));
  }

  @Test
  void rejectsUnknownFieldsBeforeCommerceRead() throws Exception {
    mvc.perform(
            post("/internal/tools/catalog.product.get")
                .header("Authorization", "Bearer signed-obo")
                .header("X-Support-Session-Id", "session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"product-1\",\"scope\":\"catalog:*\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(jdbc);
  }

  @Test
  void boundsAndAttributesOboRejectionWithoutLeakingClaimDetails(CapturedOutput output)
      throws Exception {
    doThrow(new OboAuthorizationException("private token claim detail"))
        .when(authorizer)
        .authorize(anyString(), any(OboAuthorizer.AuthorizationRequest.class));

    mvc.perform(
            post("/internal/tools/catalog.product.get")
                .header("Authorization", "Bearer direct-user-token")
                .header("X-Support-Session-Id", "session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"product-1\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Forbidden"));

    verifyNoInteractions(jdbc);
    org.assertj.core.api.Assertions.assertThat(output)
        .contains("reason_code=TOOL_OBO_AUTHORIZATION_REJECTED")
        .doesNotContain("private token claim detail");
  }

  @Test
  void boundsAndAttributesSandboxRejectionWithoutLeakingInternalDetail(CapturedOutput output) {
    AgentToolController controller = new AgentToolController(authorizer, jdbc);

    var response =
        controller.inactive(
            new EvaluationSandboxException(
                403, EvaluationRejectionReason.TOOL_SANDBOX_NOT_ACTIVE, "private sandbox detail"));

    org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(403);
    org.assertj.core.api.Assertions.assertThat(response.getBody())
        .containsExactlyEntriesOf(Map.of("error", "Forbidden"));
    org.assertj.core.api.Assertions.assertThat(output)
        .contains(
            "producer_boundary=EVALUATION_SANDBOX_EXCEPTION original_status=403"
                + " reason_code=TOOL_SANDBOX_NOT_ACTIVE")
        .doesNotContain("private sandbox detail");
  }

  @Test
  void reportsOboVerificationDependencyFailureAsUnavailable(CapturedOutput output)
      throws Exception {
    doThrow(new IdentityVerificationUnavailableException(new IllegalStateException("private")))
        .when(authorizer)
        .authorize(anyString(), any(OboAuthorizer.AuthorizationRequest.class));

    mvc.perform(
            post("/internal/tools/catalog.product.get")
                .header("Authorization", "Bearer signed-obo")
                .header("X-Support-Session-Id", "session-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"product-1\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("Service unavailable"));

    verifyNoInteractions(jdbc);
    org.assertj.core.api.Assertions.assertThat(output)
        .contains("reason_code=TOOL_OBO_JWKS_UNAVAILABLE")
        .doesNotContain("private");
  }

  @Test
  void reportsMissingEvaluationComponentsAsUnavailable(CapturedOutput output) {
    AgentToolController controller = new AgentToolController(authorizer, jdbc);

    var response =
        controller.inactive(
            new EvaluationSandboxException(
                503,
                EvaluationRejectionReason.TOOL_EVALUATION_COMPONENT_UNAVAILABLE,
                "private component detail"));

    org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(503);
    org.assertj.core.api.Assertions.assertThat(response.getBody())
        .containsExactlyEntriesOf(Map.of("error", "Service unavailable"));
    org.assertj.core.api.Assertions.assertThat(output)
        .contains("reason_code=TOOL_EVALUATION_COMPONENT_UNAVAILABLE")
        .doesNotContain("private component detail");
  }

  @Test
  void preservesAttributedEvaluationToolStatusFamilies(CapturedOutput output) {
    AgentToolController controller = new AgentToolController(authorizer, jdbc);

    var missing =
        controller.inactive(
            new EvaluationSandboxException(
                404, EvaluationRejectionReason.TOOL_PRODUCT_NOT_FOUND, "private product detail"));
    var conflict =
        controller.inactive(
            new EvaluationSandboxException(
                409,
                EvaluationRejectionReason.TOOL_AUDIT_OPERATION_CONFLICT,
                "private operation detail"));
    var unavailable =
        controller.inactive(
            new EvaluationSandboxException(
                503,
                EvaluationRejectionReason.TOOL_AUDIT_PERSISTENCE_UNAVAILABLE,
                "private persistence detail"));

    org.assertj.core.api.Assertions.assertThat(missing.getStatusCode().value()).isEqualTo(404);
    org.assertj.core.api.Assertions.assertThat(missing.getBody())
        .containsExactlyEntriesOf(Map.of("error", "Not found"));
    org.assertj.core.api.Assertions.assertThat(conflict.getStatusCode().value()).isEqualTo(409);
    org.assertj.core.api.Assertions.assertThat(conflict.getBody())
        .containsExactlyEntriesOf(Map.of("error", "Conflict"));
    org.assertj.core.api.Assertions.assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
    org.assertj.core.api.Assertions.assertThat(unavailable.getBody())
        .containsExactlyEntriesOf(Map.of("error", "Service unavailable"));
    org.assertj.core.api.Assertions.assertThat(output)
        .contains("original_status=404 reason_code=TOOL_PRODUCT_NOT_FOUND")
        .contains("original_status=409 reason_code=TOOL_AUDIT_OPERATION_CONFLICT")
        .contains("original_status=503 reason_code=TOOL_AUDIT_PERSISTENCE_UNAVAILABLE")
        .doesNotContain("private product detail")
        .doesNotContain("private operation detail")
        .doesNotContain("private persistence detail");
  }

  @Test
  void refusesUnattributedEvaluationToolBusinessStatuses() {
    AgentToolController controller = new AgentToolController(authorizer, jdbc);

    for (int status : List.of(404, 409, 503)) {
      EvaluationSandboxException unattributed =
          new EvaluationSandboxException(status, "private unattributed detail");
      org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.inactive(unattributed))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Evaluation tool rejection requires attribution");
    }
  }

  @Test
  void attributesOtherToolDataAccessFailuresAsComponentUnavailable(CapturedOutput output) {
    AgentToolController controller = new AgentToolController(authorizer, jdbc);

    var response =
        controller.unavailable(
            new DataAccessResourceFailureException("private database resource detail"));

    org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(503);
    org.assertj.core.api.Assertions.assertThat(response.getBody())
        .containsExactlyEntriesOf(Map.of("error", "Service unavailable"));
    org.assertj.core.api.Assertions.assertThat(output)
        .contains(
            "producer_boundary=TOOL_DATA_ACCESS original_status=503"
                + " reason_code=TOOL_EVALUATION_COMPONENT_UNAVAILABLE")
        .doesNotContain("private database resource detail");

    ExceptionHandlerMethodResolver mappings =
        new ExceptionHandlerMethodResolver(AgentToolController.class);
    org.assertj.core.api.Assertions.assertThat(
            mappings.resolveMethod(new DataAccessResourceFailureException("controlled")))
        .isNotNull();
    org.assertj.core.api.Assertions.assertThat(
            mappings.resolveMethod(new CannotCreateTransactionException("controlled")))
        .isNotNull();
    org.assertj.core.api.Assertions.assertThat(
            mappings.resolveMethod(new CannotAcquireLockException("controlled")))
        .isNull();
    org.assertj.core.api.Assertions.assertThat(
            mappings.resolveMethod(new DuplicateKeyException("controlled")))
        .isNull();
    org.assertj.core.api.Assertions.assertThat(
            mappings.resolveMethod(new DataIntegrityViolationException("controlled")))
        .isNull();
  }
}
