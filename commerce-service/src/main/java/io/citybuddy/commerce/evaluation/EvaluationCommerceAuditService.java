package io.citybuddy.commerce.evaluation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
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
            FOR UPDATE
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
        EvaluationAuditReferenceIdentity.productFixture(
            sandboxId,
            supportSessionId,
            traceId,
            operationId,
            product.productId(),
            product.publicationVersion());
    StoredReference existingReference = referenceForOperation(sandboxId, operationId);
    if (existingReference != null && "LEGACY_CUTOFF".equals(existingReference.createdAtAnchor())) {
      Instant legacyCreatedAt =
          requireLegacyReplay(
              existingReference,
              referenceId,
              sandboxId,
              supportSessionId,
              traceId,
              operationId,
              product.productId(),
              product.publicationVersion());
      insertOrVerifyObservation(
          referenceId,
          sandboxId,
          supportSessionId,
          traceId,
          operationId,
          product.productId(),
          product.publicationVersion(),
          legacyCreatedAt);
      return product;
    }
    Instant observedAt =
        insertOrVerifyObservation(
            referenceId,
            sandboxId,
            supportSessionId,
            traceId,
            operationId,
            product.productId(),
            product.publicationVersion(),
            EvaluationAuditReferenceWriter.monotonicCreatedAt(jdbc, sandboxId, clock.instant()));
    try {
      EvaluationAuditReferenceWriter.insert(
          jdbc,
          referenceId,
          sandboxId,
          supportSessionId,
          traceId,
          operationId,
          EvaluationAuditEntityType.PRODUCT_FIXTURE,
          product.productId(),
          product.publicationVersion(),
          observedAt);
    } catch (DuplicateKeyException exception) {
      requireSameReference(
          referenceId,
          sandboxId,
          supportSessionId,
          traceId,
          operationId,
          product.productId(),
          product.publicationVersion(),
          observedAt);
    }
    return product;
  }

  private Instant insertOrVerifyObservation(
      String referenceId,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String productId,
      long version,
      Instant observedAt) {
    jdbc.update(
        """
        INSERT IGNORE INTO eval_commerce_product_observation
          (observation_id, sandbox_id, support_session_id, trace_id, operation_id,
           product_id, product_version, outcome, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'OBSERVED', ?)
        """,
        referenceId,
        sandboxId,
        supportSessionId,
        traceId,
        operationId,
        productId,
        version,
        Timestamp.from(observedAt));
    List<ProductObservationTruth> existing =
        jdbc.query(
            """
            SELECT observation_id, sandbox_id, support_session_id, trace_id, operation_id,
                   product_id, product_version, outcome, created_at
            FROM eval_commerce_product_observation
            WHERE observation_id = ? OR (sandbox_id = ? AND operation_id = ?)
            FOR SHARE
            """,
            (result, row) ->
                new ProductObservationTruth(
                    result.getString("observation_id"),
                    result.getString("sandbox_id"),
                    result.getString("support_session_id"),
                    result.getString("trace_id"),
                    result.getString("operation_id"),
                    result.getString("product_id"),
                    result.getLong("product_version"),
                    result.getString("outcome"),
                    result.getTimestamp("created_at").toInstant()),
            referenceId,
            sandboxId,
            operationId);
    ProductObservationTruth expected =
        new ProductObservationTruth(
            referenceId,
            sandboxId,
            supportSessionId,
            traceId,
            operationId,
            productId,
            version,
            "OBSERVED",
            existing.isEmpty() ? observedAt : existing.getFirst().createdAt());
    if (existing.size() != 1 || !expected.equals(existing.getFirst())) {
      throw new EvaluationSandboxException(409, "Conflicting evaluation operation");
    }
    return existing.getFirst().createdAt();
  }

  private void requireSameReference(
      String referenceId,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String productId,
      long version,
      Instant createdAt) {
    StoredReference existing = referenceForOperation(sandboxId, operationId);
    if (existing == null
        || !matchesProductReference(
            existing,
            referenceId,
            sandboxId,
            supportSessionId,
            traceId,
            operationId,
            productId,
            version,
            createdAt,
            "BUSINESS_EVENT")) {
      throw new EvaluationSandboxException(409, "Conflicting evaluation operation");
    }
  }

  private StoredReference referenceForOperation(String sandboxId, String operationId) {
    List<StoredReference> existing =
        jdbc.query(
            """
            SELECT sequence_id, audit_reference_id, sandbox_id, support_session_id, trace_id,
                   operation_id, entity_type, entity_id, entity_version, outcome, created_at,
                   created_at_anchor
            FROM eval_commerce_audit_reference
            WHERE sandbox_id = ? AND operation_id = ?
            FOR SHARE
            """,
            (result, row) ->
                new StoredReference(
                    result.getLong("sequence_id"),
                    result.getString("audit_reference_id"),
                    result.getString("sandbox_id"),
                    result.getString("support_session_id"),
                    result.getString("trace_id"),
                    result.getString("operation_id"),
                    result.getString("entity_type"),
                    result.getString("entity_id"),
                    result.getLong("entity_version"),
                    result.getString("outcome"),
                    result.getTimestamp("created_at").toInstant(),
                    result.getString("created_at_anchor")),
            sandboxId,
            operationId);
    if (existing.size() > 1) {
      throw new EvaluationSandboxException(409, "Conflicting evaluation operation");
    }
    return existing.isEmpty() ? null : existing.getFirst();
  }

  private Instant requireLegacyReplay(
      StoredReference existing,
      String referenceId,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String productId,
      long version) {
    EvaluationLegacyAuditCommitmentStore.Snapshot commitment =
        EvaluationLegacyAuditCommitmentStore.load(jdbc);
    if (!commitment.contains(existing.sequenceId(), existing.referenceId())
        || !matchesProductReference(
            existing,
            referenceId,
            sandboxId,
            supportSessionId,
            traceId,
            operationId,
            productId,
            version,
            existing.createdAt(),
            "LEGACY_CUTOFF")) {
      throw new EvaluationSandboxException(409, "Conflicting evaluation operation");
    }
    return existing.createdAt();
  }

  private static boolean matchesProductReference(
      StoredReference existing,
      String referenceId,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String productId,
      long version,
      Instant createdAt,
      String createdAtAnchor) {
    return existing.referenceId().equals(referenceId)
        && existing.sandboxId().equals(sandboxId)
        && existing.supportSessionId().equals(supportSessionId)
        && existing.traceId().equals(traceId)
        && existing.operationId().equals(operationId)
        && existing.entityType().equals(EvaluationAuditEntityType.PRODUCT_FIXTURE.name())
        && existing.entityId().equals(productId)
        && existing.entityVersion() == version
        && "OBSERVED".equals(existing.outcome())
        && existing.createdAt().equals(createdAt)
        && existing.createdAtAnchor().equals(createdAtAnchor);
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

  public record ProductObservation(
      String productId,
      String name,
      long priceMinor,
      String currency,
      boolean available,
      long publicationVersion) {}

  private record StoredReference(
      long sequenceId,
      String referenceId,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String entityType,
      String entityId,
      long entityVersion,
      String outcome,
      Instant createdAt,
      String createdAtAnchor) {}

  private record ProductObservationTruth(
      String observationId,
      String sandboxId,
      String supportSessionId,
      String traceId,
      String operationId,
      String productId,
      long productVersion,
      String outcome,
      Instant createdAt) {}
}
