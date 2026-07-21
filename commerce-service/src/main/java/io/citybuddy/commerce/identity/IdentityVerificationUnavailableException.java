package io.citybuddy.commerce.identity;

public final class IdentityVerificationUnavailableException extends RuntimeException {
  public IdentityVerificationUnavailableException(Throwable cause) {
    super("Identity verification dependency is unavailable", cause);
  }
}
