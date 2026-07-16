package io.citybuddy.commerce.refund;

public final class RefundException extends RuntimeException {
  private final int status;
  private final String category;

  public RefundException(int status, String category, String message) {
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
