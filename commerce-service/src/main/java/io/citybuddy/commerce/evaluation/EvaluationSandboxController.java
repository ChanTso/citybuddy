package io.citybuddy.commerce.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import io.citybuddy.commerce.catalog.CatalogException;
import io.citybuddy.commerce.catalog.DirectUserAuthorizer;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("evaluation")
public final class EvaluationSandboxController {
  private final EvaluationManagementAuthenticator authenticator;
  private final EvaluationSandboxService service;
  private final EvaluationSandboxAccess access;
  private final DirectUserAuthorizer directUserAuthorizer;
  private final EvaluationViewService views;

  public EvaluationSandboxController(
      EvaluationManagementAuthenticator authenticator,
      EvaluationSandboxService service,
      EvaluationSandboxAccess access,
      DirectUserAuthorizer directUserAuthorizer,
      EvaluationViewService views) {
    this.authenticator = authenticator;
    this.service = service;
    this.access = access;
    this.directUserAuthorizer = directUserAuthorizer;
    this.views = views;
  }

  @GetMapping("/api/eval/state")
  public EvaluationViewService.StateView state(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String sandboxHeader,
      @RequestParam MultiValueMap<String, String> parameters) {
    authenticator.authenticate(authorization);
    EvaluationViewRequestParser.requireNoParameters(parameters);
    return views.state(EvaluationViewRequestParser.sandbox(sandboxHeader));
  }

  @GetMapping("/api/eval/audit/{sessionId}")
  public EvaluationViewService.AuditPage audit(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String sandboxHeader,
      @PathVariable String sessionId,
      @RequestParam MultiValueMap<String, String> parameters) {
    authenticator.authenticate(authorization);
    return views.audit(
        EvaluationViewRequestParser.sandbox(sandboxHeader),
        EvaluationViewRequestParser.session(sessionId),
        EvaluationViewRequestParser.auditPage(parameters));
  }

  @GetMapping("/api/eval/version")
  public EvaluationViewService.VersionView version(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestParam MultiValueMap<String, String> parameters) {
    authenticator.authenticate(authorization);
    EvaluationViewRequestParser.requireNoParameters(parameters);
    return views.version();
  }

  @PostMapping("/api/eval/reset")
  public EvaluationSandboxService.ResetResult reset(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody JsonNode body) {
    authenticator.authenticate(authorization);
    return service.reset(EvaluationRequestParser.parseReset(body), idempotencyKey);
  }

  @PostMapping("/api/eval/sandboxes/{sandboxId}/complete")
  public EvaluationSandboxService.CompletionResult complete(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @PathVariable String sandboxId,
      @RequestBody JsonNode body) {
    authenticator.authenticate(authorization);
    return service.complete(
        sandboxId, EvaluationRequestParser.parseCompletionCase(body), idempotencyKey);
  }

  @PostMapping("/internal/eval/sandboxes/{sandboxId}/liveness")
  public ResponseEntity<Void> liveness(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String sandboxHeader,
      @PathVariable String sandboxId) {
    DirectUserAuthorizer.DirectPrincipal principal =
        directUserAuthorizer.authorizeEvaluation(authorization, sandboxHeader, "support:chat");
    if (principal.sandboxId() == null
        || !sandboxId.equals(principal.sandboxId())
        || !sandboxId.equals(sandboxHeader)) {
      throw new EvaluationSandboxException(403, "Evaluation sandbox mismatch");
    }
    access.requireActive(sandboxId);
    return ResponseEntity.noContent().build();
  }

  @ExceptionHandler(EvaluationSandboxException.class)
  ResponseEntity<Map<String, String>> rejected(EvaluationSandboxException exception) {
    return ResponseEntity.status(exception.status()).body(Map.of("error", exception.getMessage()));
  }

  @ExceptionHandler(CatalogException.class)
  ResponseEntity<Map<String, String>> unauthorized(CatalogException exception) {
    int status = exception.status() == 404 ? 404 : 403;
    return ResponseEntity.status(status).body(Map.of("error", "Forbidden"));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<Map<String, String>> unavailable(DataAccessException exception) {
    return ResponseEntity.status(503).body(Map.of("error", "Service unavailable"));
  }
}
