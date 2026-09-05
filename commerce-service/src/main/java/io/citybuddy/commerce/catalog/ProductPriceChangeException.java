package io.citybuddy.commerce.catalog;

public final class ProductPriceChangeException extends RuntimeException {
  private final Reason reason;
  private final String productId;

  public ProductPriceChangeException(Reason reason, String productId) {
    super("Product price change rejected: " + reason + " (" + productId + ")");
    this.reason = reason;
    this.productId = productId;
  }

  public Reason reason() {
    return reason;
  }

  public String productId() {
    return productId;
  }

  public enum Reason {
    NOT_FOUND,
    VERSION_CONFLICT,
    NOT_ORDERABLE,
    CURRENCY_MISMATCH,
    SECKILL_PRODUCT
  }
}
