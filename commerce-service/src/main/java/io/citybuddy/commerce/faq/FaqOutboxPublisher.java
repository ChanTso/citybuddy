package io.citybuddy.commerce.faq;

import java.util.ArrayList;
import java.util.List;

public final class FaqOutboxPublisher {
  private final FaqRepository repository;
  private final FaqKnowledgeEventCodec codec;
  private final FaqEventSender sender;

  public FaqOutboxPublisher(
      FaqRepository repository, FaqKnowledgeEventCodec codec, FaqEventSender sender) {
    this.repository = repository;
    this.codec = codec;
    this.sender = sender;
  }

  public int publishPending(int limit) throws Exception {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Outbox publish limit must be between 1 and 100");
    }
    repository
        .findPendingOrphan()
        .ifPresent(
            ignored -> {
              throw inconsistent("Pending FAQ Outbox event has no immutable command");
            });
    List<FaqRepository.OutboxEvent> verified = new ArrayList<>();
    for (FaqRepository.PendingPublication pending : repository.pendingPublications(limit)) {
      if (pending.outbox() == null) {
        throw inconsistent("FAQ publication command has no matching Outbox event");
      }
      FaqPublicationCommitment.verify(pending.command(), pending.outbox(), codec);
      verified.add(pending.outbox());
    }

    int published = 0;
    for (FaqRepository.OutboxEvent event : verified) {
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

  private static FaqPublicationException inconsistent(String message) {
    return new FaqPublicationException(
        FaqPublicationException.Code.INCONSISTENT_DURABLE_STATE, message);
  }

  @FunctionalInterface
  public interface FaqEventSender {
    void send(FaqRepository.OutboxEvent event) throws Exception;
  }
}
