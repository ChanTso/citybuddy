package io.citybuddy.commerce.tool;

import com.fasterxml.jackson.databind.JsonNode;
import io.citybuddy.commerce.evaluation.EvaluationSandboxAccess;
import io.citybuddy.commerce.evaluation.EvaluationSandboxException;
import io.citybuddy.commerce.identity.OboAuthorizationException;
import io.citybuddy.commerce.identity.OboAuthorizer;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "citybuddy.agent-tools.enabled", havingValue = "true")
public final class AgentToolController {
  private static final String CATALOG_TOOL = "catalog.product.get";
  private static final String CATALOG_SCOPE = "catalog:read";
  private static final Set<String> CATALOG_FIELDS = Set.of("productId");

  private final OboAuthorizer authorizer;
  private final JdbcTemplate jdbc;
  private final ObjectProvider<EvaluationSandboxAccess> sandboxAccess;

  public AgentToolController(OboAuthorizer authorizer, JdbcTemplate jdbc) {
    this(authorizer, jdbc, null);
  }

  @Autowired
  public AgentToolController(
      OboAuthorizer authorizer,
      JdbcTemplate jdbc,
      ObjectProvider<EvaluationSandboxAccess> sandboxAccess) {
    this.authorizer = authorizer;
    this.jdbc = jdbc;
    this.sandboxAccess = sandboxAccess;
  }

  @PostMapping("/internal/tools/{toolName}")
  public Map<String, Object> execute(
      @PathVariable String toolName,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      @RequestHeader("X-Support-Session-Id") String supportSession,
      @RequestHeader(value = "X-Eval-Sandbox-Id", required = false) String evalSandbox,
      @RequestBody JsonNode request) {
    if (!CATALOG_TOOL.equals(toolName)) {
      throw new AgentToolException(404, "Unknown tool");
    }
    String token = bearer(authorization);
    OboAuthorizer.OboPrincipal principal =
        authorizer.authorize(
            token,
            new OboAuthorizer.AuthorizationRequest(
                CATALOG_SCOPE, null, supportSession, null, null, evalSandbox));
    if (!request.isObject()
        || request.size() != 1
        || !CATALOG_FIELDS.equals(
            java.util.stream.StreamSupport.stream(
                    java.util.Spliterators.spliteratorUnknownSize(
                        request.fieldNames(), java.util.Spliterator.ORDERED),
                    false)
                .collect(java.util.stream.Collectors.toUnmodifiableSet()))) {
      throw new AgentToolException(400, "Invalid tool input");
    }
    JsonNode productNode = request.get("productId");
    if (productNode == null
        || !productNode.isTextual()
        || productNode.textValue().isBlank()
        || productNode.textValue().length() > 64) {
      throw new AgentToolException(400, "Invalid tool input");
    }
    var products = product(principal.sandboxId(), productNode.textValue());
    if (products.size() != 1) {
      throw new AgentToolException(404, "Product not found");
    }
    return products.getFirst();
  }

  private java.util.List<Map<String, Object>> product(String sandboxId, String productId) {
    if (sandboxId != null) {
      EvaluationSandboxAccess access =
          sandboxAccess == null ? null : sandboxAccess.getIfAvailable();
      if (access == null) {
        throw new EvaluationSandboxException(403, "Evaluation sandbox is unavailable");
      }
      access.requireActive(sandboxId);
      return jdbc.query(
          """
          SELECT product_id, name, price_minor, currency, available, publication_version
          FROM eval_sandbox_product_fixture
          WHERE sandbox_id = ? AND product_id = ?
          """,
          AgentToolController::mapProduct,
          sandboxId,
          productId);
    }
    return jdbc.query(
        """
        SELECT product_id, name, price_minor, currency, available, publication_version
        FROM product WHERE product_id = ? AND publication_state = 'PUBLISHED'
        """,
        AgentToolController::mapProduct,
        productId);
  }

  private static Map<String, Object> mapProduct(java.sql.ResultSet result, int row)
      throws java.sql.SQLException {
    return Map.<String, Object>of(
        "productId", result.getString("product_id"),
        "name", result.getString("name"),
        "priceMinor", result.getLong("price_minor"),
        "currency", result.getString("currency"),
        "available", result.getBoolean("available"),
        "publicationVersion", result.getLong("publication_version"));
  }

  private static String bearer(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      throw new OboAuthorizationException("Missing OBO authorization");
    }
    return authorization.substring(7);
  }

  @ExceptionHandler(OboAuthorizationException.class)
  ResponseEntity<Map<String, String>> denied() {
    return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
  }

  @ExceptionHandler(EvaluationSandboxException.class)
  ResponseEntity<Map<String, String>> inactive() {
    return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
  }

  @ExceptionHandler(AgentToolException.class)
  ResponseEntity<Map<String, String>> rejected(AgentToolException exception) {
    return ResponseEntity.status(exception.status()).body(Map.of("error", exception.getMessage()));
  }

  private static final class AgentToolException extends RuntimeException {
    private final int status;

    private AgentToolException(int status, String message) {
      super(message);
      this.status = status;
    }

    int status() {
      return status;
    }
  }
}
