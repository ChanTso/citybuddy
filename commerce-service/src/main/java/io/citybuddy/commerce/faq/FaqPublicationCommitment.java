package io.citybuddy.commerce.faq;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class FaqPublicationCommitment {
  private static final String FORMAT = "FAQ_PUBLICATION_INTENT_V1";

  private FaqPublicationCommitment() {}

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
    if (!outbox.eventId().equals(command.eventId())
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
