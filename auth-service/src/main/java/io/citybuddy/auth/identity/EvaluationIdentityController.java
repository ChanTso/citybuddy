package io.citybuddy.auth.identity;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("evaluation")
@ConditionalOnProperty(name = "citybuddy.identity.enabled", havingValue = "true")
public final class EvaluationIdentityController {
  private static final String COMMERCE_CLIENT = "commerce-service";
  private static final String EVALUATOR_CLIENT = "evaluation-client";
  private static final String MANAGE_SCOPE = "eval:principal:manage";
  private static final String ISSUE_SCOPE = "eval:test-token:issue";

  private final AuthRepository repository;
  private final PasswordEncoder passwordEncoder;
  private final EvaluationIdentityService service;

  public EvaluationIdentityController(
      AuthRepository repository,
      PasswordEncoder passwordEncoder,
      EvaluationIdentityService service) {
    this.repository = repository;
    this.passwordEncoder = passwordEncoder;
    this.service = service;
  }

  @PostMapping("/internal/eval/test-principals/provision")
  public EvaluationIdentityService.ProvisionedPrincipal provision(
      @RequestHeader("Authorization") String authorization,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestBody ProvisionRequest request) {
    authenticate(authorization, COMMERCE_CLIENT, MANAGE_SCOPE);
    return service.provision(
        request.sandboxId(),
        request.caseCorrelation(),
        request.testUserLabel(),
        request.ttlSeconds(),
        idempotencyKey);
  }

  @PostMapping("/internal/eval/test-principals/{handle}/revoke")
  public EvaluationIdentityService.RevokedPrincipal revoke(
      @RequestHeader("Authorization") String authorization,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @PathVariable String handle,
      @RequestBody RevokeRequest request) {
    authenticate(authorization, COMMERCE_CLIENT, MANAGE_SCOPE);
    return service.revoke(handle, request.sandboxId(), request.caseCorrelation(), idempotencyKey);
  }

  @PostMapping("/auth/eval/test-token")
  public AuthController.TokenResponse testToken(
      @RequestHeader("Authorization") String authorization,
      @RequestHeader("X-Eval-Sandbox-Id") String sandboxId,
      @RequestBody TestTokenRequest request) {
    authenticate(authorization, EVALUATOR_CLIENT, ISSUE_SCOPE);
    return service.issueToken(request.handle(), sandboxId);
  }

  private void authenticate(String authorization, String expectedClient, String requiredScope) {
    BasicCredential basic = parseBasic(authorization);
    repository
        .findService(basic.clientId())
        .filter(item -> expectedClient.equals(item.clientId()))
        .filter(item -> "ACTIVE".equals(item.state()))
        .filter(item -> item.allowedScopes().contains(requiredScope))
        .filter(item -> passwordEncoder.matches(basic.secret(), item.credentialHash()))
        .orElseThrow(() -> new IdentityException(401, "Invalid evaluation credential"));
  }

  private static BasicCredential parseBasic(String authorization) {
    try {
      if (authorization == null || !authorization.startsWith("Basic ")) {
        throw new IllegalArgumentException();
      }
      String decoded =
          new String(
              Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
      int separator = decoded.indexOf(':');
      if (separator <= 0 || separator == decoded.length() - 1) {
        throw new IllegalArgumentException();
      }
      return new BasicCredential(decoded.substring(0, separator), decoded.substring(separator + 1));
    } catch (IllegalArgumentException exception) {
      throw new IdentityException(401, "Invalid evaluation credential");
    }
  }

  public record ProvisionRequest(
      String sandboxId, String caseCorrelation, String testUserLabel, int ttlSeconds) {}

  public record RevokeRequest(String sandboxId, String caseCorrelation) {}

  public record TestTokenRequest(String handle) {}

  private record BasicCredential(String clientId, String secret) {}
}
