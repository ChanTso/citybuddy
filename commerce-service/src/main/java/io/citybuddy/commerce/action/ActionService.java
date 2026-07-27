package io.citybuddy.commerce.action;

import io.citybuddy.commerce.action.ActionRepository.ActionIntegrityException;
import io.citybuddy.commerce.action.ActionRepository.ActionReceiptRecord;
import io.citybuddy.commerce.action.ActionRepository.PendingActionRecord;
import io.citybuddy.commerce.action.ActionRepository.RefundOutboxRecord;
import io.citybuddy.commerce.evaluation.EvaluationRejectionReason;
import io.citybuddy.commerce.evaluation.EvaluationSandboxAccess;
import io.citybuddy.commerce.evaluation.EvaluationSandboxException;
import io.citybuddy.commerce.refund.RefundException;
import io.citybuddy.commerce.refund.RefundRejectionReason;
import io.citybuddy.commerce.refund.RefundRequest;
import io.citybuddy.commerce.refund.RefundResult;
import io.citybuddy.commerce.refund.RefundService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.CannotCreateTransactionException;

public final class ActionService {
  static final String REFUND_REQUEST = "REFUND_REQUEST";
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern BOUNDED_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

  private final ActionRepository actions;
  private final RefundService refunds;
  private final ActionTransactions transactions;
  private final ActionProperties properties;
  private final Clock clock;
  private final ObjectProvider<EvaluationSandboxAccess> sandboxAccess;

  public ActionService(
      ActionRepository actions,
      RefundService refunds,
      ActionTransactions transactions,
      ActionProperties properties,
      Clock clock,
      ObjectProvider<EvaluationSandboxAccess> sandboxAccess) {
    this.actions = actions;
    this.refunds = refunds;
    this.transactions = transactions;
    this.properties = properties;
    this.clock = clock;
    this.sandboxAccess = sandboxAccess;
  }

  public PendingActionView prepare(ActionRequestContext context, PrepareActionCommand command) {
    ValidatedContext validContext = validateContext(context);
    ValidatedCommand validCommand = validateCommand(command);
    String argumentHash = argumentHash(validCommand);
    String actionKey = actionKey(validContext, argumentHash);
    try {
      return transactions.mutate(
          ActionTransactions.Entry.PREPARE_INITIAL_MUTATION,
          () -> prepareOnce(validContext, validCommand, argumentHash, actionKey));
    } catch (RuntimeException failure) {
      if (failure instanceof DuplicateKeyException
          || ActionTransactions.isMySqlContention(failure)) {
        Optional<PendingActionView> observed =
            observePreparedWithinBound(validContext, validCommand, argumentHash, actionKey);
        if (observed.isPresent()) {
          return observed.orElseThrow();
        }
        throw indeterminate("PendingAction prepare remains indeterminate");
      }
      throw classifyDependency(failure);
    }
  }

  public ActionReceiptView confirm(ActionRequestContext context, String pendingActionId) {
    ValidatedContext validContext = validateContext(context);
    requireUuid(pendingActionId, "PendingAction id is invalid");
    try {
      return transactions.mutate(
          ActionTransactions.Entry.CONFIRM_INITIAL_MUTATION,
          () -> confirmOnce(validContext, pendingActionId));
    } catch (RuntimeException failure) {
      if (failure instanceof DuplicateKeyException
          || ActionTransactions.isMySqlContention(failure)) {
        Optional<ActionReceiptView> observed =
            observeReceiptWithinBound(validContext, pendingActionId);
        if (observed.isPresent()) {
          return observed.orElseThrow();
        }
        throw indeterminate("Action confirmation remains indeterminate");
      }
      throw classifyDependency(failure);
    }
  }

