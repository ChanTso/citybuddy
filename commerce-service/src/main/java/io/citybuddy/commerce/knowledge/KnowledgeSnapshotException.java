package io.citybuddy.commerce.knowledge;

public final class KnowledgeSnapshotException extends RuntimeException {
  private final int status;
  private final String code;

  public KnowledgeSnapshotException(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }
}
