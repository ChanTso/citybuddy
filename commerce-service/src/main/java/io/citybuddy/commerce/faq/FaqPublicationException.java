package io.citybuddy.commerce.faq;

public final class FaqPublicationException extends RuntimeException {
  private final Code code;

  public FaqPublicationException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public Code code() {
    return code;
  }

  public enum Code {
    VALIDATION,
    NOT_FOUND,
    STALE_VERSION,
    IDEMPOTENCY_CONFLICT,
    INCONSISTENT_DURABLE_STATE
  }
}
