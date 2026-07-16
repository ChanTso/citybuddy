package io.citybuddy.commerce.refund;

import io.citybuddy.commerce.payment.MockPaymentRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

public final class RefundService {
  private static final Pattern IDEMPOTENCY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final HexFormat HEX = HexFormat.of();

  private final RefundRepository refunds;
  private final MockPaymentRepository payments;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public RefundService(
      RefundRepository refunds,
      MockPaymentRepository payments,
      TransactionTemplate transactions,
      Clock clock) {
    this.refunds = refunds;
    this.payments = payments;
    this.transactions = transactions;
    this.clock = clock;
  }

  public RefundResult request(
      String userSubject, String orderId, String idempotencyKey, RefundRequest request) {
    requireText(userSubject, 128, "Validated refund owner is missing");
    requireUuid(orderId, "Refund order id is invalid");
    requireIdempotency(idempotencyKey);
    RefundRequest valid = requireRequest(request);
    String intentHash = hash(orderId + "\n" + valid.amountMinor() + "\n" + valid.currency());
    try {
      return execute(() -> requestOnce(userSubject, orderId, idempotencyKey, valid, intentHash));
    } catch (DuplicateKeyException exception) {
      return execute(
          () -> replayAfterConcurrentRequest(userSubject, orderId, idempotencyKey, intentHash));
    }
  }

  public RefundResult status(String userSubject, String refundId) {
    requireText(userSubject, 128, "Validated refund owner is missing");
    requireUuid(refundId, "Refund id is invalid");
    RefundRepository.RefundRecord refund =
        refunds
            .findOwnedById(userSubject, refundId)
            .orElseThrow(() -> notFound("Refund is missing or not owned"));
    return result(refund, false);
  }

  public RefundResult markProcessing(String refundId) {
    requireUuid(refundId, "Refund id is invalid");
    return execute(() -> markProcessingOnce(refundId));
  }

  public RefundResult succeed(String refundId) {
    requireUuid(refundId, "Refund id is invalid");
    return execute(() -> succeedOnce(refundId));
  }

  public RefundResult fail(String refundId, String failureCode) {
    requireUuid(refundId, "Refund id is invalid");
    if (failureCode == null || !FAILURE_CODE.matcher(failureCode).matches()) {
      throw validation("Refund failure code is invalid");
    }
    return execute(() -> failOnce(refundId, failureCode));
  }

  public RefundReconciliationResult reconcile(String refundId) {
    requireUuid(refundId, "Refund id is invalid");
    return execute(() -> reconcileOnce(refundId));
  }

  private RefundResult requestOnce(
      String userSubject,
      String orderId,
      String idempotencyKey,
      RefundRequest request,
      String intentHash) {
    MockPaymentRepository.OrderTruth identified =
        refunds
            .findOrder(orderId)
            .orElseThrow(() -> notFound("Refund order is missing or not owned"));
    if (!userSubject.equals(identified.userSubject())) {
      throw notFound("Refund order is missing or not owned");
    }

    MockPaymentRepository.AttemptRecord attempt =
        payments
            .findAttemptByOrderForUpdate(identified.orderKind(), orderId)
            .orElseThrow(() -> conflict("Order has no eligible successful payment"));
    MockPaymentRepository.OrderTruth order = requireLockedOrder(attempt);
    if (!userSubject.equals(order.userSubject())) {
      throw notFound("Refund order is missing or not owned");
    }

    RefundRepository.RefundRecord existing =
        refunds.findByRequestForUpdate(userSubject, orderId, idempotencyKey).orElse(null);
    if (existing != null) {
      requireIntent(existing.intentHash(), intentHash);
      return result(existing, true);
    }
    requireEligiblePayment(attempt, order);
    if (!attempt.currency().equals(request.currency())) {
      throw conflict("Refund request conflicts with authoritative payment currency");
    }

    long reserved = refunds.reservedAmount(attempt.attemptId());
    long remaining;
    try {
      remaining = Math.subtractExact(attempt.amountMinor(), reserved);
    } catch (ArithmeticException exception) {
      throw new IllegalStateException("Refund reservation total is corrupted", exception);
    }
    if (request.amountMinor() > remaining) {
      throw conflict(remaining == 0 ? "Payment is fully refunded" : "Refund exceeds paid amount");
    }

    RefundRepository.RefundRecord created =
        RefundRepository.RefundRecord.requested(
            UUID.randomUUID().toString(),
            userSubject,
            orderId,
            order.orderKind(),
            attempt.attemptId(),
            idempotencyKey,
            intentHash,
            attempt.amountMinor(),
            request.amountMinor(),
            request.currency());
    refunds.insertRefund(created);
    refunds.insertOutbox(created, "REFUND_REQUESTED", 1);
    return result(created, false);
  }

