package io.citybuddy.commerce.payment;

import io.citybuddy.commerce.evaluation.EvaluationAuditEntityType;
import io.citybuddy.commerce.evaluation.EvaluationAuditReferenceIdentity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves one committed payment from the complete metadata-defined durable face closure.
 *
 * <p>Candidate rows are enumerated by identity and relation keys before any content predicate is
 * applied. Callers receive one immutable result and must not reconstruct a narrower payment truth.
 */
public final class CommittedPaymentTruthResolver {
  private static final HexFormat HEX = HexFormat.of();
  private static final String LOCK = " FOR UPDATE";

  private final MockPaymentRepository repository;

  public CommittedPaymentTruthResolver(MockPaymentRepository repository) {
    this.repository = repository;
  }

  public String callbackIntentHash(String idempotencyKey, MockPaymentCallbackRequest request) {
    String base =
        String.join(
            "\n",
            request.callbackEventId(),
            request.callbackCorrelationId(),
            request.orderId(),
            Long.toString(request.amountMinor()),
            request.currency(),
            request.outcome(),
            nullable(request.sandboxId()),
            nullable(request.supportSessionId()),
            nullable(request.traceId()),
            nullable(request.operationId()));
    return hash(request.sandboxId() == null ? base : base + "\n" + idempotencyKey);
  }

  public CommittedPaymentTruth resolveLocked(
      CommittedPaymentCaller caller, MockPaymentRepository.AttemptRecord target) {
    requireCaller(
        caller,
        CommittedPaymentCaller.PRODUCTION_CALLBACK_REPLAY,
        CommittedPaymentCaller.EVALUATION_CALLBACK_REPLAY,
        CommittedPaymentCaller.REFUND_LIFECYCLE,
        CommittedPaymentCaller.REFUND_RECONCILIATION);
    return resolve(caller, target, LOCK);
  }

  public CommittedPaymentTruth resolveSnapshot(
      CommittedPaymentCaller caller, MockPaymentRepository.AttemptRecord target) {
    requireCaller(
        caller, CommittedPaymentCaller.EVALUATION_STATE, CommittedPaymentCaller.EVALUATION_AUDIT);
    return resolve(caller, target, "");
  }

  /**
   * Applies the public owner boundary before durable-integrity classification.
   *
   * <p>The two declared visibility locators are always observed with the same bounded query shape.
   * An empty result means neither locator proves the target belongs to the principal and callers
   * must use their existing concealment response. Once either locator proves visibility, every
   * incomplete or contradictory committed face is an integrity failure. Data-access failures are
   * deliberately not translated here, so an indeterminate observation cannot become concealment or
   * conflict.
   */
  public Optional<CommittedPaymentTruth> resolveByOrderLocked(
      CommittedPaymentCaller caller, String orderId, String userSubject) {
    requireCaller(
        caller,
        CommittedPaymentCaller.DIRECT_REFUND_ELIGIBILITY,
        CommittedPaymentCaller.ACTION_PREPARE_CONFIRM_AND_RECEIPT_REPLAY);
    List<MockPaymentRepository.AttemptRecord> visibleAttempts =
        repository.enumerateOwnedAttemptByOrderVisibility(orderId, userSubject, LOCK);
    List<MockPaymentRepository.OrderTruth> visibleOrders =
        repository.enumerateOwnedOrderVisibility(orderId, userSubject, LOCK);
    if (visibleAttempts.isEmpty() && visibleOrders.isEmpty()) {
      return Optional.empty();
    }
    List<MockPaymentRepository.AttemptRecord> attempts =
        repository.enumerateAttemptByOrderClosure(orderId, LOCK);
    requireCardinality(attempts, "Payment attempt closure is inconsistent");
    return Optional.of(resolve(caller, attempts.getFirst(), LOCK));
  }

