package io.citybuddy.commerce.faq;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class FaqPublicationCommitment {
  private static final String FORMAT = "FAQ_PUBLICATION_INTENT_V1";
  private static final String DRAFT_FORMAT = "FAQ_DRAFT_INTENT_V1";
  private static final long MAX_DELIVERY_ATTEMPTS = 1_000_000;

  private FaqPublicationCommitment() {}

  static String createDraft(
      String faqId,
      long draftRevision,
      long expectedDraftRevision,
      String question,
      String answer,
      String occurredAt) {
    return hash(
        List.of(
            DRAFT_FORMAT,
            faqId,
            Long.toString(draftRevision),
            Long.toString(expectedDraftRevision),
            question,
            answer,
            occurredAt));
  }

  static void verifyDraftCommand(FaqRepository.DraftCommand command) {
    String expected =
        createDraft(
            command.faqId(),
            command.draftRevision(),
            command.expectedDraftRevision(),
            command.draftQuestion(),
            command.draftAnswer(),
            command.occurredAt().toString());
    if (command.draftRevision() != command.expectedDraftRevision() + 1
        || !command.createdAt().equals(command.occurredAt())
        || !command.intentHash().equals(expected)) {
      throw inconsistent("FAQ draft command no longer matches its commitment");
    }
  }

  static String create(
      String faqId,
      String idempotencyKey,
      String commandEventId,
      long expectedDraftRevision,
      long expectedPublishedVersion,
      String outboxEventId,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      String eventType,
      FaqKnowledgeEvent event) {
    return hash(
        List.of(
            FORMAT,
            faqId,
            idempotencyKey,
            commandEventId,
            Long.toString(expectedDraftRevision),
            Long.toString(expectedPublishedVersion),
            outboxEventId,
            aggregateType,
            aggregateId,
            Long.toString(aggregateVersion),
            eventType,
            event.eventId(),
            event.sourceId(),
            event.sourceType(),
            Long.toString(event.sourceVersion()),
            event.publicationState(),
            Boolean.toString(event.tombstone()),
            event.occurredTime(),
            event.content().question(),
            event.content().answer()));
  }

  static FaqKnowledgeEvent verify(
      FaqRepository.PublicationCommand command,
      FaqRepository.OutboxEvent outbox,
      FaqKnowledgeEventCodec codec) {
    FaqKnowledgeEvent event;
    try {
      event = codec.decode(outbox.payload());
    } catch (FaqPublicationException exception) {
      throw inconsistent("FAQ publication Outbox payload is invalid", exception);
    }
    if (!command.createdAt().equals(command.occurredAt())
        || !outbox.createdAt().equals(command.occurredAt())
        || !outbox.eventId().equals(command.eventId())
        || !FaqRepository.AGGREGATE_TYPE.equals(outbox.aggregateType())
        || !outbox.aggregateId().equals(command.faqId())
        || outbox.aggregateVersion() != command.sourceVersion()
        || !FaqRepository.EVENT_TYPE.equals(outbox.eventType())
        || !event.eventId().equals(command.eventId())
        || !event.sourceId().equals(command.faqId())
        || event.sourceVersion() != command.sourceVersion()
        || !event.occurredTime().equals(command.occurredAt().toString())) {
      throw inconsistent("FAQ publication command and Outbox payload disagree");
    }
    verifyDelivery(outbox);
    String expected =
        create(
            command.faqId(),
            command.idempotencyKey(),
            command.eventId(),
            command.expectedDraftRevision(),
            command.expectedPublishedVersion(),
            outbox.eventId(),
            outbox.aggregateType(),
            outbox.aggregateId(),
            outbox.aggregateVersion(),
            outbox.eventType(),
            event);
    if (!command.intentHash().equals(expected)) {
      throw inconsistent("FAQ publication event content no longer matches its commitment");
    }
    return event;
  }

  static void verifyCurrentSource(
      FaqRepository.PublicationCommand command,
      FaqRepository.DraftCommand draftCommand,
      FaqRepository.FaqSource source,
      FaqKnowledgeEvent event) {
    if (source == null) {
      throw inconsistent("FAQ publication command has no source truth");
    }
    if (draftCommand == null
        || !draftCommand.faqId().equals(source.faqId())
        || draftCommand.draftRevision() != source.draftRevision()
        || !draftCommand.draftQuestion().equals(source.draftQuestion())
        || !draftCommand.draftAnswer().equals(source.draftAnswer())) {
      throw inconsistent("Current FAQ draft is not anchored to its latest draft command");
    }
    if (command == null) {
      if (source.publishedVersion() != 0
          || source.publishedQuestion() != null
          || source.publishedAnswer() != null
          || source.publishedAt() != null
          || !"DRAFT".equals(source.workingState())
          || !source.updatedAt().equals(draftCommand.occurredAt())
          || source.createdAt().isAfter(source.updatedAt())) {
        throw inconsistent("Unpublished FAQ source has invalid publication state");
      }
      return;
    }
    if (source.publishedVersion() != command.sourceVersion()) {
      throw inconsistent("Current FAQ source is not anchored to its latest applied command");
    }
    if (!source.faqId().equals(command.faqId())
        || !source.publishedQuestion().equals(event.content().question())
        || !source.publishedAnswer().equals(event.content().answer())
        || !source.publishedAt().equals(command.occurredAt())
        || source.createdAt().isAfter(source.updatedAt())
        || source.createdAt().isAfter(source.publishedAt())
        || source.updatedAt().isBefore(source.publishedAt())) {
      throw inconsistent("Current FAQ source and publication commitment disagree");
    }
    if ("PUBLISHED".equals(source.workingState())) {
      if (source.draftRevision() != command.expectedDraftRevision()
          || !source.draftQuestion().equals(source.publishedQuestion())
          || !source.draftAnswer().equals(source.publishedAnswer())
          || !source.updatedAt().equals(command.occurredAt())) {
        throw inconsistent("Published FAQ source is not its committed draft");
      }
    } else {
      if (!"DRAFT".equals(source.workingState())
          || source.draftRevision() <= command.expectedDraftRevision()
          || !source.updatedAt().equals(draftCommand.occurredAt())) {
        throw inconsistent("Current FAQ source has an invalid working-state transition");
      }
    }
  }

  private static void verifyDelivery(FaqRepository.OutboxEvent outbox) {
    if (outbox.publishAttempts() > MAX_DELIVERY_ATTEMPTS) {
      throw inconsistent("FAQ Outbox delivery attempts exceed the bounded invariant");
    }
    if ("PENDING".equals(outbox.publicationState())) {
      if (outbox.publishedAt() != null) {
        throw inconsistent("Pending FAQ Outbox event has a publication timestamp");
      }
      return;
    }
    if (!"PUBLISHED".equals(outbox.publicationState())
        || outbox.publishAttempts() < 1
        || outbox.publishedAt() == null
        || outbox.publishedAt().isBefore(outbox.createdAt())) {
      throw inconsistent("Published FAQ Outbox delivery evidence is invalid");
    }
  }

  static boolean hasFaqOwnershipSignal(
      FaqRepository.OutboxEvent outbox, FaqKnowledgeEventCodec codec) {
    if (FaqRepository.AGGREGATE_TYPE.equals(outbox.aggregateType())
        || FaqRepository.EVENT_TYPE.equals(outbox.eventType())) {
      return true;
    }
    try {
      codec.decode(outbox.payload());
      return true;
    } catch (FaqPublicationException ignored) {
      return false;
    }
  }

  private static String hash(List<String> values) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String value : values) {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(encoded.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(encoded);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
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
}
