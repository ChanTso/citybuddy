package io.citybuddy.commerce.payment;

public final class MockPaymentException extends RuntimeException {
  private final int status;
  private final String category;
  private final MockPaymentRejectionReason reason;

  public MockPaymentException(int status, String category, String message) {
    super(message);
    if (status == 403) {
      throw new IllegalArgumentException("HTTP 403 requires an attribution reason");
    }
    this.status = status;
    this.category = category;
    this.reason = MockPaymentRejectionReason.NOT_APPLICABLE;
  }

  public MockPaymentException(
      int status, String category, MockPaymentRejectionReason reason, String message) {
    super(message);
    if (status == 403 && (reason == null || reason == MockPaymentRejectionReason.NOT_APPLICABLE)) {
      throw new IllegalArgumentException("HTTP 403 requires an attribution reason");
    }
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

  public MockPaymentRejectionReason reason() {
    return reason;
  }
}
