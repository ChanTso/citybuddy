package io.citybuddy.commerce.payment;

import io.citybuddy.commerce.evaluation.EvaluationAuditReferenceIdentity;
import io.citybuddy.commerce.evaluation.EvaluationSandboxRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;

public final class MockPaymentService {
  private static final Pattern IDEMPOTENCY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern BOUNDED_CONTEXT =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
  private static final Pattern OPERATION = Pattern.compile("[0-9a-f]{64}");
  private static final int TRUTH_OBSERVATION_ATTEMPTS = 2;
  private static final long TRUTH_OBSERVATION_BACKOFF_MILLIS = 25;

  private final MockPaymentRepository repository;
  private final CommittedPaymentTruthResolver truth;
  private final MockPaymentTransactions transactions;
  private final Clock clock;
  private final EvaluationSandboxRepository sandboxes;

  public MockPaymentService(
      MockPaymentRepository repository, MockPaymentTransactions transactions, Clock clock) {
    this(repository, transactions, clock, null);
  }

  public MockPaymentService(
      MockPaymentRepository repository,
      MockPaymentTransactions transactions,
      Clock clock,
      EvaluationSandboxRepository sandboxes) {
    this.repository = repository;
    this.truth = new CommittedPaymentTruthResolver(repository);
    this.transactions = transactions;
    this.clock = clock;
    this.sandboxes = sandboxes;
  }

  public MockPaymentResult start(
      String userSubject, String orderId, String idempotencyKey, MockPaymentRequest request) {
    return start(userSubject, null, orderId, idempotencyKey, request);
  }

  public MockPaymentResult start(
      String userSubject,
      String sandboxId,
      String orderId,
      String idempotencyKey,
      MockPaymentRequest request) {
    requireText(userSubject, 128, "Validated payment owner is missing");
    requireOptionalSandbox(sandboxId);
    requireUuid(orderId, "Payment order id is invalid");
    requireIdempotency(idempotencyKey, "Payment idempotency key is invalid");
    MockPaymentRequest valid = requireRequest(request);
    String intentHash =
        EvaluationPaymentCommittedFaces.attemptIntentHash(
            orderId, valid.amountMinor(), valid.currency(), sandboxId);
    CommittedPaymentTruthResolver.StartCommandContext context =
        new CommittedPaymentTruthResolver.StartCommandContext(
            userSubject,
            sandboxId,
            orderId,
            idempotencyKey,
            intentHash,
            valid.amountMinor(),
            valid.currency());
    try {
      return executeStart(context);
    } catch (MockPaymentIntegrityException | CommittedPaymentIntegrityException exception) {
      throw conflict(
          MockPaymentRejectionReason.COMMITTED_PAYMENT_TRUTH_INCONSISTENT,
          "Committed payment truth is inconsistent");
    }
  }

  public MockPaymentCallbackResult callback(
      String idempotencyKey, MockPaymentCallbackRequest request) {
    requireIdempotency(idempotencyKey, "Callback idempotency key is invalid");
    MockPaymentCallbackRequest valid = requireCallback(request);
    String intentHash = truth.callbackIntentHash(idempotencyKey, valid);
    try {
      return executeCallback(idempotencyKey, valid, intentHash);
    } catch (MockPaymentIntegrityException | CommittedPaymentIntegrityException exception) {
      throw conflict(
          MockPaymentRejectionReason.COMMITTED_PAYMENT_TRUTH_INCONSISTENT,
          "Committed payment truth is inconsistent");
    }
  }

