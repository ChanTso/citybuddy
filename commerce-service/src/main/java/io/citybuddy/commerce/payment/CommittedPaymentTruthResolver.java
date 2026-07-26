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
  private final CommittedOrderOriginResolver orderOrigins;

  public CommittedPaymentTruthResolver(MockPaymentRepository repository) {
    this.repository = repository;
    this.orderOrigins = new CommittedOrderOriginResolver(repository);
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
    return hash(base + "\n" + idempotencyKey);
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
   * Classifies one payment-start command from both declared visibility locators and the complete
   * durable payment closure.
   *
   * <p>The attempt-command and owned-order locators are both observed before either result is used.
   * The owned-order lookup is owner scoped; only after it proves visibility may the broader
   * order-relation enumeration participate in integrity classification. All locking durable
   * enumerations acquire attempt rows before order rows, matching callback and refund lock order.
   */
  public StartCommandResolution resolveStartCommandLocked(StartCommandContext context) {
    Objects.requireNonNull(context, "Payment start context is required");
    List<MockPaymentRepository.AttemptRecord> commandAttempts =
        observeStartAttemptCommandLocator(context);
    List<PaymentStartOrderVisibility.Visible> visibleOrders =
        observeStartOwnedOrderLocator(context).stream()
            .filter(PaymentStartOrderVisibility.Visible.class::isInstance)
            .map(PaymentStartOrderVisibility.Visible.class::cast)
            .toList();
    if (commandAttempts.isEmpty() && visibleOrders.isEmpty()) {
      return new ConcealedStart();
    }
    if (commandAttempts.size() > 1 || visibleOrders.size() > 1) {
      throw inconsistent("Payment start visibility cardinality is inconsistent");
    }

    if (!commandAttempts.isEmpty()) {
      PaymentStartReplayResolution replay = resolveStartCandidateLocked(commandAttempts.getFirst());
      requireStartCommandMatches(replay, context);
      return startResolution(replay);
    }

    List<MockPaymentRepository.AttemptRecord> orderAttempts =
        repository.enumerateAttemptByOrderClosure(context.orderId(), LOCK);
    if (orderAttempts.size() > 1) {
      throw inconsistent("Payment start order-attempt cardinality is inconsistent");
    }
    List<MockPaymentRepository.OrderTruth> orders =
        repository.enumerateOrderClosure(context.orderId(), LOCK);
    PaymentStartOrderVisibility.Visible visibleOrder = visibleOrders.getFirst();
    requireSingleEqual(orders, visibleOrder.order(), "Payment start order closure is inconsistent");
    MockPaymentRepository.OrderTruth order = orders.getFirst();

    if (!orderAttempts.isEmpty()) {
      PaymentStartReplayResolution replay = resolveStartCandidateLocked(orderAttempts.getFirst());
      requireStartCommandMatches(replay, context);
      return startResolution(replay);
    }

    List<MockPaymentRepository.PaymentLedgerRecord> ledger =
        repository.enumerateLedgerReplayClosure(null, order.orderId(), "");
    if (isCommittedOrder(order)) {
      throw inconsistent("Committed payment has no canonical attempt");
    }
    requirePendingLedgerClosure(ledger, order);
    requireCreateEligibleOrder(order, context, visibleOrder);
    return new CreateEligible(order, visibleOrder.bindingProof());
  }

  private List<MockPaymentRepository.AttemptRecord> observeStartAttemptCommandLocator(
      StartCommandContext context) {
    if (!CommittedPaymentCaller.PAYMENT_START_REPLAY
        .ownershipVisibilityLocators()
        .contains(OwnershipVisibilityLocator.START_ATTEMPT_COMMAND)) {
      throw new IllegalStateException("Payment start attempt locator is not registered");
    }
    return repository.enumerateStartAttemptVisibility(
        context.userSubject(), context.requestIdempotencyKey(), LOCK);
  }

  private List<PaymentStartOrderVisibility.Classification> observeStartOwnedOrderLocator(
      StartCommandContext context) {
    if (!CommittedPaymentCaller.PAYMENT_START_REPLAY
        .ownershipVisibilityLocators()
        .contains(OwnershipVisibilityLocator.START_OWNED_ORDER)) {
      throw new IllegalStateException("Payment start order locator is not registered");
    }
    return repository.enumerateStartOrderVisibility(
        context.orderId(), context.userSubject(), context.sandboxId());
  }

  private PaymentStartReplayResolution resolveStartCandidateLocked(
      MockPaymentRepository.AttemptRecord target) {
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
      return resolve(CommittedPaymentCaller.PAYMENT_START_REPLAY, attempt, LOCK);
    }

    requirePendingPaymentRows(attempt, order);
    if (!callbacks.isEmpty()) {
      throw inconsistent("Pending payment has callback truth");
    }
    requirePendingLedgerClosure(ledger, order);
    return new PendingPaymentTruth(order, attempt);
  }

  private static StartCommandResolution startResolution(PaymentStartReplayResolution replay) {
    if (replay instanceof CommittedPaymentTruth committed) {
      return new CommittedReplay(committed);
    }
    return new PendingReplay((PendingPaymentTruth) replay);
  }

  private static void requireStartCommandMatches(
      PaymentStartReplayResolution replay, StartCommandContext context) {
    MockPaymentRepository.AttemptRecord attempt =
        replay instanceof CommittedPaymentTruth committed
            ? committed.attempt()
            : ((PendingPaymentTruth) replay).attempt();
    if (!attempt.userSubject().equals(context.userSubject())
        || !attempt.orderId().equals(context.orderId())
        || !Objects.equals(attempt.sandboxId(), context.sandboxId())
        || !attempt.requestIdempotencyKey().equals(context.requestIdempotencyKey())
        || !attempt.intentHash().equals(context.intentHash())
        || attempt.amountMinor() != context.amountMinor()
        || !attempt.currency().equals(context.currency())) {
      throw startConflict(
          MockPaymentRejectionReason.IDEMPOTENCY_INTENT_CONFLICT,
          "Payment idempotency intent conflicts");
    }
  }

  private static void requireCreateEligibleOrder(
      MockPaymentRepository.OrderTruth order,
      StartCommandContext context,
      PaymentStartOrderVisibility.Visible visibility) {
    if (!visibility.order().equals(order)
        || !Objects.equals(context.sandboxId(), order.sandboxId())) {
      throw inconsistent("Payment start owned-order visibility changed while locking");
    }
    if (order.amountMinor() != context.amountMinor()
        || !order.currency().equals(context.currency())) {
      throw startConflict(
          MockPaymentRejectionReason.IDEMPOTENCY_INTENT_CONFLICT,
          "Payment request does not match authoritative order amount");
    }
    if (context.sandboxId() != null && !"STANDARD".equals(order.orderKind())) {
      throw startConflict(
          MockPaymentRejectionReason.ORDER_NOT_ELIGIBLE,
          "Evaluation payment order kind is not supported");
    }
    if ("PAID".equals(order.status())) {
      throw inconsistent("Paid order has no payment-attempt closure");
    }
    if (!"UNPAID".equals(order.status()) || order.stateVersion() != 1) {
      throw startConflict(
          MockPaymentRejectionReason.ORDER_NOT_ELIGIBLE, "Order is not eligible for payment");
    }
  }

  private static MockPaymentException startConflict(
      MockPaymentRejectionReason reason, String message) {
    return new MockPaymentException(409, "CONFLICT", reason, message);
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
      if (target != null) {
        MockPaymentRepository.OrderTruth order = orders.getFirst();
        requirePendingPaymentRows(target, order);
        requirePendingLedgerClosure(ledger, order);
      }
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
    orderOrigins.resolve(order);

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
    requireCorrelatedContentGroups(attempt, callback, audit);

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
                    attempt.requestIdempotencyKey(),
                    attempt.amountMinor(),
                    attempt.currency(),
                    attempt.sandboxId()))
        || !"PAID".equals(order.status())
        || order.stateVersion() != 2
        || !validOrderKindSpecificContent(order)
        || !callback.attemptId().equals(attempt.attemptId())
        || !callback.callbackCorrelationId().equals(attempt.callbackCorrelationId())
        || !Objects.equals(callback.sandboxId(), attempt.sandboxId())
        || !"SUCCEEDED".equals(callback.requestedOutcome())
        || !"APPLIED".equals(callback.resultState())
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
                    attempt.requestIdempotencyKey(),
                    attempt.amountMinor(),
                    attempt.currency(),
                    attempt.sandboxId()))
        || !"UNPAID".equals(order.status())
        || order.stateVersion() != 1
        || !validOrderKindSpecificContent(order)) {
      throw inconsistent("Pending payment content is inconsistent");
    }
  }

  private static void requirePendingLedgerClosure(
      List<MockPaymentRepository.PaymentLedgerRecord> rows,
      MockPaymentRepository.OrderTruth order) {
    requireLedgerAcquisitionBound(rows);
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
    requireLedgerAcquisitionBound(rows);
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
        || order.transactionEventId() == null
        || order.quantity() == null
        || order.quantity() < 1
        || !movement.businessEventKey().equals("seckill-order-create:" + order.transactionEventId())
        || !matchesMovementIdentity(movement, order)
        || movement.inventoryDelta() != -order.quantity()
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
        || !"BUSINESS_EVENT".equals(audit.createdAtAnchor())
        || !repository.auditSequenceOrderConsistent(audit)) {
      throw inconsistent("Evaluation payment audit content is inconsistent");
    }
    return Optional.of(audit);
  }

  private static void requireCorrelatedContentGroups(
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.CallbackRecord callback,
      Optional<MockPaymentRepository.PaymentAuditRecord> audit) {
    for (EvaluationPaymentCommittedFaces.CorrelatedContentGroup group :
        EvaluationPaymentCommittedFaces.correlatedContentGroups()) {
      switch (group.id()) {
        case PAYMENT_EVENT_TIME -> requirePaymentEventTime(group, attempt, callback, audit);
      }
    }
  }

  private static void requirePaymentEventTime(
      EvaluationPaymentCommittedFaces.CorrelatedContentGroup group,
      MockPaymentRepository.AttemptRecord attempt,
      MockPaymentRepository.CallbackRecord callback,
      Optional<MockPaymentRepository.PaymentAuditRecord> audit) {
    EvaluationPaymentCommittedFaces.PaymentTruthScope scope =
        attempt.sandboxId() == null
            ? EvaluationPaymentCommittedFaces.PaymentTruthScope.PRODUCTION
            : EvaluationPaymentCommittedFaces.PaymentTruthScope.EVALUATION;
    List<EvaluationPaymentCommittedFaces.ColumnRef> expectedMembers =
        scope == EvaluationPaymentCommittedFaces.PaymentTruthScope.PRODUCTION
            ? List.of(
                new EvaluationPaymentCommittedFaces.ColumnRef(
                    "mock_payment_callback", "created_at"),
                new EvaluationPaymentCommittedFaces.ColumnRef(
                    "mock_payment_attempt", "succeeded_at"))
            : List.of(
                new EvaluationPaymentCommittedFaces.ColumnRef(
                    "mock_payment_callback", "created_at"),
                new EvaluationPaymentCommittedFaces.ColumnRef(
                    "mock_payment_attempt", "succeeded_at"),
                new EvaluationPaymentCommittedFaces.ColumnRef(
                    "eval_commerce_audit_reference", "created_at"));
    List<EvaluationPaymentCommittedFaces.ColumnRef> registeredMembers =
        group.membersFor(scope).stream().map(member -> member.column()).toList();
    if (!registeredMembers.containsAll(expectedMembers)
        || !expectedMembers.containsAll(registeredMembers)
        || attempt.succeededAt() == null
        || callback.createdAt() == null
        || !attempt.succeededAt().equals(callback.createdAt())
        || (scope == EvaluationPaymentCommittedFaces.PaymentTruthScope.EVALUATION
            && (audit.isEmpty()
                || audit.orElseThrow().createdAt() == null
                || !audit.orElseThrow().createdAt().equals(callback.createdAt())))) {
      throw inconsistent("Committed payment event-time group is inconsistent");
    }
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
    return hash(base + "\n" + callback.callbackIdempotencyKey());
  }

  private static boolean validOrderKindSpecificContent(MockPaymentRepository.OrderTruth order) {
    if ("STANDARD".equals(order.orderKind())) {
      return order.reservationId() == null
          && order.activityId() == null
          && order.transactionEventId() == null
          && order.quantity() != null
          && order.quantity() > 0
          && order.productVersion() != null
          && order.productVersion() > 0;
    }
    return "SECKILL".equals(order.orderKind())
        && order.reservationId() != null
        && order.activityId() != null
        && order.transactionEventId() != null
        && order.quantity() != null
        && order.quantity() > 0
        && order.productVersion() == null;
  }

  private static void requireLedgerAcquisitionBound(
      List<MockPaymentRepository.PaymentLedgerRecord> rows) {
    if (rows.size() > MockPaymentRepository.MAXIMUM_LEDGER_CLOSURE_ROWS) {
      throw inconsistent("Payment ledger closure exceeds its acquisition bound");
    }
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

  public sealed interface StartCommandResolution
      permits ConcealedStart, CreateEligible, PendingReplay, CommittedReplay {}

  public record StartCommandContext(
      String userSubject,
      String sandboxId,
      String orderId,
      String requestIdempotencyKey,
      String intentHash,
      long amountMinor,
      String currency) {}

  public record ConcealedStart() implements StartCommandResolution {}

  public record CreateEligible(
      MockPaymentRepository.OrderTruth order,
      Optional<PaymentStartOrderVisibility.EvaluationOwnerBindingProof> bindingProof)
      implements StartCommandResolution {
    public CreateEligible {
      bindingProof = Optional.ofNullable(bindingProof.orElse(null));
    }
  }

  public record PendingReplay(PendingPaymentTruth truth) implements StartCommandResolution {}

  public record CommittedReplay(CommittedPaymentTruth truth) implements StartCommandResolution {}

  public enum CommittedPaymentCaller {
    PAYMENT_START_REPLAY(
        "POST /api/orders/{orderId}/mock-payment replay",
        TrustBoundary.OWNER_CONCEALING_PUBLIC,
        List.of("orderId", "userSubject", "requestIdempotencyKey", "evaluationSandbox"),
        List.of(
            OwnershipVisibilityLocator.START_ATTEMPT_COMMAND,
            OwnershipVisibilityLocator.START_OWNED_ORDER),
        "mock-payment unknown/other-owner",
        "resolveStartCommandLocked",
        "StartCommandResolution",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        true),
    PRODUCTION_CALLBACK_REPLAY(
        "production callback replay",
        TrustBoundary.AUTHENTICATED_CALLBACK,
        List.of("callbackIdempotencyKey", "callbackEventId", "callbackCorrelationId", "orderId"),
        List.of(OwnershipVisibilityLocator.VERIFIED_CALLBACK_SIGNATURE),
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
        List.of(OwnershipVisibilityLocator.VERIFIED_CALLBACK_SANDBOX_CONTEXT),
        "signed callback foreign/unknown evaluation correlation",
        "resolveReplayLocked",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        true),
    DIRECT_REFUND_ELIGIBILITY(
        "direct refund eligibility and replay",
        TrustBoundary.OWNER_CONCEALING_PUBLIC,
        List.of("orderId", "userSubject", "refundIdempotencyKey"),
        List.of(
            OwnershipVisibilityLocator.REFUND_OWNED_ATTEMPT,
            OwnershipVisibilityLocator.REFUND_OWNED_ORDER),
        "refund unknown/other-owner",
        "resolveByOrderLocked",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        false),
    ACTION_PREPARE_CONFIRM_AND_RECEIPT_REPLAY(
        "Action prepare, confirm, and receipt replay",
        TrustBoundary.OWNER_CONCEALING_OBO,
        List.of("orderId", "userSubject", "supportSessionId", "traceId", "turnId", "sandboxId"),
        List.of(
            OwnershipVisibilityLocator.OBO_OWNER_SESSION,
            OwnershipVisibilityLocator.ACTION_PAYMENT_OWNER,
            OwnershipVisibilityLocator.ACTION_SANDBOX_BINDING),
        "Action target unknown/other-owner",
        "resolveByOrderLocked",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        true),
    REFUND_LIFECYCLE(
        "refund lifecycle mutation",
        TrustBoundary.INTERNAL_DURABLE_IDENTITY,
        List.of("refundId", "paymentAttemptId"),
        List.of(OwnershipVisibilityLocator.LOCKED_REFUND_ATTEMPT),
        "internal refund identity missing",
        "resolveLocked",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        false),
    REFUND_RECONCILIATION(
        "refund reconciliation",
        TrustBoundary.INTERNAL_DURABLE_IDENTITY,
        List.of("refundId", "paymentAttemptId"),
        List.of(OwnershipVisibilityLocator.LOCKED_REFUND_ATTEMPT),
        "internal refund identity missing",
        "resolveLocked",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.RECONCILIATION_DERIVED,
        false),
    EVALUATION_STATE(
        "/api/eval/state",
        TrustBoundary.SANDBOX_WIDE_INTERNAL_EVALUATOR,
        List.of("sandboxId"),
        List.of(OwnershipVisibilityLocator.MANAGEMENT_SANDBOX_SCOPE),
        "evaluation sandbox unknown",
        "resolveSnapshot",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        false),
    EVALUATION_AUDIT(
        "/api/eval/audit",
        TrustBoundary.SANDBOX_WIDE_INTERNAL_EVALUATOR,
        List.of("sandboxId", "supportSessionId"),
        List.of(OwnershipVisibilityLocator.MANAGEMENT_SANDBOX_AUDIT_SCOPE),
        "evaluation sandbox/audit unknown",
        "resolveSnapshot",
        "CommittedPaymentTruth",
        RefundAccumulatorPolicy.EXACT_LEDGER_SUM,
        false);

    private final String surface;
    private final TrustBoundary trustBoundary;
    private final List<String> canonicalRequestLocators;
    private final List<OwnershipVisibilityLocator> ownershipVisibilityLocators;
    private final String concealedResponseFamily;
    private final String resolverMethod;
    private final String successInputType;
    private final RefundAccumulatorPolicy refundAccumulatorPolicy;
    private final boolean committedBeforeLiveness;

    CommittedPaymentCaller(
        String surface,
        TrustBoundary trustBoundary,
        List<String> canonicalRequestLocators,
        List<OwnershipVisibilityLocator> ownershipVisibilityLocators,
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

    public List<OwnershipVisibilityLocator> ownershipVisibilityLocators() {
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

  public enum OwnershipVisibilityLocator {
    START_ATTEMPT_COMMAND("attempt.userSubject+requestIdempotencyKey"),
    START_OWNED_ORDER("order.orderId+userSubject+sandboxId"),
    VERIFIED_CALLBACK_SIGNATURE("verified callback signature"),
    VERIFIED_CALLBACK_SANDBOX_CONTEXT("verified callback signature+sandbox context"),
    REFUND_OWNED_ATTEMPT("attempt.orderId+userSubject"),
    REFUND_OWNED_ORDER("order.orderId+userSubject"),
    OBO_OWNER_SESSION("OBO owner+session"),
    ACTION_PAYMENT_OWNER("attempt/order owner"),
    ACTION_SANDBOX_BINDING("sandbox binding"),
    LOCKED_REFUND_ATTEMPT("locked refund.paymentAttemptId"),
    MANAGEMENT_SANDBOX_SCOPE("management credential+sandbox scope"),
    MANAGEMENT_SANDBOX_AUDIT_SCOPE("management credential+sandbox+audit scope");

    private final String description;

    OwnershipVisibilityLocator(String description) {
      this.description = description;
    }

    public String description() {
      return description;
    }
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
