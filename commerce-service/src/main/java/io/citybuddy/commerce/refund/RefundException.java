package io.citybuddy.commerce.refund;

public final class RefundException extends RuntimeException {
  private final int status;
  private final String category;
  private final RefundRejectionReason reason;

  public RefundException(int status, String category, String message) {
    this(status, category, RefundRejectionReason.NOT_APPLICABLE, message);
  }

  public RefundException(
      int status, String category, RefundRejectionReason reason, String message) {
    super(message);
    this.status = status;
    this.category = category;
    this.reason = reason;
  }

  public int status() {
    return status;
  }

  public String category() {
    return category;
  }

  public RefundRejectionReason reason() {
    return reason;
  }
}
