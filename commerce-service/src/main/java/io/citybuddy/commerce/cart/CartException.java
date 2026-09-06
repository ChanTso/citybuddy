package io.citybuddy.commerce.cart;

public final class CartException extends RuntimeException {
  private final int status;
  private final String category;

  public CartException(int status, String category, String message) {
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
