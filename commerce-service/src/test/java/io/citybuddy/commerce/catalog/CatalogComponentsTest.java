package io.citybuddy.commerce.catalog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class CatalogComponentsTest {

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void cachedContentStillUsesLiveMysqlFields() {
    ProductRepository repository = mock(ProductRepository.class);
    ProductCache cache = mock(ProductCache.class);
    Product cached = new Product("p-1", "Name", "Description", 100, "AUD", 2, true, 3);
    when(repository.catalogGeneration()).thenReturn(9L);
    when(cache.resolve(
            org.mockito.ArgumentMatchers.eq("p-1"),
            org.mockito.ArgumentMatchers.eq(9L),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.of(cached));
    when(repository.findPublishedLiveFields("p-1"))
        .thenReturn(Optional.of(new ProductRepository.LiveFields(125, "AUD", 1, false, 3)));

    Product result =
        new ProductCatalogService(repository, cache).findPublished("p-1").orElseThrow();

    assertEquals(125, result.priceMinor());
    assertEquals(1, result.stockQuantity());
    assertEquals(false, result.available());
  }

  @Test
  void cacheIdentityMismatchRebuildsFromMysqlEvenWhenVersionMatches() {
    ProductRepository repository = mock(ProductRepository.class);
    ProductCache cache = mock(ProductCache.class);
    Product forged = new Product("other", "Forged", "", 100, "AUD", 2, true, 3);
    Product authoritative = new Product("p-1", "Current", "", 125, "AUD", 1, true, 3);
    when(repository.catalogGeneration()).thenReturn(9L);
    when(cache.resolve(
            org.mockito.ArgumentMatchers.eq("p-1"),
            org.mockito.ArgumentMatchers.eq(9L),
            org.mockito.ArgumentMatchers.any()))
        .thenReturn(Optional.of(forged));
    when(repository.findPublishedLiveFields("p-1"))
        .thenReturn(Optional.of(new ProductRepository.LiveFields(125, "AUD", 1, true, 3)));
    when(repository.findPublished("p-1")).thenReturn(Optional.of(authoritative));

    Product result =
        new ProductCatalogService(repository, cache).findPublished("p-1").orElseThrow();

    assertEquals(authoritative, result);
    verify(cache).evict("p-1", 9L);
    verify(cache).put(authoritative, 9L);
  }

  @Test
  void cacheDeleteFailureCannotReverseCommittedPublication() {
    ProductRepository repository = mock(ProductRepository.class);
    ProductCache cache = mock(ProductCache.class);
    UUID eventId = UUID.randomUUID();
    var draft = new ProductRepository.ProductDraft("p-1", "Name", "", 100, "AUD", 2, true, true);
    var event = new ProductRepository.CatalogEvent(eventId.toString(), "p-1", 1, 1, "PUBLISHED");
    when(repository.publish(draft, eventId))
        .thenReturn(new ProductRepository.Publication(event, true));
    org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("controlled"))
        .when(cache)
        .evict("p-1");
    TransactionSynchronizationManager.initSynchronization();

    new ProductPublicationService(repository, cache).publish(draft, eventId);

    assertDoesNotThrow(
        () ->
            TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchronization -> synchronization.afterCommit()));
    verify(repository).publish(draft, eventId);
  }

  @Test
  void publisherFailureRemainsPendingAndVisible() throws Exception {
    ProductRepository repository = mock(ProductRepository.class);
    var event = new ProductRepository.OutboxEvent("event-1", "{}");
    when(repository.pendingOutbox(10)).thenReturn(List.of(event));
    CatalogOutboxPublisher.CatalogEventSender sender =
        mock(CatalogOutboxPublisher.CatalogEventSender.class);
    org.mockito.Mockito.doThrow(new IllegalStateException("controlled publisher failure"))
        .when(sender)
        .send(event);

    assertThrows(
        IllegalStateException.class,
        () -> new CatalogOutboxPublisher(repository, sender).publishPending(10));
    verify(repository).recordPublishFailure("event-1");
    verify(repository, never()).markPublished("event-1");
  }

  @Test
  void staleAndDuplicateEventsRebuildOnlyFromCurrentMysql() {
    ProductRepository repository = mock(ProductRepository.class);
    ProductCache cache = mock(ProductCache.class);
    var stale =
        new ProductRepository.CatalogEvent(UUID.randomUUID().toString(), "p-1", 1, 1, "PUBLISHED");
    Product current = new Product("p-1", "Current", "", 100, "AUD", 2, true, 3);
    when(repository.parseEvent("payload")).thenReturn(stale);
    when(repository.catalogGeneration()).thenReturn(4L);
    when(repository.findPublished("p-1")).thenReturn(Optional.of(current));
    when(repository.publishedIds()).thenReturn(List.of("p-1"));
    ProductInvalidationHandler handler = new ProductInvalidationHandler(repository, cache);

    handler.handle("payload");
    handler.handle("payload");

    verify(cache, org.mockito.Mockito.times(2)).put(current, 4L);
    verify(cache, org.mockito.Mockito.times(2)).rebuildBloom(4L, List.of("p-1"));
  }

  @Test
  void invalidEventIsRejectedBeforeMysqlOrCacheMutation() {
    ProductRepository repository = mock(ProductRepository.class);
    ProductCache cache = mock(ProductCache.class);
    when(repository.parseEvent("payload"))
        .thenReturn(new ProductRepository.CatalogEvent("not-a-uuid", "p-1", 1, 1, "PUBLISHED"));

    assertThrows(
        IllegalArgumentException.class,
        () -> new ProductInvalidationHandler(repository, cache).handle("payload"));

    verify(repository, never()).catalogGeneration();
    verifyNoInteractions(cache);
  }

  @Test
  void operationalWorkerInvokesOutboxAndInvalidationPaths() throws Exception {
    ProductRepository repository = mock(ProductRepository.class);
    CatalogOutboxPublisher.CatalogEventSender sender =
        mock(CatalogOutboxPublisher.CatalogEventSender.class);
    CatalogOutboxPublisher publisher = new CatalogOutboxPublisher(repository, sender);
    RocketMqCatalogMessaging messaging = mock(RocketMqCatalogMessaging.class);
    ProductInvalidationHandler handler =
        new ProductInvalidationHandler(repository, mock(ProductCache.class));
    when(repository.pendingOutbox(100)).thenReturn(List.of());
    when(messaging.consumeOnce(handler)).thenReturn(2);
    CatalogEventWorker worker = new CatalogEventWorker(publisher, messaging, handler);

    worker.runOnce();

    verify(repository).pendingOutbox(100);
    verify(messaging).consumeOnce(handler);
  }
}
