package io.citybuddy.auth.identity;

public final class IdentityException extends RuntimeException {
  private final int status;

  public IdentityException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