  /**
   * Resolves an existing payment-start command without allowing the caller to infer success from
   * the attempt row alone. A pending result proves the complete legal pre-payment shape; any
   * indication of a committed payment must satisfy the complete committed closure.
   */
  public PaymentStartReplayResolution resolveStartReplayLocked(
      CommittedPaymentCaller caller, MockPaymentRepository.AttemptRecord target) {
    requireCaller(caller, CommittedPaymentCaller.PAYMENT_START_REPLAY);
    List<MockPaymentRepository.AttemptRecord> attempts =
        repository.enumerateAttemptClosure(target, LOCK);
    requireSingleEqual(attempts, target, "Payment start attempt closure is inconsistent");
    MockPaymentRepository.AttemptRecord attempt = attempts.getFirst();

    List<MockPaymentRepository.OrderTruth> orders =
        repository.enumerateOrderClosure(attempt.orderId(), LOCK);
    requireCardinality(orders, "Payment start order closure is inconsistent");
    MockPaymentRepository.OrderTruth order = orders.getFirst();

    List<MockPaymentRepository.CallbackRecord> callbacks =
        repository.discoverCallbackClosure(attempt, "");
    List<MockPaymentRepository.PaymentLedgerRecord> ledger =
        repository.enumerateLedgerClosure(attempt, order, "");

    boolean committed =
        isCommittedAttempt(attempt)
            || isCommittedOrder(order)
            || !callbacks.isEmpty()
            || ledger.stream()
                .anyMatch(movement -> !"SECKILL_ORDER_CREATE".equals(movement.movementType()));
    if (committed) {
      return resolve(caller, attempt, LOCK);
    }

    requirePendingPaymentRows(attempt, order);
    if (!callbacks.isEmpty()) {
      throw inconsistent("Pending payment has callback truth");
    }
    requirePendingLedgerClosure(ledger, order);
    return new PendingPaymentTruth(order, attempt);
  }

  /**
   * Resolves a callback replay without letting a caller maintain a narrower committed-face
   * inventory. An empty result means every enumerated face still has the legitimate pre-payment
   * shape; any durable indication of a completed payment requires the complete closure to resolve.
   */
  public Optional<CommittedPaymentTruth> resolveReplayLocked(
      CommittedPaymentCaller caller,
      MockPaymentRepository.AttemptRecord target,
      String callbackIdempotencyKey,
      MockPaymentCallbackRequest request) {
    requireCaller(
        caller,
        CommittedPaymentCaller.PRODUCTION_CALLBACK_REPLAY,
        CommittedPaymentCaller.EVALUATION_CALLBACK_REPLAY);
    List<MockPaymentRepository.AttemptRecord> attempts =
        target == null
            ? repository.enumerateAttemptReplayClosure(
                request.callbackCorrelationId(), request.orderId(), LOCK)
            : repository.enumerateAttemptClosure(target, LOCK);
    List<MockPaymentRepository.OrderTruth> orders =
        repository.enumerateOrderClosure(request.orderId(), LOCK);
    List<MockPaymentRepository.CallbackRecord> callbacks =
        repository.enumerateCallbackReplayClosure(target, callbackIdempotencyKey, request, "");
    List<MockPaymentRepository.PaymentLedgerRecord> ledger =
        repository.enumerateLedgerReplayClosure(target, request.orderId(), "");
    List<MockPaymentRepository.PaymentAuditRecord> audit =
        repository.enumerateAuditReplayClosure(request, "");

    if (target == null
        && request.sandboxId() != null
        && attempts.stream().noneMatch(attempt -> request.sandboxId().equals(attempt.sandboxId()))
        && orders.stream().noneMatch(order -> request.sandboxId().equals(order.sandboxId()))
        && callbacks.stream()
            .noneMatch(callback -> request.sandboxId().equals(callback.sandboxId()))
        && ledger.stream().noneMatch(movement -> request.sandboxId().equals(movement.sandboxId()))
        && audit.stream().noneMatch(row -> request.sandboxId().equals(row.sandboxId()))) {
      // A signed request must not turn consistently foreign durable truth into a sandbox oracle.
      return Optional.empty();
    }
    if (target == null && !attempts.isEmpty()) {
      throw inconsistent("Callback request conflicts with an existing payment attempt");
    }
    if (target != null) {
      requireSingleEqual(attempts, target, "Payment attempt closure is inconsistent");
      requireCardinality(orders, "Payment order closure is inconsistent");
    }
    boolean committed =
        attempts.stream().anyMatch(CommittedPaymentTruthResolver::isCommittedAttempt)
            || orders.stream().anyMatch(CommittedPaymentTruthResolver::isCommittedOrder)
            || !callbacks.isEmpty()
            || ledger.stream()
                .anyMatch(movement -> !"SECKILL_ORDER_CREATE".equals(movement.movementType()))
            || !audit.isEmpty();
    if (!committed) {
      return Optional.empty();
    }
    if (target == null) {
      throw inconsistent("Committed payment has no canonical attempt");
    }
    CommittedPaymentTruth canonical = resolve(caller, target, LOCK);
    requireSingleEqual(orders, canonical.order(), "Callback request order closure is inconsistent");
    requireSingleEqual(
        callbacks, canonical.callback(), "Callback replay key closure is inconsistent");
    return Optional.of(canonical);
  }

