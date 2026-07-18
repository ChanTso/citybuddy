package io.citybuddy.commerce.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class EvaluationCommerceAuditService {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public EvaluationCommerceAuditService(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Transactional
  public ProductObservation observeProduct(
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String productId) {
    EvaluationViewRequestParser.sandbox(sandboxId);
    EvaluationViewRequestParser.session(supportSessionId);
    EvaluationViewRequestParser.trace(traceId);
    EvaluationViewRequestParser.operation(operationId);
    EvaluationRequestParser.boundedHeader(productId, 64, "Invalid product");

    List<String> active =
        jdbc.query(
            """
            SELECT sandbox_id FROM eval_sandbox
            WHERE sandbox_id = ? AND lifecycle_state = 'ACTIVE' AND expires_at > ?
            FOR SHARE
            """,
            (result, row) -> result.getString("sandbox_id"),
            sandboxId,
            java.sql.Timestamp.from(clock.instant()));
    if (active.size() != 1) {
      throw new EvaluationSandboxException(403, "Evaluation sandbox is inactive");
    }

    List<ProductObservation> products =
        jdbc.query(
            """
            SELECT product_id, name, price_minor, currency, available, publication_version
            FROM eval_sandbox_product_fixture
            WHERE sandbox_id = ? AND product_id = ?
            """,
            EvaluationCommerceAuditService::mapProduct,
            sandboxId,
            productId);
    if (products.size() != 1) {
      throw new EvaluationSandboxException(404, "Product not found");
    }
    ProductObservation product = products.getFirst();
    String referenceId =
        digest(
            sandboxId,
            supportSessionId,
            traceId,
            operationId,
            "PRODUCT_FIXTURE",
            product.productId(),
            Long.toString(product.publicationVersion()),
            "OBSERVED");
    try {
      jdbc.update(
          """
          INSERT INTO eval_commerce_audit_reference
            (audit_reference_id, sandbox_id, support_session_id, trace_id, operation_id,
             entity_type, entity_id, entity_version, outcome)
          VALUES (?, ?, ?, ?, ?, 'PRODUCT_FIXTURE', ?, ?, 'OBSERVED')
          """,
          referenceId,
          sandboxId,
          supportSessionId,
          traceId,
          operationId,
          product.productId(),
          product.publicationVersion());
    } catch (DuplicateKeyException exception) {
      requireSameReference(
          referenceId,
          sandboxId,
          supportSessionId,
          traceId,
          operationId,
          product.productId(),
          product.publicationVersion());
    }
    return product;
  }

  private void requireSameReference(
      String referenceId,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String productId,
      long version) {
    List<StoredReference> existing =
        jdbc.query(
            """
            SELECT audit_reference_id, support_session_id, trace_id, entity_type, entity_id,
                   entity_version, outcome
            FROM eval_commerce_audit_reference
            WHERE sandbox_id = ? AND operation_id = ?
            """,
            (result, row) ->
                new StoredReference(
                    result.getString("audit_reference_id"),
                    result.getString("support_session_id"),
                    result.getString("trace_id"),
                    result.getString("entity_type"),
                    result.getString("entity_id"),
                    result.getLong("entity_version"),
                    result.getString("outcome")),
            sandboxId,
            operationId);
    StoredReference expected =
        new StoredReference(
            referenceId,
            supportSessionId,
            traceId,
            "PRODUCT_FIXTURE",
            productId,
            version,
            "OBSERVED");
    if (existing.size() != 1 || !expected.equals(existing.getFirst())) {
      throw new EvaluationSandboxException(409, "Conflicting evaluation operation");
    }
  }

  private static ProductObservation mapProduct(ResultSet result, int row) throws SQLException {
    return new ProductObservation(
        result.getString("product_id"),
        result.getString("name"),
        result.getLong("price_minor"),
        result.getString("currency"),
        result.getBoolean("available"),
        result.getLong("publication_version"));
  }

  private static String digest(String... values) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String value : values) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record ProductObservation(
      String productId,
      String name,
      long priceMinor,
      String currency,
      boolean available,
      long publicationVersion) {}

  private record StoredReference(
      String referenceId,
      String supportSessionId,
      String traceId,
      String entityType,
      String entityId,
      long entityVersion,
      String outcome) {}
}
