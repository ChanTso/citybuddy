package io.citybuddy.commerce.payment;

public enum MockPaymentRejectionReason {
  NOT_APPLICABLE(0, "", "unexpected-or-unattributed"),
  DIRECT_USER_AUTHORIZATION_REJECTED(403, "AUTHORIZATION", "direct-user-authorizer"),
  EVALUATION_COMPONENT_UNAVAILABLE(403, "AUTHORIZATION", "evaluation-component"),
  COMMITTED_PAYMENT_TRUTH_INCONSISTENT(409, "CONFLICT", "durable-closure"),
  IDEMPOTENCY_INTENT_CONFLICT(409, "CONFLICT", "canonical-intent"),
  ORDER_NOT_ELIGIBLE(409, "CONFLICT", "business-state"),
  CONCEALED_NOT_FOUND(404, "NOT_FOUND", "owner-visibility"),
  CALLBACK_TRUTH_NOT_FOUND(404, "NOT_FOUND", "callback-truth-visibility"),
  SANDBOX_NOT_ACTIVE(403, "AUTHORIZATION", "sandbox-liveness"),
  PAYMENT_CONCURRENCY_OBSERVATION_INDETERMINATE(429, "INDETERMINATE", "bounded-truth-observation"),
  DEPENDENCY_OBSERVATION_INDETERMINATE(503, "UNAVAILABLE", "dependency-resource");

  private final int status;
  private final String category;
  private final String producer;

  MockPaymentRejectionReason(int status, String category, String producer) {
    this.status = status;
    this.category = category;
    this.producer = producer;
  }

  int status() {
    return status;
  }

  String category() {
    return category;
  }

  String producer() {
    return producer;
  }
}