  private CommittedPaymentTruth resolve(
      CommittedPaymentCaller caller,
      MockPaymentRepository.AttemptRecord target,
      String lockClause) {
    List<MockPaymentRepository.AttemptRecord> attempts =
        repository.enumerateAttemptClosure(target, lockClause);
    requireSingleEqual(attempts, target, "Payment attempt closure is inconsistent");
    MockPaymentRepository.AttemptRecord attempt = attempts.getFirst();

    List<MockPaymentRepository.OrderTruth> orders =
        repository.enumerateOrderClosure(attempt.orderId(), lockClause);
    requireCardinality(orders, "Payment order closure is inconsistent");
    MockPaymentRepository.OrderTruth order = orders.getFirst();

    List<MockPaymentRepository.CallbackRecord> discovered =
        repository.discoverCallbackClosure(attempt, "");
    requireCardinality(discovered, "Payment callback closure is inconsistent");
    MockPaymentRepository.CallbackRecord callback = discovered.getFirst();
    List<MockPaymentRepository.CallbackRecord> callbacks =
        repository.enumerateCallbackClosure(attempt, callback, "");
    requireSingleEqual(callbacks, callback, "Payment callback closure is inconsistent");

    requireImmutablePaymentRows(attempt, order, callback);

    List<MockPaymentRepository.PaymentLedgerRecord> ledgerRows =
        repository.enumerateLedgerClosure(attempt, order, "");
    MockPaymentRepository.PaymentLedgerRecord paymentMovement =
        requireLedgerClosure(
            ledgerRows, attempt, order, lockClause, caller.refundAccumulatorPolicy());

    List<MockPaymentRepository.PaymentAuditRecord> auditRows =
        repository.enumerateAuditClosure(callback, attempt.stateVersion(), "");
    Optional<MockPaymentRepository.PaymentAuditRecord> audit =
        requireAuditClosure(auditRows, attempt, callback);

    return new CommittedPaymentTruth(order, attempt, callback, paymentMovement, audit);
  }

  private static void requireImmutablePaymentRows(
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.OrderTruth order,
      MockPaymentRepository.CallbackRecord callback) {
    if (!"SUCCEEDED".equals(attempt.state())
        || attempt.stateVersion() != 2
        || attempt.refundedAmountMinor() < 0
        || attempt.refundedAmountMinor() > attempt.amountMinor()
        || !attempt.orderKind().equals(order.orderKind())
        || !attempt.orderId().equals(order.orderId())
        || !Objects.equals(attempt.sandboxId(), order.sandboxId())
        || !attempt.userSubject().equals(order.userSubject())
        || attempt.amountMinor() != order.amountMinor()
        || !attempt.currency().equals(order.currency())
        || !attempt
            .intentHash()
            .equals(
                EvaluationPaymentCommittedFaces.attemptIntentHash(
                    attempt.orderId(),
                    attempt.amountMinor(),
                    attempt.currency(),
                    attempt.sandboxId()))
        || !"PAID".equals(order.status())
        || order.stateVersion() != 2
        || !callback.attemptId().equals(attempt.attemptId())
        || !callback.callbackCorrelationId().equals(attempt.callbackCorrelationId())
        || !Objects.equals(callback.sandboxId(), attempt.sandboxId())
        || !"SUCCEEDED".equals(callback.requestedOutcome())
        || !"APPLIED".equals(callback.resultState())
        || attempt.succeededAt() == null
        || !attempt.succeededAt().equals(callback.createdAt())
        || !callback.intentHash().equals(callbackIntentHash(attempt, callback))) {
      throw inconsistent("Committed payment content is inconsistent");
    }
  }

