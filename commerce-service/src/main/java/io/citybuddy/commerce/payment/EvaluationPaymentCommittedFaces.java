package io.citybuddy.commerce.payment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Single definition of the durable faces reconciled for an evaluation payment callback. */
public final class EvaluationPaymentCommittedFaces {
  public static final FaceDefinition CALLBACK =
      face(
          "callback",
          List.of("callback_correlation_id", "callback_event_id", "callback_idempotency_key"),
          table(
              "mock_payment_callback",
              "callback_event_id",
              "callback_idempotency_key",
              "attempt_id",
              "callback_correlation_id",
              "sandbox_id",
              "support_session_id",
              "trace_id",
              "operation_id",
              "intent_hash",
              "requested_outcome",
              "result_state",
              "created_at"));

  public static final FaceDefinition ATTEMPT =
      face(
          "attempt",
          List.of("callback_correlation_id", "attempt_id", "order_id"),
          table(
              "mock_payment_attempt",
              "attempt_id",
              "callback_correlation_id",
              "user_subject",
              "order_id",
              "order_kind",
              "sandbox_id",
              "request_idempotency_key",
              "intent_hash",
              "amount_minor",
              "refunded_amount_minor",
              "currency",
              "state",
              "state_version",
              "succeeded_at"));

  public static final FaceDefinition ORDER =
      face(
          "order",
          List.of("order_id"),
          table(
              "standard_order",
              "order_id",
              "user_subject",
              "sandbox_id",
              "evaluation_owner_handle",
              "product_id",
              "total_price_minor",
              "currency",
              "status",
              "state_version"),
          table(
              "seckill_order",
              "order_id",
              "user_subject",
              "product_id",
              "reservation_id",
              "activity_id",
              "total_price_minor",
              "currency",
              "status",
              "state_version"));

  public static final FaceDefinition LEDGER =
      face(
          "ledger",
          List.of("order_id", "business_event_key"),
          table(
              "inventory_ledger",
              "movement_id",
              "business_event_key",
              "movement_type",
              "order_id",
              "reservation_id",
              "activity_id",
              "product_id",
              "sandbox_id",
              "inventory_delta",
              "activity_quota_delta",
              "payment_amount_minor",
              "payment_currency"));

  public static final FaceDefinition AUDIT =
      face(
          "audit",
          List.of(
              "entity_id",
              "audit_reference_id",
              "sandbox_id+support_session_id+trace_id+operation_id"),
          table(
              "eval_commerce_audit_reference",
              "sequence_id",
              "audit_reference_id",
              "sandbox_id",
              "support_session_id",
              "trace_id",
              "operation_id",
              "entity_type",
              "entity_id",
              "entity_version",
              "outcome",
              "created_at",
              "created_at_anchor"));

  private EvaluationPaymentCommittedFaces() {}

  public static String onlyTable(FaceDefinition face) {
    if (face.tables().size() != 1) {
      throw new IllegalArgumentException(face.name() + " does not have exactly one table");
    }
    return face.tables().keySet().iterator().next();
  }

  public static String columnsCsv(FaceDefinition face) {
    return String.join(", ", face.tables().get(onlyTable(face)));
  }

  public static String standardOrderByIdSql(String lockClause) {
    return "SELECT "
        + orderProjection(0)
        + " FROM "
        + orderTable(0)
        + " WHERE "
        + orderStableKey()
        + " = ?"
        + lockClause;
  }

  public static String seckillOrderByIdSql(String lockClause) {
    return "SELECT "
        + orderProjection(1)
        + " FROM "
        + orderTable(1)
        + " WHERE "
        + orderStableKey()
        + " = ?"
        + lockClause;
  }

  public static String orderFaceUnionSql() {
    return "SELECT 'STANDARD' AS order_kind, "
        + orderProjection(0)
        + " FROM "
        + orderTable(0)
        + " UNION ALL SELECT 'SECKILL' AS order_kind, "
        + orderProjection(1)
        + " FROM "
        + orderTable(1);
  }

  public static String evaluationOrderKeysBySandboxSql() {
    return "SELECT " + orderStableKey() + " FROM " + orderTable(0) + " WHERE sandbox_id = ?";
  }

  private static String orderTable(int index) {
    return ORDER.tables().keySet().stream().toList().get(index);
  }

  private static String orderStableKey() {
    return ORDER.stableKeys().getFirst();
  }

  private static String orderProjection(int tableIndex) {
    String table = orderTable(tableIndex);
    List<String> physicalColumns = ORDER.tables().get(table);
    LinkedHashSet<String> faceColumns = new LinkedHashSet<>();
    ORDER.tables().values().forEach(faceColumns::addAll);
    return faceColumns.stream()
        .map(column -> physicalColumns.contains(column) ? column : "NULL AS " + column)
        .collect(java.util.stream.Collectors.joining(", "));
  }

  private static FaceDefinition face(
      String name, List<String> stableKeys, TableDefinition... tables) {
    Map<String, List<String>> physicalTables = new LinkedHashMap<>();
    for (TableDefinition table : tables) {
      if (physicalTables.put(table.name(), table.columns()) != null) {
        throw new IllegalArgumentException("Duplicate face table " + table.name());
      }
    }
    return new FaceDefinition(name, stableKeys, physicalTables);
  }

  private static TableDefinition table(String name, String... columns) {
    return new TableDefinition(name, List.of(columns));
  }

  public record FaceDefinition(
      String name, List<String> stableKeys, Map<String, List<String>> tables) {
    public FaceDefinition {
      stableKeys = List.copyOf(stableKeys);
      Map<String, List<String>> copy = new LinkedHashMap<>();
      tables.forEach((table, columns) -> copy.put(table, List.copyOf(columns)));
      tables = Collections.unmodifiableMap(copy);
      if (stableKeys.isEmpty() || tables.isEmpty()) {
        throw new IllegalArgumentException("A committed face requires keys and tables");
      }
    }
  }

  private record TableDefinition(String name, List<String> columns) {}
}
