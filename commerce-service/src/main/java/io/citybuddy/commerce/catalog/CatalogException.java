package io.citybuddy.commerce.catalog;

public final class CatalogException extends RuntimeException {
  private final int status;

  public CatalogException(int status, String message) {
    super(message);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
