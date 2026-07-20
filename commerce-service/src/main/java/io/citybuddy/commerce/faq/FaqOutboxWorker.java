package io.citybuddy.commerce.faq;

import org.springframework.scheduling.annotation.Scheduled;

public final class FaqOutboxWorker {
  private static final int OUTBOX_BATCH_SIZE = 100;

  private final FaqOutboxPublisher publisher;

  public FaqOutboxWorker(FaqOutboxPublisher publisher) {
    this.publisher = publisher;
  }

  @Scheduled(
      initialDelayString = "${citybuddy.catalog.worker-initial-delay-ms:5000}",
      fixedDelayString = "${citybuddy.catalog.worker-delay-ms:1000}")
  public int publishOnce() throws Exception {
    return publisher.publishPending(OUTBOX_BATCH_SIZE);
  }
}
