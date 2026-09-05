package io.citybuddy.commerce.merchant;

public final class MerchantException extends RuntimeException {
  private final int status;
  private final String category;

  public MerchantException(int status, String category, String message) {
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
