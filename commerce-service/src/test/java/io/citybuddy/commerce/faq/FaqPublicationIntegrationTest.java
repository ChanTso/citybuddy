package io.citybuddy.commerce.faq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.catalog.CatalogProperties;
import io.citybuddy.commerce.catalog.RocketMqCatalogMessaging;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest
class FaqPublicationIntegrationTest {
  @DynamicPropertySource
  static void integrationProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("spring.data.redis.url", () -> required("CATALOG_REDIS_URL"));
    registry.add("citybuddy.catalog.enabled", () -> "true");
    registry.add("citybuddy.catalog.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.catalog.user-audience", () -> "citybuddy-web");
    registry.add("citybuddy.catalog.jwks-url", () -> required("IDENTITY_JWKS_URL"));
    registry.add("citybuddy.catalog.worker-initial-delay-ms", () -> "3600000");
    registry.add("citybuddy.catalog.worker-delay-ms", () -> "3600000");
    registry.add("citybuddy.catalog.rocketmq-endpoints", () -> required("ROCKETMQ_ENDPOINTS"));
    registry.add("citybuddy.catalog.rocketmq-topic", () -> required("ROCKETMQ_TOPIC"));
    registry.add(
        "citybuddy.catalog.rocketmq-consumer-group", () -> required("ROCKETMQ_CONSUMER_GROUP"));
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private CatalogProperties properties;
  @Autowired private FaqRepository repository;
  @Autowired private FaqKnowledgeEventCodec codec;
  @Autowired private FaqPublicationService service;
  @Autowired private RocketMqCatalogMessaging messaging;
  @Autowired private TransactionTemplate transactions;

  @Test
  void provesFaqPublicationTruthAtomicityIdempotencyAndNormalOutboxRecovery() throws Exception {
    proveDraftIsolationBoundsAndFirstRevisedPublication();
    proveRollbackAfterBusinessAndOutboxWrites();
    proveConcurrentReplayAndConflictingIdentityConvergence();
    proveNormalMessageFailureRetryRestartAndDuplicateDelivery();
  }

  private void proveDraftIsolationBoundsAndFirstRevisedPublication() {
    FaqRepository.FaqSource draft =
        service.saveDraft("faq-main", "How do returns work?", "Returns take five days.", 0);
    assertThat(draft.workingState()).isEqualTo("DRAFT");
    assertThat(draft.draftRevision()).isEqualTo(1);
    assertThat(draft.publishedVersion()).isZero();
    assertThat(draft.publishedQuestion()).isNull();
    assertThat(faqOutboxCount("faq-main")).isZero();

    assertCode(
        () -> service.saveDraft("FAQ INVALID", "Question", "Answer", 0),
        FaqPublicationException.Code.VALIDATION);
    assertCode(
        () -> service.saveDraft("faq-blank", " ", "Answer", 0),
        FaqPublicationException.Code.VALIDATION);
    assertCode(
        () ->
            service.saveDraft(
                "faq-oversized",
                "q".repeat(FaqKnowledgeEventCodec.MAX_QUESTION_LENGTH + 1),
                "Answer",
                0),
        FaqPublicationException.Code.VALIDATION);
    assertCode(
        () ->
            service.saveDraft(
                "faq-oversized-answer",
                "Question",
                "a".repeat(FaqKnowledgeEventCodec.MAX_ANSWER_LENGTH + 1),
                0),
        FaqPublicationException.Code.VALIDATION);

    service.saveDraft("faq-escaped-bound", "Escaped answer?", "x" + "\0".repeat(1000), 0);
    FaqPublicationService.PublicationCommand escaped =
        command("faq-escaped-bound", "faq-escaped-bound", 1, 0);
    FaqPublicationService.PublicationResult escapedPublished = service.publish(escaped);
    assertThat(service.publish(escaped).event()).isEqualTo(escapedPublished.event());

    service.saveDraft("faq-expanded-oversize", "Expanded answer?", "x" + "\0".repeat(3999), 0);
    assertCode(
        () -> service.publish(command("faq-expanded-oversize", "faq-expanded-oversize", 1, 0)),
        FaqPublicationException.Code.VALIDATION);
    assertSource("faq-expanded-oversize", 1, 0, "DRAFT", null);
    assertThat(faqCommandCount("faq-expanded-oversize")).isZero();
    assertThat(faqOutboxCount("faq-expanded-oversize")).isZero();

    FaqPublicationService.PublicationCommand first = command("faq-main", "faq-main-first", 1, 0);
    FaqPublicationService.PublicationResult published = service.publish(first);
    assertThat(published.replayed()).isFalse();
    assertThat(published.event().sourceVersion()).isEqualTo(1);
    assertThat(published.event().publicationState()).isEqualTo("PUBLISHED");
    assertThat(published.event().tombstone()).isFalse();
    assertThat(published.event().content().question()).isEqualTo("How do returns work?");
    assertThat(faqOutboxCount("faq-main")).isEqualTo(1);
    assertThat(faqCommandCount("faq-main")).isEqualTo(1);
    assertSource("faq-main", 1, 1, "PUBLISHED", "How do returns work?");

    FaqPublicationService.PublicationResult replay = service.publish(first);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.event()).isEqualTo(published.event());
    assertThat(faqOutboxCount("faq-main")).isEqualTo(1);
    assertThat(faqCommandCount("faq-main")).isEqualTo(1);

