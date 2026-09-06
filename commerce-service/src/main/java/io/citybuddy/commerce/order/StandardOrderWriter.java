package io.citybuddy.commerce.order;

final class StandardOrderWriter {
  private StandardOrderWriter() {}

  static OrderResult write(
      OrderRepository repository,
      String owner,
      String orderId,
      OrderRepository.ProductSnapshot product,
      int quantity,
      long expectedVersion,
      String correlationId) {
    if (!"PUBLISHED".equals(product.publicationState()) || !product.available()) {
      throw new OrderException(
          422, OrderCategory.VALIDATION, "Product is missing or not orderable", correlationId);
    }
    if (product.publicationVersion() != expectedVersion) {
      throw new OrderException(
          409, OrderCategory.STALE_VERSION, "Product version is stale", correlationId);
    }
    if (product.stockQuantity() < quantity) {
      throw new OrderException(
          409, OrderCategory.INSUFFICIENT_STOCK, "Insufficient authoritative stock", correlationId);
    }
    Math.multiplyExact(product.priceMinor(), quantity);
    if (!repository.decrementStock(product, quantity)) {
      throw new OrderService.StockRaceException();
    }
    repository.insertOrder(owner, orderId, product, quantity);
    repository.insertOutbox(orderId, product, quantity);
    return repository.findOwnedOrder(owner, orderId, correlationId);
  }
}
