package io.citybuddy.commerce.action;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.action.ActionRepository.ActionIntegrityException;
import io.citybuddy.commerce.evaluation.EvaluationSandboxException;
import io.citybuddy.commerce.identity.IdentityVerificationUnavailableException;
import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import io.citybuddy.commerce.refund.RefundException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "citybuddy.actions.enabled", havingValue = "true")
public final class ActionController {
  private static final Logger LOG = LoggerFactory.getLogger(ActionController.class);
  private static final int MAXIMUM_REQUEST_BYTES = 2048;
  private static final Set<String> PREPARE_FIELDS = Set.of("actionType", "arguments");
  private static final Set<String> ARGUMENT_FIELDS = Set.of("orderId", "amountMinor", "currency");

  private final OboAuthorizer authorizer;
  private final ActionService service;
  private final ActionProperties properties;
  private final ObjectMapper objectMapper;

  public ActionController(
      OboAuthorizer authorizer,
      ActionService service,
      ActionProperties properties,
      ObjectMapper objectMapper) {
    this.authorizer = authorizer;
    this.service = service;
    this.properties = properties;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/internal/tools/actions/prepare")
  public ResponseEntity<PendingActionView> prepare(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader("X-Support-Session-Id") String supportSession,
      @RequestHeader("X-Agent-Trace-Id") String traceId,
      @RequestHeader("X-Agent-Turn-Id") String turnId,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      HttpServletRequest request) {
    OboAuthorizer.OboPrincipal principal = authorize(authorization, supportSession, evalSandbox);
    PrepareActionCommand command = parsePrepare(parseJson(request));
    PendingActionView result =
        service.prepare(context(principal, supportSession, traceId, turnId), command);
    return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
        .body(result);
  }

  @PostMapping("/internal/tools/actions/{pendingActionId}/confirm")
  public ActionReceiptView confirm(
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader("X-Support-Session-Id") String supportSession,
      @RequestHeader("X-Agent-Trace-Id") String traceId,
      @RequestHeader("X-Agent-Turn-Id") String turnId,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      @PathVariable String pendingActionId,
      HttpServletRequest request) {
    OboAuthorizer.OboPrincipal principal = authorize(authorization, supportSession, evalSandbox);
    JsonNode body = parseJson(request);
    if (body != null && (!body.isObject() || !body.isEmpty())) {
      throw new ActionException(400, "VALIDATION", "Confirm body must be empty");
    }
    return service.confirm(context(principal, supportSession, traceId, turnId), pendingActionId);
  }

  private OboAuthorizer.OboPrincipal authorize(
      String authorization, String supportSession, String evalSandbox) {
    return authorizer.authorize(
        bearer(authorization),
        new OboAuthorizer.AuthorizationRequest(
            properties.requiredScope(), null, supportSession, null, null, evalSandbox));
  }

  private ActionRequestContext context(
      OboAuthorizer.OboPrincipal principal, String supportSession, String traceId, String turnId) {
    return new ActionRequestContext(
        principal.subject(),
        supportSession,
        traceId,
        turnId,
        principal.sandboxId(),
        properties.requiredScope());
  }

  private static PrepareActionCommand parsePrepare(JsonNode request) {
    if (request == null
        || !request.isObject()
        || !fields(request).equals(PREPARE_FIELDS)
        || !request.get("actionType").isTextual()) {
      throw new ActionException(400, "VALIDATION", "Action request schema is invalid");
    }
    JsonNode arguments = request.get("arguments");
    if (arguments == null || !arguments.isObject() || !fields(arguments).equals(ARGUMENT_FIELDS)) {
      throw new ActionException(400, "VALIDATION", "Action arguments schema is invalid");
    }
    JsonNode orderId = arguments.get("orderId");
    JsonNode amount = arguments.get("amountMinor");
    JsonNode currency = arguments.get("currency");
    return new PrepareActionCommand(
        request.get("actionType").textValue(),
        orderId != null && orderId.isTextual() ? orderId.textValue() : null,
        amount != null && amount.isIntegralNumber() && amount.canConvertToLong()
            ? amount.longValue()
            : null,
        currency != null && currency.isTextual() ? currency.textValue() : null);
  }

  private JsonNode parseJson(byte[] request) {
    if (request == null || request.length == 0) {
      return null;
    }
    try (var parser = objectMapper.getFactory().createParser(request)) {
      parser.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
      JsonNode result = objectMapper.readTree(parser);
      if (result == null || parser.nextToken() != null) {
        throw new IllegalArgumentException("Action request must contain one JSON value");
      }
      return result;
    } catch (Exception exception) {
      throw new ActionException(400, "VALIDATION", "Action request JSON is invalid");
    }
  }

  private JsonNode parseJson(HttpServletRequest request) {
    try {
      long declaredLength = request.getContentLengthLong();
      if (declaredLength > MAXIMUM_REQUEST_BYTES) {
        throw new ActionException(400, "VALIDATION", "Action request body is too large");
      }
      byte[] body = request.getInputStream().readNBytes(MAXIMUM_REQUEST_BYTES + 1);
      if (body.length > MAXIMUM_REQUEST_BYTES) {
        throw new ActionException(400, "VALIDATION", "Action request body is too large");
      }
      return parseJson(body);
    } catch (ActionException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new ActionException(400, "VALIDATION", "Action request JSON is invalid");
    }
  }

  private static Set<String> fields(JsonNode node) {
    java.util.HashSet<String> result = new java.util.HashSet<>();
    node.fieldNames().forEachRemaining(result::add);
    return Set.copyOf(result);
  }

  private static String bearer(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      throw new OboAuthorizationException("Missing OBO authorization");
    }
    return authorization.substring(7);
  }

  @ExceptionHandler(OboAuthorizationException.class)
  ResponseEntity<Map<String, String>> denied() {
    LOG.warn("action_request_rejected reason_code=ACTION_OBO_AUTHORIZATION_REJECTED");
    return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
  }

  @ExceptionHandler(IdentityVerificationUnavailableException.class)
  ResponseEntity<Map<String, String>> identityUnavailable() {
    LOG.warn("action_request_rejected reason_code=ACTION_OBO_JWKS_UNAVAILABLE");
    return ResponseEntity.status(503).body(Map.of("error", "Service unavailable"));
  }

  @ExceptionHandler(EvaluationSandboxException.class)
  ResponseEntity<Map<String, String>> sandbox(EvaluationSandboxException exception) {
    LOG.warn("action_request_rejected reason_code={}", exception.reason());
    return ResponseEntity.status(exception.status() == 503 ? 503 : 403)
        .body(Map.of("error", exception.status() == 503 ? "Service unavailable" : "Forbidden"));
  }

  @ExceptionHandler(ActionException.class)
  ResponseEntity<Map<String, String>> action(ActionException exception) {
    return ResponseEntity.status(exception.status())
        .body(Map.of("category", exception.category(), "message", exception.getMessage()));
  }

  @ExceptionHandler(RefundException.class)
  ResponseEntity<Map<String, String>> refund(RefundException exception) {
    return ResponseEntity.status(exception.status())
        .body(Map.of("category", exception.category(), "message", exception.getMessage()));
  }

  @ExceptionHandler(ActionIntegrityException.class)
  ResponseEntity<Map<String, String>> integrity() {
    return ResponseEntity.status(409)
        .body(
            Map.of(
                "category",
                "INCONSISTENT_DURABLE_STATE",
                "message",
                "Action durable truth is inconsistent"));
  }
}
