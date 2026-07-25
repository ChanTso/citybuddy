package io.citybuddy.commerce.refund;

public final class RefundIndeterminateException extends RuntimeException {
  public RefundIndeterminateException(String message, Throwable cause) {
    super(message, cause);
  }

  public RefundRejectionReason reason() {
    return RefundRejectionReason.REFUND_CONCURRENCY_OBSERVATION_INDETERMINATE;
  }
}