  private static void requirePendingPaymentRows(
      MockPaymentRepository.AttemptRecord attempt, MockPaymentRepository.OrderTruth order) {
    if (!"PENDING".equals(attempt.state())
        || attempt.stateVersion() != 1
        || attempt.refundedAmountMinor() != 0
        || attempt.succeededAt() != null
        || !attempt.orderKind().equals(order.orderKind())
        || !attempt.orderId().equals(order.orderId())
        || !Objects.equals(attempt.sandboxId(), order.sandboxId())
        || !attempt.userSubject().equals(order.userSubject())
        || attempt.amountMinor() != order.amountMinor()
        || !attempt.currency().equals(order.currency())
        || !attempt
            .intentHash()
            .equals(
                EvaluationPaymentCommittedFaces.attemptIntentHash(
                    attempt.orderId(),
                    attempt.amountMinor(),
                    attempt.currency(),
                    attempt.sandboxId()))
        || !"UNPAID".equals(order.status())
        || order.stateVersion() != 1) {
      throw inconsistent("Pending payment content is inconsistent");
    }
  }

  private static void requirePendingLedgerClosure(
      List<MockPaymentRepository.PaymentLedgerRecord> rows,
      MockPaymentRepository.OrderTruth order) {
    if ("STANDARD".equals(order.orderKind())) {
      if (!rows.isEmpty()) {
        throw inconsistent("Pending standard payment has lifecycle movements");
      }
      return;
    }
    if (!"SECKILL".equals(order.orderKind()) || rows.size() != 1) {
      throw inconsistent("Pending seckill payment ledger closure is inconsistent");
    }
    MockPaymentRepository.PaymentLedgerRecord creation = rows.getFirst();
    if (!"SECKILL_ORDER_CREATE".equals(creation.movementType())
        || !creation.businessEventKey().startsWith("seckill-order-create:")) {
      throw inconsistent("Pending seckill payment ledger closure is inconsistent");
    }
    requireSeckillCreationMovement(creation, order);
  }

  private MockPaymentRepository.PaymentLedgerRecord requireLedgerClosure(
      List<MockPaymentRepository.PaymentLedgerRecord> rows,
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.OrderTruth order,
      String lockClause,
      RefundAccumulatorPolicy refundAccumulatorPolicy) {
    MockPaymentRepository.PaymentLedgerRecord payment = null;
    long refundedAmount = 0;
    for (MockPaymentRepository.PaymentLedgerRecord row : rows) {
      switch (row.movementType()) {
        case "STANDARD_PAYMENT", "SECKILL_PAYMENT" -> {
          if (payment != null || !matchesPaymentMovement(row, attempt, order)) {
            throw inconsistent("Payment ledger closure is inconsistent");
          }
          payment = row;
        }
        case "STANDARD_REFUND", "SECKILL_REFUND" -> {
          long movementAmount = requireRefundMovement(row, attempt, order, lockClause);
          try {
            refundedAmount = Math.addExact(refundedAmount, movementAmount);
          } catch (ArithmeticException exception) {
            throw inconsistent("Refund movement total is inconsistent");
          }
        }
        case "SECKILL_ORDER_CREATE" -> requireSeckillCreationMovement(row, order);
        case "SECKILL_UNPAID_CANCEL" ->
            throw inconsistent("A paid order cannot retain a cancellation movement");
        default -> throw inconsistent("Payment ledger contains an unknown movement class");
      }
    }
    if (payment == null) {
      throw inconsistent("Payment movement is missing");
    }
    if (refundAccumulatorPolicy == RefundAccumulatorPolicy.EXACT_LEDGER_SUM
        && refundedAmount != attempt.refundedAmountMinor()) {
      throw inconsistent("Payment refund accumulator is inconsistent");
    }
    return payment;
  }

  private long requireRefundMovement(
      MockPaymentRepository.PaymentLedgerRecord movement,
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.OrderTruth order,
      String lockClause) {
    if (!movement.businessEventKey().startsWith("mock-refund:")) {
      throw inconsistent("Refund movement business identity is inconsistent");
    }
    String refundId = movement.businessEventKey().substring("mock-refund:".length());
    MockPaymentRepository.RefundMovementAnchor refund =
        repository
            .refundMovementAnchor(refundId, lockClause)
            .orElseThrow(() -> inconsistent("Refund movement is orphaned"));
    if (!"SUCCEEDED".equals(refund.state())
        || !refund.paymentAttemptId().equals(attempt.attemptId())
        || !refund.orderId().equals(order.orderId())
        || !refund.orderKind().equals(order.orderKind())
        || !refund.userSubject().equals(order.userSubject())
        || refund.requestedAmountMinor() != refund.refundedAmountMinor()
        || !refund.currency().equals(order.currency())
        || !movement.movementType().equals(order.orderKind() + "_REFUND")
        || !matchesCommonMovement(movement, order)
        || !Objects.equals(movement.paymentAmountMinor(), refund.refundedAmountMinor())
        || !Objects.equals(movement.paymentCurrency(), refund.currency())) {
      throw inconsistent("Refund movement contradicts its durable lifecycle");
    }
    return refund.refundedAmountMinor();
  }

