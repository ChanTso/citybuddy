package io.citybuddy.commerce.action;

import io.citybuddy.commerce.action.ActionRepository.ActionIntegrityException;
import io.citybuddy.commerce.action.ActionRepository.ActionReceiptRecord;
import io.citybuddy.commerce.action.ActionRepository.PendingActionRecord;
import io.citybuddy.commerce.action.ActionRepository.RefundOutboxRecord;
import io.citybuddy.commerce.evaluation.EvaluationRejectionReason;
import io.citybuddy.commerce.evaluation.EvaluationSandboxAccess;
import io.citybuddy.commerce.evaluation.EvaluationSandboxException;
import io.citybuddy.commerce.refund.RefundException;
import io.citybuddy.commerce.refund.RefundRequest;
import io.citybuddy.commerce.refund.RefundResult;
import io.citybuddy.commerce.refund.RefundService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

public final class ActionService {
  static final String REFUND_REQUEST = "REFUND_REQUEST";
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern BOUNDED_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
  private static final int OBSERVATION_ATTEMPTS = 3;
  private static final long OBSERVATION_BACKOFF_MILLIS = 25;

  private final ActionRepository actions;
  private final RefundService refunds;
  private final TransactionTemplate transactions;
  private final ActionProperties properties;
  private final Clock clock;
  private final ObjectProvider<EvaluationSandboxAccess> sandboxAccess;

  public ActionService(
      ActionRepository actions,
      RefundService refunds,
      TransactionTemplate transactions,
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
    String argumentHash =
        ActionCanonical.hash(
            REFUND_REQUEST,
            validCommand.orderId(),
            Long.toString(validCommand.amountMinor()),
            validCommand.currency());
    String actionKey =
        ActionCanonical.hash(
            validContext.userSubject(),
            validContext.supportSessionId(),
            validContext.turnId(),
            REFUND_REQUEST,
            argumentHash);

    for (int attempt = 1; attempt <= properties.maximumConcurrencyAttempts(); attempt++) {
      try {
        PendingActionView result =
            execute(
                () -> prepareOnce(validContext, validCommand, argumentHash, actionKey),
                "PendingAction prepare transaction returned no result");
        return result;
      } catch (DuplicateKeyException | PessimisticLockingFailureException exception) {
        PendingObservation observation =
            observePending(validContext, validCommand, argumentHash, actionKey);
        if (observation.record() != null) {
          return pendingView(observation.record(), true);
        }
        if (attempt == properties.maximumConcurrencyAttempts()) {
          throw retryable("PendingAction prepare remains indeterminate");
        }
      } catch (DataAccessResourceFailureException | CannotCreateTransactionException exception) {
        throw unavailable("Action database is unavailable");
      }
    }
    throw new IllegalStateException("Unreachable PendingAction retry state");
  }

  public ActionReceiptView confirm(ActionRequestContext context, String pendingActionId) {
    ValidatedContext validContext = validateContext(context);
    requireUuid(pendingActionId, "PendingAction id is invalid");
    for (int attempt = 1; attempt <= properties.maximumConcurrencyAttempts(); attempt++) {
      try {
        return execute(
            () -> confirmOnce(validContext, pendingActionId),
            "Action confirmation transaction returned no result");
      } catch (DuplicateKeyException | PessimisticLockingFailureException exception) {
        ReceiptObservation observation = observeReceipt(validContext, pendingActionId);
        if (observation.receipt() != null) {
          return observation.receipt();
        }
        if (attempt == properties.maximumConcurrencyAttempts()) {
          throw retryable("Action confirmation remains indeterminate");
        }
      } catch (DataAccessResourceFailureException | CannotCreateTransactionException exception) {
        throw unavailable("Action database is unavailable");
      }
    }
    throw new IllegalStateException("Unreachable ActionReceipt retry state");
  }

