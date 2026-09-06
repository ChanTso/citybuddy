package io.citybuddy.commerce.merchant;

public final class MerchantProductOperationException extends RuntimeException {
  private final String reason;
  private final String targetId;

  public MerchantProductOperationException(String reason, String targetId) {
    super("Product operation rejected: " + reason + " (" + targetId + ")");
    this.reason = reason;
    this.targetId = targetId;
  }

  public String reason() {
    return reason;
  }

  public String targetId() {
    return targetId;
  }
}