    assertCode(
        () ->
            service.publish(
                new FaqPublicationService.PublicationCommand(
                    first.faqId(),
                    first.idempotencyKey(),
                    UUID.randomUUID().toString(),
                    first.expectedDraftRevision(),
                    first.expectedPublishedVersion())),
        FaqPublicationException.Code.IDEMPOTENCY_CONFLICT);
    service.saveDraft("faq-rebound-source", "Another FAQ?", "Another answer.", 0);
    assertCode(
        () ->
            service.publish(
                new FaqPublicationService.PublicationCommand(
                    "faq-rebound-source",
                    first.idempotencyKey(),
                    first.eventId(),
                    first.expectedDraftRevision(),
                    first.expectedPublishedVersion())),
        FaqPublicationException.Code.IDEMPOTENCY_CONFLICT);
    assertCode(
        () ->
            service.publish(
                new FaqPublicationService.PublicationCommand(
                    first.faqId(),
                    first.idempotencyKey(),
                    first.eventId(),
                    first.expectedDraftRevision() + 1,
                    first.expectedPublishedVersion())),
        FaqPublicationException.Code.IDEMPOTENCY_CONFLICT);
    assertCode(
        () ->
            service.publish(
                new FaqPublicationService.PublicationCommand(
                    first.faqId(),
                    first.idempotencyKey(),
                    first.eventId(),
                    first.expectedDraftRevision(),
                    first.expectedPublishedVersion() + 1)),
        FaqPublicationException.Code.IDEMPOTENCY_CONFLICT);
    assertCode(
        () ->
            service.publish(
                new FaqPublicationService.PublicationCommand(
                    first.faqId(),
                    "faq-main-rebound-event",
                    first.eventId(),
                    first.expectedDraftRevision(),
                    first.expectedPublishedVersion())),
        FaqPublicationException.Code.IDEMPOTENCY_CONFLICT);
    assertCode(
        () -> service.publish(command("faq-main", "faq-main-stale", 1, 0)),
        FaqPublicationException.Code.STALE_VERSION);

    proveCommittedOutboxColumnsRejectCorruptReplay(first, published);
    assertCode(
        () -> service.publish(command("faq-main", "faq-main-repeat-revision", 1, 1)),
        FaqPublicationException.Code.STALE_VERSION);
    assertSource("faq-main", 1, 1, "PUBLISHED", "How do returns work?");
    assertThat(faqCommandCount("faq-main")).isEqualTo(1);
    assertThat(faqOutboxCount("faq-main")).isEqualTo(1);

