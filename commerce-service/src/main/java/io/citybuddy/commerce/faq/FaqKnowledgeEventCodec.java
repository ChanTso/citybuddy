package io.citybuddy.commerce.faq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class FaqKnowledgeEventCodec {
  public static final String SOURCE_TYPE = "faq";
  public static final String PUBLICATION_STATE = "PUBLISHED";
  public static final int MAX_QUESTION_LENGTH = 500;
  public static final int MAX_ANSWER_LENGTH = 4000;
  private static final int MAX_PAYLOAD_LENGTH = 8192;
  private static final Pattern SOURCE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
  private static final Set<String> EVENT_FIELDS =
      Set.of(
          "eventId",
          "sourceId",
          "sourceType",
          "sourceVersion",
          "publicationState",
          "tombstone",
          "occurredTime",
          "content");
  private static final Set<String> CONTENT_FIELDS = Set.of("question", "answer");

  private final ObjectMapper objectMapper;

  public FaqKnowledgeEventCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String encode(FaqKnowledgeEvent event) {
    validate(event);
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("FAQ knowledge event serialization failed", exception);
    }
  }

  public FaqKnowledgeEvent decode(String payload) {
    if (payload == null || payload.isBlank() || payload.length() > MAX_PAYLOAD_LENGTH) {
      throw invalid("FAQ knowledge event payload is invalid");
    }
    try {
      JsonNode root = objectMapper.readTree(payload);
      requireExactObject(root, EVENT_FIELDS);
      requireExactObject(root.get("content"), CONTENT_FIELDS);
      FaqKnowledgeEvent event = objectMapper.treeToValue(root, FaqKnowledgeEvent.class);
      validate(event);
      return event;
    } catch (FaqPublicationException exception) {
      throw exception;
    } catch (JsonProcessingException exception) {
      throw invalid("FAQ knowledge event payload is invalid", exception);
    }
  }

  private static void requireExactObject(JsonNode node, Set<String> expectedFields) {
    if (node == null || !node.isObject()) {
      throw invalid("FAQ knowledge event object is invalid");
    }
    Set<String> actual = new java.util.HashSet<>();
    node.fieldNames().forEachRemaining(actual::add);
    if (!actual.equals(expectedFields)) {
      throw invalid("FAQ knowledge event fields are not allowlisted");
    }
  }

  private static void validate(FaqKnowledgeEvent event) {
    if (event == null
        || !canonicalUuid(event.eventId())
        || event.sourceId() == null
        || !SOURCE_ID.matcher(event.sourceId()).matches()
        || !SOURCE_TYPE.equals(event.sourceType())
        || event.sourceVersion() < 1
        || !PUBLICATION_STATE.equals(event.publicationState())
        || event.tombstone()
        || event.content() == null
        || !boundedText(event.content().question(), MAX_QUESTION_LENGTH)
        || !boundedText(event.content().answer(), MAX_ANSWER_LENGTH)
        || !validInstant(event.occurredTime())) {
      throw invalid("FAQ knowledge event values are invalid");
    }
  }

  private static boolean canonicalUuid(String value) {
    try {
      return value != null && UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static boolean boundedText(String value, int maximum) {
    return value != null && !value.isBlank() && value.length() <= maximum;
  }

  private static boolean validInstant(String value) {
    try {
      return value != null && Instant.parse(value).toString().equals(value);
    } catch (DateTimeParseException exception) {
      return false;
    }
  }

  private static FaqPublicationException invalid(String message) {
    return new FaqPublicationException(FaqPublicationException.Code.VALIDATION, message);
  }

  private static FaqPublicationException invalid(String message, Throwable cause) {
    FaqPublicationException exception = invalid(message);
    exception.initCause(cause);
    return exception;
  }
}