  private MockPaymentResult startOnce(CommittedPaymentTruthResolver.StartCommandContext context) {
    CommittedPaymentTruthResolver.StartCommandResolution resolution =
        truth.resolveStartCommandLocked(context);
    if (resolution instanceof CommittedPaymentTruthResolver.ConcealedStart) {
      throw notFound(
          MockPaymentRejectionReason.CONCEALED_NOT_FOUND, "Payment order is missing or not owned");
    }
    if (resolution instanceof CommittedPaymentTruthResolver.CommittedReplay committed) {
      return committedStartResult(committed.truth());
    }
    if (resolution instanceof CommittedPaymentTruthResolver.PendingReplay pending) {
      fenceSandbox(context.sandboxId());
      return pendingStartResult(pending.truth(), true);
    }
    CommittedPaymentTruthResolver.CreateEligible eligible =
        (CommittedPaymentTruthResolver.CreateEligible) resolution;
    fenceSandbox(context.sandboxId());
    if (eligible.bindingProof().isPresent()) {
      repository.bindEvaluationOrderOwner(
          eligible.bindingProof().orElseThrow(), context.userSubject());
      resolution = truth.resolveStartCommandLocked(context);
      if (!(resolution instanceof CommittedPaymentTruthResolver.CreateEligible rebound)
          || rebound.bindingProof().isPresent()) {
        throw new CommittedPaymentIntegrityException(
            "Evaluation payment owner binding did not converge");
      }
      eligible = rebound;
    }
    MockPaymentRepository.OrderTruth order = eligible.order();
    MockPaymentRepository.AttemptRecord created =
        MockPaymentRepository.AttemptRecord.pending(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            context.userSubject(),
            order.orderId(),
            order.orderKind(),
            context.sandboxId(),
            context.requestIdempotencyKey(),
            context.intentHash(),
            order.amountMinor(),
            order.currency());
    repository.insertAttempt(created);
    CommittedPaymentTruthResolver.StartCommandResolution createdResolution =
        truth.resolveStartCommandLocked(context);
    if (!(createdResolution instanceof CommittedPaymentTruthResolver.PendingReplay pending)) {
      throw new CommittedPaymentIntegrityException(
          "New payment attempt did not resolve as complete pending truth");
    }
    return pendingStartResult(pending.truth(), false);
  }

  private MockPaymentCallbackResult callbackOnce(
      String idempotencyKey, MockPaymentCallbackRequest request, String intentHash) {
    MockPaymentRepository.AttemptRecord attempt =
        (request.sandboxId() == null
                ? repository.findAttemptByCorrelationForUpdate(request.callbackCorrelationId())
                : repository.findEvaluationAttemptByCorrelationForUpdate(
                    request.callbackCorrelationId(), request.sandboxId()))
            .orElse(null);

    if (request.sandboxId() != null) {
      MockPaymentCallbackResult committed =
          resolveCommittedEvaluationCallback(attempt, idempotencyKey, request, intentHash);
      if (committed != null) {
        return committed;
      }
      fenceSandbox(request.sandboxId());
      if (attempt == null) {
        throw notFound(
            MockPaymentRejectionReason.CALLBACK_TRUTH_NOT_FOUND,
            "Payment callback correlation is unknown");
      }
    } else if (attempt == null) {
      throw notFound(
          MockPaymentRejectionReason.CALLBACK_TRUTH_NOT_FOUND,
          "Payment callback correlation is unknown");
    }
    requireCallbackMatches(attempt, request);

    if (attempt.sandboxId() == null) {
      MockPaymentCallbackResult committed =
          resolveCommittedStandardCallback(attempt, idempotencyKey, request, intentHash);
      if (committed != null) {
        return committed;
      }
    }
    if (!"PENDING".equals(attempt.state()) || attempt.stateVersion() != 1) {
      throw conflict(
          MockPaymentRejectionReason.ORDER_NOT_ELIGIBLE,
          "Payment attempt is not eligible for success");
    }

    MockPaymentRepository.OrderTruth order =
        repository
            .findOrderForUpdate(attempt.orderId())
            .orElseThrow(
                () -> new CommittedPaymentIntegrityException("Payment order truth is missing"));
    if (!attempt.orderKind().equals(order.orderKind())
        || !attempt.userSubject().equals(order.userSubject())
        || !java.util.Objects.equals(attempt.sandboxId(), order.sandboxId())
        || attempt.amountMinor() != order.amountMinor()
        || !attempt.currency().equals(order.currency())) {
      throw new CommittedPaymentIntegrityException("Payment attempt conflicts with order truth");
    }
    if (!"UNPAID".equals(order.status()) || order.stateVersion() != 1) {
      throw conflict(
          MockPaymentRejectionReason.ORDER_NOT_ELIGIBLE, "Cancelled or final order cannot be paid");
    }

    Instant paymentEventTime =
        attempt.sandboxId() == null
            ? clock.instant().truncatedTo(ChronoUnit.MICROS)
            : repository.monotonicEvaluationAuditCreatedAt(attempt.sandboxId(), clock.instant());
    repository.markOrderPaid(order);
    repository.markAttemptSucceeded(attempt, paymentEventTime);
    repository.insertPaymentMovement(attempt, order);
    MockPaymentRepository.CallbackRecord callback =
        new MockPaymentRepository.CallbackRecord(
            request.callbackEventId(),
            idempotencyKey,
            attempt.attemptId(),
            attempt.callbackCorrelationId(),
            attempt.sandboxId(),
            request.supportSessionId(),
            request.traceId(),
            request.operationId(),
            intentHash,
            "SUCCEEDED",
            "APPLIED",
            paymentEventTime);
    repository.insertCallback(callback, paymentEventTime);
    if (attempt.sandboxId() != null) {
      repository.insertPaymentAuditReference(
          EvaluationAuditReferenceIdentity.paymentCallback(
              attempt.sandboxId(),
              callback.supportSessionId(),
              callback.traceId(),
              callback.operationId(),
              callback.callbackEventId(),
              2),
          callback,
          2,
          paymentEventTime);
    }
    MockPaymentRepository.AttemptRecord succeeded =
        repository
            .findAttemptByIdForUpdate(attempt.attemptId())
            .orElseThrow(
                () ->
                    new CommittedPaymentIntegrityException("Succeeded payment attempt is missing"));
    CommittedPaymentTruthResolver.CommittedPaymentTruth committed =
        truth.resolveLocked(
            attempt.sandboxId() == null
                ? CommittedPaymentTruthResolver.CommittedPaymentCaller.PRODUCTION_CALLBACK_REPLAY
                : CommittedPaymentTruthResolver.CommittedPaymentCaller.EVALUATION_CALLBACK_REPLAY,
            succeeded);
    return committedCallbackResult(committed, false);
  }

