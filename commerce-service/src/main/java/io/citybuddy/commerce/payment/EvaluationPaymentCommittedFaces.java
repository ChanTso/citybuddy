package io.citybuddy.commerce.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Single definition of the durable faces reconciled for an evaluation payment callback. */
public final class EvaluationPaymentCommittedFaces {
  public static final int MAXIMUM_LEDGER_CLOSURE_ROWS = 1024;
  public static final int MAXIMUM_ORDER_ORIGIN_ROWS = 1;

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
          responsibilities(
              hashCommitted(
                  "mock_payment_callback",
                  "CALLBACK_INTENT_V1",
                  "callback_event_id",
                  "callback_idempotency_key",
                  "callback_correlation_id",
                  "sandbox_id",
                  "support_session_id",
                  "trace_id",
                  "operation_id",
                  "intent_hash",
                  "requested_outcome"),
              derived(
                  "mock_payment_callback",
                  List.of(allScopesAnchor("mock_payment_attempt", "attempt_id")),
                  "attempt_id"),
              responsibility(
                  "mock_payment_callback", ContentDisposition.DATABASE_CONSTRAINED, "result_state"),
              correlated(
                  "mock_payment_callback",
                  "created_at",
                  CorrelatedContentGroupId.PAYMENT_EVENT_TIME,
                  PaymentTruthScope.PRODUCTION,
                  PaymentTruthScope.EVALUATION)),
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
          responsibilities(
              responsibility(
                  "mock_payment_attempt",
                  ContentDisposition.AUTHORITATIVE_ROOT,
                  "attempt_id",
                  "callback_correlation_id"),
              derived(
                  "mock_payment_attempt",
                  List.of(
                      standardScopesAnchor("standard_order", "user_subject"),
                      seckillAnchor("seckill_order", "user_subject")),
                  "user_subject"),
              derived(
                  "mock_payment_attempt",
                  List.of(
                      standardScopesAnchor("standard_order", "order_id"),
                      seckillAnchor("seckill_order", "order_id")),
                  "order_kind"),
              hashCommitted(
                  "mock_payment_attempt",
                  "PAYMENT_START_INTENT_V1",
                  "order_id",
                  "sandbox_id",
                  "request_idempotency_key",
                  "intent_hash",
                  "amount_minor",
                  "currency"),
              responsibility(
                  "mock_payment_attempt",
                  ContentDisposition.DATABASE_CONSTRAINED,
                  "refunded_amount_minor",
                  "state",
                  "state_version"),
              correlated(
                  "mock_payment_attempt",
                  "succeeded_at",
                  CorrelatedContentGroupId.PAYMENT_EVENT_TIME,
                  PaymentTruthScope.PRODUCTION,
                  PaymentTruthScope.EVALUATION)),
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
              CommittedPaymentTruthResolver.CommittedPaymentCaller.PAYMENT_START_REPLAY,
              Map.of(
                  "order_id", CallerColumnRole.VISIBILITY_INPUT,
                  "sandbox_id", CallerColumnRole.VISIBILITY_INPUT,
                  "user_subject", CallerColumnRole.VISIBILITY_INPUT,
                  "evaluation_owner_handle", CallerColumnRole.BINDING_PROVENANCE)),
          responsibilities(
              originCommitted(
                  "standard_order",
                  List.of(
                      standardAnchor("order_idempotency", "order_id"),
                      evaluationAnchor("standard_order", "order_id")),
                  "",
                  "order_id"),
              originCommitted(
                  "standard_order",
                  List.of(
                      standardAnchor("order_idempotency", "user_subject"),
                      evaluationAnchor("standard_order", "user_subject")),
                  "",
                  "user_subject"),
              derived(
                  "standard_order",
                  List.of(allScopesAnchor("mock_payment_attempt", "sandbox_id")),
                  "sandbox_id"),
              derived(
                  "standard_order",
                  List.of(
                      standardScopesAnchor("standard_order", "unit_price_minor"),
                      standardScopesAnchor("standard_order", "quantity")),
                  "total_price_minor"),
              originCommitted(
                  "standard_order",
                  List.of(
                      anchor(
                          "order_idempotency", "intent_hash", OrderOriginScope.PRODUCTION_STANDARD),
                      evaluationAnchor("standard_order", "product_id"),
                      evaluationAnchor("standard_order", "quantity"),
                      evaluationAnchor("standard_order", "product_version")),
                  "STANDARD_ORDER_INTENT_V1",
                  "product_id",
                  "quantity",
                  "product_version"),
              responsibility(
                  "standard_order",
                  ContentDisposition.AUTHORITATIVE_ROOT,
                  "unit_price_minor",
                  "currency"),
              residual(
                  "standard_order",
                  "evaluation_owner_handle",
                  "The fixture-owner handle is reset provenance; committed replay is anchored to "
                      + "the effective user_subject, while historical handle recovery is an "
                      + "owner-accepted internal-view residual risk."),
              responsibility(
                  "standard_order",
                  ContentDisposition.DATABASE_CONSTRAINED,
                  "status",
                  "state_version"),
              originCommitted(
                  "seckill_order",
                  List.of(seckillAnchor("seckill_reservation", "order_id")),
                  "",
                  "order_id"),
              originCommitted(
                  "seckill_order",
                  List.of(seckillAnchor("seckill_reservation", "user_subject")),
                  "",
                  "user_subject"),
              originCommitted(
                  "seckill_order",
                  List.of(seckillAnchor("seckill_reservation", "reservation_id")),
                  "",
                  "reservation_id"),
              originCommitted(
                  "seckill_order",
                  List.of(seckillAnchor("seckill_reservation", "activity_id")),
                  "",
                  "activity_id"),
              originCommitted(
                  "seckill_order",
                  List.of(seckillAnchor("seckill_reservation", "quantity")),
                  "",
                  "quantity"),
              responsibility(
                  "seckill_order", ContentDisposition.AUTHORITATIVE_ROOT, "transaction_event_id"),
              derived(
                  "seckill_order",
                  List.of(
                      seckillAnchor("seckill_order", "unit_price_minor"),
                      seckillAnchor("seckill_reservation", "quantity")),
                  "total_price_minor"),
              responsibility(
                  "seckill_order",
                  ContentDisposition.AUTHORITATIVE_ROOT,
                  "unit_price_minor",
                  "currency"),
              originCommitted(
                  "seckill_order",
                  List.of(
                      anchor(
                          "seckill_activity", "product_id", OrderOriginScope.PRODUCTION_SECKILL)),
                  "",
                  "product_id"),
              responsibility(
                  "seckill_order",
                  ContentDisposition.DATABASE_CONSTRAINED,
                  "status",
                  "state_version")),
          table(
              "standard_order",
              "order_id",
              "user_subject",
              "sandbox_id",
              "evaluation_owner_handle",
              "product_id",
              "quantity",
              "product_version",
              "unit_price_minor",
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
              "transaction_event_id",
              "quantity",
              "unit_price_minor",
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
          responsibilities(
              residual(
                  "inventory_ledger",
                  "movement_id",
                  "The database-generated movement primary key has uniqueness but no second "
                      + "content anchor; valid-UUID substitution is an owner-accepted "
                      + "internal-view residual risk."),
              responsibility(
                  "inventory_ledger", ContentDisposition.AUTHORITATIVE_ROOT, "business_event_key"),
              derived(
                  "inventory_ledger",
                  List.of(
                      standardScopesAnchor("standard_order", "order_id"),
                      seckillAnchor("seckill_order", "order_id")),
                  "movement_type",
                  "order_id"),
              derived(
                  "inventory_ledger",
                  List.of(seckillAnchor("seckill_reservation", "reservation_id")),
                  "reservation_id"),
              derived(
                  "inventory_ledger",
                  List.of(seckillAnchor("seckill_reservation", "activity_id")),
                  "activity_id"),
              derived(
                  "inventory_ledger",
                  List.of(
                      allScopesAnchor("mock_payment_attempt", "sandbox_id"),
                      allScopesAnchor("mock_payment_attempt", "amount_minor"),
                      allScopesAnchor("mock_payment_attempt", "currency")),
                  "sandbox_id",
                  "payment_amount_minor",
                  "payment_currency"),
              derived(
                  "inventory_ledger",
                  List.of(
                      standardAnchor("standard_order", "quantity"),
                      seckillAnchor("seckill_reservation", "quantity")),
                  "inventory_delta",
                  "activity_quota_delta"),
              derived(
                  "inventory_ledger",
                  "product_id",
                  anchor(
                      "standard_order",
                      "product_id",
                      OrderOriginScope.PRODUCTION_STANDARD,
                      OrderOriginScope.EVALUATION_STANDARD),
                  anchor("seckill_activity", "product_id", OrderOriginScope.PRODUCTION_SECKILL))),
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
          responsibilities(
              responsibility(
                  "eval_commerce_audit_reference",
                  ContentDisposition.DATABASE_CONSTRAINED,
                  "sequence_id",
                  "created_at_anchor"),
              hashCommitted(
                  "eval_commerce_audit_reference",
                  "EVALUATION_AUDIT_REFERENCE_V1",
                  "audit_reference_id"),
              derived(
                  "eval_commerce_audit_reference",
                  List.of(
                      evaluationAnchor("mock_payment_callback", "sandbox_id"),
                      evaluationAnchor("mock_payment_callback", "support_session_id"),
                      evaluationAnchor("mock_payment_callback", "trace_id"),
                      evaluationAnchor("mock_payment_callback", "operation_id"),
                      evaluationAnchor("mock_payment_callback", "callback_event_id"),
                      evaluationAnchor("mock_payment_attempt", "state_version")),
                  "sandbox_id",
                  "support_session_id",
                  "trace_id",
                  "operation_id",
                  "entity_type",
                  "entity_id",
                  "entity_version",
                  "outcome"),
              correlated(
                  "eval_commerce_audit_reference",
                  "created_at",
                  CorrelatedContentGroupId.PAYMENT_EVENT_TIME,
                  PaymentTruthScope.EVALUATION)),
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

  public static List<CorrelatedContentGroup> correlatedContentGroups() {
    Map<CorrelatedContentGroupId, List<CorrelatedContentMember>> members = new LinkedHashMap<>();
    for (FaceDefinition face : all()) {
      face.columnResponsibilities()
          .forEach(
              (column, responsibility) -> {
                if (responsibility.disposition() == ContentDisposition.CORRELATED_GROUP) {
                  members
                      .computeIfAbsent(
                          responsibility.correlatedGroup(), ignored -> new java.util.ArrayList<>())
                      .add(
                          new CorrelatedContentMember(
                              face.name(), column, responsibility.applicableScopes()));
                }
              });
    }
    return members.entrySet().stream()
        .map(entry -> new CorrelatedContentGroup(entry.getKey(), entry.getValue()))
        .toList();
  }

  public static List<OrderOriginDefinition> orderOriginDefinitions() {
    return List.of(
        new OrderOriginDefinition(
            "standard-order-intent",
            "order_idempotency",
            List.of("user_subject", "intent_hash", "order_id"),
            OrderOriginScope.PRODUCTION_STANDARD,
            OrderOriginValidator.STANDARD_ORDER_INTENT_HASH,
            "STANDARD_ORDER_INTENT_V1",
            MAXIMUM_ORDER_ORIGIN_ROWS),
        new OrderOriginDefinition(
            "seckill-activity",
            "seckill_activity",
            List.of("activity_id", "product_id"),
            OrderOriginScope.PRODUCTION_SECKILL,
            OrderOriginValidator.SECKILL_ACTIVITY_PRODUCT,
            "",
            MAXIMUM_ORDER_ORIGIN_ROWS),
        new OrderOriginDefinition(
            "seckill-reservation",
            "seckill_reservation",
            List.of(
                "reservation_id", "user_subject", "activity_id", "quantity", "state", "order_id"),
            OrderOriginScope.PRODUCTION_SECKILL,
            OrderOriginValidator.SECKILL_RESERVATION_RELATION,
            "",
            MAXIMUM_ORDER_ORIGIN_ROWS));
  }

  public static OrderOriginDefinition orderOriginDefinition(OrderOriginValidator validator) {
    List<OrderOriginDefinition> definitions =
        orderOriginDefinitions().stream()
            .filter(definition -> definition.validator() == validator)
            .toList();
    if (definitions.size() != 1) {
      throw new IllegalStateException("Order-origin validator requires exactly one definition");
    }
    return definitions.getFirst();
  }

  public static CorrelatedContentGroup correlatedContentGroup(CorrelatedContentGroupId id) {
    return correlatedContentGroups().stream()
        .filter(group -> group.id() == id)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown correlated content group " + id));
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
      String orderId,
      String requestIdempotencyKey,
      long amountMinor,
      String currency,
      String sandboxId) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      String canonical =
          orderId
              + "\n"
              + requestIdempotencyKey
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

  public static String standardOwnedOrderByIdSql(String lockClause) {
    return standardOrderByIdSql("") + " AND user_subject = ?" + lockClause;
  }

  public static String seckillOwnedOrderByIdSql(String lockClause) {
    return seckillOrderByIdSql("") + " AND user_subject = ?" + lockClause;
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
      ColumnResponsibilities responsibilities,
      TableDefinition... tables) {
    return face(
        name, stableKeys, relationKeys, cardinalityControls, Map.of(), responsibilities, tables);
  }

  private static FaceDefinition face(
      String name,
      List<String> stableKeys,
      List<String> relationKeys,
      Map<String, CardinalityControl> cardinalityControls,
      Map<CommittedPaymentTruthResolver.CommittedPaymentCaller, Map<String, CallerColumnRole>>
          callerColumnDispositions,
      ColumnResponsibilities responsibilities,
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
        responsibilities.values(),
        callerColumnDispositions);
  }

  private static ColumnResponsibilities responsibilities(ResponsibilityEntry... entries) {
    Map<ColumnRef, ColumnResponsibility> values = new LinkedHashMap<>();
    for (ResponsibilityEntry entry : entries) {
      for (Map.Entry<ColumnRef, ColumnResponsibility> value : entry.values().entrySet()) {
        if (values.put(value.getKey(), value.getValue()) != null) {
          throw new IllegalArgumentException(
              "Duplicate content responsibility for " + value.getKey());
        }
      }
    }
    return new ColumnResponsibilities(values);
  }

  private static ResponsibilityEntry responsibility(
      String table, ContentDisposition disposition, String... columns) {
    if (disposition == ContentDisposition.CORRELATED_GROUP
        || disposition == ContentDisposition.OWNER_ACCEPTED_RESIDUAL
        || disposition == ContentDisposition.HASH_COMMITTED
        || disposition == ContentDisposition.ORIGIN_COMMITTED
        || disposition == ContentDisposition.DERIVED_REPLICA) {
      throw new IllegalArgumentException("Special content disposition requires explicit metadata");
    }
    Map<ColumnRef, ColumnResponsibility> values = new LinkedHashMap<>();
    for (String column : columns) {
      values.put(
          new ColumnRef(table, column),
          new ColumnResponsibility(disposition, null, Set.of(), "", List.of(), ""));
    }
    return new ResponsibilityEntry(values);
  }

  private static ResponsibilityEntry hashCommitted(
      String table, String canonicalizerId, String... columns) {
    return anchored(table, ContentDisposition.HASH_COMMITTED, canonicalizerId, List.of(), columns);
  }

  private static ResponsibilityEntry originCommitted(
      String table, List<AnchorBinding> anchors, String canonicalizerId, String... columns) {
    return anchored(table, ContentDisposition.ORIGIN_COMMITTED, canonicalizerId, anchors, columns);
  }

  private static ResponsibilityEntry derived(
      String table, String column, AnchorBinding... anchors) {
    return anchored(table, ContentDisposition.DERIVED_REPLICA, "", List.of(anchors), column);
  }

  private static ResponsibilityEntry derived(
      String table, List<AnchorBinding> anchors, String... columns) {
    return anchored(table, ContentDisposition.DERIVED_REPLICA, "", anchors, columns);
  }

  private static ResponsibilityEntry anchored(
      String table,
      ContentDisposition disposition,
      String canonicalizerId,
      List<AnchorBinding> anchors,
      String... columns) {
    Map<ColumnRef, ColumnResponsibility> values = new LinkedHashMap<>();
    for (String column : columns) {
      values.put(
          new ColumnRef(table, column),
          new ColumnResponsibility(disposition, null, Set.of(), canonicalizerId, anchors, ""));
    }
    return new ResponsibilityEntry(values);
  }

  private static AnchorBinding anchor(
      String table, String column, OrderOriginScope... applicableScopes) {
    return new AnchorBinding(
        new ColumnRef(table, column), EnumSet.copyOf(List.of(applicableScopes)));
  }

  private static AnchorBinding standardAnchor(String table, String column) {
    return anchor(table, column, OrderOriginScope.PRODUCTION_STANDARD);
  }

  private static AnchorBinding evaluationAnchor(String table, String column) {
    return anchor(table, column, OrderOriginScope.EVALUATION_STANDARD);
  }

  private static AnchorBinding seckillAnchor(String table, String column) {
    return anchor(table, column, OrderOriginScope.PRODUCTION_SECKILL);
  }

  private static AnchorBinding standardScopesAnchor(String table, String column) {
    return anchor(
        table, column, OrderOriginScope.PRODUCTION_STANDARD, OrderOriginScope.EVALUATION_STANDARD);
  }

  private static AnchorBinding allScopesAnchor(String table, String column) {
    return anchor(
        table,
        column,
        OrderOriginScope.PRODUCTION_STANDARD,
        OrderOriginScope.EVALUATION_STANDARD,
        OrderOriginScope.PRODUCTION_SECKILL);
  }

  private static ResponsibilityEntry correlated(
      String table, String column, CorrelatedContentGroupId group, PaymentTruthScope... scopes) {
    return new ResponsibilityEntry(
        Map.of(
            new ColumnRef(table, column),
            new ColumnResponsibility(
                ContentDisposition.CORRELATED_GROUP,
                group,
                EnumSet.copyOf(List.of(scopes)),
                "",
                List.of(),
                "")));
  }

  private static ResponsibilityEntry residual(String table, String column, String rationale) {
    return new ResponsibilityEntry(
        Map.of(
            new ColumnRef(table, column),
            new ColumnResponsibility(
                ContentDisposition.OWNER_ACCEPTED_RESIDUAL,
                null,
                Set.of(),
                "",
                List.of(),
                rationale)));
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
    List<String> declaredColumns = List.of(columns);
    if (new LinkedHashSet<>(declaredColumns).size() != declaredColumns.size()) {
      throw new IllegalArgumentException("Duplicate physical column in face table " + name);
    }
    return new TableDefinition(name, declaredColumns);
  }

  public record FaceDefinition(
      String name,
      List<String> stableKeys,
      List<String> relationKeys,
      Map<String, CardinalityControl> cardinalityControls,
      Map<String, List<String>> tables,
      Map<ColumnRef, ColumnResponsibility> columnResponsibilities,
      Map<CommittedPaymentTruthResolver.CommittedPaymentCaller, Map<String, CallerColumnRole>>
          callerColumnDispositions) {
    public FaceDefinition {
      stableKeys = List.copyOf(stableKeys);
      relationKeys = List.copyOf(relationKeys);
      cardinalityControls = Map.copyOf(cardinalityControls);
      Map<String, List<String>> copy = new LinkedHashMap<>();
      tables.forEach((table, columns) -> copy.put(table, List.copyOf(columns)));
      tables = Collections.unmodifiableMap(copy);
      columnResponsibilities = Map.copyOf(columnResponsibilities);
      Map<CommittedPaymentTruthResolver.CommittedPaymentCaller, Map<String, CallerColumnRole>>
          callerCopy = new LinkedHashMap<>();
      callerColumnDispositions.forEach(
          (caller, dispositions) -> callerCopy.put(caller, Map.copyOf(dispositions)));
      callerColumnDispositions = Collections.unmodifiableMap(callerCopy);
      if (stableKeys.isEmpty() || tables.isEmpty()) {
        throw new IllegalArgumentException("A committed face requires keys and tables");
      }
      LinkedHashSet<String> enumerationKeys = new LinkedHashSet<>(stableKeys);
      enumerationKeys.addAll(relationKeys);
      if (!cardinalityControls.keySet().equals(enumerationKeys)) {
        throw new IllegalArgumentException(
            "Every enumeration key requires exactly one cardinality control");
      }
      LinkedHashSet<ColumnRef> declaredColumns = new LinkedHashSet<>();
      tables.forEach(
          (table, columns) ->
              columns.forEach(column -> declaredColumns.add(new ColumnRef(table, column))));
      if (!declaredColumns.equals(columnResponsibilities.keySet())) {
        throw new IllegalArgumentException(
            "Every physical content column requires exactly one responsibility");
      }
      if (callerColumnDispositions.values().stream()
          .anyMatch(
              dispositions ->
                  dispositions.keySet().stream()
                      .anyMatch(
                          column ->
                              declaredColumns.stream()
                                  .noneMatch(declared -> declared.column().equals(column))))) {
        throw new IllegalArgumentException("Caller disposition names an undeclared column");
      }
    }

    public List<String> enumerationKeys() {
      LinkedHashSet<String> keys = new LinkedHashSet<>(stableKeys);
      keys.addAll(relationKeys);
      return List.copyOf(keys);
    }

    public List<String> participatingColumns() {
      LinkedHashSet<String> participating = new LinkedHashSet<>();
      columnResponsibilities.forEach(
          (column, responsibility) -> {
            if (responsibility.disposition() != ContentDisposition.OWNER_ACCEPTED_RESIDUAL) {
              participating.add(column.column());
            }
          });
      return List.copyOf(participating);
    }

    public Map<String, String> residualColumnDispositions() {
      Map<String, String> residuals = new LinkedHashMap<>();
      columnResponsibilities.forEach(
          (column, responsibility) -> {
            if (responsibility.disposition() == ContentDisposition.OWNER_ACCEPTED_RESIDUAL) {
              residuals.put(column.column(), responsibility.rationale());
            }
          });
      return Collections.unmodifiableMap(residuals);
    }
  }

  public enum CardinalityMode {
    DATABASE_UNIQUE,
    INSERTABLE_SIBLING
  }

  public enum CallerColumnRole {
    COMMITTED_CONTENT,
    VISIBILITY_INPUT,
    BINDING_PROVENANCE,
    OWNER_ACCEPTED_RESIDUAL
  }

  public enum ContentDisposition {
    AUTHORITATIVE_ROOT,
    HASH_COMMITTED,
    ORIGIN_COMMITTED,
    DERIVED_REPLICA,
    DATABASE_CONSTRAINED,
    CORRELATED_GROUP,
    OWNER_ACCEPTED_RESIDUAL
  }

  public enum CorrelatedContentGroupId {
    PAYMENT_EVENT_TIME
  }

  public enum PaymentTruthScope {
    PRODUCTION,
    EVALUATION
  }

  public enum OrderOriginScope {
    PRODUCTION_STANDARD,
    EVALUATION_STANDARD,
    PRODUCTION_SECKILL
  }

  public enum OrderOriginValidator {
    STANDARD_ORDER_INTENT_HASH,
    SECKILL_ACTIVITY_PRODUCT,
    SECKILL_RESERVATION_RELATION
  }

  public record ColumnRef(String table, String column) {
    public ColumnRef {
      if (table.isBlank() || column.isBlank()) {
        throw new IllegalArgumentException("Content responsibility requires table and column");
      }
    }
  }

  public record ColumnResponsibility(
      ContentDisposition disposition,
      CorrelatedContentGroupId correlatedGroup,
      Set<PaymentTruthScope> applicableScopes,
      String canonicalizerId,
      List<AnchorBinding> anchorBindings,
      String rationale) {
    public ColumnResponsibility {
      applicableScopes = Set.copyOf(applicableScopes);
      anchorBindings = List.copyOf(anchorBindings);
      if (disposition == ContentDisposition.CORRELATED_GROUP) {
        if (correlatedGroup == null
            || applicableScopes.isEmpty()
            || !canonicalizerId.isEmpty()
            || !anchorBindings.isEmpty()
            || !rationale.isEmpty()) {
          throw new IllegalArgumentException("Invalid correlated content responsibility");
        }
      } else if (disposition == ContentDisposition.OWNER_ACCEPTED_RESIDUAL) {
        if (correlatedGroup != null
            || !applicableScopes.isEmpty()
            || !canonicalizerId.isEmpty()
            || !anchorBindings.isEmpty()
            || rationale.isBlank()) {
          throw new IllegalArgumentException("Invalid residual content responsibility");
        }
      } else if (disposition == ContentDisposition.HASH_COMMITTED) {
        if (correlatedGroup != null
            || !applicableScopes.isEmpty()
            || canonicalizerId.isBlank()
            || !anchorBindings.isEmpty()
            || !rationale.isEmpty()) {
          throw new IllegalArgumentException("Invalid hash content responsibility");
        }
      } else if (disposition == ContentDisposition.ORIGIN_COMMITTED
          || disposition == ContentDisposition.DERIVED_REPLICA) {
        if (correlatedGroup != null
            || !applicableScopes.isEmpty()
            || anchorBindings.isEmpty()
            || !rationale.isEmpty()
            || (disposition == ContentDisposition.DERIVED_REPLICA && !canonicalizerId.isEmpty())) {
          throw new IllegalArgumentException("Invalid anchored content responsibility");
        }
      } else if (correlatedGroup != null
          || !applicableScopes.isEmpty()
          || !canonicalizerId.isEmpty()
          || !anchorBindings.isEmpty()
          || !rationale.isEmpty()) {
        throw new IllegalArgumentException("Invalid independently anchored responsibility");
      }
    }
  }

  public record AnchorBinding(ColumnRef root, Set<OrderOriginScope> applicableScopes) {
    public AnchorBinding {
      applicableScopes = Set.copyOf(applicableScopes);
      if (applicableScopes.isEmpty()) {
        throw new IllegalArgumentException("An anchor binding requires an applicability scope");
      }
    }
  }

  public record OrderOriginDefinition(
      String name,
      String table,
      List<String> columns,
      OrderOriginScope scope,
      OrderOriginValidator validator,
      String canonicalizerId,
      int maximumRows) {
    public OrderOriginDefinition {
      columns = List.copyOf(columns);
      if (name.isBlank()
          || table.isBlank()
          || columns.isEmpty()
          || maximumRows < 1
          || maximumRows > MAXIMUM_ORDER_ORIGIN_ROWS
          || (validator == OrderOriginValidator.STANDARD_ORDER_INTENT_HASH
              && canonicalizerId.isBlank())
          || ((validator == OrderOriginValidator.SECKILL_ACTIVITY_PRODUCT
                  || validator == OrderOriginValidator.SECKILL_RESERVATION_RELATION)
              && !canonicalizerId.isEmpty())) {
        throw new IllegalArgumentException("Invalid order-origin definition");
      }
    }
  }

  public record CorrelatedContentMember(
      String face, ColumnRef column, Set<PaymentTruthScope> applicableScopes) {
    public CorrelatedContentMember {
      applicableScopes = Set.copyOf(applicableScopes);
    }
  }

  public record CorrelatedContentGroup(
      CorrelatedContentGroupId id, List<CorrelatedContentMember> members) {
    public CorrelatedContentGroup {
      members = List.copyOf(members);
      if (members.size() < 2) {
        throw new IllegalArgumentException("A correlated content group requires multiple members");
      }
    }

    public List<CorrelatedContentMember> membersFor(PaymentTruthScope scope) {
      return members.stream().filter(member -> member.applicableScopes().contains(scope)).toList();
    }
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

  private record ResponsibilityEntry(Map<ColumnRef, ColumnResponsibility> values) {}

  private record ColumnResponsibilities(Map<ColumnRef, ColumnResponsibility> values) {
    private ColumnResponsibilities {
      values = Map.copyOf(values);
    }
  }
}
