package io.citybuddy.commerce.evaluation;

public final class EvaluationSandboxException extends RuntimeException {
  private final int status;
  private final EvaluationRejectionReason reason;

  public EvaluationSandboxException(int status, String message) {
    super(message);
    if (status == 403) {
      throw new IllegalArgumentException("HTTP 403 requires an attribution reason");
    }
    this.status = status;
    this.reason = EvaluationRejectionReason.NOT_APPLICABLE;
  }

  public EvaluationSandboxException(int status, EvaluationRejectionReason reason, String message) {
    super(message);
    if (status == 403 && (reason == null || reason == EvaluationRejectionReason.NOT_APPLICABLE)) {
      throw new IllegalArgumentException("HTTP 403 requires an attribution reason");
    }
    this.status = status;
    this.reason = reason;
  }

  public int status() {
    return status;
  }

  public EvaluationRejectionReason reason() {
    return reason;
  }
}