  private MockPaymentCallbackResult resolveCommittedStandardCallback(
      MockPaymentRepository.AttemptRecord attempt,
      String idempotencyKey,
      MockPaymentCallbackRequest request,
      String intentHash) {
    return resolveCommittedCallback(attempt, idempotencyKey, request, intentHash, false);
  }

  private MockPaymentCallbackResult resolveCommittedEvaluationCallback(
      MockPaymentRepository.AttemptRecord attempt,
      String idempotencyKey,
      MockPaymentCallbackRequest request,
      String intentHash) {
    return resolveCommittedCallback(attempt, idempotencyKey, request, intentHash, true);
  }

  private MockPaymentCallbackResult resolveCommittedCallback(
      MockPaymentRepository.AttemptRecord attempt,
      String idempotencyKey,
      MockPaymentCallbackRequest request,
      String intentHash,
      boolean evaluation) {
    CommittedPaymentTruthResolver.CommittedPaymentTruth committed;
    try {
      committed =
          truth
              .resolveReplayLocked(
                  evaluation
                      ? CommittedPaymentTruthResolver.CommittedPaymentCaller
                          .EVALUATION_CALLBACK_REPLAY
                      : CommittedPaymentTruthResolver.CommittedPaymentCaller
                          .PRODUCTION_CALLBACK_REPLAY,
                  attempt,
                  idempotencyKey,
                  request)
              .orElse(null);
    } catch (CommittedPaymentIntegrityException exception) {
      throw conflict(
          MockPaymentRejectionReason.COMMITTED_PAYMENT_TRUTH_INCONSISTENT,
          "Committed payment truth is inconsistent");
    }
    if (committed == null) {
      return null;
    }
    requireCallbackMatches(committed.attempt(), request);
    if (evaluation) {
      requireEvaluationCallbackReplay(
          committed.callback(), committed.attempt(), idempotencyKey, request, intentHash);
      if (committed.attempt().refundedAmountMinor() != 0) {
        throw conflict(
            MockPaymentRejectionReason.COMMITTED_PAYMENT_TRUTH_INCONSISTENT,
            "Committed payment truth is inconsistent");
      }
    } else {
      boolean addressesCanonicalCallback =
          committed.callback().callbackIdempotencyKey().equals(idempotencyKey)
              || committed.callback().callbackEventId().equals(request.callbackEventId());
      if (addressesCanonicalCallback) {
        requireCallbackReplay(committed.callback(), request, intentHash);
      }
      requireCallbackAttempt(committed.callback(), committed.attempt());
    }
    return committedCallbackResult(committed, true);
  }

