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

  public CommittedPaymentTruth resolveLocked(MockPaymentRepository.AttemptRecord target) {
    return resolve(target, LOCK);
  }

  public CommittedPaymentTruth resolveSnapshot(MockPaymentRepository.AttemptRecord target) {
    return resolve(target, "");
  }

  /**
   * Resolves a callback replay without letting a caller maintain a narrower committed-face
   * inventory. An empty result means every enumerated face still has the legitimate pre-payment
   * shape; any durable indication of a completed payment requires the complete closure to resolve.
   */
  public Optional<CommittedPaymentTruth> resolveReplayLocked(
      MockPaymentRepository.AttemptRecord target,
      String callbackIdempotencyKey,
      MockPaymentCallbackRequest request) {
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
    return Optional.of(resolve(target, LOCK));
  }

  private CommittedPaymentTruth resolve(
      MockPaymentRepository.AttemptRecord target, String lockClause) {
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
        requireLedgerClosure(ledgerRows, attempt, order, lockClause);

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

  private MockPaymentRepository.PaymentLedgerRecord requireLedgerClosure(
      List<MockPaymentRepository.PaymentLedgerRecord> rows,
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.OrderTruth order,
      String lockClause) {
    MockPaymentRepository.PaymentLedgerRecord payment = null;
    for (MockPaymentRepository.PaymentLedgerRecord row : rows) {
      switch (row.movementType()) {
        case "STANDARD_PAYMENT", "SECKILL_PAYMENT" -> {
          if (payment != null || !matchesPaymentMovement(row, attempt, order)) {
            throw inconsistent("Payment ledger closure is inconsistent");
          }
          payment = row;
        }
        case "STANDARD_REFUND", "SECKILL_REFUND" ->
            requireRefundMovement(row, attempt, order, lockClause);
        case "SECKILL_ORDER_CREATE" -> requireSeckillCreationMovement(row, order);
        case "SECKILL_UNPAID_CANCEL" ->
            throw inconsistent("A paid order cannot retain a cancellation movement");
        default -> throw inconsistent("Payment ledger contains an unknown movement class");
      }
    }
    if (payment == null) {
      throw inconsistent("Payment movement is missing");
    }
    return payment;
  }

  private void requireRefundMovement(
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

  public record CommittedPaymentTruth(
      MockPaymentRepository.OrderTruth order,
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.CallbackRecord callback,
      MockPaymentRepository.PaymentLedgerRecord paymentMovement,
      Optional<MockPaymentRepository.PaymentAuditRecord> evaluationAudit) {
    public CommittedPaymentTruth {
      evaluationAudit = Optional.ofNullable(evaluationAudit.orElse(null));
    }
  }
}