  private RefundResult replayAfterConcurrentRequest(
      String userSubject, String orderId, String idempotencyKey, String intentHash) {
    RefundRepository.RefundRecord existing =
        refunds
            .findByRequestForUpdate(userSubject, orderId, idempotencyKey)
            .orElseThrow(() -> conflict("Refund identity conflicts with a concurrent request"));
    requireIntent(existing.intentHash(), intentHash);
    return result(existing, true);
  }

  private RefundResult markProcessingOnce(String refundId) {
    LockedRefund locked = lockRefund(refundId);
    RefundRepository.RefundRecord refund = locked.refund();
    if ("PROCESSING".equals(refund.state())
        || "SUCCEEDED".equals(refund.state())
        || "FAILED".equals(refund.state())) {
      return result(refund, true);
    }
    if (!"REQUESTED".equals(refund.state()) || refund.stateVersion() != 1) {
      throw conflict("Refund cannot enter processing from its current state");
    }
    requireEligiblePayment(locked.attempt(), locked.order());
    refunds.markProcessing(refund, clock.instant());
    RefundRepository.RefundRecord processing = requireRefund(refundId);
    refunds.insertOutbox(processing, "REFUND_PROCESSING", 2);
    return result(processing, false);
  }

  private RefundResult succeedOnce(String refundId) {
    LockedRefund locked = lockRefund(refundId);
    RefundRepository.RefundRecord refund = locked.refund();
    if ("SUCCEEDED".equals(refund.state())) {
      requireSuccessfulRefundTruth(refund, locked.attempt(), locked.order());
      return result(refund, true);
    }
    if (!"PROCESSING".equals(refund.state()) || refund.stateVersion() != 2) {
      throw conflict("Refund cannot succeed from its current state");
    }
    requireEligiblePayment(locked.attempt(), locked.order());
    refunds.markSucceeded(refund, clock.instant());
    refunds.addRefundedAmount(locked.attempt(), refund.requestedAmountMinor());
    refunds.insertRefundMovement(refund, locked.order());
    RefundRepository.RefundRecord succeeded = requireRefund(refundId);
    refunds.insertOutbox(succeeded, "REFUND_SUCCEEDED", 3);
    return result(succeeded, false);
  }

  private RefundResult failOnce(String refundId, String failureCode) {
    LockedRefund locked = lockRefund(refundId);
    RefundRepository.RefundRecord refund = locked.refund();
    if ("FAILED".equals(refund.state())) {
      if (!failureCode.equals(refund.failureCode())) {
        throw conflict("Failed refund reason conflicts with its existing result");
      }
      return result(refund, true);
    }
    if ("SUCCEEDED".equals(refund.state())) {
      throw conflict("Succeeded refund cannot fail");
    }
    if (!"PROCESSING".equals(refund.state()) || refund.stateVersion() != 2) {
      throw conflict("Refund cannot fail from its current state");
    }
    refunds.markFailed(refund, failureCode, clock.instant());
    RefundRepository.RefundRecord failed = requireRefund(refundId);
    refunds.insertOutbox(failed, "REFUND_FAILED", 3);
    return result(failed, false);
  }

