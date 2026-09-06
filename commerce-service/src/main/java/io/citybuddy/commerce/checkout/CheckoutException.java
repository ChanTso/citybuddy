package io.citybuddy.commerce.checkout;

public final class CheckoutException extends RuntimeException {
  private final int status;
  private final String category;

  public CheckoutException(int status, String category, String message) {
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
