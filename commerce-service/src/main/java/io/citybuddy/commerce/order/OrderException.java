package io.citybuddy.commerce.order;

public final class OrderException extends RuntimeException {
  private final int status;
  private final OrderCategory category;
  private final String correlationId;

  public OrderException(int status, OrderCategory category, String message, String correlationId) {
    super(message);
    this.status = status;
    this.category = category;
    this.correlationId = correlationId;
  }

  public int status() {
    return status;
  }

  public OrderCategory category() {
    return category;
  }

  public String correlationId() {
    return correlationId;
  }
}