  private RefundReconciliationResult reconcileOnce(String refundId) {
    LockedRefund locked = lockRefundForReconciliation(refundId);
    MockPaymentRepository.AttemptRecord attempt = locked.attempt();
    MockPaymentRepository.OrderTruth order = locked.order();
    List<RefundRepository.RefundRecord> all = refunds.findByAttemptForUpdate(attempt.attemptId());
    List<String> contradictions = new ArrayList<>();

    if (!attempt.orderKind().equals(order.orderKind())
        || !attempt.orderId().equals(order.orderId())
        || !attempt.userSubject().equals(order.userSubject())
        || attempt.amountMinor() != order.amountMinor()
        || !attempt.currency().equals(order.currency())) {
      contradictions.add("PAYMENT_ORDER_MISMATCH");
    }
    if (!"SUCCEEDED".equals(attempt.state())
        || attempt.stateVersion() != 2
        || !"PAID".equals(order.status())
        || order.stateVersion() != 2) {
      contradictions.add("PAYMENT_NOT_DURABLY_SUCCEEDED");
    }
    if (!hasMatchingCallback(attempt)) {
      contradictions.add("PAYMENT_CALLBACK_MISSING");
    }
    RefundRepository.MovementRecord paymentMovement =
        refunds.findMovement("mock-payment:" + attempt.attemptId()).orElse(null);
    if (!matchesPaymentMovement(paymentMovement, attempt, order)) {
      contradictions.add("PAYMENT_LEDGER_MISMATCH");
    }
    if (refunds.hasMovement(order.orderId(), "SECKILL_UNPAID_CANCEL")) {
      contradictions.add("PAID_ORDER_HAS_TIMEOUT_RESTORATION");
    }

    long succeededTotal = 0;
    Set<String> expectedMovementKeys = new HashSet<>();
    for (RefundRepository.RefundRecord refund : all) {
      if (!matchesRefundIdentity(refund, attempt, order)) {
        contradictions.add("REFUND_IDENTITY_MISMATCH:" + refund.refundId());
      }
      String movementKey = RefundRepository.refundEventKey(refund.refundId());
      RefundRepository.MovementRecord movement = refunds.findMovement(movementKey).orElse(null);
      if ("SUCCEEDED".equals(refund.state())) {
        try {
          succeededTotal = Math.addExact(succeededTotal, refund.refundedAmountMinor());
        } catch (ArithmeticException exception) {
          contradictions.add("REFUND_TOTAL_OVERFLOW");
        }
        expectedMovementKeys.add(movementKey);
        if (!matchesRefundMovement(movement, refund, order)) {
          contradictions.add("REFUND_LEDGER_MISMATCH:" + refund.refundId());
        }
      } else if (movement != null) {
        contradictions.add("NON_SUCCESSFUL_REFUND_HAS_LEDGER:" + refund.refundId());
      }
    }
    List<RefundRepository.MovementRecord> movements = refunds.findRefundMovements(order.orderId());
    Set<String> actualMovementKeys = new HashSet<>();
    for (RefundRepository.MovementRecord movement : movements) {
      actualMovementKeys.add(movement.businessEventKey());
    }
    if (!actualMovementKeys.equals(expectedMovementKeys)) {
      contradictions.add("REFUND_LEDGER_SET_MISMATCH");
    }
    if (succeededTotal > attempt.amountMinor()) {
      contradictions.add("REFUND_TOTAL_EXCEEDS_PAYMENT");
    }

    if (!contradictions.isEmpty()) {
      return new RefundReconciliationResult(
          attempt.attemptId(),
          order.orderId(),
          RefundReconciliationResult.Outcome.CONTRADICTION,
          succeededTotal,
          contradictions);
    }
    if (attempt.refundedAmountMinor() != succeededTotal) {
      refunds.convergeRefundedAmount(attempt, succeededTotal);
      return new RefundReconciliationResult(
          attempt.attemptId(),
          order.orderId(),
          RefundReconciliationResult.Outcome.CONVERGED,
          succeededTotal,
          List.of());
    }
    return new RefundReconciliationResult(
        attempt.attemptId(),
        order.orderId(),
        RefundReconciliationResult.Outcome.CONSISTENT,
        succeededTotal,
        List.of());
  }