  private static void requireSeckillCreationMovement(
      MockPaymentRepository.PaymentLedgerRecord movement, MockPaymentRepository.OrderTruth order) {
    if (!"SECKILL".equals(order.orderKind())
        || !matchesMovementIdentity(movement, order)
        || movement.inventoryDelta() >= 0
        || movement.activityQuotaDelta() != movement.inventoryDelta()
        || movement.paymentAmountMinor() != null
        || movement.paymentCurrency() != null) {
      throw inconsistent("Seckill creation movement contradicts paid order truth");
    }
  }

  private Optional<MockPaymentRepository.PaymentAuditRecord> requireAuditClosure(
      List<MockPaymentRepository.PaymentAuditRecord> rows,
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.CallbackRecord callback) {
    if (attempt.sandboxId() == null) {
      if (!rows.isEmpty()) {
        throw inconsistent("Production payment has evaluation audit truth");
      }
      return Optional.empty();
    }
    requireCardinality(rows, "Evaluation payment audit closure is inconsistent");
    MockPaymentRepository.PaymentAuditRecord audit = rows.getFirst();
    String expectedReference =
        EvaluationAuditReferenceIdentity.paymentCallback(
            callback.sandboxId(),
            callback.supportSessionId(),
            callback.traceId(),
            callback.operationId(),
            callback.callbackEventId(),
            attempt.stateVersion());
    if (!audit.auditReferenceId().equals(expectedReference)
        || !audit.sandboxId().equals(callback.sandboxId())
        || !audit.supportSessionId().equals(callback.supportSessionId())
        || !audit.traceId().equals(callback.traceId())
        || !audit.operationId().equals(callback.operationId())
        || !audit.entityType().equals(EvaluationAuditEntityType.PAYMENT_CALLBACK.name())
        || !audit.entityId().equals(callback.callbackEventId())
        || audit.entityVersion() != attempt.stateVersion()
        || !"OBSERVED".equals(audit.outcome())
        || !audit.createdAt().equals(callback.createdAt())
        || !"BUSINESS_EVENT".equals(audit.createdAtAnchor())
        || !repository.auditSequenceOrderConsistent(audit.sandboxId())) {
      throw inconsistent("Evaluation payment audit content is inconsistent");
    }
    return Optional.of(audit);
  }

  private static boolean matchesPaymentMovement(
      MockPaymentRepository.PaymentLedgerRecord movement,
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.OrderTruth order) {
    return movement.businessEventKey().equals("mock-payment:" + attempt.attemptId())
        && movement.movementType().equals(order.orderKind() + "_PAYMENT")
        && matchesCommonMovement(movement, order)
        && Objects.equals(movement.paymentAmountMinor(), attempt.amountMinor())
        && Objects.equals(movement.paymentCurrency(), attempt.currency());
  }

  private static boolean matchesCommonMovement(
      MockPaymentRepository.PaymentLedgerRecord movement, MockPaymentRepository.OrderTruth order) {
    return matchesMovementIdentity(movement, order)
        && movement.inventoryDelta() == 0
        && movement.activityQuotaDelta() == 0;
  }

  private static boolean matchesMovementIdentity(
      MockPaymentRepository.PaymentLedgerRecord movement, MockPaymentRepository.OrderTruth order) {
    return movement.orderId().equals(order.orderId())
        && Objects.equals(movement.sandboxId(), order.sandboxId())
        && movement.productId().equals(order.productId())
        && Objects.equals(movement.reservationId(), order.reservationId())
        && Objects.equals(movement.activityId(), order.activityId());
  }

  private static boolean isCommittedAttempt(MockPaymentRepository.AttemptRecord attempt) {
    return !"PENDING".equals(attempt.state()) || attempt.stateVersion() != 1;
  }

