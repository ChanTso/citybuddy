package io.citybuddy.commerce.retail;

public final class RetailFulfillmentException extends RuntimeException {
  private final String category;

  public RetailFulfillmentException(String category, String message) {
    super(message);
    this.category = category;
  }

  public String category() {
    return category;
  }
}
