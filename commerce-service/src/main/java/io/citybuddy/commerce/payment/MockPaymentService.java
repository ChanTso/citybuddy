package io.citybuddy.commerce.payment;

import io.citybuddy.commerce.evaluation.EvaluationAuditReferenceIdentity;
import io.citybuddy.commerce.evaluation.EvaluationSandboxRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

public final class MockPaymentService {
  private static final Pattern IDEMPOTENCY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern BOUNDED_CONTEXT =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
  private static final Pattern OPERATION = Pattern.compile("[0-9a-f]{64}");
  private static final HexFormat HEX = HexFormat.of();
  private static final int MAXIMUM_CONCURRENCY_ATTEMPTS = 2;

  private final MockPaymentRepository repository;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final EvaluationSandboxRepository sandboxes;

  public MockPaymentService(
      MockPaymentRepository repository, TransactionTemplate transactions, Clock clock) {
    this(repository, transactions, clock, null);
  }

  public MockPaymentService(
      MockPaymentRepository repository,
      TransactionTemplate transactions,
      Clock clock,
      EvaluationSandboxRepository sandboxes) {
    this.repository = repository;
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
        hash(
            orderId
                + "\n"
                + valid.amountMinor()
                + "\n"
                + valid.currency()
                + "\n"
                + nullable(sandboxId));
    return withDuplicateRetry(
        () -> startOnce(userSubject, sandboxId, orderId, idempotencyKey, valid, intentHash));
  }

  public MockPaymentCallbackResult callback(
      String idempotencyKey, MockPaymentCallbackRequest request) {
    requireIdempotency(idempotencyKey, "Callback idempotency key is invalid");
    MockPaymentCallbackRequest valid = requireCallback(request);
    String intentHash = hash(callbackIntent(idempotencyKey, valid));
    return withCallbackDeadlockRetry(() -> callbackOnce(idempotencyKey, valid, intentHash));
  }

  private MockPaymentResult startOnce(
      String userSubject,
      String sandboxId,
      String orderId,
      String idempotencyKey,
      MockPaymentRequest request,
      String intentHash) {
    fenceSandbox(sandboxId);
    MockPaymentRepository.AttemptRecord existing =
        repository.findAttemptByRequestForUpdate(userSubject, idempotencyKey).orElse(null);
    if (existing != null) {
      requireIntent(existing.intentHash(), intentHash, "Payment idempotency intent conflicts");
      requireSameSandbox(existing.sandboxId(), sandboxId);
      return result(existing, true);
    }

    MockPaymentRepository.OrderTruth order =
        (sandboxId == null
                ? repository.findOrderForUpdate(orderId)
                : repository.findEvaluationOrderForUpdate(orderId, sandboxId))
            .orElseThrow(() -> notFound("Payment order is missing or not owned"));
    if (sandboxId != null && !"STANDARD".equals(order.orderKind())) {
      throw conflict("Evaluation payment order kind is not supported");
    }
    if (sandboxId != null && !userSubject.equals(order.userSubject())) {
      if (order.evaluationOwnerHandle() == null
          || !io.citybuddy.commerce.evaluation.EvaluationSandboxRepository.fixtureOwner(
                  order.evaluationOwnerHandle())
              .equals(order.userSubject())) {
        throw notFound("Payment order is missing or not owned");
      }
      repository.bindEvaluationOrderOwner(
          order.orderId(), sandboxId, order.evaluationOwnerHandle(), userSubject);
      order =
          repository
              .findEvaluationOrderForUpdate(orderId, sandboxId)
              .orElseThrow(() -> new IllegalStateException("Evaluation payment order disappeared"));
    }
    if (!userSubject.equals(order.userSubject())) {
      throw notFound("Payment order is missing or not owned");
    }
    requireOrderIntent(order, request.amountMinor(), request.currency());
    if (!"UNPAID".equals(order.status()) || order.stateVersion() != 1) {
      throw conflict("Order is not eligible for payment");
    }
    MockPaymentRepository.AttemptRecord orderAttempt =
        repository.findAttemptByOrderForUpdate(order.orderKind(), order.orderId()).orElse(null);
    if (orderAttempt != null) {
      throw conflict("Payment order already has a different attempt identity");
    }

    MockPaymentRepository.AttemptRecord created =
        MockPaymentRepository.AttemptRecord.pending(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            userSubject,
            order.orderId(),
            order.orderKind(),
            sandboxId,
            idempotencyKey,
            intentHash,
            order.amountMinor(),
            order.currency());
    repository.insertAttempt(created);
    return result(created, false);
  }

