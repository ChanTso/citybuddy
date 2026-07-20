package io.citybuddy.commerce.faq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.catalog.CatalogProperties;
import io.citybuddy.commerce.catalog.RocketMqCatalogMessaging;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
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
  private SingleConnectionDataSource corruptionDataSource;
  private JdbcTemplate corruptionJdbc;

  @AfterEach
  void closeCorruptionConnection() {
    if (corruptionDataSource != null) {
      corruptionDataSource.destroy();
    }
  }

  @Test
  void provesFaqPublicationTruthAtomicityIdempotencyAndNormalOutboxRecovery() throws Exception {
    FaqPublicationService.PublicationResult payloadBoundary =
        proveDraftIsolationBoundsAndFirstRevisedPublication();
    proveRollbackAfterBusinessAndOutboxWrites();
    proveConcurrentReplayAndConflictingIdentityConvergence();
    proveNormalMessageFailureRetryRestartAndDuplicateDelivery(payloadBoundary);
  }

  private FaqPublicationService.PublicationResult
      proveDraftIsolationBoundsAndFirstRevisedPublication() throws Exception {
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

    Instant boundaryTime =
        Clock.systemUTC()
            .instant()
            .plusSeconds(60)
            .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    FaqPublicationService.PublicationCommand boundaryCommand =
        command("faq-json-boundary-a", "faq-json-boundary-a", 1, 0);
    FaqPublicationService boundaryService =
        new FaqPublicationService(repository, codec, Clock.fixed(boundaryTime, ZoneOffset.UTC));
    BoundaryAnswers boundary = boundaryAnswers(boundaryCommand, boundaryTime);
    service.saveDraft("faq-json-boundary-a", "q", boundary.accepted(), 0);
    FaqPublicationService.PublicationResult boundaryPublished =
        transactions.execute(ignored -> boundaryService.publish(boundaryCommand));
    assertThat(boundaryPublished).isNotNull();
    String compactPayload = codec.encode(boundaryPublished.event());
    FaqRepository.OutboxEvent durableBoundary =
        repository.findOutbox(boundaryCommand.eventId()).orElseThrow();
    assertThat(utf8Bytes(compactPayload)).isEqualTo(boundary.acceptedCompactBytes());
    assertThat(utf8Bytes(durableBoundary.payload()))
        .isEqualTo(
            boundary.acceptedCompactBytes()
                + FaqKnowledgeEventCodec.MYSQL_JSON_NORMALIZATION_OVERHEAD_BYTES)
        .isLessThanOrEqualTo(FaqKnowledgeEventCodec.MAX_DURABLE_PAYLOAD_BYTES);
    assertThat(codec.decode(durableBoundary.payload())).isEqualTo(boundaryPublished.event());
    FaqPublicationService.PublicationResult boundaryReplay =
        transactions.execute(ignored -> boundaryService.publish(boundaryCommand));
    assertThat(boundaryReplay).isNotNull();
    assertThat(boundaryReplay.replayed()).isTrue();
    assertThat(boundaryReplay.event()).isEqualTo(boundaryPublished.event());

    FaqPublicationService.PublicationCommand overLimitCommand =
        command("faq-json-boundary-b", "faq-json-boundary-b", 1, 0);
    BoundaryAnswers overLimitBoundary = boundaryAnswers(overLimitCommand, boundaryTime);
    assertThat(overLimitBoundary.acceptedCompactBytes()).isEqualTo(boundary.acceptedCompactBytes());
    assertThat(overLimitBoundary.rejectedCompactBytes())
        .isGreaterThan(FaqKnowledgeEventCodec.MAX_COMPACT_PAYLOAD_BYTES);
    service.saveDraft("faq-json-boundary-b", "q", overLimitBoundary.rejected(), 0);
    assertCode(
        () -> transactions.execute(ignored -> boundaryService.publish(overLimitCommand)),
        FaqPublicationException.Code.VALIDATION);
    assertSource("faq-json-boundary-b", 1, 0, "DRAFT", null);
    assertThat(faqCommandCount("faq-json-boundary-b")).isZero();
    assertThat(faqOutboxCount("faq-json-boundary-b")).isZero();

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

    FaqPublicationService.PublicationCommand secondCommand =
        command("faq-main", "faq-main-second", 2, 1);
    FaqPublicationService.PublicationResult second = service.publish(secondCommand);
    assertThat(second.event().sourceVersion()).isEqualTo(2);
    assertThat(second.event().content().question()).isEqualTo("Can I return an item?");
    assertSource("faq-main", 2, 2, "PUBLISHED", "Can I return an item?");
    assertThat(faqOutboxCount("faq-main")).isEqualTo(2);

    proveCommittedOutboxColumnsRejectCorruptReplay(secondCommand, second, "PUBLISHED");

    FaqPublicationService.PublicationResult historicalReplay = service.publish(first);
    assertThat(historicalReplay.replayed()).isTrue();
    assertThat(historicalReplay.event()).isEqualTo(published.event());
    assertSource("faq-main", 2, 2, "PUBLISHED", "Can I return an item?");
    assertThat(faqOutboxCount("faq-main")).isEqualTo(2);
    proveDraftStateSchemaColumnClosure();
    return boundaryPublished;
  }

  private void proveDraftStateSchemaColumnClosure() throws Exception {
    service.saveDraft("faq-draft-matrix", "Draft v1?", "Answer v1.", 0);
    FaqPublicationService.PublicationCommand first =
        command("faq-draft-matrix", "faq-draft-matrix-v1", 1, 0);
    service.publish(first);
    service.saveDraft("faq-draft-matrix", "Draft v2?", "Answer v2.", 1);
    FaqPublicationService.PublicationCommand second =
        command("faq-draft-matrix", "faq-draft-matrix-v2", 2, 1);
    FaqPublicationService.PublicationResult published = service.publish(second);
    service.saveDraft("faq-draft-matrix", "Draft v3?", "Answer v3.", 2);
    assertSource("faq-draft-matrix", 3, 2, "DRAFT", "Draft v2?");
    proveCommittedOutboxColumnsRejectCorruptReplay(second, published, "DRAFT");
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
      FaqPublicationService.PublicationResult originalResult,
      String reachableState)
      throws Exception {
    FaqRepository.OutboxEvent original = repository.findOutbox(command.eventId()).orElseThrow();
    FaqRepository.PublicationCommand durableCommand =
        repository.findCommandsByIdentity(command.idempotencyKey(), command.eventId()).getFirst();
    FaqRepository.FaqSource durableSource = repository.findSource(command.faqId()).orElseThrow();
    assertThat(durableSource.workingState()).isEqualTo(reachableState);
    FaqRepository.DraftCommand durableDraft =
        repository.allDraftCommands().stream()
            .filter(candidate -> candidate.faqId().equals(command.faqId()))
            .max(java.util.Comparator.comparingLong(FaqRepository.DraftCommand::draftRevision))
            .orElseThrow();
    boolean draftState = "DRAFT".equals(reachableState);
    String changedEventId = UUID.randomUUID().toString();
    String changedCommandEventId = UUID.randomUUID().toString();
    String changedIdempotencyKey = durableCommand.idempotencyKey() + "-corrupt";
    List<SchemaCorruption> physicalCorruptions =
        List.of(
            sourceCorruption(
                "faq_id",
                "anchored source identity",
                "faq_id = 'faq-corrupt-source'",
                durableSource,
                "faq-corrupt-source"),
            sourceCorruption(
                "draft_question",
                "published-state draft/public pair is database-bound and anchored to the event",
                draftState
                    ? "draft_question = 'Corrupt question?'"
                    : "draft_question = 'Corrupt question?', published_question = 'Corrupt question?'",
                durableSource,
                durableSource.faqId()),
            sourceCorruption(
                "draft_answer",
                "published-state draft/public pair is database-bound and anchored to the event",
                draftState
                    ? "draft_answer = 'Corrupt answer.'"
                    : "draft_answer = 'Corrupt answer.', published_answer = 'Corrupt answer.'",
                durableSource,
                durableSource.faqId()),
            sourceCorruption(
                "draft_revision",
                "published-state draft revision is anchored to the command",
                "draft_revision = draft_revision + 1",
                durableSource,
                durableSource.faqId()),
            sourceCorruption(
                "working_state",
                "state-machine predecessor is anchored to the revision transition",
                draftState
                    ? "working_state = 'PUBLISHED', draft_question = published_question, draft_answer = published_answer"
                    : "working_state = 'DRAFT'",
                durableSource,
                durableSource.faqId()),
            sourceCorruption(
                "published_question",
                "published content and its database-bound draft pair are anchored to the event",
                draftState
                    ? "published_question = 'Corrupt question?'"
                    : "published_question = 'Corrupt question?', draft_question = 'Corrupt question?'",
                durableSource,
                durableSource.faqId()),
            sourceCorruption(
                "published_answer",
                "published content and its database-bound draft pair are anchored to the event",
                draftState
                    ? "published_answer = 'Corrupt answer.'"
                    : "published_answer = 'Corrupt answer.', draft_answer = 'Corrupt answer.'",
                durableSource,
                durableSource.faqId()),
            sourceCorruption(
                "published_version",
                "published version is anchored exactly to the latest applied command and event",
                "published_version = published_version + 1",
                durableSource,
                durableSource.faqId()),
            sourceCorruption(
                "published_at",
                "publication time is anchored to command and event",
                "published_at = TIMESTAMPADD(SECOND, 1, published_at)",
                durableSource,
                durableSource.faqId()),
            sourceCorruption(
                "created_at",
                "database time obeys created_at <= published_at <= updated_at",
                "created_at = TIMESTAMPADD(DAY, 1, updated_at)",
                durableSource,
                durableSource.faqId()),
            sourceCorruption(
                "updated_at",
                "database time obeys created_at <= published_at <= updated_at",
                "updated_at = TIMESTAMPADD(DAY, -1, created_at)",
                durableSource,
                durableSource.faqId()),
            commandCorruption(
                "idempotency_key",
                "committed command identity",
                "idempotency_key = '" + changedIdempotencyKey + "'",
                durableCommand),
            commandCorruption(
                "event_id",
                "stable command/Outbox correlation identity",
                "event_id = '" + changedCommandEventId + "'",
                durableCommand),
            commandCorruption(
                "faq_id",
                "committed source identity",
                "faq_id = 'faq-corrupt-source'",
                durableCommand),
            commandCorruption(
                "expected_draft_revision",
                "committed publication intent",
                "expected_draft_revision = expected_draft_revision + 1",
                durableCommand),
            commandCorruption(
                "expected_published_version",
                "committed publication intent",
                "expected_published_version = expected_published_version + 1, source_version = source_version + 1",
                durableCommand),
            commandCorruption(
                "source_version",
                "committed event version",
                "source_version = source_version + 1, expected_published_version = expected_published_version + 1",
                durableCommand),
            commandCorruption(
                "intent_hash",
                "canonical content commitment",
                "intent_hash = REPEAT('0', 64)",
                durableCommand),
            commandCorruption(
                "occurred_at",
                "business event time anchor",
                "occurred_at = TIMESTAMPADD(SECOND, 1, occurred_at)",
                durableCommand),
            commandCorruption(
                "created_at",
                "database-generated column is invariant-bound to occurred_at",
                "created_at = TIMESTAMPADD(SECOND, 1, created_at)",
                durableCommand),
            draftCommandCorruption(
                "faq_id",
                "stable draft/source identity",
                "faq_id = 'faq-corrupt-source'",
                durableDraft),
            draftCommandCorruption(
                "draft_revision",
                "resulting draft revision",
                "draft_revision = draft_revision + 1, expected_draft_revision = expected_draft_revision + 1",
                durableDraft),
            draftCommandCorruption(
                "expected_draft_revision",
                "draft state-machine predecessor",
                "expected_draft_revision = expected_draft_revision + 1, draft_revision = draft_revision + 1",
                durableDraft),
            draftCommandCorruption(
                "draft_question",
                "committed current draft content",
                "draft_question = 'Corrupt draft?'",
                durableDraft),
            draftCommandCorruption(
                "draft_answer",
                "committed current draft content",
                "draft_answer = 'Corrupt draft.'",
                durableDraft),
            draftCommandCorruption(
                "intent_hash",
                "canonical draft commitment",
                "intent_hash = REPEAT('0', 64)",
                durableDraft),
            draftCommandCorruption(
                "occurred_at",
                "draft business event time",
                "occurred_at = TIMESTAMPADD(SECOND, 1, occurred_at)",
                durableDraft),
            draftCommandCorruption(
                "created_at",
                "draft persistence time anchored to event time",
                "created_at = TIMESTAMPADD(SECOND, 1, created_at)",
                durableDraft),
            outboxCorruption(
                "event_id",
                "stable command/Outbox correlation identity",
                () ->
                    corruptionUpdateOne(
                        "UPDATE commerce_outbox SET event_id = ? WHERE event_id = ?",
                        changedEventId,
                        original.eventId()),
                original,
                changedEventId),
            outboxCorruption(
                "aggregate_type",
                "committed ownership type asserted after enumeration; COALESCE predicate probe",
                () ->
                    corruptionUpdateOne(
                        "UPDATE commerce_outbox SET aggregate_type = 'FAQ_CORRUPT' WHERE event_id = ?",
                        original.eventId()),
                original,
                original.eventId()),
            outboxCorruption(
                "aggregate_id",
                "committed aggregate identity",
                () ->
                    corruptionUpdateOne(
                        "UPDATE commerce_outbox SET aggregate_id = 'faq-corrupt' WHERE event_id = ?",
                        original.eventId()),
                original,
                original.eventId()),
            outboxCorruption(
                "aggregate_version",
                "committed aggregate version",
                () ->
                    corruptionUpdateOne(
                        "UPDATE commerce_outbox SET aggregate_version = aggregate_version + 1 WHERE event_id = ?",
                        original.eventId()),
                original,
                original.eventId()),
            outboxCorruption(
                "event_type",
                "committed ownership event type asserted after enumeration; hidden-helper probe",
                () ->
                    corruptionUpdateOne(
                        "UPDATE commerce_outbox SET event_type = 'FAQ_CORRUPT' WHERE event_id = ?",
                        original.eventId()),
                original,
                original.eventId()),
            outboxCorruption(
                "payload",
                "closed event content commitment",
                () ->
                    corruptionUpdateOne(
                        "UPDATE commerce_outbox SET payload = JSON_SET(payload, '$.content.answer', 'Corrupt payload') WHERE event_id = ?",
                        original.eventId()),
                original,
                original.eventId()),
            outboxCorruption(
                "publication_state",
                "delivery state is bound to attempts and publication time",
                () ->
                    corruptionUpdateOne(
                        "UPDATE commerce_outbox SET publication_state = 'PUBLISHED' WHERE event_id = ?",
                        original.eventId()),
                original,
                original.eventId()),
            outboxCorruption(
                "publish_attempts",
                "database counter has a bounded internal invariant",
                () ->
                    corruptionUpdateOne(
                        "UPDATE commerce_outbox SET publish_attempts = 1000001 WHERE event_id = ?",
                        original.eventId()),
                original,
                original.eventId()),
            outboxCorruption(
                "created_at",
                "ordering time is anchored to the business event time",
                () ->
                    corruptionUpdateOne(
                        "UPDATE commerce_outbox SET created_at = TIMESTAMPADD(SECOND, 1, created_at) WHERE event_id = ?",
                        original.eventId()),
                original,
                original.eventId()),
            outboxCorruption(
                "published_at",
                "pending delivery requires no publication timestamp",
                () ->
                    corruptionUpdateOne(
                        "UPDATE commerce_outbox SET published_at = created_at WHERE event_id = ?",
                        original.eventId()),
                original,
                original.eventId()));
    assertSchemaDispositionCoverage(physicalCorruptions);

    List<SchemaCorruption> payloadCorruptions =
        List.of(
            jsonCorruption(original, "$.eventId", UUID.randomUUID().toString()),
            jsonCorruption(original, "$.sourceId", "faq-corrupt"),
            jsonCorruption(original, "$.sourceType", "product"),
            jsonCorruption(original, "$.sourceVersion", 99),
            jsonCorruption(original, "$.publicationState", "DRAFT"),
            jsonCorruption(original, "$.tombstone", true),
            jsonCorruption(original, "$.occurredTime", "2026-07-20T09:00:00Z"),
            jsonCorruption(original, "$.content.question", "Changed but bounded question?"),
            jsonCorruption(original, "$.content.answer", "Changed but bounded answer."));
    SchemaCorruption historicalVersionMembership =
        sourceCorruption(
            "published_version",
            "current source must equal the latest applied command, not any historical command",
            "published_version = published_version - 1",
            durableSource,
            durableSource.faqId());

    try (SimpleConsumer consumer = faqConsumer()) {
      for (SchemaCorruption corruption : physicalCorruptions) {
        exerciseSchemaCorruption(corruption, command, originalResult, durableCommand);
      }
      for (SchemaCorruption corruption : payloadCorruptions) {
        exerciseSchemaCorruption(corruption, command, originalResult, durableCommand);
      }
      exerciseSchemaCorruption(
          historicalVersionMembership, command, originalResult, durableCommand);
      deleteCommand(durableCommand);
      try {
        assertPublisherRejects(null);
      } finally {
        restoreCommand(durableCommand);
      }
      deleteDraftCommand(durableDraft);
      try {
        assertPublisherRejects(null);
      } finally {
        restoreDraftCommand(durableDraft);
      }
      assertThat(consumer.receive(1, Duration.ofSeconds(10))).isEmpty();
      assertThat(outboxState(original.eventId())).isEqualTo("PENDING:0");
    }
  }

  private void exerciseSchemaCorruption(
      SchemaCorruption corruption,
      FaqPublicationService.PublicationCommand command,
      FaqPublicationService.PublicationResult originalResult,
      FaqRepository.PublicationCommand durableCommand) {
    corruption.inject().run();
    try {
      assertPublisherRejects(corruption);
      if (corruption.table().startsWith("commerce_outbox")) {
        assertCode(
            () -> service.publish(command),
            FaqPublicationException.Code.INCONSISTENT_DURABLE_STATE);
      }
    } finally {
      corruption.restore().run();
    }
    FaqPublicationService.PublicationResult replay = service.publish(command);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.event()).isEqualTo(originalResult.event());

    if (!"faq_publication_command".equals(corruption.table())) {
      deleteCommand(durableCommand);
      corruption.inject().run();
      try {
        assertPublisherRejects(corruption);
      } finally {
        corruption.restore().run();
        restoreCommand(durableCommand);
      }
      replay = service.publish(command);
      assertThat(replay.replayed()).isTrue();
      assertThat(replay.event()).isEqualTo(originalResult.event());
    }
  }

  private void assertPublisherRejects(SchemaCorruption corruption) {
    int[] sendAttempts = {0};
    String label =
        corruption == null
            ? "missing command face"
            : corruption.table() + "." + corruption.column();
    assertThatThrownBy(
            () ->
                new FaqOutboxPublisher(
                        repository,
                        codec,
                        event -> {
                          sendAttempts[0]++;
                          messaging.send(event);
                        })
                    .publishPending(100))
        .as(label)
        .isInstanceOfSatisfying(
            FaqPublicationException.class,
            exception ->
                assertThat(exception.code())
                    .isEqualTo(FaqPublicationException.Code.INCONSISTENT_DURABLE_STATE));
    assertThat(sendAttempts[0]).isZero();
  }

  private SchemaCorruption sourceCorruption(
      String column,
      String reason,
      String assignment,
      FaqRepository.FaqSource original,
      String currentFaqId) {
    String disposition =
        List.of("created_at", "updated_at").contains(column) ? "INVARIANT" : "ANCHORED";
    String preserveUpdatedAt = "updated_at".equals(column) ? "" : ", updated_at = updated_at";
    return new SchemaCorruption(
        "faq_source",
        column,
        disposition,
        reason,
        () ->
            corruptionUpdateOne(
                "UPDATE faq_source SET " + assignment + preserveUpdatedAt + " WHERE faq_id = ?",
                original.faqId()),
        () -> restoreSource(original, currentFaqId),
        original.faqId());
  }

  private SchemaCorruption commandCorruption(
      String column, String reason, String assignment, FaqRepository.PublicationCommand original) {
    return new SchemaCorruption(
        "faq_publication_command",
        column,
        "ANCHORED",
        reason,
        () ->
            corruptionUpdateOne(
                "UPDATE faq_publication_command SET " + assignment + " WHERE idempotency_key = ?",
                original.idempotencyKey()),
        () -> restoreCommand(original),
        original.eventId());
  }

  private SchemaCorruption draftCommandCorruption(
      String column, String reason, String assignment, FaqRepository.DraftCommand original) {
    return new SchemaCorruption(
        "faq_draft_command",
        column,
        "ANCHORED",
        reason,
        () ->
            corruptionUpdateOne(
                "UPDATE faq_draft_command SET "
                    + assignment
                    + " WHERE faq_id = ? AND draft_revision = ?",
                original.faqId(),
                original.draftRevision()),
        () -> restoreDraftCommand(original),
        original.faqId());
  }

  private SchemaCorruption outboxCorruption(
      String column,
      String reason,
      Runnable inject,
      FaqRepository.OutboxEvent original,
      String currentEventId) {
    String disposition =
        List.of("publication_state", "publish_attempts", "published_at").contains(column)
            ? "INVARIANT"
            : "ANCHORED";
    return new SchemaCorruption(
        "commerce_outbox",
        column,
        disposition,
        reason,
        inject,
        () -> restoreOutbox(original, currentEventId),
        currentEventId);
  }

  private SchemaCorruption jsonCorruption(
      FaqRepository.OutboxEvent original, String path, Object value) {
    return new SchemaCorruption(
        "commerce_outbox_payload",
        path,
        "ANCHORED",
        "closed payload member",
        () ->
            corruptionUpdateOne(
                "UPDATE commerce_outbox SET payload = JSON_SET(payload, ?, ?) WHERE event_id = ?",
                path,
                value,
                original.eventId()),
        () -> restoreOutbox(original, original.eventId()),
        original.eventId());
  }

  private void assertSchemaDispositionCoverage(List<SchemaCorruption> corruptions) {
    assertThat(
            corruptionJdbc()
                .queryForObject(
                    """
                    SELECT column_type
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'faq_source'
                      AND column_name = 'working_state'
                    """,
                    String.class))
        .isEqualTo("enum('DRAFT','PUBLISHED')");
    Map<String, List<String>> actual = new LinkedHashMap<>();
    for (Map<String, Object> row :
        corruptionJdbc()
            .queryForList(
                """
            SELECT table_name, column_name
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name IN (
                'faq_source', 'faq_publication_command', 'faq_draft_command', 'commerce_outbox'
              )
            ORDER BY FIELD(
                       table_name,
                       'faq_source', 'faq_publication_command', 'faq_draft_command', 'commerce_outbox'
                     ),
                     ordinal_position
            """)) {
      actual
          .computeIfAbsent((String) row.get("table_name"), ignored -> new ArrayList<>())
          .add((String) row.get("column_name"));
    }
    Map<String, List<String>> dispositions = new LinkedHashMap<>();
    for (SchemaCorruption corruption : corruptions) {
      dispositions
          .computeIfAbsent(corruption.table(), ignored -> new ArrayList<>())
          .add(corruption.column());
      assertThat(corruption.disposition()).isIn("ANCHORED", "INVARIANT");
      assertThat(corruption.reason()).isNotBlank();
    }
    assertThat(dispositions).isEqualTo(actual);
  }

  private void restoreOutbox(FaqRepository.OutboxEvent original, String currentEventId) {
    updateOne(
        """
        UPDATE commerce_outbox
        SET event_id = ?, aggregate_type = ?, aggregate_id = ?, aggregate_version = ?,
            event_type = ?, payload = CAST(? AS JSON), publication_state = ?,
            publish_attempts = ?, created_at = ?, published_at = ?
        WHERE event_id = ?
        """,
        original.eventId(),
        original.aggregateType(),
        original.aggregateId(),
        original.aggregateVersion(),
        original.eventType(),
        original.payload(),
        original.publicationState(),
        original.publishAttempts(),
        Timestamp.from(original.createdAt()),
        original.publishedAt() == null ? null : Timestamp.from(original.publishedAt()),
        currentEventId);
  }

  private void restoreSource(FaqRepository.FaqSource source, String currentFaqId) {
    corruptionUpdateOne(
        """
        UPDATE faq_source
        SET faq_id = ?, draft_question = ?, draft_answer = ?, draft_revision = ?,
            working_state = ?, published_question = ?, published_answer = ?,
            published_version = ?, published_at = ?, created_at = ?, updated_at = ?
        WHERE faq_id = ?
        """,
        source.faqId(),
        source.draftQuestion(),
        source.draftAnswer(),
        source.draftRevision(),
        source.workingState(),
        source.publishedQuestion(),
        source.publishedAnswer(),
        source.publishedVersion(),
        source.publishedAt() == null ? null : Timestamp.from(source.publishedAt()),
        Timestamp.from(source.createdAt()),
        Timestamp.from(source.updatedAt()),
        currentFaqId);
  }

  private void deleteCommand(FaqRepository.PublicationCommand command) {
    corruptionUpdateOne(
        "DELETE FROM faq_publication_command WHERE event_id = ?", command.eventId());
  }

  private void restoreCommand(FaqRepository.PublicationCommand command) {
    corruptionJdbc()
        .update(
            """
            DELETE FROM faq_publication_command
            WHERE idempotency_key IN (?, ?)
               OR event_id = ?
               OR faq_id = 'faq-corrupt-source'
            """,
            command.idempotencyKey(),
            command.idempotencyKey() + "-corrupt",
            command.eventId());
    corruptionUpdateOne(
        """
        INSERT INTO faq_publication_command
          (idempotency_key, event_id, faq_id, expected_draft_revision,
           expected_published_version, source_version, intent_hash, occurred_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        command.idempotencyKey(),
        command.eventId(),
        command.faqId(),
        command.expectedDraftRevision(),
        command.expectedPublishedVersion(),
        command.sourceVersion(),
        command.intentHash(),
        Timestamp.from(command.occurredAt()),
        Timestamp.from(command.createdAt()));
  }

  private void restoreDraftCommand(FaqRepository.DraftCommand command) {
    corruptionJdbc()
        .update(
            """
            DELETE FROM faq_draft_command
            WHERE (faq_id = ? AND draft_revision IN (?, ?))
               OR faq_id = 'faq-corrupt-source'
            """,
            command.faqId(),
            command.draftRevision(),
            command.draftRevision() + 1);
    corruptionUpdateOne(
        """
        INSERT INTO faq_draft_command
          (faq_id, draft_revision, expected_draft_revision, draft_question, draft_answer,
           intent_hash, occurred_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        command.faqId(),
        command.draftRevision(),
        command.expectedDraftRevision(),
        command.draftQuestion(),
        command.draftAnswer(),
        command.intentHash(),
        Timestamp.from(command.occurredAt()),
        Timestamp.from(command.createdAt()));
  }

  private void deleteDraftCommand(FaqRepository.DraftCommand command) {
    corruptionUpdateOne(
        "DELETE FROM faq_draft_command WHERE faq_id = ? AND draft_revision = ?",
        command.faqId(),
        command.draftRevision());
  }

  private JdbcTemplate corruptionJdbc() {
    if (corruptionJdbc == null) {
      corruptionDataSource =
          new SingleConnectionDataSource(
              required("CATALOG_MYSQL_URL"),
              "bootstrap_admin",
              required("MYSQL_BOOTSTRAP_PASSWORD"),
              true);
      corruptionJdbc = new JdbcTemplate(corruptionDataSource);
      corruptionJdbc.execute("SET ROLE bootstrap_grant_role");
      corruptionJdbc.execute("SET SESSION FOREIGN_KEY_CHECKS = 0");
    }
    return corruptionJdbc;
  }

  private void corruptionUpdateOne(String sql, Object... arguments) {
    assertThat(corruptionJdbc().update(sql, arguments)).isEqualTo(1);
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

  private void proveNormalMessageFailureRetryRestartAndDuplicateDelivery(
      FaqPublicationService.PublicationResult payloadBoundary) throws Exception {
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
    assertThatThrownBy(() -> new FaqOutboxPublisher(repository, codec, closed).publishPending(1))
        .isInstanceOf(Exception.class);
    assertThat(outboxState(firstPending.eventId())).isEqualTo("PENDING:1");
    assertThat(repository.findSource(failedEvent.sourceId())).contains(failedSourceBefore);
    assertSource("faq-delivery", 1, 1, "PUBLISHED", "Will delivery retry?");

    try (SimpleConsumer consumer = faqConsumer()) {
      int expected = repository.pendingOutbox(100).size();
      FaqOutboxPublisher restarted = new FaqOutboxPublisher(repository, codec, messaging);
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
      assertThat(delivered)
          .anySatisfy(event -> assertThat(event).isEqualTo(payloadBoundary.event()));

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
      assertThatThrownBy(
              () -> new FaqOutboxPublisher(markFailure, codec, messaging).publishPending(1))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Controlled failure after broker acceptance");
      assertThat(outboxState(markWindow.event().eventId())).isEqualTo("PENDING:1");
      assertThat(receive(consumer, 1, Duration.ofSeconds(30)))
          .anySatisfy(event -> assertThat(event).isEqualTo(markWindow.event()));

      FaqOutboxPublisher afterMarkFailure = new FaqOutboxPublisher(repository, codec, messaging);
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

  private BoundaryAnswers boundaryAnswers(
      FaqPublicationService.PublicationCommand command, Instant occurredAt) throws Exception {
    FaqKnowledgeEvent template =
        new FaqKnowledgeEvent(
            command.eventId(),
            command.faqId(),
            FaqKnowledgeEventCodec.SOURCE_TYPE,
            1,
            FaqKnowledgeEventCodec.PUBLICATION_STATE,
            false,
            occurredAt.toString(),
            new FaqKnowledgeEvent.PublicContent("q", "x"));
    int acceptedCount = 0;
    int rejectedCount = FaqKnowledgeEventCodec.MAX_ANSWER_LENGTH;
    while (acceptedCount + 1 < rejectedCount) {
      int candidateCount = (acceptedCount + rejectedCount) / 2;
      FaqKnowledgeEvent candidate = withAnswer(template, "x" + "\0".repeat(candidateCount));
      if (utf8Bytes(objectMapper.writeValueAsString(candidate))
          <= FaqKnowledgeEventCodec.MAX_COMPACT_PAYLOAD_BYTES) {
        acceptedCount = candidateCount;
      } else {
        rejectedCount = candidateCount;
      }
    }
    String accepted = "x" + "\0".repeat(acceptedCount);
    String rejected = accepted + "\0";
    return new BoundaryAnswers(
        accepted,
        rejected,
        utf8Bytes(objectMapper.writeValueAsString(withAnswer(template, accepted))),
        utf8Bytes(objectMapper.writeValueAsString(withAnswer(template, rejected))));
  }

  private static FaqKnowledgeEvent withAnswer(FaqKnowledgeEvent event, String answer) {
    return new FaqKnowledgeEvent(
        event.eventId(),
        event.sourceId(),
        event.sourceType(),
        event.sourceVersion(),
        event.publicationState(),
        event.tombstone(),
        event.occurredTime(),
        new FaqKnowledgeEvent.PublicContent(event.content().question(), answer));
  }

  private static int utf8Bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
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

  private record SchemaCorruption(
      String table,
      String column,
      String disposition,
      String reason,
      Runnable inject,
      Runnable restore,
      String currentEventId) {}

  private record BoundaryAnswers(
      String accepted, String rejected, int acceptedCompactBytes, int rejectedCompactBytes) {}
}