  private PendingActionView prepareOnce(
      ValidatedContext context, ValidatedCommand command, String argumentHash, String actionKey) {
    PendingActionRecord existing =
        actions
            .findPendingByTurnForUpdate(
                context.userSubject(), context.supportSessionId(), context.turnId())
            .orElse(null);
    if (existing != null) {
      return resolvePreparedReplay(existing, context, command, argumentHash, actionKey);
    }
    requireActiveSandbox(context.sandboxId());
    RefundService.ActionTarget target =
        refundBoundary(
            () ->
                refunds.prepareActionInCurrentTransaction(
                    context.userSubject(),
                    command.orderId(),
                    refundRequest(command),
                    context.sandboxId()));
    Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    String pendingActionId = UUID.randomUUID().toString();
    Instant expiresAt = createdAt.plus(properties.pendingTtl());
    String pendingHash =
        pendingHash(
            pendingActionId,
            actionKey,
            argumentHash,
            context,
            command,
            target,
            expiresAt,
            createdAt);
    PendingActionRecord created =
        new PendingActionRecord(
            pendingActionId,
            actionKey,
            pendingHash,
            REFUND_REQUEST,
            argumentHash,
            context.userSubject(),
            context.supportSessionId(),
            context.traceId(),
            context.turnId(),
            context.requiredScope(),
            context.sandboxId(),
            command.orderId(),
            target.order().orderKind(),
            target.attempt().attemptId(),
            target.order().stateVersion(),
            command.amountMinor(),
            command.currency(),
            "PREPARED",
            1,
            expiresAt,
            null,
            createdAt);
    actions.insertPending(created);
    return pendingView(created, false);
  }

  private ActionReceiptView confirmOnce(ValidatedContext context, String pendingActionId) {
    PendingActionRecord pending =
        actions
            .findPendingByIdForUpdate(pendingActionId)
            .orElseThrow(() -> concealed("PendingAction is missing or not owned"));
    requireConfirmVisibility(pending, context);
    ValidatedCommand command =
        new ValidatedCommand(pending.orderId(), pending.amountMinor(), pending.currency());
    requirePreparedCommitment(
        pending,
        context,
        command,
        argumentHash(command),
        actionKey(context, argumentHash(command)));
    ActionReceiptRecord existing = actions.findReceiptByPending(pendingActionId).orElse(null);
    if ("CONSUMED".equals(pending.state())) {
      if (existing == null) {
        throw integrityFailure("Consumed PendingAction has no ActionReceipt");
      }
      // A committed receipt is authoritative and replays before mutable sandbox liveness.
      return validateReceipt(pending, existing, true);
    }
    if (existing != null) {
      throw integrityFailure("Prepared PendingAction already has an ActionReceipt");
    }
    requirePreparedState(pending);
    if (!clock.instant().isBefore(pending.expiresAt())) {
      throw conflict("PendingAction is expired");
    }
    requireActiveSandbox(context.sandboxId());
    RefundService.ActionTarget target =
        refundBoundary(
            () ->
                refunds.prepareActionInCurrentTransaction(
                    context.userSubject(),
                    command.orderId(),
                    refundRequest(command),
                    context.sandboxId()));
    requireTarget(pending, target);

    RefundService.ActionMutation mutation =
        refundBoundary(
            () ->
                refunds.requestActionInCurrentTransaction(
                    context.userSubject(),
                    pending.orderId(),
                    refundIdempotencyKey(pending.pendingActionId()),
                    refundRequest(command),
                    context.sandboxId()));
    if (mutation.refund().replayed() || mutation.outbox() == null) {
      throw integrityFailure("Prepared PendingAction points to an existing refund result");
    }
    RefundOutboxRecord outbox =
        requireRefundOutboxClosure(
            actions.findRefundOutboxByAggregateForUpdate(mutation.refund().refundId()),
            mutation.refund(),
            mutation.outbox().eventId());

    Instant committedAt =
        latest(
            latest(
                clock.instant().truncatedTo(ChronoUnit.MICROS),
                pending.createdAt().truncatedTo(ChronoUnit.MICROS)),
            outbox.createdAt());
    actions.consume(pending, committedAt);
    String receiptId = UUID.randomUUID().toString();
    String receiptKey = ActionCanonical.hash("ACTION_RECEIPT", pending.actionIdempotencyKey());
    ActionReceiptRecord receipt =
        new ActionReceiptRecord(
            receiptId,
            receiptKey,
            pending.pendingActionId(),
            pending.actionType(),
            pending.argumentHash(),
            resultHash(
                receiptId,
                receiptKey,
                pending,
                mutation.refund().refundId(),
                outbox.eventId(),
                outbox.createdAt(),
                committedAt),
            pending.userSubject(),
            pending.supportSessionId(),
            pending.traceId(),
            pending.turnId(),
            pending.sandboxId(),
            pending.orderId(),
            pending.paymentAttemptId(),
            mutation.refund().refundId(),
            mutation.refund().stateVersion(),
            mutation.refund().state(),
            mutation.refund().requestedAmountMinor(),
            mutation.refund().currency(),
            outbox.eventId(),
            outbox.createdAt(),
            committedAt);
    actions.insertReceipt(receipt);
    return receiptView(receipt, false);
  }