  private void requireCallbackReplay(
      MockPaymentRepository.CallbackRecord callback,
      MockPaymentCallbackRequest request,
      String intentHash) {
    requireIntent(callback.intentHash(), intentHash, "Callback idempotency intent conflicts");
    if (!callback.callbackEventId().equals(request.callbackEventId())
        || !callback.callbackCorrelationId().equals(request.callbackCorrelationId())) {
      throw conflict(
          MockPaymentRejectionReason.IDEMPOTENCY_INTENT_CONFLICT,
          "Callback identity conflicts with its existing result");
    }
  }

  private void requireEvaluationCallbackReplay(
      MockPaymentRepository.CallbackRecord callback,
      MockPaymentRepository.AttemptRecord attempt,
      String idempotencyKey,
      MockPaymentCallbackRequest request,
      String intentHash) {
    requireCallbackAttempt(callback, attempt);
    requireIntent(callback.intentHash(), intentHash, "Callback idempotency intent conflicts");
    if (!callback.callbackIdempotencyKey().equals(idempotencyKey)
        || !callback.callbackEventId().equals(request.callbackEventId())
        || !callback.callbackCorrelationId().equals(request.callbackCorrelationId())
        || !java.util.Objects.equals(callback.sandboxId(), request.sandboxId())
        || !java.util.Objects.equals(callback.supportSessionId(), request.supportSessionId())
        || !java.util.Objects.equals(callback.traceId(), request.traceId())
        || !java.util.Objects.equals(callback.operationId(), request.operationId())) {
      throw conflict(
          MockPaymentRejectionReason.IDEMPOTENCY_INTENT_CONFLICT,
          "Callback identity conflicts with its existing result");
    }
  }

  private static void requireCallbackMatches(
      MockPaymentRepository.AttemptRecord attempt, MockPaymentCallbackRequest request) {
    if (!attempt.callbackCorrelationId().equals(request.callbackCorrelationId())
        || !attempt.orderId().equals(request.orderId())
        || !java.util.Objects.equals(attempt.sandboxId(), request.sandboxId())
        || attempt.amountMinor() != request.amountMinor()
        || !attempt.currency().equals(request.currency())
        || !"SUCCEEDED".equals(request.outcome())) {
      throw conflict(
          MockPaymentRejectionReason.IDEMPOTENCY_INTENT_CONFLICT,
          "Payment callback intent does not match its attempt");
    }
  }

  private static void requireCallbackAttempt(
      MockPaymentRepository.CallbackRecord callback, MockPaymentRepository.AttemptRecord attempt) {
    if (!callback.attemptId().equals(attempt.attemptId())) {
      throw new CommittedPaymentIntegrityException(
          "Callback identity conflicts with its existing attempt");
    }
  }

  private MockPaymentResult executeStart(
      CommittedPaymentTruthResolver.StartCommandContext context) {
    try {
      return requireTransactionResult(
          transactions.mutate(
              MockPaymentTransactions.Entry.START_INITIAL_MUTATION, () -> startOnce(context)));
    } catch (DuplicateKeyException exception) {
      return recoverStartAfterCompetition(context);
    } catch (PessimisticLockingFailureException exception) {
      if (!isRetryableMySqlLockCompetition(exception)) {
        throw exception;
      }
      return recoverStartAfterCompetition(context);
    }
  }