  private PendingActionView prepareOnce(
      ValidatedContext context, ValidatedCommand command, String argumentHash, String actionKey) {
    PendingActionRecord existing =
        actions
            .findPendingByTurnForUpdate(
                context.userSubject(), context.supportSessionId(), context.turnId())
            .orElse(null);
    if (existing != null) {
      requirePreparedCommitment(existing, context, command, argumentHash, actionKey);
      ActionReceiptRecord receipt =
          actions.findReceiptByPending(existing.pendingActionId()).orElse(null);
      if ("CONSUMED".equals(existing.state())) {
        if (receipt == null) {
          throw integrityFailure("Consumed PendingAction has no ActionReceipt");
        }
        validateReceipt(existing, receipt, true);
      } else if (receipt != null) {
        throw integrityFailure("Prepared PendingAction already has an ActionReceipt");
      } else {
        requireActiveSandbox(context.sandboxId());
        RefundService.ActionTarget target =
            integrity(
                () ->
                    refunds.prepareActionInCurrentTransaction(
                        context.userSubject(),
                        command.orderId(),
                        new RefundRequest(command.amountMinor(), command.currency(), null),
                        context.sandboxId()));
        requireTarget(existing, target);
      }
      return pendingView(existing, true);
    }
    requireActiveSandbox(context.sandboxId());
    RefundService.ActionTarget target =
        integrity(
            () ->
                refunds.prepareActionInCurrentTransaction(
                    context.userSubject(),
                    command.orderId(),
                    new RefundRequest(command.amountMinor(), command.currency(), null),
                    context.sandboxId()));
    Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    PendingActionRecord created =
        new PendingActionRecord(
            UUID.randomUUID().toString(),
            actionKey,
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
            createdAt.plus(properties.pendingTtl()),
            null,
            createdAt);
    actions.insertPending(created);
    return pendingView(created, false);
  }

  private ActionReceiptView confirmOnce(ValidatedContext context, String pendingActionId) {
    PendingActionRecord pending =
        actions
            .findPendingByIdForUpdate(pendingActionId)
            .orElseGet(
                () ->
                    missingPendingOrConflict(
                        context.userSubject(), context.supportSessionId(), context.turnId()));
    requireConfirmBinding(pending, context);
    ValidatedCommand command =
        new ValidatedCommand(pending.orderId(), pending.amountMinor(), pending.currency());
    String argumentHash =
        ActionCanonical.hash(
            REFUND_REQUEST,
            command.orderId(),
            Long.toString(command.amountMinor()),
            command.currency());
    String actionKey =
        ActionCanonical.hash(
            context.userSubject(),
            context.supportSessionId(),
            context.turnId(),
            REFUND_REQUEST,
            argumentHash);
    requirePreparedCommitment(pending, context, command, argumentHash, actionKey);
    ActionReceiptRecord existing = actions.findReceiptByPending(pendingActionId).orElse(null);
    if ("CONSUMED".equals(pending.state())) {
      if (existing == null) {
        throw integrityFailure("Consumed PendingAction has no ActionReceipt");
      }
      return validateReceipt(pending, existing, true);
    }
    if (existing != null) {
      throw integrityFailure("Prepared PendingAction already has an ActionReceipt");
    }
    if (!"PREPARED".equals(pending.state())
        || pending.stateVersion() != 1
        || pending.consumedAt() != null) {
      throw integrityFailure("PendingAction state is malformed");
    }
    if (!pending.createdAt().plus(properties.pendingTtl()).equals(pending.expiresAt())) {
      throw integrityFailure("PendingAction expiry commitment is malformed");
    }
    if (!clock.instant().isBefore(pending.expiresAt())) {
      throw conflict("PendingAction is expired");
    }
    requireActiveSandbox(context.sandboxId());
    RefundService.ActionTarget target =
        integrity(
            () ->
                refunds.prepareActionInCurrentTransaction(
                    context.userSubject(),
                    command.orderId(),
                    new RefundRequest(command.amountMinor(), command.currency(), null),
                    context.sandboxId()));
    requireTarget(pending, target);

    String refundKey = refundIdempotencyKey(pending.pendingActionId());
    RefundService.ActionMutation mutation =
        integrity(
            () ->
                refunds.requestActionInCurrentTransaction(
                    context.userSubject(),
                    pending.orderId(),
                    refundKey,
                    new RefundRequest(pending.amountMinor(), pending.currency(), null),
                    context.sandboxId()));
    RefundResult refund = mutation.refund();
    if (refund.replayed()) {
      throw integrityFailure("Prepared PendingAction points to an existing refund result");
    }
    RefundOutboxRecord outbox =
        actions
            .findRefundRequestedOutbox(mutation.outbox().eventId())
            .orElseThrow(() -> integrityFailure("Action refund Outbox truth is missing"));
    requireOutbox(outbox, refund);

    Instant committedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    actions.consume(pending, committedAt);
    String receiptId = UUID.randomUUID().toString();
    String receiptKey = ActionCanonical.hash("ACTION_RECEIPT", pending.actionIdempotencyKey());
    String resultHash =
        resultHash(
            receiptId,
            pending,
            refund.refundId(),
            outbox.eventId(),
            outbox.createdAt(),
            committedAt,
            receiptKey);
    ActionReceiptRecord receipt =
        new ActionReceiptRecord(
            receiptId,
            receiptKey,
            pending.pendingActionId(),
            pending.actionType(),
            pending.argumentHash(),
            resultHash,
            pending.userSubject(),
            pending.supportSessionId(),
            pending.traceId(),
            pending.turnId(),
            pending.sandboxId(),
            pending.orderId(),
            pending.paymentAttemptId(),
            refund.refundId(),
            refund.stateVersion(),
            refund.state(),
            refund.requestedAmountMinor(),
            refund.currency(),
            outbox.eventId(),
            outbox.createdAt(),
            committedAt);
    actions.insertReceipt(receipt);
    return receiptView(receipt, false);
  }

