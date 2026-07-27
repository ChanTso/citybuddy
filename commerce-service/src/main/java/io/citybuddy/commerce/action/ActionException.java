package io.citybuddy.commerce.action;

public final class ActionException extends RuntimeException {
  private final int status;
  private final String category;
  private final ActionRejectionReason reason;

  ActionException(int status, String category, ActionRejectionReason reason, String message) {
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

  public ActionRejectionReason reason() {
    return reason;
  }
}
