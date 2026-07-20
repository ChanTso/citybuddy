package io.citybuddy.commerce.faq;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

public class FaqRepository {
  static final String AGGREGATE_TYPE = "FAQ";
  static final String EVENT_TYPE = "FAQ_KNOWLEDGE_SYNCHRONIZATION";
  private static final String SOURCE_COLUMNS =
      "faq_id AS source_faq_id, draft_question, draft_answer, draft_revision, working_state, "
          + "published_question, published_answer, published_version, "
          + "published_at AS source_published_at, created_at AS source_created_at, "
          + "updated_at AS source_updated_at";

  private final JdbcTemplate jdbc;

  public FaqRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<FaqSource> findSource(String faqId) {
    return jdbc
        .query(
            "SELECT " + SOURCE_COLUMNS + " FROM faq_source WHERE faq_id = ?",
            FaqRepository::mapSource,
            faqId)
        .stream()
        .findFirst();
  }

  public Optional<FaqSource> lockSource(String faqId) {
    return jdbc
        .query(
            "SELECT " + SOURCE_COLUMNS + " FROM faq_source WHERE faq_id = ? FOR UPDATE",
            FaqRepository::mapSource,
            faqId)
        .stream()
        .findFirst();
  }

  public void insertDraft(String faqId, String question, String answer) {
    jdbc.update(
        """
        INSERT INTO faq_source
          (faq_id, draft_question, draft_answer, draft_revision, working_state,
           published_version)
        VALUES (?, ?, ?, 1, 'DRAFT', 0)
        """,
        faqId,
        question,
        answer);
  }

  public void updateDraft(
      String faqId, String question, String answer, long expectedDraftRevision) {
    int changed =
        jdbc.update(
            """
            UPDATE faq_source
            SET draft_question = ?, draft_answer = ?,
                draft_revision = draft_revision + 1, working_state = 'DRAFT'
            WHERE faq_id = ? AND draft_revision = ?
            """,
            question,
            answer,
            faqId,
            expectedDraftRevision);
    if (changed != 1) {
      throw inconsistent("FAQ draft changed without its locked revision");
    }
  }

  public void publishSource(FaqSource source, long sourceVersion, Instant occurredAt) {
    int changed =
        jdbc.update(
            """
            UPDATE faq_source
            SET published_question = draft_question,
                published_answer = draft_answer,
                published_version = ?,
                published_at = ?,
                working_state = 'PUBLISHED',
                updated_at = ?
            WHERE faq_id = ?
              AND draft_revision = ?
              AND published_version = ?
              AND working_state = 'DRAFT'
            """,
            sourceVersion,
            Timestamp.from(occurredAt),
            Timestamp.from(occurredAt),
            source.faqId(),
            source.draftRevision(),
            source.publishedVersion());
    if (changed != 1) {
      throw inconsistent("FAQ publication changed without its locked versions");
    }
  }

  public List<PublicationCommand> findCommandsByIdentity(String idempotencyKey, String eventId) {
    return jdbc.query(
        """
        SELECT idempotency_key, event_id, faq_id, expected_draft_revision,
               expected_published_version, source_version, intent_hash, occurred_at, created_at
        FROM faq_publication_command
        WHERE idempotency_key = ? OR event_id = ?
        ORDER BY idempotency_key
        """,
        (result, row) ->
            new PublicationCommand(
                result.getString("idempotency_key"),
                result.getString("event_id"),
                result.getString("faq_id"),
                result.getLong("expected_draft_revision"),
                result.getLong("expected_published_version"),
                result.getLong("source_version"),
                result.getString("intent_hash"),
                result.getTimestamp("occurred_at").toInstant(),
                result.getTimestamp("created_at").toInstant()),
        idempotencyKey,
        eventId);
  }

  public void insertCommand(PublicationCommand command) {
    jdbc.update(
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
        Timestamp.from(command.occurredAt()));
  }

  public void insertOutbox(PublicationCommand command, String payload) {
    jdbc.update(
        """
        INSERT INTO commerce_outbox
          (event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload, created_at)
        VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?)
        """,
        command.eventId(),
        AGGREGATE_TYPE,
        command.faqId(),
        command.sourceVersion(),
        EVENT_TYPE,
        payload,
        Timestamp.from(command.occurredAt()));
  }

  public Optional<OutboxEvent> findOutbox(String eventId) {
    return jdbc
        .query(
            """
            SELECT event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload,
                   publication_state, publish_attempts, created_at, published_at
            FROM commerce_outbox
            WHERE event_id = ?
            """,
            FaqRepository::mapOutbox,
            eventId)
        .stream()
        .findFirst();
  }

  public List<OutboxEvent> pendingOutbox(int limit) {
    return jdbc.query(
        """
        SELECT event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload,
               publication_state, publish_attempts, created_at, published_at
        FROM commerce_outbox
        WHERE publication_state = 'PENDING'
          AND aggregate_type = ?
          AND event_type = ?
        ORDER BY created_at, event_id
        LIMIT ?
        """,
        FaqRepository::mapOutbox,
        AGGREGATE_TYPE,
        EVENT_TYPE,
        limit);
  }