  private Optional<PendingActionView> observePreparedWithinBound(
      ValidatedContext context, ValidatedCommand command, String argumentHash, String actionKey) {
    for (int attempt = 1; attempt <= transactions.maximumObservationAttempts(); attempt++) {
      try {
        return transactions.observe(
            ActionTransactions.Entry.PREPARE_TRUTH_OBSERVATION,
            () -> {
              PendingActionRecord pending =
                  actions
                      .findPendingByTurnForUpdate(
                          context.userSubject(), context.supportSessionId(), context.turnId())
                      .orElse(null);
              if (pending == null) {
                return Optional.empty();
              }
              return Optional.of(
                  resolvePreparedReplay(pending, context, command, argumentHash, actionKey));
            });
      } catch (RuntimeException failure) {
        if (!ActionTransactions.isMySqlContention(failure)) {
          throw classifyDependency(failure);
        }
        if (attempt == transactions.maximumObservationAttempts() || !transactions.pause(attempt)) {
          return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }

  private PendingActionView resolvePreparedReplay(
      PendingActionRecord pending,
      ValidatedContext context,
      ValidatedCommand command,
      String argumentHash,
      String actionKey) {
    requirePreparedCommitment(pending, context, command, argumentHash, actionKey);
    ActionReceiptRecord receipt =
        actions.findReceiptByPending(pending.pendingActionId()).orElse(null);
    if ("CONSUMED".equals(pending.state())) {
      if (receipt == null) {
        throw integrityFailure("Consumed PendingAction has no ActionReceipt");
      }
      validateReceipt(pending, receipt, true);
    } else if (receipt != null) {
      throw integrityFailure("Prepared PendingAction already has an ActionReceipt");
    } else {
      requireActiveSandbox(context.sandboxId());
      RefundService.ActionTarget target =
          refundBoundary(
              () ->
                  refunds.prepareActionInCurrentTransaction(
                      context.userSubject(),
                      command.orderId(),
                      refundRequest(command),
                      context.sandboxId()));
      requireTarget(pending, target);
    }
    return pendingView(pending, true);
  }

  private Optional<ActionReceiptView> observeReceiptWithinBound(
      ValidatedContext context, String pendingActionId) {
    for (int attempt = 1; attempt <= transactions.maximumObservationAttempts(); attempt++) {
      try {
        return transactions.observe(
            ActionTransactions.Entry.CONFIRM_TRUTH_OBSERVATION,
            () -> {
              PendingActionRecord pending =
                  actions.findPendingByIdForUpdate(pendingActionId).orElse(null);
              if (pending == null) {
                throw concealed("PendingAction is missing or not owned");
              }
              requireConfirmVisibility(pending, context);
              requirePendingIntegrity(pending);
              requireConfirmIntent(pending, context);
              ActionReceiptRecord receipt =
                  actions.findReceiptByPending(pendingActionId).orElse(null);
              if (receipt == null) {
                if ("CONSUMED".equals(pending.state())) {
                  throw integrityFailure("Consumed PendingAction has no ActionReceipt");
                }
                return Optional.empty();
              }
              return Optional.of(validateReceipt(pending, receipt, true));
            });
      } catch (RuntimeException failure) {
        if (!ActionTransactions.isMySqlContention(failure)) {
          throw classifyDependency(failure);
        }
        if (attempt == transactions.maximumObservationAttempts() || !transactions.pause(attempt)) {
          return Optional.empty();
        }
      }
    }
    return Optional.empty();
  }

  private ActionReceiptView validateReceipt(
      PendingActionRecord pending, ActionReceiptRecord receipt, boolean replayed) {
    String receiptKey = ActionCanonical.hash("ACTION_RECEIPT", pending.actionIdempotencyKey());
    if (!receipt.pendingActionId().equals(pending.pendingActionId())
        || !receipt.receiptIdempotencyKey().equals(receiptKey)
        || !receipt.actionType().equals(pending.actionType())
        || !receipt.argumentHash().equals(pending.argumentHash())
        || !receipt.userSubject().equals(pending.userSubject())
        || !receipt.supportSessionId().equals(pending.supportSessionId())
        || !receipt.traceId().equals(pending.traceId())
        || !receipt.turnId().equals(pending.turnId())
        || !Objects.equals(receipt.sandboxId(), pending.sandboxId())
        || !receipt.orderId().equals(pending.orderId())
        || !receipt.paymentAttemptId().equals(pending.paymentAttemptId())
        || receipt.resultingResourceVersion() != 1
        || !"REQUESTED".equals(receipt.resultState())
        || receipt.amountMinor() != pending.amountMinor()
        || !receipt.currency().equals(pending.currency())
        || receipt.outboxCreatedAt() == null
        || receipt.outboxCreatedAt().isAfter(receipt.committedAt())
        || pending.consumedAt() == null
        || !pending.consumedAt().equals(receipt.committedAt())
        || !uuid(receipt.receiptId())) {
      throw integrityFailure("ActionReceipt commitment conflicts with PendingAction truth");
    }
    String expectedHash =
        resultHash(
            receipt.receiptId(),
            receiptKey,
            pending,
            receipt.refundId(),
            receipt.outboxEventId(),
            receipt.outboxCreatedAt(),
            receipt.committedAt());
    if (!expectedHash.equals(receipt.resultHash())) {
      throw integrityFailure("ActionReceipt result commitment is corrupted");
    }
    RefundService.ActionReplayTruth replay =
        refundBoundary(
            () ->
                refunds.validateActionReplayInCurrentTransaction(
                    pending.userSubject(),
                    pending.orderId(),
                    refundIdempotencyKey(pending.pendingActionId()),
                    new RefundRequest(pending.amountMinor(), pending.currency(), null),
                    receipt.refundId(),
                    pending.sandboxId()));
    requireTarget(pending, replay.target());
    RefundResult refund = replay.refund();
    if (!refund.refundId().equals(receipt.refundId())
        || !refund.orderId().equals(receipt.orderId())
        || !refund.paymentAttemptId().equals(receipt.paymentAttemptId())
        || refund.requestedAmountMinor() != receipt.amountMinor()
        || !refund.currency().equals(receipt.currency())) {
      throw integrityFailure("ActionReceipt conflicts with refund truth");
    }
    RefundOutboxRecord outbox =
        requireRefundOutboxClosure(
            actions.findRefundOutboxByAggregateForUpdate(refund.refundId()),
            refund,
            receipt.outboxEventId());
    if (!receipt.outboxCreatedAt().equals(outbox.createdAt())) {
      throw integrityFailure("ActionReceipt Outbox identity is corrupted");
    }
    return receiptView(receipt, replayed);
  }

  private void requirePreparedCommitment(
      PendingActionRecord pending,
      ValidatedContext context,
      ValidatedCommand command,
      String argumentHash,
      String actionKey) {
    requirePendingIntegrity(pending);
    if (!pending.actionIdempotencyKey().equals(actionKey)
        || !pending.argumentHash().equals(argumentHash)
        || !pending.userSubject().equals(context.userSubject())
        || !pending.supportSessionId().equals(context.supportSessionId())
        || !pending.traceId().equals(context.traceId())
        || !pending.turnId().equals(context.turnId())
        || !pending.requiredScope().equals(context.requiredScope())
        || !Objects.equals(pending.sandboxId(), context.sandboxId())
        || !pending.orderId().equals(command.orderId())
        || pending.amountMinor() != command.amountMinor()
        || !pending.currency().equals(command.currency())) {
      throw conflict("PendingAction idempotency intent conflicts");
    }
  }

  private void requirePendingIntegrity(PendingActionRecord pending) {
    if (!uuid(pending.pendingActionId())
        || !REFUND_REQUEST.equals(pending.actionType())
        || !bounded(pending.userSubject(), 128)
        || !bounded(pending.supportSessionId(), 64)
        || !BOUNDED_ID.matcher(nullToEmpty(pending.traceId())).matches()
        || !uuid(pending.turnId())
        || !properties.requiredScope().equals(pending.requiredScope())
        || (pending.sandboxId() != null && !BOUNDED_ID.matcher(pending.sandboxId()).matches())
        || !uuid(pending.orderId())
        || !("STANDARD".equals(pending.orderKind()) || "SECKILL".equals(pending.orderKind()))
        || !uuid(pending.paymentAttemptId())
        || pending.targetOrderVersion() < 1
        || pending.amountMinor() < 1
        || pending.currency() == null
        || !CURRENCY.matcher(pending.currency()).matches()
        || pending.createdAt() == null
        || pending.expiresAt() == null
        || !pending.createdAt().plus(properties.pendingTtl()).equals(pending.expiresAt())
        || (!"PREPARED".equals(pending.state()) && !"CONSUMED".equals(pending.state()))
        || ("PREPARED".equals(pending.state())
            && (pending.stateVersion() != 1 || pending.consumedAt() != null))
        || ("CONSUMED".equals(pending.state())
            && (pending.stateVersion() != 2 || pending.consumedAt() == null))) {
      throw integrityFailure("PendingAction durable commitment is corrupted");
    }
    ValidatedCommand storedCommand =
        new ValidatedCommand(pending.orderId(), pending.amountMinor(), pending.currency());
    String storedArgumentHash = argumentHash(storedCommand);
    String storedActionKey =
        ActionCanonical.hash(
            pending.userSubject(),
            pending.supportSessionId(),
            pending.turnId(),
            REFUND_REQUEST,
            storedArgumentHash);
    if (!storedArgumentHash.equals(pending.argumentHash())
        || !storedActionKey.equals(pending.actionIdempotencyKey())
        || !pendingHash(pending).equals(pending.pendingHash())) {
      throw integrityFailure("PendingAction canonical commitment is corrupted");
    }
  }

  private static void requirePreparedState(PendingActionRecord pending) {
    if (!"PREPARED".equals(pending.state())
        || pending.stateVersion() != 1
        || pending.consumedAt() != null) {
      throw integrityFailure("PendingAction state is malformed");
    }
  }

  private static void requireTarget(
      PendingActionRecord pending, RefundService.ActionTarget target) {
    if (!pending.orderKind().equals(target.order().orderKind())
        || !pending.paymentAttemptId().equals(target.attempt().attemptId())) {
      throw integrityFailure("PendingAction target identity is corrupted");
    }
    if (pending.targetOrderVersion() != target.order().stateVersion()) {
      throw conflict("PendingAction target resource version is stale");
    }
  }

  private static void requireConfirmVisibility(
      PendingActionRecord pending, ValidatedContext context) {
    if (!pending.userSubject().equals(context.userSubject())
        || !pending.supportSessionId().equals(context.supportSessionId())
        || !Objects.equals(pending.sandboxId(), context.sandboxId())) {
      throw concealed("PendingAction is missing or not owned");
    }
  }

  private static void requireConfirmIntent(PendingActionRecord pending, ValidatedContext context) {
    if (!pending.traceId().equals(context.traceId())
        || !pending.turnId().equals(context.turnId())
        || !pending.requiredScope().equals(context.requiredScope())) {
      throw conflict("PendingAction confirmation binding conflicts");
    }
  }

  private static RefundOutboxRecord requireRefundOutboxClosure(
      List<RefundOutboxRecord> rows, RefundResult refund, String requestedEventId) {
    Map<Long, String> expected =
        switch (refund.state()) {
          case "REQUESTED" -> Map.of(1L, "REFUND_REQUESTED");
          case "PROCESSING" -> Map.of(1L, "REFUND_REQUESTED", 2L, "REFUND_PROCESSING");
          case "SUCCEEDED" ->
              Map.of(1L, "REFUND_REQUESTED", 2L, "REFUND_PROCESSING", 3L, "REFUND_SUCCEEDED");
          case "FAILED" ->
              Map.of(1L, "REFUND_REQUESTED", 2L, "REFUND_PROCESSING", 3L, "REFUND_FAILED");
          default -> throw integrityFailure("Action refund lifecycle is malformed");
        };
    if (refund.stateVersion() != expected.size() || rows.size() != expected.size()) {
      throw integrityFailure("Action refund Outbox closure cardinality is inconsistent");
    }
    RefundOutboxRecord requested = null;
    for (RefundOutboxRecord row : rows) {
      String eventType = expected.get(row.aggregateVersion());
      if (eventType == null || !eventType.equals(row.eventType())) {
        throw integrityFailure("Action refund Outbox lifecycle is inconsistent");
      }
      requireOutbox(row, refund);
      if (row.aggregateVersion() == 1) {
        requested = row;
      }
    }
    if (requested == null || !requested.eventId().equals(requestedEventId)) {
      throw integrityFailure("ActionReceipt refund request Outbox identity is corrupted");
    }
    return requested;
  }

  private static void requireOutbox(RefundOutboxRecord outbox, RefundResult refund) {
    var payload = outbox.payload();
    if (!"REFUND".equals(outbox.aggregateType())
        || !refund.refundId().equals(outbox.aggregateId())
        || payload == null
        || !payload.isObject()
        || payload.size() != 7
        || !outbox.eventId().equals(text(payload, "eventId"))
        || !refund.refundId().equals(text(payload, "refundId"))
        || !refund.orderId().equals(text(payload, "orderId"))
        || !refund.paymentAttemptId().equals(text(payload, "paymentAttemptId"))
        || refund.requestedAmountMinor() != number(payload, "amountMinor")
        || !refund.currency().equals(text(payload, "currency"))
        || outbox.aggregateVersion() != number(payload, "stateVersion")
        || outbox.createdAt() == null
        || (!"PENDING".equals(outbox.publicationState())
            && !"PUBLISHED".equals(outbox.publicationState()))
        || ("PENDING".equals(outbox.publicationState()) && outbox.publishedAt() != null)
        || ("PUBLISHED".equals(outbox.publicationState())
            && (outbox.publishAttempts() < 1
                || outbox.publishedAt() == null
                || outbox.publishedAt().isBefore(outbox.createdAt())))) {
      throw integrityFailure("Refund request Outbox commitment is corrupted");
    }
  }

  private void requireActiveSandbox(String sandboxId) {
    if (sandboxId == null) {
      return;
    }
    EvaluationSandboxAccess access = sandboxAccess.getIfAvailable();
    if (access == null) {
      throw new EvaluationSandboxException(
          503,
          EvaluationRejectionReason.TOOL_EVALUATION_COMPONENT_UNAVAILABLE,
          "Evaluation sandbox is unavailable");
    }
    access.requireActive(sandboxId);
  }

  private ValidatedContext validateContext(ActionRequestContext context) {
    if (context == null
        || !bounded(context.userSubject(), 128)
        || !bounded(context.supportSessionId(), 64)
        || !BOUNDED_ID.matcher(nullToEmpty(context.traceId())).matches()
        || !uuid(context.turnId())
        || !properties.requiredScope().equals(context.requiredScope())
        || (context.sandboxId() != null && !BOUNDED_ID.matcher(context.sandboxId()).matches())) {
      throw validation("Action request context is invalid");
    }
    return new ValidatedContext(
        context.userSubject().strip(),
        context.supportSessionId().strip(),
        context.traceId(),
        context.turnId(),
        context.sandboxId(),
        context.requiredScope());
  }

  private static ValidatedCommand validateCommand(PrepareActionCommand command) {
    if (command == null
        || !REFUND_REQUEST.equals(command.actionType())
        || !uuid(command.orderId())
        || command.amountMinor() == null
        || command.amountMinor() < 1
        || command.currency() == null
        || !CURRENCY.matcher(command.currency()).matches()) {
      throw validation("Action request is invalid");
    }
    return new ValidatedCommand(command.orderId(), command.amountMinor(), command.currency());
  }

  private static String argumentHash(ValidatedCommand command) {
    return ActionCanonical.hash(
        REFUND_REQUEST,
        command.orderId(),
        Long.toString(command.amountMinor()),
        command.currency());
  }

  private static String actionKey(ValidatedContext context, String argumentHash) {
    return ActionCanonical.hash(
        context.userSubject(),
        context.supportSessionId(),
        context.turnId(),
        REFUND_REQUEST,
        argumentHash);
  }

  private static RefundRequest refundRequest(ValidatedCommand command) {
    return new RefundRequest(command.amountMinor(), command.currency(), null);
  }

  private static PendingActionView pendingView(PendingActionRecord pending, boolean replayed) {
    return new PendingActionView(
        pending.pendingActionId(),
        pending.actionType(),
        pending.orderId(),
        pending.amountMinor(),
        pending.currency(),
        pending.state(),
        pending.expiresAt(),
        replayed);
  }

  private static ActionReceiptView receiptView(ActionReceiptRecord receipt, boolean replayed) {
    return new ActionReceiptView(
        receipt.receiptId(),
        receipt.pendingActionId(),
        receipt.actionType(),
        receipt.resultState(),
        receipt.orderId(),
        receipt.refundId(),
        receipt.resultingResourceVersion(),
        receipt.amountMinor(),
        receipt.currency(),
        receipt.committedAt(),
        replayed);
  }

  private static String resultHash(
      String receiptId,
      String receiptKey,
      PendingActionRecord pending,
      String refundId,
      String outboxEventId,
      Instant outboxCreatedAt,
      Instant committedAt) {
    return ActionCanonical.hash(
        receiptId,
        receiptKey,
        pending.pendingActionId(),
        pending.actionIdempotencyKey(),
        pending.actionType(),
        pending.argumentHash(),
        pending.userSubject(),
        pending.supportSessionId(),
        pending.traceId(),
        pending.turnId(),
        nullToEmpty(pending.sandboxId()),
        pending.orderId(),
        pending.orderKind(),
        pending.paymentAttemptId(),
        Long.toString(pending.targetOrderVersion()),
        pending.requiredScope(),
        pending.expiresAt().toString(),
        pending.createdAt().toString(),
        refundId,
        "1",
        "REQUESTED",
        Long.toString(pending.amountMinor()),
        pending.currency(),
        outboxEventId,
        outboxCreatedAt.toString(),
        committedAt.toString());
  }

  private static String pendingHash(
      String pendingActionId,
      String actionKey,
      String argumentHash,
      ValidatedContext context,
      ValidatedCommand command,
      RefundService.ActionTarget target,
      Instant expiresAt,
      Instant createdAt) {
    return ActionCanonical.hash(
        pendingActionId,
        actionKey,
        REFUND_REQUEST,
        argumentHash,
        context.userSubject(),
        context.supportSessionId(),
        context.traceId(),
        context.turnId(),
        context.requiredScope(),
        nullToEmpty(context.sandboxId()),
        command.orderId(),
        target.order().orderKind(),
        target.attempt().attemptId(),
        Long.toString(target.order().stateVersion()),
        Long.toString(command.amountMinor()),
        command.currency(),
        expiresAt.toString(),
        createdAt.toString());
  }

  private static String pendingHash(PendingActionRecord pending) {
    return ActionCanonical.hash(
        pending.pendingActionId(),
        pending.actionIdempotencyKey(),
        pending.actionType(),
        pending.argumentHash(),
        pending.userSubject(),
        pending.supportSessionId(),
        pending.traceId(),
        pending.turnId(),
        pending.requiredScope(),
        nullToEmpty(pending.sandboxId()),
        pending.orderId(),
        pending.orderKind(),
        pending.paymentAttemptId(),
        Long.toString(pending.targetOrderVersion()),
        Long.toString(pending.amountMinor()),
        pending.currency(),
        pending.expiresAt().toString(),
        pending.createdAt().toString());
  }

  private static String refundIdempotencyKey(String pendingActionId) {
    return "action:" + pendingActionId;
  }

  private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
    var value = node.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  private static long number(com.fasterxml.jackson.databind.JsonNode node, String field) {
    var value = node.get(field);
    return value != null && value.isIntegralNumber() ? value.longValue() : Long.MIN_VALUE;
  }

  private static <T> T refundBoundary(Supplier<T> work) {
    try {
      return work.get();
    } catch (RefundException exception) {
      if (exception.reason() == RefundRejectionReason.REFUND_CONCEALED_NOT_FOUND
          || exception.status() == 404) {
        throw concealed("Action refund target is missing or not owned");
      }
      if (exception.reason() == RefundRejectionReason.REFUND_DURABLE_TRUTH_INCONSISTENT) {
        throw integrityFailure("Action refund durable truth is inconsistent");
      }
      if (exception.reason() == RefundRejectionReason.REFUND_IDEMPOTENCY_INTENT_CONFLICT
          || exception.reason() == RefundRejectionReason.REFUND_BUSINESS_CONFLICT
          || exception.status() == 409) {
        throw conflict("Action refund request conflicts with authoritative truth");
      }
      if (exception.reason() == RefundRejectionReason.REFUND_CONCURRENCY_OBSERVATION_INDETERMINATE
          || exception.status() == 429) {
        throw indeterminate("Action refund truth remains indeterminate");
      }
      if (exception.reason() == RefundRejectionReason.REFUND_DEPENDENCY_UNAVAILABLE
          || exception.status() == 503) {
        throw unavailable("Action refund persistence is unavailable");
      }
      if (exception.status() == 400) {
        throw validation("Action refund request is invalid");
      }
      throw exception;
    } catch (IllegalStateException exception) {
      throw new ActionIntegrityException("Action durable truth is inconsistent", exception);
    }
  }

  private static RuntimeException classifyDependency(RuntimeException failure) {
    if (failure instanceof ActionException
        || failure instanceof RefundException
        || failure instanceof EvaluationSandboxException) {
      return failure;
    }
    if (failure instanceof ActionIntegrityException) {
      return integrityFailure("Action durable truth is inconsistent");
    }
    if (failure instanceof DataAccessResourceFailureException
        || failure instanceof CannotCreateTransactionException) {
      return unavailable("Action database is unavailable");
    }
    return failure;
  }

  private static boolean bounded(String value, int maximum) {
    return value != null && !value.isBlank() && value.length() <= maximum;
  }

  private static boolean uuid(String value) {
    try {
      return value != null && UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static void requireUuid(String value, String message) {
    if (!uuid(value)) {
      throw validation(message);
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static Instant latest(Instant left, Instant right) {
    return left.isBefore(right) ? right : left;
  }

  private static ActionException validation(String message) {
    return new ActionException(
        400, "VALIDATION", ActionRejectionReason.ACTION_VALIDATION_REJECTED, message);
  }

  private static ActionException concealed(String message) {
    return new ActionException(
        404, "NOT_FOUND", ActionRejectionReason.ACTION_CONCEALED_NOT_FOUND, message);
  }

  private static ActionException conflict(String message) {
    return new ActionException(
        409, "CONFLICT", ActionRejectionReason.ACTION_IDEMPOTENCY_INTENT_CONFLICT, message);
  }

  private static ActionException integrityFailure(String message) {
    return new ActionException(
        409,
        "INCONSISTENT_DURABLE_STATE",
        ActionRejectionReason.ACTION_DURABLE_TRUTH_INCONSISTENT,
        message);
  }

  private static ActionException indeterminate(String message) {
    return new ActionException(
        429,
        "INDETERMINATE",
        ActionRejectionReason.ACTION_CONCURRENCY_OBSERVATION_INDETERMINATE,
        message);
  }

  private static ActionException unavailable(String message) {
    return new ActionException(
        503,
        "DEPENDENCY_UNAVAILABLE",
        ActionRejectionReason.ACTION_DEPENDENCY_UNAVAILABLE,
        message);
  }

  private record ValidatedContext(
      String userSubject,
      String supportSessionId,
      String traceId,
      String turnId,
      String sandboxId,
      String requiredScope) {}

  private record ValidatedCommand(String orderId, long amountMinor, String currency) {}
}
