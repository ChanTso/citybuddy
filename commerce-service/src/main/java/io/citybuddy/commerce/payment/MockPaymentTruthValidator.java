package io.citybuddy.commerce.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class MockPaymentTruthValidator {
  private static final HexFormat HEX = HexFormat.of();

  private final MockPaymentRepository repository;

  public MockPaymentTruthValidator(MockPaymentRepository repository) {
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

  public MockPaymentRepository.OrderTruth requireSucceededTruth(
      MockPaymentRepository.AttemptRecord attempt) {
    MockPaymentRepository.OrderTruth order;
    MockPaymentRepository.CallbackRecord callback;
    try {
      order =
          repository
              .findOrderForUpdate(attempt.orderId())
              .orElseThrow(() -> new IllegalStateException("Payment order truth is missing"));
      callback = repository.findCallbackByAttempt(attempt.attemptId()).orElse(null);
    } catch (MockPaymentIntegrityException exception) {
      throw new IllegalStateException("Payment truth cardinality is corrupted", exception);
    }
    if (!"SUCCEEDED".equals(attempt.state())
        || attempt.stateVersion() != 2
        || !attempt.orderKind().equals(order.orderKind())
        || !java.util.Objects.equals(attempt.sandboxId(), order.sandboxId())
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
        || callback == null
        || !"SUCCEEDED".equals(callback.requestedOutcome())
        || !"APPLIED".equals(callback.resultState())
        || attempt.succeededAt() == null
        || !attempt.succeededAt().equals(callback.createdAt())
        || !java.util.Objects.equals(attempt.sandboxId(), callback.sandboxId())
        || !attempt.callbackCorrelationId().equals(callback.callbackCorrelationId())
        || !callback.intentHash().equals(callbackIntentHash(attempt, callback))
        || !repository.hasPaymentMovement(attempt, order)
        || (attempt.sandboxId() != null
            && !repository.hasPaymentAuditReference(callback, attempt.stateVersion()))) {
      throw new IllegalStateException("Committed payment truth is inconsistent");
    }
    return order;
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
}
