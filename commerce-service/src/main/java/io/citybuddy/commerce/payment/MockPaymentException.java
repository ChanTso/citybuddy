package io.citybuddy.commerce.payment;

public final class MockPaymentException extends RuntimeException {
  private final int status;
  private final String category;

  public MockPaymentException(int status, String category, String message) {
    super(message);
    this.status = status;
    this.category = category;
  }

  public int status() {
    return status;
  }

  public String category() {
    return category;
  }
}