  private MockPaymentCallbackResult callbackOnce(
      String idempotencyKey, MockPaymentCallbackRequest request, String intentHash) {
    fenceSandbox(request.sandboxId());
    MockPaymentRepository.AttemptRecord attempt =
        (request.sandboxId() == null
                ? repository.findAttemptByCorrelationForUpdate(request.callbackCorrelationId())
                : repository.findEvaluationAttemptByCorrelationForUpdate(
                    request.callbackCorrelationId(), request.sandboxId()))
            .orElseThrow(() -> notFound("Payment callback correlation is unknown"));
    requireCallbackMatches(attempt, request);

    if (attempt.sandboxId() != null) {
      MockPaymentRepository.CallbackRecord durable =
          repository.findCallbackByAttempt(attempt.attemptId()).orElse(null);
      if (durable != null) {
        requireEvaluationCallbackReplay(durable, attempt, idempotencyKey, request, intentHash);
        return callbackResult(requireSucceededTruth(attempt), true);
      }
      if ("SUCCEEDED".equals(attempt.state())) {
        throw conflict("Committed payment truth is inconsistent");
      }
    }

    if (attempt.sandboxId() == null) {
      MockPaymentRepository.CallbackRecord keyed =
          repository.findCallbackByKey(idempotencyKey).orElse(null);
      if (keyed != null) {
        requireCallbackReplay(keyed, request, intentHash);
        requireCallbackAttempt(keyed, attempt);
        return callbackResult(requireSucceededTruth(attempt), true);
      }
      MockPaymentRepository.CallbackRecord event =
          repository.findCallbackByEvent(request.callbackEventId()).orElse(null);
      if (event != null) {
        requireCallbackReplay(event, request, intentHash);
        requireCallbackAttempt(event, attempt);
        return callbackResult(requireSucceededTruth(attempt), true);
      }
      if ("SUCCEEDED".equals(attempt.state())) {
        return callbackResult(requireSucceededTruth(attempt), true);
      }
    }
    if (!"PENDING".equals(attempt.state()) || attempt.stateVersion() != 1) {
      throw conflict("Payment attempt is not eligible for success");
    }

    MockPaymentRepository.OrderTruth order =
        repository
            .findOrderForUpdate(attempt.orderId())
            .orElseThrow(() -> new IllegalStateException("Payment order truth is missing"));
    if (!attempt.orderKind().equals(order.orderKind())
        || !attempt.userSubject().equals(order.userSubject())
        || !java.util.Objects.equals(attempt.sandboxId(), order.sandboxId())
        || attempt.amountMinor() != order.amountMinor()
        || !attempt.currency().equals(order.currency())) {
      throw new IllegalStateException("Payment attempt conflicts with order truth");
    }
    if (!"UNPAID".equals(order.status()) || order.stateVersion() != 1) {
      throw conflict("Cancelled or final order cannot be paid");
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
            intentHash);
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
            .orElseThrow(() -> new IllegalStateException("Succeeded payment attempt is missing"));
    return callbackResult(succeeded, false);
  }

  private MockPaymentRepository.AttemptRecord requireSucceededTruth(
      MockPaymentRepository.AttemptRecord attempt) {
    MockPaymentRepository.OrderTruth order =
        repository
            .findOrderForUpdate(attempt.orderId())
            .orElseThrow(() -> conflict("Committed payment truth is inconsistent"));
    MockPaymentRepository.CallbackRecord callback =
        repository.findCallbackByAttempt(attempt.attemptId()).orElse(null);
    if (!"SUCCEEDED".equals(attempt.state())
        || attempt.stateVersion() != 2
        || !attempt.orderKind().equals(order.orderKind())
        || !java.util.Objects.equals(attempt.sandboxId(), order.sandboxId())
        || !attempt.userSubject().equals(order.userSubject())
        || attempt.amountMinor() != order.amountMinor()
        || !attempt.currency().equals(order.currency())
        || !"PAID".equals(order.status())
        || order.stateVersion() != 2
        || callback == null
        || !java.util.Objects.equals(attempt.sandboxId(), callback.sandboxId())
        || !attempt.callbackCorrelationId().equals(callback.callbackCorrelationId())
        || !callback.intentHash().equals(hash(callbackIntent(attempt, callback)))
        || !repository.hasPaymentMovement(attempt, order)
        || (attempt.sandboxId() != null
            && !repository.hasPaymentAuditReference(callback, attempt.stateVersion()))) {
      throw conflict("Committed payment truth is inconsistent");
    }
    return attempt;
  }