  private LockedRefund lockRefund(String refundId) {
    LockedRefund locked = lockRefundForReconciliation(refundId);
    if (!matchesRefundIdentity(locked.refund(), locked.attempt(), locked.order())) {
      throw new IllegalStateException("Refund conflicts with payment or order truth");
    }
    return locked;
  }

  private LockedRefund lockRefundForReconciliation(String refundId) {
    RefundRepository.RefundRecord identified =
        refunds.findById(refundId).orElseThrow(() -> notFound("Refund is missing"));
    MockPaymentRepository.AttemptRecord attempt =
        payments
            .findAttemptByIdForUpdate(identified.paymentAttemptId())
            .orElseThrow(() -> new IllegalStateException("Refund payment truth is missing"));
    MockPaymentRepository.OrderTruth order = requireLockedOrder(attempt);
    RefundRepository.RefundRecord refund = requireRefund(refundId);
    return new LockedRefund(refund, attempt, order);
  }

  private MockPaymentRepository.OrderTruth requireLockedOrder(
      MockPaymentRepository.AttemptRecord attempt) {
    return payments
        .findOrderForUpdate(attempt.orderId())
        .orElseThrow(() -> new IllegalStateException("Refund order truth is missing"));
  }

  private RefundRepository.RefundRecord requireRefund(String refundId) {
    return refunds
        .findByIdForUpdate(refundId)
        .orElseThrow(() -> new IllegalStateException("Refund truth disappeared"));
  }

  private void requireSuccessfulRefundTruth(
      RefundRepository.RefundRecord refund,
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.OrderTruth order) {
    requireEligiblePayment(attempt, order);
    RefundRepository.MovementRecord movement =
        refunds.findMovement(RefundRepository.refundEventKey(refund.refundId())).orElse(null);
    if (!matchesRefundMovement(movement, refund, order)
        || attempt.refundedAmountMinor() < refund.refundedAmountMinor()) {
      throw new IllegalStateException("Succeeded refund truth is incomplete");
    }
  }

  private void requireEligiblePayment(
      MockPaymentRepository.AttemptRecord attempt, MockPaymentRepository.OrderTruth order) {
    if (!attempt.orderKind().equals(order.orderKind())
        || !attempt.orderId().equals(order.orderId())
        || !attempt.userSubject().equals(order.userSubject())
        || attempt.amountMinor() != order.amountMinor()
        || !attempt.currency().equals(order.currency())) {
      throw new IllegalStateException("Payment conflicts with refund order truth");
    }
    if (!"SUCCEEDED".equals(attempt.state())
        || attempt.stateVersion() != 2
        || !"PAID".equals(order.status())
        || order.stateVersion() != 2
        || !hasMatchingCallback(attempt)
        || !matchesPaymentMovement(
            refunds.findMovement("mock-payment:" + attempt.attemptId()).orElse(null),
            attempt,
            order)) {
      throw conflict("Order has no eligible successful payment");
    }
  }

  private boolean hasMatchingCallback(MockPaymentRepository.AttemptRecord attempt) {
    MockPaymentRepository.CallbackRecord callback =
        payments.findCallbackByAttempt(attempt.attemptId()).orElse(null);
    return callback != null
        && callback.attemptId().equals(attempt.attemptId())
        && callback.callbackCorrelationId().equals(attempt.callbackCorrelationId());
  }

