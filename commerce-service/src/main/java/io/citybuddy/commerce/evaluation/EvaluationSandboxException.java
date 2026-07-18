package io.citybuddy.commerce.evaluation;

public final class EvaluationSandboxException extends RuntimeException {
  private final int status;

  public EvaluationSandboxException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