  private void requireCallbackReplay(
      MockPaymentRepository.CallbackRecord callback,
      MockPaymentCallbackRequest request,
      String intentHash) {
    requireIntent(callback.intentHash(), intentHash, "Callback idempotency intent conflicts");
    if (!callback.callbackEventId().equals(request.callbackEventId())
        || !callback.callbackCorrelationId().equals(request.callbackCorrelationId())) {
      throw conflict("Callback identity conflicts with its existing result");
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
      throw conflict("Callback identity conflicts with its existing result");
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
      throw conflict("Payment callback intent does not match its attempt");
    }
  }

  private static void requireCallbackAttempt(
      MockPaymentRepository.CallbackRecord callback, MockPaymentRepository.AttemptRecord attempt) {
    if (!callback.attemptId().equals(attempt.attemptId())) {
      throw conflict("Callback identity conflicts with its existing attempt");
    }
  }

  private static void requireOrderIntent(
      MockPaymentRepository.OrderTruth order, long amountMinor, String currency) {
    if (order.amountMinor() != amountMinor || !order.currency().equals(currency)) {
      throw conflict("Payment request does not match authoritative order amount");
    }
  }

  private <T> T withDuplicateRetry(java.util.function.Supplier<T> work) {
    try {
      return execute(work);
    } catch (DuplicateKeyException exception) {
      return execute(work);
    }
  }

  private <T> T withCallbackDeadlockRetry(java.util.function.Supplier<T> work) {
    for (int attempt = 1; attempt <= MAXIMUM_CONCURRENCY_ATTEMPTS; attempt++) {
      try {
        return execute(work);
      } catch (DuplicateKeyException exception) {
        throw conflict("Callback identity conflicts with a concurrent result");
      } catch (CannotAcquireLockException exception) {
        if (attempt == MAXIMUM_CONCURRENCY_ATTEMPTS || !isMySqlDeadlock(exception)) {
          throw exception;
        }
      }
    }
    throw new IllegalStateException("Unreachable payment callback retry state");
  }

  private static boolean isMySqlDeadlock(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sqlException && sqlException.getErrorCode() == 1213) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private <T> T execute(java.util.function.Supplier<T> work) {
    T result = transactions.execute(status -> work.get());
    if (result == null) {
      throw new IllegalStateException("Payment transaction returned no result");
    }
    return result;
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

  private static void requireSameSandbox(String existing, String supplied) {
    if (!java.util.Objects.equals(existing, supplied)) {
      throw conflict("Payment sandbox correlation conflicts");
    }
  }

  private static boolean matches(Pattern pattern, String value) {
    return value != null && pattern.matcher(value).matches();
  }

  private static String nullable(String value) {
    return value == null ? "" : value;
  }

  private static String callbackIntent(String idempotencyKey, MockPaymentCallbackRequest request) {
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
    return request.sandboxId() == null ? base : base + "\n" + idempotencyKey;
  }

  private static String callbackIntent(
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
    return callback.sandboxId() == null ? base : base + "\n" + callback.callbackIdempotencyKey();
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
      throw conflict(message);
    }
  }

  private static String hash(String value) {
    try {
      return HEX.formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Payment intent hash algorithm is unavailable", exception);
    }
  }

  private static MockPaymentResult result(
      MockPaymentRepository.AttemptRecord attempt, boolean replayed) {
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

  private static MockPaymentCallbackResult callbackResult(
      MockPaymentRepository.AttemptRecord attempt, boolean replayed) {
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

  private static MockPaymentException notFound(String message) {
    return new MockPaymentException(404, "NOT_FOUND", message);
  }

  private static MockPaymentException conflict(String message) {
    return new MockPaymentException(409, "CONFLICT", message);
  }
}
