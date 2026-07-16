package io.citybuddy.commerce.seckill;

public final class SeckillRequestException extends RuntimeException {
  private final int status;
  private final String category;

  public SeckillRequestException(int status, String category, String message) {
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