  private static boolean isCommittedOrder(MockPaymentRepository.OrderTruth order) {
    return !"UNPAID".equals(order.status()) || order.stateVersion() != 1;
  }

  private static String callbackIntentHash(
      MockPaymentRepository.AttemptRecord attempt, MockPaymentRepository.CallbackRecord callback) {
    String base =
        String.join(
            "\n",
            callback.callbackEventId(),
            callback.callbackCorrelationId(),
            attempt.orderId(),
            Long.toString(attempt.amountMinor()),
            attempt.currency(),
            "SUCCEEDED",
            nullable(callback.sandboxId()),
            nullable(callback.supportSessionId()),
            nullable(callback.traceId()),
            nullable(callback.operationId()));
    return hash(
        callback.sandboxId() == null ? base : base + "\n" + callback.callbackIdempotencyKey());
  }

  private static <T> void requireCardinality(List<T> rows, String message) {
    if (rows.size() != 1) {
      throw inconsistent(message);
    }
  }

  private static <T> void requireSingleEqual(List<T> rows, T expected, String message) {
    requireCardinality(rows, message);
    if (!rows.getFirst().equals(expected)) {
      throw inconsistent(message);
    }
  }

  private static void requireCaller(
      CommittedPaymentCaller actual, CommittedPaymentCaller... allowed) {
    for (CommittedPaymentCaller candidate : allowed) {
      if (actual == candidate) {
        return;
      }
    }
    throw new IllegalArgumentException("Committed payment caller is not valid for this resolver");
  }

  private static CommittedPaymentIntegrityException inconsistent(String message) {
    return new CommittedPaymentIntegrityException(message);
  }

  private static String nullable(String value) {
    return value == null ? "" : value;
  }

  private static String hash(String value) {
    try {
      return HEX.formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Payment intent hash algorithm is unavailable", exception);
    }
  }

  public sealed interface PaymentStartReplayResolution
      permits PendingPaymentTruth, CommittedPaymentTruth {}

  public enum CommittedPaymentCaller {
    PAYMENT_START_REPLAY(
        "POST /api/orders/{orderId}/mock-payment replay",
        TrustBoundary.OWNER_CONCEALING_PUBLIC,
        List.of("orderId", "userSubject", "requestIdempotencyKey", "evaluationSandbox"),
        List.of("attempt.userSubject+requestIdempotencyKey", "order.orderId+userSubject+sandboxId"),
        "mock-payment unknown/other-owner",
        "resolveStartReplayLocked",
        "PaymentStartReplayResolution",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        true),
    PRODUCTION_CALLBACK_REPLAY(
        "production callback replay",
        TrustBoundary.AUTHENTICATED_CALLBACK,
        List.of("callbackIdempotencyKey", "callbackEventId", "callbackCorrelationId", "orderId"),
        List.of("verified callback signature"),
        "signed callback unknown correlation",
        "resolveReplayLocked",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        true),
    EVALUATION_CALLBACK_REPLAY(
        "evaluation callback replay",
        TrustBoundary.AUTHENTICATED_CALLBACK,
        List.of(
            "callbackIdempotencyKey",
            "callbackEventId",
            "callbackCorrelationId",
            "orderId",
            "sandboxId"),
        List.of("verified callback signature+sandbox context"),
        "signed callback foreign/unknown evaluation correlation",
        "resolveReplayLocked",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        true),
    DIRECT_REFUND_ELIGIBILITY(
        "direct refund eligibility and replay",
        TrustBoundary.OWNER_CONCEALING_PUBLIC,
        List.of("orderId", "userSubject", "refundIdempotencyKey"),
        List.of("attempt.orderId+userSubject", "order.orderId+userSubject"),
        "refund unknown/other-owner",
        "resolveByOrderLocked",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        false),
    ACTION_PREPARE_CONFIRM_AND_RECEIPT_REPLAY(
        "Action prepare, confirm, and receipt replay",
        TrustBoundary.OWNER_CONCEALING_OBO,
        List.of("orderId", "userSubject", "supportSessionId", "traceId", "turnId", "sandboxId"),
        List.of("OBO owner+session", "attempt/order owner", "sandbox binding"),
        "Action target unknown/other-owner",
        "resolveByOrderLocked",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        true),
    REFUND_LIFECYCLE(
        "refund lifecycle mutation",
        TrustBoundary.INTERNAL_DURABLE_IDENTITY,
        List.of("refundId", "paymentAttemptId"),
        List.of("locked refund.paymentAttemptId"),
        "internal refund identity missing",
        "resolveLocked",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        false),
    REFUND_RECONCILIATION(
        "refund reconciliation",
        TrustBoundary.INTERNAL_DURABLE_IDENTITY,
        List.of("refundId", "paymentAttemptId"),
        List.of("locked refund.paymentAttemptId"),
        "internal refund identity missing",
        "resolveLocked",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.RECONCILIATION_DERIVED,
        false),
    EVALUATION_STATE(
        "/api/eval/state",
        TrustBoundary.SANDBOX_WIDE_INTERNAL_EVALUATOR,
        List.of("sandboxId"),
        List.of("management credential+sandbox scope"),
        "evaluation sandbox unknown",
        "resolveSnapshot",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        false),
    EVALUATION_AUDIT(
        "/api/eval/audit",
        TrustBoundary.SANDBOX_WIDE_INTERNAL_EVALUATOR,
        List.of("sandboxId", "supportSessionId"),
        List.of("management credential+sandbox scope"),
        "evaluation sandbox/audit unknown",
        "resolveSnapshot",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        false);

