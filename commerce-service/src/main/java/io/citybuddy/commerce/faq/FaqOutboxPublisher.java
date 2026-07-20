package io.citybuddy.commerce.faq;

public final class FaqOutboxPublisher {
  private final FaqRepository repository;
  private final FaqEventSender sender;

  public FaqOutboxPublisher(FaqRepository repository, FaqEventSender sender) {
    this.repository = repository;
    this.sender = sender;
  }

  public int publishPending(int limit) throws Exception {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Outbox publish limit must be between 1 and 100");
    }
    int published = 0;
    for (FaqRepository.OutboxEvent event : repository.pendingOutbox(limit)) {
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
  public interface FaqEventSender {
    void send(FaqRepository.OutboxEvent event) throws Exception;
  }
}
