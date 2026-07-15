package io.citybuddy.commerce.catalog;

import org.springframework.scheduling.annotation.Scheduled;

public final class CatalogEventWorker {
  private static final int OUTBOX_BATCH_SIZE = 100;

  private final CatalogOutboxPublisher publisher;
  private final RocketMqCatalogMessaging messaging;
  private final ProductInvalidationHandler handler;

  public CatalogEventWorker(
      CatalogOutboxPublisher publisher,
      RocketMqCatalogMessaging messaging,
      ProductInvalidationHandler handler) {
    this.publisher = publisher;
    this.messaging = messaging;
    this.handler = handler;
  }

  @Scheduled(
      initialDelayString = "${citybuddy.catalog.worker-initial-delay-ms:5000}",
      fixedDelayString = "${citybuddy.catalog.worker-delay-ms:1000}")
  public void runOnce() throws Exception {
    publishOnce();
    consumeOnce();
  }

  public int publishOnce() throws Exception {
    return publisher.publishPending(OUTBOX_BATCH_SIZE);
  }

  public int consumeOnce() throws Exception {
    return messaging.consumeOnce(handler);
  }
}
