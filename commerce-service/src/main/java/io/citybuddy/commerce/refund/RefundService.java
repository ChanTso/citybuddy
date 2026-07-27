package io.citybuddy.commerce.refund;

import io.citybuddy.commerce.payment.CommittedPaymentIntegrityException;
import io.citybuddy.commerce.payment.CommittedPaymentTruthResolver;
import io.citybuddy.commerce.payment.MockPaymentRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public final class RefundService {
  private static final Pattern IDEMPOTENCY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
  private static final HexFormat HEX = HexFormat.of();

  private final RefundRepository refunds;
  private final MockPaymentRepository payments;
  private final CommittedPaymentTruthResolver paymentTruth;
  private final RefundTransactions transactions;
  private final Clock clock;

  public RefundService(
      RefundRepository refunds,
      MockPaymentRepository payments,
      RefundTransactions transactions,
      Clock clock) {
    this.refunds = refunds;
    this.payments = payments;
    this.paymentTruth = new CommittedPaymentTruthResolver(payments);
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
      return transactions.mutate(
          RefundTransactions.Entry.DIRECT_INITIAL_MUTATION,
          () -> requestOnce(userSubject, orderId, idempotencyKey, valid, intentHash).refund());
    } catch (RuntimeException failure) {
      if (!isDirectCompetition(failure)) {
        throw failure;
      }
      return recoverRequestAfterCompetition(
          userSubject, orderId, idempotencyKey, valid, intentHash);
    }
  }

  /**
   * Resolves and locks the complete refund target in the caller's already-active transaction.
   *
   * <p>This method deliberately owns no retry or transaction boundary. MySQL 1205/1213 must leave
   * the enclosing Action transaction so that its rollback completes before Action recovery begins.
   */
  public ActionTarget prepareActionInCurrentTransaction(
      String userSubject, String orderId, RefundRequest request, String sandboxId) {
    requireCurrentTransaction();
    requireText(userSubject, 128, "Validated refund owner is missing");
    requireUuid(orderId, "Refund order id is invalid");
    RefundRequest valid = requireRequest(request);
    RefundTarget target = resolveRefundTarget(userSubject, orderId, valid);
    requireActionSandbox(target.payment(), sandboxId);
    requireCapacity(target.payment().attempt(), valid.amountMinor());
    return new ActionTarget(target.payment().order(), target.payment().attempt());
  }

  /**
   * Creates the refund and its Outbox row inside the caller's current Action transaction.
   *
   * <p>No contention is consumed here. The enclosing transaction must roll back before any
   * re-observation occurs.
   */
  public ActionMutation requestActionInCurrentTransaction(
      String userSubject,
      String orderId,
      String idempotencyKey,
      RefundRequest request,
      String sandboxId) {
    requireCurrentTransaction();
    requireText(userSubject, 128, "Validated refund owner is missing");
    requireUuid(orderId, "Refund order id is invalid");
    requireIdempotency(idempotencyKey);
    RefundRequest valid = requireRequest(request);
    String intentHash = hash(orderId + "\n" + valid.amountMinor() + "\n" + valid.currency());
    RefundTarget target = resolveRefundTarget(userSubject, orderId, valid);
    requireActionSandbox(target.payment(), sandboxId);
    RefundMutation mutation =
        requestOnceWithTarget(
            userSubject, orderId, idempotencyKey, valid, intentHash, target.payment());
    return new ActionMutation(mutation.refund(), mutation.outbox());
  }

  /**
   * Reconciles the complete Action refund truth while retaining the caller's Action transaction.
   */
  public ActionReplayTruth validateActionReplayInCurrentTransaction(
      String userSubject,
      String orderId,
      String idempotencyKey,
      RefundRequest request,
      String expectedRefundId,
      String sandboxId) {
    requireCurrentTransaction();
    requireText(userSubject, 128, "Validated refund owner is missing");
    requireUuid(orderId, "Refund order id is invalid");
    requireUuid(expectedRefundId, "Refund id is invalid");
    requireIdempotency(idempotencyKey);
    RefundRequest valid = requireRequest(request);
    String intentHash = hash(orderId + "\n" + valid.amountMinor() + "\n" + valid.currency());
    RefundTarget target = resolveRefundTarget(userSubject, orderId, valid);
    requireActionSandbox(target.payment(), sandboxId);
    RefundRepository.RefundRecord existing =
        refunds
            .findByRequestForUpdate(userSubject, orderId, idempotencyKey)
            .orElseThrow(() -> durableConflict("Action refund truth is missing"));
    requireIntent(existing.intentHash(), intentHash);
    if (!expectedRefundId.equals(existing.refundId())) {
      throw durableConflict("Action refund identity conflicts with its receipt");
    }
    requireRefundIdentity(existing, target.payment());
    requireStateSpecificTruth(existing, target.payment());
    return new ActionReplayTruth(
        result(existing, true),
        new ActionTarget(target.payment().order(), target.payment().attempt()));
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
    try {
      return transactions.mutate(
          RefundTransactions.Entry.MARK_PROCESSING_MUTATION, () -> markProcessingOnce(refundId));
    } catch (RuntimeException failure) {
      return recoverLifecycle(
          failure,
          refundId,
          RefundTransactions.Entry.MARK_PROCESSING_OBSERVATION,
          refund -> Set.of("PROCESSING", "SUCCEEDED", "FAILED").contains(refund.state()),
          null);
    }
  }

  public RefundResult succeed(String refundId) {
    requireUuid(refundId, "Refund id is invalid");
    try {
      return transactions.mutate(
          RefundTransactions.Entry.SUCCEED_MUTATION, () -> succeedOnce(refundId));
    } catch (RuntimeException failure) {
      return recoverLifecycle(
          failure,
          refundId,
          RefundTransactions.Entry.SUCCEED_OBSERVATION,
          refund -> "SUCCEEDED".equals(refund.state()),
          null);
    }
  }

  public RefundResult fail(String refundId, String failureCode) {
    requireUuid(refundId, "Refund id is invalid");
    if (failureCode == null || !FAILURE_CODE.matcher(failureCode).matches()) {
      throw validation("Refund failure code is invalid");
    }
    try {
      return transactions.mutate(
          RefundTransactions.Entry.FAIL_MUTATION, () -> failOnce(refundId, failureCode));
    } catch (RuntimeException failure) {
      return recoverLifecycle(
          failure,
          refundId,
          RefundTransactions.Entry.FAIL_OBSERVATION,
          refund -> "FAILED".equals(refund.state()) && failureCode.equals(refund.failureCode()),
          failureCode);
    }
  }

  public RefundReconciliationResult reconcile(String refundId) {
    requireUuid(refundId, "Refund id is invalid");
    try {
      return transactions.mutate(
          RefundTransactions.Entry.RECONCILE_MUTATION, () -> reconcileLocked(refundId, true));
    } catch (RuntimeException failure) {
      if (!RefundTransactions.isMySqlContention(failure)) {
        throw failure;
      }
      Optional<RefundReconciliationResult> observed = observeReconciliationWithinBound(refundId);
      if (observed != null && observed.isPresent()) {
        return observed.get();
      }
      throw new RefundIndeterminateException(
          "Refund reconciliation truth remains indeterminate", failure);
    }
  }

  private RefundResult recoverRequestAfterCompetition(
      String userSubject,
      String orderId,
      String idempotencyKey,
      RefundRequest request,
      String intentHash) {
    RequestObservation observation =
        observeRequestWithinBound(userSubject, orderId, idempotencyKey, request, intentHash);
    if (observation.state() == RequestObservationState.FOUND) {
      return observation.requireResult();
    }
    if (observation.state() != RequestObservationState.CONFIRMED_ABSENT) {
      throw refundConcurrencyIndeterminate();
    }
    try {
      return transactions.mutate(
          RefundTransactions.Entry.DIRECT_FINAL_MUTATION,
          () -> requestOnce(userSubject, orderId, idempotencyKey, request, intentHash).refund());
    } catch (RuntimeException failure) {
      if (!isDirectCompetition(failure)) {
        throw failure;
      }
      RequestObservation finalObservation =
          observeRequestOnce(userSubject, orderId, idempotencyKey, request, intentHash);
      if (finalObservation.state() == RequestObservationState.FOUND) {
        return finalObservation.requireResult();
      }
      throw refundConcurrencyIndeterminate();
    }
  }

  private RequestObservation observeRequestWithinBound(
      String userSubject,
      String orderId,
      String idempotencyKey,
      RefundRequest request,
      String intentHash) {
    for (int attempt = 1; attempt <= transactions.maximumObservationAttempts(); attempt++) {
      try {
        return transactions.observe(
            RefundTransactions.Entry.DIRECT_TRUTH_OBSERVATION,
            () -> observeRequestTruth(userSubject, orderId, idempotencyKey, request, intentHash));
      } catch (RuntimeException failure) {
        if (!RefundTransactions.isMySqlContention(failure)) {
          throw failure;
        }
        if (attempt < transactions.maximumObservationAttempts() && !transactions.pause(attempt)) {
          break;
        }
      }
    }
    return RequestObservation.indeterminate();
  }

  private RequestObservation observeRequestOnce(
      String userSubject,
      String orderId,
      String idempotencyKey,
      RefundRequest request,
      String intentHash) {
    try {
      return transactions.observe(
          RefundTransactions.Entry.DIRECT_FINAL_OBSERVATION,
          () -> observeRequestTruth(userSubject, orderId, idempotencyKey, request, intentHash));
    } catch (RuntimeException failure) {
      if (!RefundTransactions.isMySqlContention(failure)) {
        throw failure;
      }
      return RequestObservation.indeterminate();
    }
  }

  private RequestObservation observeRequestTruth(
      String userSubject,
      String orderId,
      String idempotencyKey,
      RefundRequest request,
      String intentHash) {
    RefundTarget target = resolveRefundTarget(userSubject, orderId, request);
    RefundRepository.RefundRecord existing =
        refunds.findByRequestForUpdate(userSubject, orderId, idempotencyKey).orElse(null);
    if (existing == null) {
      requireCapacity(target.payment().attempt(), request.amountMinor());
      return RequestObservation.absent();
    }
    requireIntent(existing.intentHash(), intentHash);
    requireRefundIdentity(existing, target.payment());
    requireStateSpecificTruth(existing, target.payment());
    return RequestObservation.found(result(existing, true));
  }

  private RefundResult recoverLifecycle(
      RuntimeException failure,
      String refundId,
      RefundTransactions.Entry observationEntry,
      Predicate<RefundRepository.RefundRecord> completed,
      String expectedFailureCode) {
    if (!RefundTransactions.isMySqlContention(failure)) {
      throw failure;
    }
    ValidatedLifecycleTruth observed =
        observeLifecycleWithinBound(refundId, observationEntry, completed, expectedFailureCode);
    if (observed != null && observed.completedResult().isPresent()) {
      return observed.completedResult().orElseThrow();
    }
    throw new RefundIndeterminateException("Refund lifecycle truth remains indeterminate", failure);
  }

  private ValidatedLifecycleTruth observeLifecycleWithinBound(
      String refundId,
      RefundTransactions.Entry observationEntry,
      Predicate<RefundRepository.RefundRecord> completed,
      String expectedFailureCode) {
    for (int attempt = 1; attempt <= transactions.maximumObservationAttempts(); attempt++) {
      try {
        return transactions.observe(
            observationEntry,
            () ->
                observeLifecycleTruth(refundId, observationEntry, completed, expectedFailureCode));
      } catch (RuntimeException failure) {
        if (!RefundTransactions.isMySqlContention(failure)) {
          throw failure;
        }
        if (attempt < transactions.maximumObservationAttempts() && !transactions.pause(attempt)) {
          break;
        }
      }
    }
    return null;
  }

  /**
   * Performs every locking read and validation before returning the immutable observation value.
   * Callers must not perform a second repository read after this transaction completes.
   */
  private ValidatedLifecycleTruth observeLifecycleTruth(
      String refundId,
      RefundTransactions.Entry observationEntry,
      Predicate<RefundRepository.RefundRecord> completed,
      String expectedFailureCode) {
    LockedRefund locked = lockRefund(refundId);
    RefundRepository.RefundRecord refund = locked.refund();
    requireStateSpecificTruth(refund, locked.payment());
    if (!completed.test(refund)) {
      return new ValidatedLifecycleTruth(observationEntry, Optional.empty());
    }
    if (observationEntry == RefundTransactions.Entry.FAIL_OBSERVATION
        && !expectedFailureCode.equals(refund.failureCode())) {
      throw businessConflict("Failed refund reason conflicts with its existing result");
    }
    return new ValidatedLifecycleTruth(observationEntry, Optional.of(result(refund, true)));
  }

  private Optional<RefundReconciliationResult> observeReconciliationWithinBound(String refundId) {
    for (int attempt = 1; attempt <= transactions.maximumObservationAttempts(); attempt++) {
      try {
        return transactions.observe(
            RefundTransactions.Entry.RECONCILE_OBSERVATION,
            () -> Optional.ofNullable(reconcileLocked(refundId, false)));
      } catch (RuntimeException failure) {
        if (!RefundTransactions.isMySqlContention(failure)) {
          throw failure;
        }
        if (attempt < transactions.maximumObservationAttempts() && !transactions.pause(attempt)) {
          break;
        }
      }
    }
    return null;
  }

  private RefundMutation requestOnce(
      String userSubject,
      String orderId,
      String idempotencyKey,
      RefundRequest request,
      String intentHash) {
    RefundTarget target = resolveRefundTarget(userSubject, orderId, request);
    return requestOnceWithTarget(
        userSubject, orderId, idempotencyKey, request, intentHash, target.payment());
  }

  private RefundMutation requestOnceWithTarget(
      String userSubject,
      String orderId,
      String idempotencyKey,
      RefundRequest request,
      String intentHash,
      CommittedPaymentTruthResolver.CommittedPaymentTruth payment) {
    MockPaymentRepository.AttemptRecord attempt = payment.attempt();
    MockPaymentRepository.OrderTruth order = payment.order();

    RefundRepository.RefundRecord existing =
        refunds.findByRequestForUpdate(userSubject, orderId, idempotencyKey).orElse(null);
    if (existing != null) {
      requireIntent(existing.intentHash(), intentHash);
      requireRefundIdentity(existing, payment);
      requireStateSpecificTruth(existing, payment);
      return new RefundMutation(result(existing, true), null);
    }
    requireCapacity(attempt, request.amountMinor());
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
    RefundRepository.OutboxIdentity outbox = refunds.insertOutbox(created, "REFUND_REQUESTED", 1);
    return new RefundMutation(result(created, false), outbox);
  }

  private RefundTarget resolveRefundTarget(
      String userSubject, String orderId, RefundRequest request) {
    CommittedPaymentTruthResolver.CommittedPaymentTruth committed;
    try {
      committed =
          paymentTruth
              .resolveByOrderLocked(
                  CommittedPaymentTruthResolver.CommittedPaymentCaller.DIRECT_REFUND_ELIGIBILITY,
                  orderId,
                  userSubject)
              .orElse(null);
    } catch (CommittedPaymentIntegrityException exception) {
      throw durableConflict("Order has no eligible successful payment");
    }
    if (committed == null) {
      throw notFound("Refund order is missing or not owned");
    }
    if (!committed.attempt().currency().equals(request.currency())) {
      throw businessConflict("Refund request conflicts with authoritative payment currency");
    }
    return new RefundTarget(committed);
  }

  private void requireCapacity(
      MockPaymentRepository.AttemptRecord attempt, long requestedAmountMinor) {
    long reserved = refunds.reservedAmount(attempt.attemptId());
    long remaining;
    try {
      remaining = Math.subtractExact(attempt.amountMinor(), reserved);
    } catch (ArithmeticException exception) {
      throw durableConflict("Refund reservation total is corrupted");
    }
    if (requestedAmountMinor > remaining) {
      throw businessConflict(
          remaining == 0 ? "Payment is fully refunded" : "Refund exceeds paid amount");
    }
  }

  private RefundResult markProcessingOnce(String refundId) {
    LockedRefund locked = lockRefund(refundId);
    RefundRepository.RefundRecord refund = locked.refund();
    requireStateSpecificTruth(refund, locked.payment());
    if ("PROCESSING".equals(refund.state())
        || "SUCCEEDED".equals(refund.state())
        || "FAILED".equals(refund.state())) {
      return result(refund, true);
    }
    if (!"REQUESTED".equals(refund.state()) || refund.stateVersion() != 1) {
      throw businessConflict("Refund cannot enter processing from its current state");
    }
    refunds.markProcessing(refund, clock.instant());
    RefundRepository.RefundRecord processing = requireRefund(refundId);
    refunds.insertOutbox(processing, "REFUND_PROCESSING", 2);
    return result(processing, false);
  }

  private RefundResult succeedOnce(String refundId) {
    LockedRefund locked = lockRefund(refundId);
    RefundRepository.RefundRecord refund = locked.refund();
    requireStateSpecificTruth(refund, locked.payment());
    if ("SUCCEEDED".equals(refund.state())) {
      return result(refund, true);
    }
    if (!"PROCESSING".equals(refund.state()) || refund.stateVersion() != 2) {
      throw businessConflict("Refund cannot succeed from its current state");
    }
    refunds.markSucceeded(refund, clock.instant());
    refunds.addRefundedAmount(locked.payment().attempt(), refund.requestedAmountMinor());
    refunds.insertRefundMovement(refund, locked.payment().order());
    RefundRepository.RefundRecord succeeded = requireRefund(refundId);
    refunds.insertOutbox(succeeded, "REFUND_SUCCEEDED", 3);
    return result(succeeded, false);
  }

  private RefundResult failOnce(String refundId, String failureCode) {
    LockedRefund locked = lockRefund(refundId);
    RefundRepository.RefundRecord refund = locked.refund();
    requireStateSpecificTruth(refund, locked.payment());
    if ("FAILED".equals(refund.state())) {
      if (!failureCode.equals(refund.failureCode())) {
        throw businessConflict("Failed refund reason conflicts with its existing result");
      }
      return result(refund, true);
    }
    if ("SUCCEEDED".equals(refund.state())) {
      throw businessConflict("Succeeded refund cannot fail");
    }
    if (!"PROCESSING".equals(refund.state()) || refund.stateVersion() != 2) {
      throw businessConflict("Refund cannot fail from its current state");
    }
    refunds.markFailed(refund, failureCode, clock.instant());
    RefundRepository.RefundRecord failed = requireRefund(refundId);
    refunds.insertOutbox(failed, "REFUND_FAILED", 3);
    return result(failed, false);
  }

  private RefundReconciliationResult reconcileLocked(String refundId, boolean allowConvergence) {
    BasicLockedRefund locked = lockRefundForReconciliation(refundId);
    MockPaymentRepository.AttemptRecord attempt = locked.attempt();
    MockPaymentRepository.OrderTruth order = locked.order();
    List<RefundRepository.RefundRecord> all = refunds.findByAttemptForUpdate(attempt.attemptId());
    List<String> contradictions = new ArrayList<>();

    try {
      CommittedPaymentTruthResolver.CommittedPaymentTruth committed =
          paymentTruth.resolveLocked(
              CommittedPaymentTruthResolver.CommittedPaymentCaller.REFUND_RECONCILIATION, attempt);
      if (!committed.order().equals(order)) {
        contradictions.add("PAYMENT_ORDER_MISMATCH");
      }
    } catch (CommittedPaymentIntegrityException exception) {
      addPaymentClosureContradiction(attempt, contradictions);
    }
    if (!locked.refund().paymentAttemptId().equals(attempt.attemptId())) {
      contradictions.add("REFUND_IDENTITY_MISMATCH:" + locked.refund().refundId());
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
      if (!allowConvergence) {
        return null;
      }
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
    RefundRepository.RefundRecord identified =
        refunds.findById(refundId).orElseThrow(() -> notFound("Refund is missing"));
    MockPaymentRepository.AttemptRecord attempt =
        payments
            .findAttemptByIdForUpdate(identified.paymentAttemptId())
            .orElseThrow(() -> durableConflict("Refund payment truth is missing"));
    CommittedPaymentTruthResolver.CommittedPaymentTruth committed;
    try {
      committed =
          paymentTruth.resolveLocked(
              CommittedPaymentTruthResolver.CommittedPaymentCaller.REFUND_LIFECYCLE, attempt);
    } catch (CommittedPaymentIntegrityException exception) {
      throw durableConflict("Order has no eligible successful payment");
    }
    RefundRepository.RefundRecord refund = requireRefund(refundId);
    if (!refund.paymentAttemptId().equals(identified.paymentAttemptId())
        || !matchesRefundIdentity(refund, committed.attempt(), committed.order())) {
      throw durableConflict("Refund conflicts with payment or order truth");
    }
    return new LockedRefund(refund, committed);
  }

  private BasicLockedRefund lockRefundForReconciliation(String refundId) {
    RefundRepository.RefundRecord identified =
        refunds.findById(refundId).orElseThrow(() -> notFound("Refund is missing"));
    MockPaymentRepository.AttemptRecord attempt =
        payments
            .findAttemptByIdForUpdate(identified.paymentAttemptId())
            .orElseThrow(() -> durableConflict("Refund payment truth is missing"));
    MockPaymentRepository.OrderTruth order =
        payments
            .findOrderForUpdate(attempt.orderId())
            .orElseThrow(() -> durableConflict("Refund order truth is missing"));
    RefundRepository.RefundRecord refund = requireRefund(refundId);
    return new BasicLockedRefund(refund, attempt, order);
  }

  private void addPaymentClosureContradiction(
      MockPaymentRepository.AttemptRecord attempt, List<String> contradictions) {
    try {
      if (payments.findCallbackByAttempt(attempt.attemptId()).isEmpty()) {
        contradictions.add("PAYMENT_CALLBACK_MISSING");
        return;
      }
    } catch (IllegalStateException ignored) {
      // Non-unique or malformed callback truth remains a generic closure contradiction.
    }
    contradictions.add("PAYMENT_CLOSURE_INCONSISTENT");
  }

  private RefundRepository.RefundRecord requireRefund(String refundId) {
    return refunds
        .findByIdForUpdate(refundId)
        .orElseThrow(() -> durableConflict("Refund truth disappeared"));
  }

  private void requireRefundIdentity(
      RefundRepository.RefundRecord refund,
      CommittedPaymentTruthResolver.CommittedPaymentTruth payment) {
    if (!matchesRefundIdentity(refund, payment.attempt(), payment.order())) {
      throw durableConflict("Refund durable truth is inconsistent");
    }
  }

  private void requireStateSpecificTruth(
      RefundRepository.RefundRecord refund,
      CommittedPaymentTruthResolver.CommittedPaymentTruth payment) {
    requireRefundIdentity(refund, payment);
    RefundRepository.MovementRecord movement =
        refunds.findMovement(RefundRepository.refundEventKey(refund.refundId())).orElse(null);
    if ("SUCCEEDED".equals(refund.state())) {
      if (!matchesRefundMovement(movement, refund, payment.order())
          || payment.attempt().refundedAmountMinor() < refund.refundedAmountMinor()) {
        throw durableConflict("Succeeded refund truth is incomplete");
      }
    } else if (movement != null) {
      throw durableConflict("Non-successful refund has a refund ledger movement");
    }
    if ("FAILED".equals(refund.state()) && refund.failureCode() == null) {
      throw durableConflict("Failed refund truth is incomplete");
    }
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

  private static void requireCurrentTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Action refund work requires an active transaction");
    }
  }

  private static void requireActionSandbox(
      CommittedPaymentTruthResolver.CommittedPaymentTruth payment, String sandboxId) {
    if (!java.util.Objects.equals(payment.order().sandboxId(), sandboxId)
        || !java.util.Objects.equals(payment.attempt().sandboxId(), sandboxId)) {
      throw durableConflict("Action refund sandbox truth is inconsistent");
    }
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

  private static boolean isDirectCompetition(RuntimeException failure) {
    return failure instanceof DuplicateKeyException
        || RefundTransactions.isMySqlContention(failure);
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
      throw new RefundException(
          409,
          "CONFLICT",
          RefundRejectionReason.REFUND_IDEMPOTENCY_INTENT_CONFLICT,
          "Refund idempotency intent conflicts");
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
    return new RefundException(
        404, "NOT_FOUND", RefundRejectionReason.REFUND_CONCEALED_NOT_FOUND, message);
  }

  private static RefundException businessConflict(String message) {
    return new RefundException(
        409, "CONFLICT", RefundRejectionReason.REFUND_BUSINESS_CONFLICT, message);
  }

  private static RefundException durableConflict(String message) {
    return new RefundException(
        409, "CONFLICT", RefundRejectionReason.REFUND_DURABLE_TRUTH_INCONSISTENT, message);
  }

  private static RefundException refundConcurrencyIndeterminate() {
    return new RefundException(
        429,
        "INDETERMINATE",
        RefundRejectionReason.REFUND_CONCURRENCY_OBSERVATION_INDETERMINATE,
        "Refund truth is indeterminate; retry the same request");
  }

  private record RefundTarget(CommittedPaymentTruthResolver.CommittedPaymentTruth payment) {}

  private record RefundMutation(RefundResult refund, RefundRepository.OutboxIdentity outbox) {}

  public record ActionTarget(
      MockPaymentRepository.OrderTruth order, MockPaymentRepository.AttemptRecord attempt) {}

  public record ActionMutation(RefundResult refund, RefundRepository.OutboxIdentity outbox) {}

  public record ActionReplayTruth(RefundResult refund, ActionTarget target) {}

  private record LockedRefund(
      RefundRepository.RefundRecord refund,
      CommittedPaymentTruthResolver.CommittedPaymentTruth payment) {}

  private record BasicLockedRefund(
      RefundRepository.RefundRecord refund,
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.OrderTruth order) {}

  private enum RequestObservationState {
    FOUND,
    CONFIRMED_ABSENT,
    INDETERMINATE
  }

  private record RequestObservation(RequestObservationState state, RefundResult result) {
    static RequestObservation found(RefundResult result) {
      return new RequestObservation(RequestObservationState.FOUND, result);
    }

    static RequestObservation absent() {
      return new RequestObservation(RequestObservationState.CONFIRMED_ABSENT, null);
    }

    static RequestObservation indeterminate() {
      return new RequestObservation(RequestObservationState.INDETERMINATE, null);
    }

    RefundResult requireResult() {
      if (state != RequestObservationState.FOUND || result == null) {
        throw new IllegalStateException("Refund observation has no committed result");
      }
      return result;
    }
  }

  private record ValidatedLifecycleTruth(
      RefundTransactions.Entry entry, Optional<RefundResult> completedResult) {}
}