  private static boolean matchesRefundIdentity(
      RefundRepository.RefundRecord refund,
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.OrderTruth order) {
    return refund.paymentAttemptId().equals(attempt.attemptId())
        && refund.orderId().equals(order.orderId())
        && refund.orderKind().equals(order.orderKind())
        && refund.userSubject().equals(order.userSubject())
        && refund.eligibleAmountMinor() == attempt.amountMinor()
        && refund.currency().equals(attempt.currency());
  }

  private static boolean matchesPaymentMovement(
      RefundRepository.MovementRecord movement,
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.OrderTruth order) {
    return movement != null
        && movement.movementType().equals(order.orderKind() + "_PAYMENT")
        && movement.orderId().equals(order.orderId())
        && movement.productId().equals(order.productId())
        && java.util.Objects.equals(movement.reservationId(), order.reservationId())
        && java.util.Objects.equals(movement.activityId(), order.activityId())
        && movement.inventoryDelta() == 0
        && movement.activityQuotaDelta() == 0
        && movement.amountMinor() == attempt.amountMinor()
        && movement.currency().equals(attempt.currency());
  }

  private static boolean matchesRefundMovement(
      RefundRepository.MovementRecord movement,
      RefundRepository.RefundRecord refund,
      MockPaymentRepository.OrderTruth order) {
    return movement != null
        && movement.movementType().equals(refund.orderKind() + "_REFUND")
        && movement.orderId().equals(refund.orderId())
        && movement.productId().equals(order.productId())
        && java.util.Objects.equals(movement.reservationId(), order.reservationId())
        && java.util.Objects.equals(movement.activityId(), order.activityId())
        && movement.inventoryDelta() == 0
        && movement.activityQuotaDelta() == 0
        && movement.amountMinor() == refund.refundedAmountMinor()
        && movement.currency().equals(refund.currency());
  }

  private <T> T execute(java.util.function.Supplier<T> work) {
    T result = transactions.execute(status -> work.get());
    if (result == null) {
      throw new IllegalStateException("Refund transaction returned no result");
    }
    return result;
  }

  private static RefundRequest requireRequest(RefundRequest request) {
    if (request == null
        || request.amountMinor() == null
        || request.amountMinor() < 1
        || request.currency() == null
        || !CURRENCY.matcher(request.currency()).matches()
        || request.userSubject() != null) {
      throw validation("Refund request is invalid");
    }
    return request;
  }

  private static void requireIdempotency(String value) {
    if (value == null || !IDEMPOTENCY.matcher(value).matches()) {
      throw validation("Refund idempotency key is invalid");
    }
  }

  private static void requireUuid(String value, String message) {
    try {
      if (value == null || !UUID.fromString(value).toString().equals(value)) {
        throw validation(message);
      }
    } catch (IllegalArgumentException exception) {
      throw validation(message);
    }
  }

  private static void requireText(String value, int maximumLength, String message) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw validation(message);
    }
  }

  private static void requireIntent(String existing, String supplied) {
    if (!existing.equals(supplied)) {
      throw conflict("Refund idempotency intent conflicts");
    }
  }

  private static String hash(String value) {
    try {
      return HEX.formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Refund intent hash algorithm is unavailable", exception);
    }
  }

  private static RefundResult result(RefundRepository.RefundRecord refund, boolean replayed) {
    return new RefundResult(
        refund.refundId(),
        refund.orderId(),
        refund.orderKind(),
        refund.paymentAttemptId(),
        refund.eligibleAmountMinor(),
        refund.requestedAmountMinor(),
        refund.refundedAmountMinor(),
        refund.currency(),
        refund.state(),
        refund.stateVersion(),
        refund.failureCode(),
        replayed);
  }

  private static RefundException validation(String message) {
    return new RefundException(400, "VALIDATION", message);
  }

  private static RefundException notFound(String message) {
    return new RefundException(404, "NOT_FOUND", message);
  }

  private static RefundException conflict(String message) {
    return new RefundException(409, "CONFLICT", message);
  }

  private record LockedRefund(
      RefundRepository.RefundRecord refund,
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.OrderTruth order) {}
}
