package io.citybuddy.commerce.refund;

public enum RefundRejectionReason {
  NOT_APPLICABLE(-1),
  REFUND_CONCURRENCY_OBSERVATION_INDETERMINATE(429),
  REFUND_DURABLE_TRUTH_INCONSISTENT(409),
  REFUND_IDEMPOTENCY_INTENT_CONFLICT(409),
  REFUND_BUSINESS_CONFLICT(409),
  REFUND_DEPENDENCY_UNAVAILABLE(503),
  REFUND_CONCEALED_NOT_FOUND(404);

  private final int publicStatus;

  RefundRejectionReason(int publicStatus) {
    this.publicStatus = publicStatus;
  }

  boolean permits(int status) {
    return this == NOT_APPLICABLE || publicStatus == status;
  }
}