  private MockPaymentResult recoverStartAfterCompetition(
      CommittedPaymentTruthResolver.StartCommandContext context) {
    CompetitionObservation<MockPaymentResult> observation =
        observeStartWithinBound(
            context,
            MockPaymentTransactions.Entry.START_TRUTH_OBSERVATION,
            TRUTH_OBSERVATION_ATTEMPTS);
    if (observation.state() == MockPaymentTransactions.ObservationOutcome.FOUND) {
      return observation.result();
    }
    if (observation.state() == MockPaymentTransactions.ObservationOutcome.INDETERMINATE) {
      throw concurrencyIndeterminate();
    }

    try {
      return requireTransactionResult(
          transactions.mutate(
              MockPaymentTransactions.Entry.START_FINAL_MUTATION, () -> startOnce(context)));
    } catch (DuplicateKeyException exception) {
      return resolveAfterFinalStartCompetition(context);
    } catch (PessimisticLockingFailureException exception) {
      if (!isRetryableMySqlLockCompetition(exception)) {
        throw exception;
      }
      return resolveAfterFinalStartCompetition(context);
    }
  }

  private MockPaymentResult resolveAfterFinalStartCompetition(
      CommittedPaymentTruthResolver.StartCommandContext context) {
    CompetitionObservation<MockPaymentResult> observation =
        observeStartWithinBound(context, MockPaymentTransactions.Entry.START_FINAL_OBSERVATION, 1);
    if (observation.state() == MockPaymentTransactions.ObservationOutcome.FOUND) {
      return observation.result();
    }
    throw concurrencyIndeterminate();
  }