  public List<PublicationTruth> publicationTruths() {
    return jdbc.query(
        """
        SELECT c.idempotency_key, c.event_id AS command_event_id, c.faq_id,
               c.expected_draft_revision, c.expected_published_version, c.source_version,
               c.intent_hash, c.occurred_at, c.created_at AS command_created_at,
               s.faq_id AS source_faq_id, s.draft_question, s.draft_answer,
               s.draft_revision, s.working_state, s.published_question, s.published_answer,
               s.published_version, s.published_at AS source_published_at,
               s.created_at AS source_created_at, s.updated_at AS source_updated_at,
               o.event_id AS outbox_event_id, o.aggregate_type, o.aggregate_id,
               o.aggregate_version, o.event_type, o.payload, o.publication_state,
               o.publish_attempts, o.created_at AS outbox_created_at, o.published_at
        FROM faq_publication_command c
        LEFT JOIN commerce_outbox o ON o.event_id = c.event_id
        LEFT JOIN faq_source s ON s.faq_id = c.faq_id
        ORDER BY c.occurred_at, c.event_id
        """,
        (result, row) -> {
          PublicationCommand command =
              new PublicationCommand(
                  result.getString("idempotency_key"),
                  result.getString("command_event_id"),
                  result.getString("faq_id"),
                  result.getLong("expected_draft_revision"),
                  result.getLong("expected_published_version"),
                  result.getLong("source_version"),
                  result.getString("intent_hash"),
                  result.getTimestamp("occurred_at").toInstant(),
                  result.getTimestamp("command_created_at").toInstant());
          String sourceFaqId = result.getString("source_faq_id");
          FaqSource source = sourceFaqId == null ? null : mapSource(result, row);
          String outboxEventId = result.getString("outbox_event_id");
          OutboxEvent outbox =
              outboxEventId == null
                  ? null
                  : new OutboxEvent(
                      outboxEventId,
                      result.getString("aggregate_type"),
                      result.getString("aggregate_id"),
                      result.getLong("aggregate_version"),
                      result.getString("event_type"),
                      result.getString("payload"),
                      result.getString("publication_state"),
                      result.getLong("publish_attempts"),
                      result.getTimestamp("outbox_created_at").toInstant(),
                      timestamp(result, "published_at"));
          return new PublicationTruth(command, source, outbox);
        });
  }

  public List<FaqSource> allSources() {
    return jdbc.query(
        "SELECT " + SOURCE_COLUMNS + " FROM faq_source ORDER BY faq_id", FaqRepository::mapSource);
  }

  public List<OutboxEvent> allOutboxEvents() {
    return jdbc.query(
        """
        SELECT o.event_id, o.aggregate_type, o.aggregate_id, o.aggregate_version,
               o.event_type, o.payload, o.publication_state, o.publish_attempts,
               o.created_at, o.published_at
        FROM commerce_outbox o
        ORDER BY o.created_at, o.event_id
        """,
        FaqRepository::mapOutbox);
  }

  public void recordPublishFailure(String eventId) {
    int changed =
        jdbc.update(
            """
            UPDATE commerce_outbox
            SET publish_attempts = publish_attempts + 1
            WHERE event_id = ?
              AND aggregate_type = ?
              AND event_type = ?
              AND publication_state = 'PENDING'
            """,
            eventId,
            AGGREGATE_TYPE,
            EVENT_TYPE);
    if (changed != 1) {
      throw inconsistent("FAQ Outbox event is not pending after delivery failure");
    }
  }

  public void markPublished(String eventId) {
    int changed =
        jdbc.update(
            """
            UPDATE commerce_outbox
            SET publication_state = 'PUBLISHED', publish_attempts = publish_attempts + 1,
                published_at = GREATEST(CURRENT_TIMESTAMP(6), created_at)
            WHERE event_id = ?
              AND aggregate_type = ?
              AND event_type = ?
              AND publication_state = 'PENDING'
            """,
            eventId,
            AGGREGATE_TYPE,
            EVENT_TYPE);
    if (changed != 1) {
      throw inconsistent("FAQ Outbox event is not pending after broker acceptance");
    }
  }

  private static FaqSource mapSource(ResultSet result, int row) throws SQLException {
    Timestamp publishedAt = result.getTimestamp("source_published_at");
    return new FaqSource(
        result.getString("source_faq_id"),
        result.getString("draft_question"),
        result.getString("draft_answer"),
        result.getLong("draft_revision"),
        result.getString("working_state"),
        result.getString("published_question"),
        result.getString("published_answer"),
        result.getLong("published_version"),
        publishedAt == null ? null : publishedAt.toInstant(),
        result.getTimestamp("source_created_at").toInstant(),
        result.getTimestamp("source_updated_at").toInstant());
  }

  private static OutboxEvent mapOutbox(ResultSet result, int row) throws SQLException {
    return new OutboxEvent(
        result.getString("event_id"),
        result.getString("aggregate_type"),
        result.getString("aggregate_id"),
        result.getLong("aggregate_version"),
        result.getString("event_type"),
        result.getString("payload"),
        result.getString("publication_state"),
        result.getLong("publish_attempts"),
        result.getTimestamp("created_at").toInstant(),
        timestamp(result, "published_at"));
  }

  private static Instant timestamp(ResultSet result, String column) throws SQLException {
    Timestamp value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static FaqPublicationException inconsistent(String message) {
    return new FaqPublicationException(
        FaqPublicationException.Code.INCONSISTENT_DURABLE_STATE, message);
  }

  public record FaqSource(
      String faqId,
      String draftQuestion,
      String draftAnswer,
      long draftRevision,
      String workingState,
      String publishedQuestion,
      String publishedAnswer,
      long publishedVersion,
      Instant publishedAt,
      Instant createdAt,
      Instant updatedAt) {}

  public record PublicationCommand(
      String idempotencyKey,
      String eventId,
      String faqId,
      long expectedDraftRevision,
      long expectedPublishedVersion,
      long sourceVersion,
      String intentHash,
      Instant occurredAt,
      Instant createdAt) {}

  public record OutboxEvent(
      String eventId,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      String eventType,
      String payload,
      String publicationState,
      long publishAttempts,
      Instant createdAt,
      Instant publishedAt) {}

  public record PublicationTruth(
      PublicationCommand command, FaqSource source, OutboxEvent outbox) {}
}