    private final String surface;
    private final TrustBoundary trustBoundary;
    private final List<String> canonicalRequestLocators;
    private final List<String> ownershipVisibilityLocators;
    private final String concealedResponseFamily;
    private final String resolverMethod;
    private final String successInputType;
    private final RefundAccumulatorPolicy refundAccumulatorPolicy;
    private final boolean committedBeforeLiveness;

    CommittedPaymentCaller(
        String surface,
        TrustBoundary trustBoundary,
        List<String> canonicalRequestLocators,
        List<String> ownershipVisibilityLocators,
        String concealedResponseFamily,
        String resolverMethod,
        String successInputType,
        RefundAccumulatorPolicy refundAccumulatorPolicy,
        boolean committedBeforeLiveness) {
      this.surface = surface;
      this.trustBoundary = trustBoundary;
      this.canonicalRequestLocators = List.copyOf(canonicalRequestLocators);
      this.ownershipVisibilityLocators = List.copyOf(ownershipVisibilityLocators);
      this.concealedResponseFamily = concealedResponseFamily;
      this.resolverMethod = resolverMethod;
      this.successInputType = successInputType;
      this.refundAccumulatorPolicy = refundAccumulatorPolicy;
      this.committedBeforeLiveness = committedBeforeLiveness;
    }

    public String surface() {
      return surface;
    }

    public String resolverMethod() {
      return resolverMethod;
    }

    public TrustBoundary trustBoundary() {
      return trustBoundary;
    }

    public List<String> canonicalRequestLocators() {
      return canonicalRequestLocators;
    }

    public List<String> ownershipVisibilityLocators() {
      return ownershipVisibilityLocators;
    }

    public String concealedResponseFamily() {
      return concealedResponseFamily;
    }

    public String successInputType() {
      return successInputType;
    }

    public RefundAccumulatorPolicy refundAccumulatorPolicy() {
      return refundAccumulatorPolicy;
    }

    public boolean committedBeforeLiveness() {
      return committedBeforeLiveness;
    }
  }

  public enum RefundAccumulatorPolicy {
    EXACT_LEDGER_SUM,
    RECONCILIATION_DERIVED
  }

  public enum TrustBoundary {
    OWNER_CONCEALING_PUBLIC,
    OWNER_CONCEALING_OBO,
    AUTHENTICATED_CALLBACK,
    INTERNAL_DURABLE_IDENTITY,
    SANDBOX_WIDE_INTERNAL_EVALUATOR
  }

  public record PendingPaymentTruth(
      MockPaymentRepository.OrderTruth order, MockPaymentRepository.AttemptRecord attempt)
      implements PaymentStartReplayResolution {}

  public record CommittedPaymentTruth(
      MockPaymentRepository.OrderTruth order,
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.CallbackRecord callback,
      MockPaymentRepository.PaymentLedgerRecord paymentMovement,
      Optional<MockPaymentRepository.PaymentAuditRecord> evaluationAudit)
      implements PaymentStartReplayResolution {
    public CommittedPaymentTruth {
      evaluationAudit = Optional.ofNullable(evaluationAudit.orElse(null));
    }
  }
}
