package io.citybuddy.commerce.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
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
          List.of("attempt_id"),
          cardinality(
              sibling("callback_correlation_id"),
              unique("callback_event_id", "PRIMARY"),
              unique("callback_idempotency_key", "uq_mock_payment_callback_key"),
              unique("attempt_id", "uq_mock_payment_callback_attempt")),
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
          List.of("attempt_id", "callback_correlation_id"),
          List.of("order_id"),
          cardinality(
              unique("attempt_id", "PRIMARY"),
              unique("callback_correlation_id", "uq_mock_payment_callback_correlation"),
              sibling("order_id")),
          Map.of(
              "request_idempotency_key",
              "The start-command key has no independent durable anchor after creation; its "
                  + "database uniqueness and bounded-format invariant remain, and exact historical "
                  + "value recovery is an owner-accepted internal-view residual risk."),
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
          List.of(),
          cardinality(sibling("order_id")),
          Map.of(
              "evaluation_owner_handle",
              "The fixture-owner handle is reset provenance; committed replay is anchored to the "
                  + "effective user_subject, while historical handle recovery is an owner-accepted "
                  + "internal-view residual risk."),
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
          List.of("business_event_key"),
          List.of("order_id"),
          cardinality(
              unique("business_event_key", "uq_inventory_ledger_business_event"),
              sibling("order_id")),
          Map.of(
              "movement_id",
              "The database-generated movement primary key has uniqueness but no second content "
                  + "anchor; valid-UUID substitution is an owner-accepted internal-view residual "
                  + "risk."),
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
          List.of("audit_reference_id"),
          List.of("entity_id", "sandbox_id+support_session_id+trace_id+operation_id"),
          cardinality(
              unique("audit_reference_id", "uq_eval_audit_reference_id"),
              sibling("entity_id"),
              unique(
                  "sandbox_id+support_session_id+trace_id+operation_id",
                  "uq_eval_audit_operation")),
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

  public static List<FaceDefinition> all() {
    return List.of(CALLBACK, ATTEMPT, ORDER, LEDGER, AUDIT);
  }

  public static String onlyTable(FaceDefinition face) {
    if (face.tables().size() != 1) {
      throw new IllegalArgumentException(face.name() + " does not have exactly one table");
    }
    return face.tables().keySet().iterator().next();
  }

  public static String columnsCsv(FaceDefinition face) {
    return String.join(", ", face.tables().get(onlyTable(face)));
  }

  public static String attemptIntentHash(
      String orderId, long amountMinor, String currency, String sandboxId) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String canonical =
          orderId
              + "\n"
              + amountMinor
              + "\n"
              + currency
              + "\n"
              + (sandboxId == null ? "" : sandboxId);
      return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
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
      String name,
      List<String> stableKeys,
      List<String> relationKeys,
      Map<String, CardinalityControl> cardinalityControls,
      TableDefinition... tables) {
    return face(name, stableKeys, relationKeys, cardinalityControls, Map.of(), tables);
  }

  private static FaceDefinition face(
      String name,
      List<String> stableKeys,
      List<String> relationKeys,
      Map<String, CardinalityControl> cardinalityControls,
      Map<String, String> residualColumnDispositions,
      TableDefinition... tables) {
    Map<String, List<String>> physicalTables = new LinkedHashMap<>();
    for (TableDefinition table : tables) {
      if (physicalTables.put(table.name(), table.columns()) != null) {
        throw new IllegalArgumentException("Duplicate face table " + table.name());
      }
    }
    return new FaceDefinition(
        name,
        stableKeys,
        relationKeys,
        cardinalityControls,
        physicalTables,
        residualColumnDispositions);
  }

  private static Map<String, CardinalityControl> cardinality(CardinalityControl... controls) {
    Map<String, CardinalityControl> byKey = new LinkedHashMap<>();
    for (CardinalityControl control : controls) {
      if (byKey.put(control.key(), control) != null) {
        throw new IllegalArgumentException("Duplicate cardinality control for " + control.key());
      }
    }
    return Collections.unmodifiableMap(byKey);
  }

  private static CardinalityControl unique(String key, String constraintName) {
    return new CardinalityControl(key, CardinalityMode.DATABASE_UNIQUE, constraintName);
  }

  private static CardinalityControl sibling(String key) {
    return new CardinalityControl(key, CardinalityMode.INSERTABLE_SIBLING, "");
  }

  private static TableDefinition table(String name, String... columns) {
    return new TableDefinition(name, List.of(columns));
  }

  public record FaceDefinition(
      String name,
      List<String> stableKeys,
      List<String> relationKeys,
      Map<String, CardinalityControl> cardinalityControls,
      Map<String, List<String>> tables,
      Map<String, String> residualColumnDispositions) {
    public FaceDefinition {
      stableKeys = List.copyOf(stableKeys);
      relationKeys = List.copyOf(relationKeys);
      cardinalityControls = Map.copyOf(cardinalityControls);
      Map<String, List<String>> copy = new LinkedHashMap<>();
      tables.forEach((table, columns) -> copy.put(table, List.copyOf(columns)));
      tables = Collections.unmodifiableMap(copy);
      residualColumnDispositions = Map.copyOf(residualColumnDispositions);
      if (stableKeys.isEmpty() || tables.isEmpty()) {
        throw new IllegalArgumentException("A committed face requires keys and tables");
      }
      LinkedHashSet<String> enumerationKeys = new LinkedHashSet<>(stableKeys);
      enumerationKeys.addAll(relationKeys);
      if (!cardinalityControls.keySet().equals(enumerationKeys)) {
        throw new IllegalArgumentException(
            "Every enumeration key requires exactly one cardinality control");
      }
      LinkedHashSet<String> declaredColumns = new LinkedHashSet<>();
      tables.values().forEach(declaredColumns::addAll);
      if (!declaredColumns.containsAll(residualColumnDispositions.keySet())) {
        throw new IllegalArgumentException("Residual disposition names an undeclared column");
      }
    }

    public List<String> enumerationKeys() {
      LinkedHashSet<String> keys = new LinkedHashSet<>(stableKeys);
      keys.addAll(relationKeys);
      return List.copyOf(keys);
    }

    public List<String> participatingColumns() {
      LinkedHashSet<String> participating = new LinkedHashSet<>();
      tables.values().forEach(participating::addAll);
      participating.removeAll(residualColumnDispositions.keySet());
      return List.copyOf(participating);
    }
  }

  public enum CardinalityMode {
    DATABASE_UNIQUE,
    INSERTABLE_SIBLING
  }

  public record CardinalityControl(String key, CardinalityMode mode, String constraintName) {
    public CardinalityControl {
      if (key.isBlank()
          || (mode == CardinalityMode.DATABASE_UNIQUE && constraintName.isBlank())
          || (mode == CardinalityMode.INSERTABLE_SIBLING && !constraintName.isEmpty())) {
        throw new IllegalArgumentException("Invalid committed-face cardinality control");
      }
    }
  }

  private record TableDefinition(String name, List<String> columns) {}
}
