package io.citybuddy.commerce.faq;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class FaqPublicationService {
  private static final Pattern FAQ_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
  private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;

  private final FaqRepository repository;
  private final FaqKnowledgeEventCodec codec;
  private final Clock clock;

  public FaqPublicationService(
      FaqRepository repository, FaqKnowledgeEventCodec codec, Clock clock) {
    this.repository = repository;
    this.codec = codec;
    this.clock = clock;
  }

  @Transactional
  public FaqRepository.FaqSource saveDraft(
      String faqId, String question, String answer, long expectedDraftRevision) {
    validateDraft(faqId, question, answer, expectedDraftRevision);
    FaqRepository.FaqSource current = repository.lockSource(faqId).orElse(null);
    if (current == null) {
      if (expectedDraftRevision != 0) {
        throw stale("A new FAQ draft requires expected draft revision zero");
      }
      repository.insertDraft(faqId, question, answer);
    } else {
      if (current.draftRevision() != expectedDraftRevision) {
        throw stale("FAQ draft revision is stale");
      }
      repository.updateDraft(faqId, question, answer, expectedDraftRevision);
    }
    requireTransaction();
    FaqRepository.FaqSource saved =
        repository
            .findSource(faqId)
            .orElseThrow(() -> inconsistent("FAQ draft disappeared inside its transaction"));
    String intentHash =
        FaqPublicationCommitment.createDraft(
            saved.faqId(),
            saved.draftRevision(),
            expectedDraftRevision,
            saved.draftQuestion(),
            saved.draftAnswer(),
            saved.updatedAt().toString());
    repository.insertDraftCommand(
        new FaqRepository.DraftCommand(
            saved.faqId(),
            saved.draftRevision(),
            expectedDraftRevision,
            saved.draftQuestion(),
            saved.draftAnswer(),
            intentHash,
            saved.updatedAt(),
            saved.updatedAt()));
    return saved;
  }

  @Transactional
  public PublicationResult publish(PublicationCommand command) {
    ValidatedCommand valid = validateCommand(command);
    FaqRepository.FaqSource source =
        repository
            .lockSource(valid.faqId())
            .orElseThrow(() -> notFound("FAQ draft does not exist"));
    List<FaqRepository.PublicationCommand> existing =
        repository.findCommandsByIdentity(valid.idempotencyKey(), valid.eventId());
    if (!existing.isEmpty()) {
      return replay(existing, valid);
    }
    if (!"DRAFT".equals(source.workingState())
        || source.draftRevision() != valid.expectedDraftRevision()
        || source.publishedVersion() != valid.expectedPublishedVersion()) {
      throw stale("FAQ draft is not publishable or its version is stale");
    }

    long sourceVersion;
    try {
      sourceVersion = Math.addExact(valid.expectedPublishedVersion(), 1);
    } catch (ArithmeticException exception) {
      throw validation("FAQ published version cannot advance", exception);
    }
    Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    Instant occurredAt = observedAt.isBefore(source.createdAt()) ? source.createdAt() : observedAt;
    FaqKnowledgeEvent event =
        new FaqKnowledgeEvent(
            valid.eventId(),
            valid.faqId(),
            FaqKnowledgeEventCodec.SOURCE_TYPE,
            sourceVersion,
            FaqKnowledgeEventCodec.PUBLICATION_STATE,
            false,
            occurredAt.toString(),
            new FaqKnowledgeEvent.PublicContent(source.draftQuestion(), source.draftAnswer()));
    String payload = codec.encode(event);
    String intentHash =
        FaqPublicationCommitment.create(
            valid.faqId(),
            valid.idempotencyKey(),
            valid.eventId(),
            valid.expectedDraftRevision(),
            valid.expectedPublishedVersion(),
            valid.eventId(),
            FaqRepository.AGGREGATE_TYPE,
            valid.faqId(),
            sourceVersion,
            FaqRepository.EVENT_TYPE,
            event);
    FaqRepository.PublicationCommand durable =
        new FaqRepository.PublicationCommand(
            valid.idempotencyKey(),
            valid.eventId(),
            valid.faqId(),
            valid.expectedDraftRevision(),
            valid.expectedPublishedVersion(),
            sourceVersion,
            intentHash,
            occurredAt,
            occurredAt);
    try {
      repository.publishSource(source, sourceVersion, occurredAt);
      repository.insertCommand(durable);
      repository.insertOutbox(durable, payload);
    } catch (DataIntegrityViolationException exception) {
      throw conflict("FAQ publication identity or version conflicts", exception);
    }
    requireTransaction();
    return new PublicationResult(event, false);
  }

  private PublicationResult replay(
      List<FaqRepository.PublicationCommand> existing, ValidatedCommand valid) {
    if (existing.size() != 1) {
      throw conflict("FAQ event and idempotency identities belong to different commands");
    }
    FaqRepository.PublicationCommand durable = existing.getFirst();
    if (!durable.idempotencyKey().equals(valid.idempotencyKey())
        || !durable.eventId().equals(valid.eventId())
        || !durable.faqId().equals(valid.faqId())
        || durable.expectedDraftRevision() != valid.expectedDraftRevision()
        || durable.expectedPublishedVersion() != valid.expectedPublishedVersion()) {
      throw conflict("FAQ event or idempotency identity has conflicting intent");
    }
    FaqRepository.OutboxEvent outbox =
        repository
            .findOutbox(durable.eventId())
            .orElseThrow(() -> inconsistent("FAQ publication command has no matching Outbox"));
    FaqKnowledgeEvent event = FaqPublicationCommitment.verify(durable, outbox, codec);
    requireTransaction();
    return new PublicationResult(event, true);
  }

  private static ValidatedCommand validateCommand(PublicationCommand command) {
    if (command == null
        || !validFaqId(command.faqId())
        || !boundedText(command.idempotencyKey(), MAX_IDEMPOTENCY_KEY_LENGTH)
        || !canonicalUuid(command.eventId())
        || command.expectedDraftRevision() < 1
        || command.expectedPublishedVersion() < 0) {
      throw validation("FAQ publication command is invalid");
    }
    return new ValidatedCommand(
        command.faqId(),
        command.idempotencyKey(),
        command.eventId(),
        command.expectedDraftRevision(),
        command.expectedPublishedVersion());
  }

  private static void validateDraft(
      String faqId, String question, String answer, long expectedDraftRevision) {
    if (!validFaqId(faqId)
        || !boundedText(question, FaqKnowledgeEventCodec.MAX_QUESTION_LENGTH)
        || !boundedText(answer, FaqKnowledgeEventCodec.MAX_ANSWER_LENGTH)
        || expectedDraftRevision < 0) {
      throw validation("FAQ draft is invalid");
    }
  }

  private static boolean validFaqId(String value) {
    return value != null && FAQ_ID.matcher(value).matches();
  }

  private static boolean boundedText(String value, int maximum) {
    return value != null && !value.isBlank() && value.length() <= maximum;
  }

  private static boolean canonicalUuid(String value) {
    try {
      return value != null && UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw inconsistent("FAQ publication requires an active transaction");
    }
  }

  private static FaqPublicationException validation(String message) {
    return new FaqPublicationException(FaqPublicationException.Code.VALIDATION, message);
  }

  private static FaqPublicationException validation(String message, Throwable cause) {
    FaqPublicationException exception = validation(message);
    exception.initCause(cause);
    return exception;
  }

  private static FaqPublicationException notFound(String message) {
    return new FaqPublicationException(FaqPublicationException.Code.NOT_FOUND, message);
  }

  private static FaqPublicationException stale(String message) {
    return new FaqPublicationException(FaqPublicationException.Code.STALE_VERSION, message);
  }

  private static FaqPublicationException conflict(String message) {
    return new FaqPublicationException(FaqPublicationException.Code.IDEMPOTENCY_CONFLICT, message);
  }

  private static FaqPublicationException conflict(String message, Throwable cause) {
    FaqPublicationException exception = conflict(message);
    exception.initCause(cause);
    return exception;
  }

  private static FaqPublicationException inconsistent(String message) {
    return new FaqPublicationException(
        FaqPublicationException.Code.INCONSISTENT_DURABLE_STATE, message);
  }

  private static FaqPublicationException inconsistent(String message, Throwable cause) {
    FaqPublicationException exception = inconsistent(message);
    exception.initCause(cause);
    return exception;
  }

  public record PublicationCommand(
      String faqId,
      String idempotencyKey,
      String eventId,
      long expectedDraftRevision,
      long expectedPublishedVersion) {}

  public record PublicationResult(FaqKnowledgeEvent event, boolean replayed) {}

  private record ValidatedCommand(
      String faqId,
      String idempotencyKey,
      String eventId,
      long expectedDraftRevision,
      long expectedPublishedVersion) {}
}