  private ReceiptObservation observeReceipt(ValidatedContext context, String pendingActionId) {
    for (int attempt = 1; attempt <= OBSERVATION_ATTEMPTS; attempt++) {
      try {
        ActionReceiptView receipt =
            execute(
                () -> {
                  PendingActionRecord pending =
                      actions.findPendingByIdForUpdate(pendingActionId).orElse(null);
                  if (pending == null) {
                    return null;
                  }
                  requireConfirmBinding(pending, context);
                  ActionReceiptRecord found =
                      actions.findReceiptByPending(pendingActionId).orElse(null);
                  return found == null ? null : validateReceipt(pending, found, true);
                },
                "Action receipt observation returned no transaction result",
                true);
        if (receipt != null) {
          return new ReceiptObservation(receipt, false);
        }
        return new ReceiptObservation(null, false);
      } catch (PessimisticLockingFailureException
          | QueryTimeoutException
          | TransactionTimedOutException exception) {
        if (attempt == OBSERVATION_ATTEMPTS || !pause(attempt)) {
          return new ReceiptObservation(null, true);
        }
      } catch (DataAccessResourceFailureException | CannotCreateTransactionException exception) {
        throw unavailable("Action database is unavailable");
      }
    }
    return new ReceiptObservation(null, true);
  }