    FaqRepository.FaqSource revised =
        service.saveDraft("faq-main", "Can I return an item?", "Yes, within five days.", 1);
    assertThat(revised.workingState()).isEqualTo("DRAFT");
    assertThat(revised.draftRevision()).isEqualTo(2);
    assertThat(revised.publishedVersion()).isEqualTo(1);
    assertThat(revised.publishedQuestion()).isEqualTo("How do returns work?");
    assertThat(faqOutboxCount("faq-main")).isEqualTo(1);
    assertCode(
        () -> service.saveDraft("faq-main", "Stale edit", "Must reject", 1),
        FaqPublicationException.Code.STALE_VERSION);

    FaqPublicationService.PublicationResult second =
        service.publish(command("faq-main", "faq-main-second", 2, 1));
    assertThat(second.event().sourceVersion()).isEqualTo(2);
    assertThat(second.event().content().question()).isEqualTo("Can I return an item?");
    assertSource("faq-main", 2, 2, "PUBLISHED", "Can I return an item?");
    assertThat(faqOutboxCount("faq-main")).isEqualTo(2);

    FaqPublicationService.PublicationResult historicalReplay = service.publish(first);
    assertThat(historicalReplay.replayed()).isTrue();
    assertThat(historicalReplay.event()).isEqualTo(published.event());
    assertSource("faq-main", 2, 2, "PUBLISHED", "Can I return an item?");
    assertThat(faqOutboxCount("faq-main")).isEqualTo(2);
  }

  private void proveRollbackAfterBusinessAndOutboxWrites() {
    service.saveDraft("faq-rollback-command", "Rollback command?", "Yes.", 0);
    FaqRepository failBeforeCommand =
        new FaqRepository(jdbc) {
          @Override
          public void insertCommand(PublicationCommand command) {
            throw new IllegalStateException("Controlled failure after FAQ truth write");
          }
        };
    FaqPublicationService beforeCommand =
        new FaqPublicationService(failBeforeCommand, codec, Clock.systemUTC());
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    ignored ->
                        beforeCommand.publish(
                            command("faq-rollback-command", "rollback-command", 1, 0))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Controlled failure after FAQ truth write");
    assertSource("faq-rollback-command", 1, 0, "DRAFT", null);
    assertThat(faqCommandCount("faq-rollback-command")).isZero();
    assertThat(faqOutboxCount("faq-rollback-command")).isZero();

    service.saveDraft("faq-rollback-outbox", "Rollback Outbox?", "Yes.", 0);
    FaqRepository failAfterOutbox =
        new FaqRepository(jdbc) {
          @Override
          public void insertOutbox(PublicationCommand command, String payload) {
            super.insertOutbox(command, payload);
            throw new IllegalStateException("Controlled failure after FAQ Outbox write");
          }
        };
    FaqPublicationService afterOutbox =
        new FaqPublicationService(failAfterOutbox, codec, Clock.systemUTC());
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    ignored ->
                        afterOutbox.publish(
                            command("faq-rollback-outbox", "rollback-outbox", 1, 0))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Controlled failure after FAQ Outbox write");
    assertSource("faq-rollback-outbox", 1, 0, "DRAFT", null);
    assertThat(faqCommandCount("faq-rollback-outbox")).isZero();
    assertThat(faqOutboxCount("faq-rollback-outbox")).isZero();
  }

  private void proveCommittedOutboxColumnsRejectCorruptReplay(
      FaqPublicationService.PublicationCommand command,
      FaqPublicationService.PublicationResult originalResult) {
    FaqRepository.OutboxEvent original = repository.findOutbox(command.eventId()).orElseThrow();
    String changedEventId = UUID.randomUUID().toString();
    List<OutboxCorruption> corruptions =
        List.of(
            new OutboxCorruption(
                () ->
                    updateOne(
                        "UPDATE commerce_outbox SET event_id = ? WHERE event_id = ?",
                        changedEventId,
                        original.eventId()),
                changedEventId),
            new OutboxCorruption(
                () ->
                    updateOne(
                        "UPDATE commerce_outbox SET aggregate_type = 'FAQ_CORRUPT' WHERE event_id = ?",
                        original.eventId()),
                original.eventId()),
            new OutboxCorruption(
                () ->
                    updateOne(
                        "UPDATE commerce_outbox SET aggregate_id = 'faq-corrupt' WHERE event_id = ?",
                        original.eventId()),
                original.eventId()),
            new OutboxCorruption(
                () ->
                    updateOne(
                        "UPDATE commerce_outbox SET aggregate_version = aggregate_version + 1 WHERE event_id = ?",
                        original.eventId()),
                original.eventId()),
            new OutboxCorruption(
                () ->
                    updateOne(
                        "UPDATE commerce_outbox SET event_type = 'FAQ_CORRUPT' WHERE event_id = ?",
                        original.eventId()),
                original.eventId()),
            jsonCorruption(original, "$.eventId", UUID.randomUUID().toString()),
            jsonCorruption(original, "$.sourceId", "faq-corrupt"),
            jsonCorruption(original, "$.sourceType", "product"),
            jsonCorruption(original, "$.sourceVersion", 99),
            jsonCorruption(original, "$.publicationState", "DRAFT"),
            jsonCorruption(original, "$.tombstone", true),
            jsonCorruption(original, "$.occurredTime", "2026-07-20T09:00:00Z"),
            jsonCorruption(original, "$.content.question", "Changed but bounded question?"),
            jsonCorruption(original, "$.content.answer", "Changed but bounded answer."));

    for (OutboxCorruption corruption : corruptions) {
      corruption.inject().run();
      try {
        assertCode(
            () -> service.publish(command),
            FaqPublicationException.Code.INCONSISTENT_DURABLE_STATE);
      } finally {
        restoreOutbox(original, corruption.currentEventId());
      }
      FaqPublicationService.PublicationResult replay = service.publish(command);
      assertThat(replay.replayed()).isTrue();
      assertThat(replay.event()).isEqualTo(originalResult.event());
    }
  }

  private OutboxCorruption jsonCorruption(
      FaqRepository.OutboxEvent original, String path, Object value) {
    return new OutboxCorruption(
        () ->
            updateOne(
                "UPDATE commerce_outbox SET payload = JSON_SET(payload, ?, ?) WHERE event_id = ?",
                path,
                value,
                original.eventId()),
        original.eventId());
  }

  private void restoreOutbox(FaqRepository.OutboxEvent original, String currentEventId) {
    updateOne(
        """
        UPDATE commerce_outbox
        SET event_id = ?, aggregate_type = ?, aggregate_id = ?, aggregate_version = ?,
            event_type = ?, payload = CAST(? AS JSON)
        WHERE event_id = ?
        """,
        original.eventId(),
        original.aggregateType(),
        original.aggregateId(),
        original.aggregateVersion(),
        original.eventType(),
        original.payload(),
        currentEventId);
  }

  private void updateOne(String sql, Object... arguments) {
    assertThat(jdbc.update(sql, arguments)).isEqualTo(1);
  }

  private void proveConcurrentReplayAndConflictingIdentityConvergence() throws Exception {
    service.saveDraft("faq-concurrent-replay", "Concurrent replay?", "Converges.", 0);
    FaqPublicationService.PublicationCommand shared =
        command("faq-concurrent-replay", "faq-concurrent-shared", 1, 0);
    CountDownLatch ready = new CountDownLatch(8);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(8);
    List<java.util.concurrent.Future<FaqPublicationService.PublicationResult>> futures =
        new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      futures.add(
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return service.publish(shared);
              }));
    }
    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    int firstResults = 0;
    for (var future : futures) {
      if (!future.get(20, TimeUnit.SECONDS).replayed()) {
        firstResults++;
      }
    }
    executor.shutdownNow();
    assertThat(firstResults).isEqualTo(1);
    assertSource("faq-concurrent-replay", 1, 1, "PUBLISHED", "Concurrent replay?");
    assertThat(faqCommandCount("faq-concurrent-replay")).isEqualTo(1);
    assertThat(faqOutboxCount("faq-concurrent-replay")).isEqualTo(1);

    service.saveDraft("faq-concurrent-publish", "Publish once?", "Exactly once.", 0);
    FaqPublicationService.PublicationCommand publishLeft =
        command("faq-concurrent-publish", "faq-concurrent-publish-left", 1, 0);
    FaqPublicationService.PublicationCommand publishRight =
        command("faq-concurrent-publish", "faq-concurrent-publish-right", 1, 0);
    CountDownLatch publishReady = new CountDownLatch(2);
    CountDownLatch publishStart = new CountDownLatch(1);
    var publishExecutor = Executors.newFixedThreadPool(2);
    List<java.util.concurrent.Future<String>> publications = new ArrayList<>();
    for (FaqPublicationService.PublicationCommand candidate : List.of(publishLeft, publishRight)) {
      publications.add(
          publishExecutor.submit(
              () -> {
                publishReady.countDown();
                publishStart.await();
                try {
                  service.publish(candidate);
                  return "SUCCESS";
                } catch (FaqPublicationException exception) {
                  return exception.code().name();
                }
              }));
    }
    assertThat(publishReady.await(5, TimeUnit.SECONDS)).isTrue();
    publishStart.countDown();
    List<String> publicationResults = new ArrayList<>();
    for (var publication : publications) {
      publicationResults.add(publication.get(20, TimeUnit.SECONDS));
    }
    publishExecutor.shutdownNow();
    assertThat(publicationResults)
        .containsExactlyInAnyOrder("SUCCESS", FaqPublicationException.Code.STALE_VERSION.name());
    assertSource("faq-concurrent-publish", 1, 1, "PUBLISHED", "Publish once?");
    assertThat(faqCommandCount("faq-concurrent-publish")).isEqualTo(1);
    assertThat(faqOutboxCount("faq-concurrent-publish")).isEqualTo(1);

    service.saveDraft("faq-concurrent-conflict", "Concurrent conflict?", "One wins.", 0);
    FaqPublicationService.PublicationCommand left =
        command("faq-concurrent-conflict", "faq-concurrent-conflict-key", 1, 0);
    FaqPublicationService.PublicationCommand right =
        new FaqPublicationService.PublicationCommand(
            left.faqId(),
            left.idempotencyKey(),
            UUID.randomUUID().toString(),
            left.expectedDraftRevision(),
            left.expectedPublishedVersion());
    CountDownLatch conflictReady = new CountDownLatch(2);
    CountDownLatch conflictStart = new CountDownLatch(1);
    var conflictExecutor = Executors.newFixedThreadPool(2);
    List<java.util.concurrent.Future<String>> conflicts = new ArrayList<>();
    for (FaqPublicationService.PublicationCommand candidate : List.of(left, right)) {
      conflicts.add(
          conflictExecutor.submit(
              () -> {
                conflictReady.countDown();
                conflictStart.await();
                try {
                  service.publish(candidate);
                  return "SUCCESS";
                } catch (FaqPublicationException exception) {
                  return exception.code().name();
                }
              }));
    }
    assertThat(conflictReady.await(5, TimeUnit.SECONDS)).isTrue();
    conflictStart.countDown();
    List<String> results = new ArrayList<>();
    for (var conflict : conflicts) {
      results.add(conflict.get(20, TimeUnit.SECONDS));
    }
    conflictExecutor.shutdownNow();
    assertThat(results)
        .containsExactlyInAnyOrder(
            "SUCCESS", FaqPublicationException.Code.IDEMPOTENCY_CONFLICT.name());
    assertSource("faq-concurrent-conflict", 1, 1, "PUBLISHED", "Concurrent conflict?");
    assertThat(faqCommandCount("faq-concurrent-conflict")).isEqualTo(1);
    assertThat(faqOutboxCount("faq-concurrent-conflict")).isEqualTo(1);
  }

  private void proveNormalMessageFailureRetryRestartAndDuplicateDelivery() throws Exception {
    service.saveDraft("faq-delivery", "Will delivery retry?", "Yes.", 0);
    FaqPublicationService.PublicationResult publication =
        service.publish(command("faq-delivery", "faq-delivery-first", 1, 0));
    List<FaqRepository.OutboxEvent> pending = repository.pendingOutbox(100);
    assertThat(pending).isNotEmpty();
    FaqRepository.OutboxEvent firstPending = pending.getFirst();
    FaqKnowledgeEvent failedEvent = codec.decode(firstPending.payload());
    FaqRepository.FaqSource failedSourceBefore =
        repository.findSource(failedEvent.sourceId()).orElseThrow();

    CatalogProperties closedProperties =
        new CatalogProperties(
            properties.issuer(),
            properties.userAudience(),
            properties.jwksUrl(),
            properties.jwksCacheTtl(),
            properties.clockSkew(),
            properties.requiredPermission(),
            properties.cacheTtl(),
            properties.cacheJitter(),
            properties.nullTtl(),
            properties.mutexTtl(),
            properties.rocketmqEndpoints(),
            properties.rocketmqTopic(),
            properties.rocketmqConsumerGroup() + "-faq-closed");
    RocketMqCatalogMessaging closed = new RocketMqCatalogMessaging(closedProperties);
    closed.close();
    assertThatThrownBy(() -> new FaqOutboxPublisher(repository, closed).publishPending(1))
        .isInstanceOf(Exception.class);
    assertThat(outboxState(firstPending.eventId())).isEqualTo("PENDING:1");
    assertThat(repository.findSource(failedEvent.sourceId())).contains(failedSourceBefore);
    assertSource("faq-delivery", 1, 1, "PUBLISHED", "Will delivery retry?");

    try (SimpleConsumer consumer = faqConsumer()) {
      int expected = repository.pendingOutbox(100).size();
      FaqOutboxPublisher restarted = new FaqOutboxPublisher(repository, messaging);
      assertThat(restarted.publishPending(100)).isEqualTo(expected);
      assertThat(repository.pendingOutbox(100)).isEmpty();
      List<FaqKnowledgeEvent> delivered = receive(consumer, expected, Duration.ofSeconds(45));
      assertThat(delivered).hasSizeGreaterThanOrEqualTo(expected);
      assertThat(delivered)
          .anySatisfy(
              event -> {
                assertThat(event.eventId()).isEqualTo(publication.event().eventId());
                assertThat(event.sourceId()).isEqualTo("faq-delivery");
                assertThat(event.tombstone()).isFalse();
              });

      FaqRepository.OutboxEvent duplicate =
          repository.findOutbox(publication.event().eventId()).orElseThrow();
      messaging.send(duplicate);
      List<FaqKnowledgeEvent> redelivered = receive(consumer, 1, Duration.ofSeconds(30));
      assertThat(redelivered).anySatisfy(event -> assertThat(event).isEqualTo(publication.event()));
      assertSource("faq-delivery", 1, 1, "PUBLISHED", "Will delivery retry?");
      assertThat(outboxState(publication.event().eventId())).startsWith("PUBLISHED:");

      service.saveDraft("faq-mark-window", "What if marking fails?", "Retry safely.", 0);
      FaqPublicationService.PublicationResult markWindow =
          service.publish(command("faq-mark-window", "faq-mark-window-first", 1, 0));
      FaqRepository markFailure =
          new FaqRepository(jdbc) {
            @Override
            public void markPublished(String eventId) {
              throw new IllegalStateException("Controlled failure after broker acceptance");
            }
          };
      assertThatThrownBy(() -> new FaqOutboxPublisher(markFailure, messaging).publishPending(1))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Controlled failure after broker acceptance");
      assertThat(outboxState(markWindow.event().eventId())).isEqualTo("PENDING:1");
      assertThat(receive(consumer, 1, Duration.ofSeconds(30)))
          .anySatisfy(event -> assertThat(event).isEqualTo(markWindow.event()));

      FaqOutboxPublisher afterMarkFailure = new FaqOutboxPublisher(repository, messaging);
      assertThat(afterMarkFailure.publishPending(1)).isEqualTo(1);
      assertThat(receive(consumer, 1, Duration.ofSeconds(30)))
          .anySatisfy(event -> assertThat(event).isEqualTo(markWindow.event()));
      assertThat(outboxState(markWindow.event().eventId())).isEqualTo("PUBLISHED:2");
      assertSource("faq-mark-window", 1, 1, "PUBLISHED", "What if marking fails?");
    }

    FaqPublicationService restartedService =
        new FaqPublicationService(repository, codec, Clock.systemUTC());
    FaqPublicationService.PublicationResult restartReplay =
        transactions.execute(
            ignored ->
                restartedService.publish(
                    new FaqPublicationService.PublicationCommand(
                        "faq-delivery",
                        "faq-delivery-first",
                        publication.event().eventId(),
                        1,
                        0)));
    assertThat(restartReplay).isNotNull();
    assertThat(restartReplay.replayed()).isTrue();
    assertThat(restartReplay.event()).isEqualTo(publication.event());
  }

  private SimpleConsumer faqConsumer() throws Exception {
    ClientConfiguration configuration =
        ClientConfiguration.newBuilder()
            .setEndpoints(properties.rocketmqEndpoints())
            .setRequestTimeout(Duration.ofSeconds(10))
            .enableSsl(false)
            .build();
    return ClientServiceProvider.loadService()
        .newSimpleConsumerBuilder()
        .setClientConfiguration(configuration)
        .setConsumerGroup(properties.rocketmqConsumerGroup() + "-faq")
        .setAwaitDuration(Duration.ofSeconds(2))
        .setSubscriptionExpressions(
            Collections.singletonMap(
                properties.rocketmqTopic(),
                new FilterExpression(RocketMqCatalogMessaging.FAQ_TAG, FilterExpressionType.TAG)))
        .build();
  }

  private List<FaqKnowledgeEvent> receive(SimpleConsumer consumer, int expected, Duration timeout)
      throws Exception {
    List<FaqKnowledgeEvent> events = new ArrayList<>();
    long deadline = System.nanoTime() + timeout.toNanos();
    while (events.size() < expected && System.nanoTime() < deadline) {
      for (MessageView message : consumer.receive(16, Duration.ofSeconds(15))) {
        events.add(codec.decode(body(message)));
        consumer.ack(message);
      }
    }
    return events;
  }

  private void assertSource(
      String faqId,
      long draftRevision,
      long publishedVersion,
      String state,
      String publishedQuestion) {
    FaqRepository.FaqSource source = repository.findSource(faqId).orElseThrow();
    assertThat(source.draftRevision()).isEqualTo(draftRevision);
    assertThat(source.publishedVersion()).isEqualTo(publishedVersion);
    assertThat(source.workingState()).isEqualTo(state);
    assertThat(source.publishedQuestion()).isEqualTo(publishedQuestion);
  }

  private long faqOutboxCount(String faqId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type = 'FAQ' AND aggregate_id = ?",
        Long.class,
        faqId);
  }

  private long faqCommandCount(String faqId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM faq_publication_command WHERE faq_id = ?", Long.class, faqId);
  }

  private String outboxState(String eventId) {
    return jdbc.queryForObject(
        "SELECT CONCAT(publication_state, ':', publish_attempts) FROM commerce_outbox WHERE event_id = ?",
        String.class,
        eventId);
  }

  private static FaqPublicationService.PublicationCommand command(
      String faqId, String idempotencyKey, long expectedDraftRevision, long expectedVersion) {
    return new FaqPublicationService.PublicationCommand(
        faqId,
        idempotencyKey,
        UUID.randomUUID().toString(),
        expectedDraftRevision,
        expectedVersion);
  }

  private static String body(MessageView message) {
    ByteBuffer buffer = message.getBody();
    byte[] bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static void assertCode(
      org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
      FaqPublicationException.Code code) {
    assertThatThrownBy(callable)
        .isInstanceOf(FaqPublicationException.class)
        .extracting(exception -> ((FaqPublicationException) exception).code())
        .isEqualTo(code);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }

  private record OutboxCorruption(Runnable inject, String currentEventId) {}
}