  private CompetitionObservation<MockPaymentResult> observeStartWithinBound(
      CommittedPaymentTruthResolver.StartCommandContext context,
      MockPaymentTransactions.Entry entry,
      int attempts) {
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        return requireTransactionResult(
            transactions.observe(entry, () -> observeStartOnce(context)));
      } catch (PessimisticLockingFailureException exception) {
        if (!isRetryableMySqlLockCompetition(exception)) {
          throw exception;
        }
        if (attempt < attempts && !pauseBeforeTruthObservation(attempt)) {
          return CompetitionObservation.indeterminate();
        }
      }
    }
    return CompetitionObservation.indeterminate();
  }

  private CompetitionObservation<MockPaymentResult> observeStartOnce(
      CommittedPaymentTruthResolver.StartCommandContext context) {
    CommittedPaymentTruthResolver.StartCommandResolution resolution =
        truth.resolveStartCommandLocked(context);
    if (resolution instanceof CommittedPaymentTruthResolver.ConcealedStart) {
      throw notFound(
          MockPaymentRejectionReason.CONCEALED_NOT_FOUND, "Payment order is missing or not owned");
    }
    if (resolution instanceof CommittedPaymentTruthResolver.CommittedReplay committed) {
      return CompetitionObservation.found(committedStartResult(committed.truth()));
    }
    if (resolution instanceof CommittedPaymentTruthResolver.PendingReplay pending) {
      fenceSandbox(context.sandboxId());
      return CompetitionObservation.found(pendingStartResult(pending.truth(), true));
    }
    return CompetitionObservation.confirmedAbsent();
  }

  private MockPaymentCallbackResult executeCallback(
      String idempotencyKey, MockPaymentCallbackRequest request, String intentHash) {
    try {
      return requireTransactionResult(
          transactions.mutate(
              MockPaymentTransactions.Entry.CALLBACK_INITIAL_MUTATION,
              () -> callbackOnce(idempotencyKey, request, intentHash)));
    } catch (DuplicateKeyException exception) {
      return resolveAfterCallbackCompetition(idempotencyKey, request, intentHash);
    } catch (PessimisticLockingFailureException exception) {
      if (!isRetryableMySqlLockCompetition(exception)) {
        throw exception;
      }
      return resolveAfterCallbackCompetition(idempotencyKey, request, intentHash);
    }
  }

  private MockPaymentCallbackResult resolveAfterCallbackCompetition(
      String idempotencyKey, MockPaymentCallbackRequest request, String intentHash) {
    for (int attempt = 1; attempt <= TRUTH_OBSERVATION_ATTEMPTS; attempt++) {
      try {
        CompetitionObservation<MockPaymentCallbackResult> observation =
            requireTransactionResult(
                transactions.observe(
                    MockPaymentTransactions.Entry.CALLBACK_TRUTH_OBSERVATION,
                    () -> observeCallbackOnce(idempotencyKey, request, intentHash)));
        if (observation.state() == MockPaymentTransactions.ObservationOutcome.FOUND) {
          return observation.result();
        }
        throw concurrencyIndeterminate();
      } catch (PessimisticLockingFailureException exception) {
        if (!isRetryableMySqlLockCompetition(exception)) {
          throw exception;
        }
        if (attempt < TRUTH_OBSERVATION_ATTEMPTS && !pauseBeforeTruthObservation(attempt)) {
          break;
        }
      }
    }
    throw concurrencyIndeterminate();
  }

  private CompetitionObservation<MockPaymentCallbackResult> observeCallbackOnce(
      String idempotencyKey, MockPaymentCallbackRequest request, String intentHash) {
    MockPaymentRepository.AttemptRecord attempt =
        repository.findAttemptByCorrelation(request.callbackCorrelationId()).orElse(null);
    MockPaymentCallbackResult result =
        request.sandboxId() == null
            ? resolveCommittedStandardCallback(attempt, idempotencyKey, request, intentHash)
            : resolveCommittedEvaluationCallback(attempt, idempotencyKey, request, intentHash);
    return result == null
        ? CompetitionObservation.confirmedAbsent()
        : CompetitionObservation.found(result);
  }

  private static boolean isRetryableMySqlLockCompetition(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sqlException) {
        return sqlException.getErrorCode() == 1205 || sqlException.getErrorCode() == 1213;
      }
      current = current.getCause();
    }
    return false;
  }

  private static <T> T requireTransactionResult(T result) {
    if (result == null) {
      throw new IllegalStateException("Payment transaction returned no result");
    }
    return result;
  }

  private static boolean pauseBeforeTruthObservation(int attempt) {
    try {
      Thread.sleep(TRUTH_OBSERVATION_BACKOFF_MILLIS * attempt);
      return true;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static MockPaymentRequest requireRequest(MockPaymentRequest request) {
    if (request == null
        || request.amountMinor() == null
        || request.amountMinor() < 1
        || request.currency() == null
        || !CURRENCY.matcher(request.currency()).matches()
        || request.userSubject() != null
        || request.hasExtraFields()) {
      throw validation("Payment request is invalid");
    }
    return request;
  }

  private static MockPaymentCallbackRequest requireCallback(MockPaymentCallbackRequest request) {
    if (request == null
        || request.amountMinor() == null
        || request.amountMinor() < 1
        || request.currency() == null
        || !CURRENCY.matcher(request.currency()).matches()
        || !"SUCCEEDED".equals(request.outcome())
        || request.hasExtraFields()) {
      throw validation("Payment callback is invalid");
    }
    requireUuid(request.callbackEventId(), "Callback event id is invalid");
    requireUuid(request.callbackCorrelationId(), "Callback correlation is invalid");
    requireUuid(request.orderId(), "Callback order id is invalid");
    boolean anyEvaluationContext =
        request.sandboxId() != null
            || request.supportSessionId() != null
            || request.traceId() != null
            || request.operationId() != null;
    boolean completeEvaluationContext =
        matches(BOUNDED_CONTEXT, request.sandboxId())
            && matches(BOUNDED_CONTEXT, request.supportSessionId())
            && matches(BOUNDED_CONTEXT, request.traceId())
            && matches(OPERATION, request.operationId());
    if (anyEvaluationContext != completeEvaluationContext) {
      throw validation("Payment callback is invalid");
    }
    return request;
  }

  private void fenceSandbox(String sandboxId) {
    if (sandboxId == null) {
      return;
    }
    if (sandboxes == null) {
      throw new MockPaymentException(
          403,
          "AUTHORIZATION",
          MockPaymentRejectionReason.EVALUATION_COMPONENT_UNAVAILABLE,
          "Evaluation payment is unavailable");
    }
    EvaluationSandboxRepository.Sandbox sandbox = sandboxes.lockForPayment(sandboxId);
    if (!"ACTIVE".equals(sandbox.lifecycleState())
        || sandbox.expiresAt() == null
        || !sandbox.expiresAt().isAfter(clock.instant())) {
      throw new io.citybuddy.commerce.evaluation.EvaluationSandboxException(
          403,
          io.citybuddy.commerce.evaluation.EvaluationRejectionReason.PAYMENT_SANDBOX_NOT_ACTIVE,
          "Evaluation sandbox is inactive");
    }
  }

  private static void requireOptionalSandbox(String sandboxId) {
    if (sandboxId != null && !matches(BOUNDED_CONTEXT, sandboxId)) {
      throw validation("Payment sandbox is invalid");
    }
  }

  private static boolean matches(Pattern pattern, String value) {
    return value != null && pattern.matcher(value).matches();
  }

  private static void requireIdempotency(String value, String message) {
    if (value == null || !IDEMPOTENCY.matcher(value).matches()) {
      throw validation(message);
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

  private static void requireIntent(String existing, String supplied, String message) {
    if (!existing.equals(supplied)) {
      throw conflict(MockPaymentRejectionReason.IDEMPOTENCY_INTENT_CONFLICT, message);
    }
  }

  private static MockPaymentResult pendingStartResult(
      CommittedPaymentTruthResolver.PendingPaymentTruth pending, boolean replayed) {
    MockPaymentRepository.AttemptRecord attempt = pending.attempt();
    if (!"PENDING".equals(attempt.state())) {
      throw new IllegalArgumentException("Pending start result requires pending truth");
    }
    return new MockPaymentResult(
        attempt.attemptId(),
        attempt.callbackCorrelationId(),
        attempt.orderId(),
        attempt.orderKind(),
        attempt.amountMinor(),
        attempt.currency(),
        attempt.state(),
        replayed);
  }

  private static MockPaymentResult committedStartResult(
      CommittedPaymentTruthResolver.CommittedPaymentTruth committed) {
    MockPaymentRepository.AttemptRecord attempt = committed.attempt();
    return new MockPaymentResult(
        attempt.attemptId(),
        attempt.callbackCorrelationId(),
        attempt.orderId(),
        attempt.orderKind(),
        attempt.amountMinor(),
        attempt.currency(),
        attempt.state(),
        true);
  }

  private static MockPaymentCallbackResult committedCallbackResult(
      CommittedPaymentTruthResolver.CommittedPaymentTruth committed, boolean replayed) {
    MockPaymentRepository.AttemptRecord attempt = committed.attempt();
    return new MockPaymentCallbackResult(
        attempt.attemptId(),
        attempt.callbackCorrelationId(),
        attempt.orderId(),
        attempt.state(),
        replayed);
  }

  private static MockPaymentException validation(String message) {
    return new MockPaymentException(400, "VALIDATION", message);
  }

  private static MockPaymentException notFound(MockPaymentRejectionReason reason, String message) {
    return new MockPaymentException(404, "NOT_FOUND", reason, message);
  }

  private static MockPaymentException conflict(String message) {
    return new MockPaymentException(409, "CONFLICT", message);
  }

  private static MockPaymentException conflict(MockPaymentRejectionReason reason, String message) {
    return new MockPaymentException(409, "CONFLICT", reason, message);
  }

  private static MockPaymentException concurrencyIndeterminate() {
    return new MockPaymentException(
        429,
        "INDETERMINATE",
        MockPaymentRejectionReason.PAYMENT_CONCURRENCY_OBSERVATION_INDETERMINATE,
        "Payment truth is indeterminate; retry the same request");
  }

  record CompetitionObservation<T>(MockPaymentTransactions.ObservationOutcome state, T result) {
    static <T> CompetitionObservation<T> found(T result) {
      return new CompetitionObservation<>(
          MockPaymentTransactions.ObservationOutcome.FOUND,
          java.util.Objects.requireNonNull(result));
    }

    static <T> CompetitionObservation<T> confirmedAbsent() {
      return new CompetitionObservation<>(
          MockPaymentTransactions.ObservationOutcome.CONFIRMED_ABSENT, null);
    }

    static <T> CompetitionObservation<T> indeterminate() {
      return new CompetitionObservation<>(
          MockPaymentTransactions.ObservationOutcome.INDETERMINATE, null);
    }
  }
}
