package io.citybuddy.commerce.catalog;

public final class CatalogOutboxPublisher {
  private final ProductRepository repository;
  private final CatalogEventSender sender;

  public CatalogOutboxPublisher(ProductRepository repository, CatalogEventSender sender) {
    this.repository = repository;
    this.sender = sender;
  }

  public int publishPending(int limit) throws Exception {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Outbox publish limit must be between 1 and 100");
    }
    int published = 0;
    for (ProductRepository.OutboxEvent event : repository.pendingOutbox(limit)) {
      try {
        sender.send(event);
        repository.markPublished(event.eventId());
        published++;
      } catch (Exception exception) {
        repository.recordPublishFailure(event.eventId());
        throw exception;
      }
    }
    return published;
  }

  @FunctionalInterface
  public interface CatalogEventSender {
    void send(ProductRepository.OutboxEvent event) throws Exception;
  }
}