  private PendingObservation observePending(
      ValidatedContext context, ValidatedCommand command, String argumentHash, String actionKey) {
    for (int attempt = 1; attempt <= OBSERVATION_ATTEMPTS; attempt++) {
      try {
        PendingActionRecord pending =
            execute(
                () ->
                    actions
                        .findPendingByTurnForUpdate(
                            context.userSubject(), context.supportSessionId(), context.turnId())
                        .orElse(null),
                "PendingAction observation returned no transaction result",
                true);
        if (pending != null) {
          requirePreparedCommitment(pending, context, command, argumentHash, actionKey);
        }
        return new PendingObservation(pending, false);
      } catch (PessimisticLockingFailureException
          | QueryTimeoutException
          | TransactionTimedOutException exception) {
        if (attempt == OBSERVATION_ATTEMPTS || !pause(attempt)) {
          return new PendingObservation(null, true);
        }
      } catch (DataAccessResourceFailureException | CannotCreateTransactionException exception) {
        throw unavailable("Action database is unavailable");
      }
    }
    return new PendingObservation(null, true);
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
        || !pending.consumedAt().equals(receipt.committedAt())) {
      throw integrityFailure("ActionReceipt commitment conflicts with PendingAction truth");
    }
    if (!uuid(receipt.receiptId())) {
      throw integrityFailure("ActionReceipt identity is malformed");
    }
    String expectedResultHash =
        resultHash(
            receipt.receiptId(),
            pending,
            receipt.refundId(),
            receipt.outboxEventId(),
            receipt.outboxCreatedAt(),
            receipt.committedAt(),
            receiptKey);
    if (!expectedResultHash.equals(receipt.resultHash())) {
      throw integrityFailure("ActionReceipt result commitment is corrupted");
    }
    RefundService.ActionReplayTruth replay =
        integrity(
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
        || refund.requestedAmountMinor() != receipt.amountMinor()
        || !refund.currency().equals(receipt.currency())) {
      throw integrityFailure("ActionReceipt conflicts with refund truth");
    }
    RefundOutboxRecord outbox =
        actions
            .findRefundRequestedOutbox(receipt.outboxEventId())
            .orElseThrow(() -> integrityFailure("ActionReceipt refund Outbox truth is missing"));
    requireOutbox(outbox, refund);
    if (!receipt.outboxEventId().equals(outbox.eventId())
        || !receipt.outboxCreatedAt().equals(outbox.createdAt())) {
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
    if (!uuid(pending.pendingActionId())
        || !pending.actionIdempotencyKey().equals(actionKey)
        || !REFUND_REQUEST.equals(pending.actionType())
        || !pending.argumentHash().equals(argumentHash)
        || !pending.userSubject().equals(context.userSubject())
        || !pending.supportSessionId().equals(context.supportSessionId())
        || !pending.traceId().equals(context.traceId())
        || !pending.turnId().equals(context.turnId())
        || !pending.requiredScope().equals(context.requiredScope())
        || !Objects.equals(pending.sandboxId(), context.sandboxId())
        || !pending.orderId().equals(command.orderId())
        || !("STANDARD".equals(pending.orderKind()) || "SECKILL".equals(pending.orderKind()))
        || !uuid(pending.paymentAttemptId())
        || pending.targetOrderVersion() < 1
        || pending.amountMinor() != command.amountMinor()
        || !pending.currency().equals(command.currency())
        || pending.createdAt() == null
        || pending.expiresAt() == null
        || !pending.createdAt().plus(properties.pendingTtl()).equals(pending.expiresAt())
        || (!"PREPARED".equals(pending.state()) && !"CONSUMED".equals(pending.state()))
        || ("PREPARED".equals(pending.state())
            && (pending.stateVersion() != 1 || pending.consumedAt() != null))
        || ("CONSUMED".equals(pending.state())
            && (pending.stateVersion() != 2 || pending.consumedAt() == null))) {
      throw conflict("PendingAction idempotency intent conflicts");
    }
  }

  private PendingActionRecord missingPendingOrConflict(
      String userSubject, String supportSessionId, String turnId) {
    PendingActionRecord correlated =
        actions.findPendingByTurnForUpdate(userSubject, supportSessionId, turnId).orElse(null);
    if (correlated != null) {
      throw integrityFailure("PendingAction identity conflicts with its turn truth");
    }
    throw notFound("PendingAction is missing or not owned");
  }

  private static void requireTarget(
      PendingActionRecord pending, RefundService.ActionTarget target) {
    if (!pending.orderKind().equals(target.order().orderKind())
        || !pending.paymentAttemptId().equals(target.attempt().attemptId())
        || pending.targetOrderVersion() != target.order().stateVersion()) {
      throw conflict("PendingAction target resource version is stale");
    }
  }

  private void requireConfirmBinding(PendingActionRecord pending, ValidatedContext context) {
    if (!pending.userSubject().equals(context.userSubject())
        || !pending.supportSessionId().equals(context.supportSessionId())
        || !Objects.equals(pending.sandboxId(), context.sandboxId())) {
      throw notFound("PendingAction is missing or not owned");
    }
    if (!pending.traceId().equals(context.traceId())
        || !pending.turnId().equals(context.turnId())
        || !pending.requiredScope().equals(context.requiredScope())) {
      throw conflict("PendingAction confirmation binding conflicts");
    }
  }

