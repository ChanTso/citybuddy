package io.citybuddy.commerce.action;

public final class ActionException extends RuntimeException {
  private final int status;
  private final String category;

  ActionException(int status, String category, String message) {
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
