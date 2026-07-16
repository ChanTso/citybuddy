package io.citybuddy.commerce.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

public final class MockPaymentService {
  private static final Pattern IDEMPOTENCY = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final HexFormat HEX = HexFormat.of();
  private static final int MAXIMUM_CONCURRENCY_ATTEMPTS = 2;

  private final MockPaymentRepository repository;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public MockPaymentService(
      MockPaymentRepository repository, TransactionTemplate transactions, Clock clock) {
    this.repository = repository;
    this.transactions = transactions;
    this.clock = clock;
  }

  public MockPaymentResult start(
      String userSubject, String orderId, String idempotencyKey, MockPaymentRequest request) {
    requireText(userSubject, 128, "Validated payment owner is missing");
    requireUuid(orderId, "Payment order id is invalid");
    requireIdempotency(idempotencyKey, "Payment idempotency key is invalid");
    MockPaymentRequest valid = requireRequest(request);
    String intentHash = hash(orderId + "\n" + valid.amountMinor() + "\n" + valid.currency());
    return withDuplicateRetry(
        () -> startOnce(userSubject, orderId, idempotencyKey, valid, intentHash));
  }

  public MockPaymentCallbackResult callback(
      String idempotencyKey, MockPaymentCallbackRequest request) {
    requireIdempotency(idempotencyKey, "Callback idempotency key is invalid");
    MockPaymentCallbackRequest valid = requireCallback(request);
    String intentHash =
        hash(
            String.join(
                "\n",
                valid.callbackEventId(),
                valid.callbackCorrelationId(),
                valid.orderId(),
                Long.toString(valid.amountMinor()),
                valid.currency(),
                valid.outcome()));
    return withCallbackConcurrencyRetry(() -> callbackOnce(idempotencyKey, valid, intentHash));
  }

  private MockPaymentResult startOnce(
      String userSubject,
      String orderId,
      String idempotencyKey,
      MockPaymentRequest request,
      String intentHash) {
    MockPaymentRepository.AttemptRecord existing =
        repository.findAttemptByRequestForUpdate(userSubject, idempotencyKey).orElse(null);
    if (existing != null) {
      requireIntent(existing.intentHash(), intentHash, "Payment idempotency intent conflicts");
      return result(existing, true);
    }

    MockPaymentRepository.OrderTruth order =
        repository
            .findOrderForUpdate(orderId)
            .orElseThrow(() -> notFound("Payment order is missing or not owned"));
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
            idempotencyKey,
            intentHash,
            order.amountMinor(),
            order.currency());
    repository.insertAttempt(created);
    return result(created, false);
  }

  private MockPaymentCallbackResult callbackOnce(
      String idempotencyKey, MockPaymentCallbackRequest request, String intentHash) {
    MockPaymentRepository.AttemptRecord attempt =
        repository
            .findAttemptByCorrelationForUpdate(request.callbackCorrelationId())
            .orElseThrow(() -> notFound("Payment callback correlation is unknown"));
    requireCallbackMatches(attempt, request);

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
    if (!"PENDING".equals(attempt.state()) || attempt.stateVersion() != 1) {
      throw conflict("Payment attempt is not eligible for success");
    }

    MockPaymentRepository.OrderTruth order =
        repository
            .findOrderForUpdate(attempt.orderId())
            .orElseThrow(() -> new IllegalStateException("Payment order truth is missing"));
    if (!attempt.orderKind().equals(order.orderKind())
        || !attempt.userSubject().equals(order.userSubject())
        || attempt.amountMinor() != order.amountMinor()
        || !attempt.currency().equals(order.currency())) {
      throw new IllegalStateException("Payment attempt conflicts with order truth");
    }
    if (!"UNPAID".equals(order.status()) || order.stateVersion() != 1) {
      throw conflict("Cancelled or final order cannot be paid");
    }

    repository.markOrderPaid(order);
    repository.markAttemptSucceeded(attempt, clock.instant());
    repository.insertPaymentMovement(attempt, order);
    repository.insertCallback(
        new MockPaymentRepository.CallbackRecord(
            request.callbackEventId(),
            idempotencyKey,
            attempt.attemptId(),
            attempt.callbackCorrelationId(),
            intentHash));
    MockPaymentRepository.AttemptRecord succeeded =
        repository
            .findAttemptByIdForUpdate(attempt.attemptId())
            .orElseThrow(() -> new IllegalStateException("Succeeded payment attempt is missing"));
    return callbackResult(succeeded, false);
  }

  private MockPaymentRepository.AttemptRecord requireSucceededTruth(
      MockPaymentRepository.AttemptRecord attempt) {
    if (attempt.stateVersion() != 2 || !repository.hasPaymentMovement(attempt.attemptId())) {
      throw new IllegalStateException("Succeeded payment truth is incomplete");
    }
    MockPaymentRepository.OrderTruth order =
        repository
            .findOrderForUpdate(attempt.orderId())
            .orElseThrow(() -> new IllegalStateException("Succeeded payment order is missing"));
    if (!attempt.orderKind().equals(order.orderKind())
        || !"PAID".equals(order.status())
        || order.stateVersion() != 2
        || repository.findCallbackByAttempt(attempt.attemptId()).isEmpty()) {
      throw new IllegalStateException("Succeeded payment truth is incomplete");
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

  private static void requireCallbackMatches(
      MockPaymentRepository.AttemptRecord attempt, MockPaymentCallbackRequest request) {
    if (!attempt.callbackCorrelationId().equals(request.callbackCorrelationId())
        || !attempt.orderId().equals(request.orderId())
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

  private <T> T withCallbackConcurrencyRetry(java.util.function.Supplier<T> work) {
    for (int attempt = 1; attempt <= MAXIMUM_CONCURRENCY_ATTEMPTS; attempt++) {
      try {
        return execute(work);
      } catch (DuplicateKeyException exception) {
        if (attempt == MAXIMUM_CONCURRENCY_ATTEMPTS) {
          throw exception;
        }
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
        || request.userSubject() != null) {
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
        || !"SUCCEEDED".equals(request.outcome())) {
      throw validation("Payment callback is invalid");
    }
    requireUuid(request.callbackEventId(), "Callback event id is invalid");
    requireUuid(request.callbackCorrelationId(), "Callback correlation is invalid");
    requireUuid(request.orderId(), "Callback order id is invalid");
    return request;
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