  private static void requireOutbox(RefundOutboxRecord outbox, RefundResult refund) {
    var payload = outbox.payload();
    if (!"REFUND".equals(outbox.aggregateType())
        || !refund.refundId().equals(outbox.aggregateId())
        || outbox.aggregateVersion() != 1
        || !"REFUND_REQUESTED".equals(outbox.eventType())
        || !payload.isObject()
        || payload.size() != 8
        || !outbox.eventId().equals(text(payload, "eventId"))
        || !refund.refundId().equals(text(payload, "refundId"))
        || !refund.orderId().equals(text(payload, "orderId"))
        || !refund.paymentAttemptId().equals(text(payload, "paymentAttemptId"))
        || refund.requestedAmountMinor() != number(payload, "amountMinor")
        || !refund.currency().equals(text(payload, "currency"))
        || outbox.aggregateVersion() != number(payload, "stateVersion")
        || outbox.createdAt() == null
        || !outbox.createdAt().toString().equals(text(payload, "occurredAt"))
        || (!"PENDING".equals(outbox.publicationState())
            && !"PUBLISHED".equals(outbox.publicationState()))
        || ("PENDING".equals(outbox.publicationState()) && outbox.publishedAt() != null)
        || ("PUBLISHED".equals(outbox.publicationState())
            && (outbox.publishAttempts() < 1
                || outbox.publishedAt() == null
                || outbox.publishedAt().isBefore(outbox.createdAt())))) {
      throw new ActionIntegrityException("Refund request Outbox commitment is corrupted");
    }
  }

  private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
    var value = node.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  private static long number(com.fasterxml.jackson.databind.JsonNode node, String field) {
    var value = node.get(field);
    return value != null && value.isIntegralNumber() ? value.longValue() : Long.MIN_VALUE;
  }

  private static String resultHash(
      String receiptId,
      PendingActionRecord pending,
      String refundId,
      String outboxEventId,
      Instant outboxCreatedAt,
      Instant committedAt,
      String receiptKey) {
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

  private static String refundIdempotencyKey(String pendingActionId) {
    return "action:" + pendingActionId;
  }

  private <T> T execute(Supplier<T> work, String nullMessage) {
    return execute(work, nullMessage, false);
  }

  private <T> T execute(Supplier<T> work, String nullMessage, boolean allowNull) {
    T result =
        transactions.execute(
            status -> actions.withLockWaitTimeout(properties.lockWaitTimeoutSeconds(), work));
    if (result == null && !allowNull) {
      throw new IllegalStateException(nullMessage);
    }
    return result;
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

  private static <T> T integrity(Supplier<T> work) {
    try {
      return work.get();
    } catch (RefundException exception) {
      throw exception;
    } catch (IllegalStateException exception) {
      throw new ActionIntegrityException("Action durable truth is inconsistent", exception);
    }
  }

  private static boolean pause(int attempt) {
    try {
      Thread.sleep(OBSERVATION_BACKOFF_MILLIS * attempt);
      return true;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
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

  private static ActionException validation(String message) {
    return new ActionException(400, "VALIDATION", message);
  }

  private static ActionException notFound(String message) {
    return new ActionException(404, "NOT_FOUND", message);
  }

  private static ActionException conflict(String message) {
    return new ActionException(409, "CONFLICT", message);
  }

  private static ActionException integrityFailure(String message) {
    return new ActionException(409, "INCONSISTENT_DURABLE_STATE", message);
  }

  private static ActionException retryable(String message) {
    return new ActionException(429, "INDETERMINATE", message);
  }

  private static ActionException unavailable(String message) {
    return new ActionException(503, "DEPENDENCY_UNAVAILABLE", message);
  }

  private record ValidatedContext(
      String userSubject,
      String supportSessionId,
      String traceId,
      String turnId,
      String sandboxId,
      String requiredScope) {}

  private record ValidatedCommand(String orderId, long amountMinor, String currency) {}

  private record PendingObservation(PendingActionRecord record, boolean indeterminate) {}

  private record ReceiptObservation(ActionReceiptView receipt, boolean indeterminate) {}
}
