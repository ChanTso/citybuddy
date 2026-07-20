package io.citybuddy.commerce.faq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FaqKnowledgeEventCodecTest {
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final FaqKnowledgeEventCodec codec = new FaqKnowledgeEventCodec(objectMapper);

  @Test
  void roundTripsTheClosedBoundedPublicEnvelope() throws Exception {
    FaqKnowledgeEvent event = validEvent();

    String payload = codec.encode(event);
    JsonNode root = objectMapper.readTree(payload);

    assertThat(root.fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder(
            "eventId",
            "sourceId",
            "sourceType",
            "sourceVersion",
            "publicationState",
            "tombstone",
            "occurredTime",
            "content");
    assertThat(root.get("content").fieldNames())
        .toIterable()
        .containsExactlyInAnyOrder("question", "answer");
    assertThat(payload)
        .doesNotContain("draft", "user", "credential", "token", "metadata", "sql", "internal");
    assertThat(codec.decode(payload)).isEqualTo(event);
  }

  @Test
  void rejectsUnknownFieldsAndNonPublicationSemantics() throws Exception {
    String valid = codec.encode(validEvent());
    JsonNode root = objectMapper.readTree(valid);
    ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("metadata", "private");
    assertValidation(root.toString());

    root = objectMapper.readTree(valid);
    ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("content"))
        .put("draftNotes", "private");
    assertValidation(root.toString());

    root = objectMapper.readTree(valid);
    ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("sourceType", "product");
    assertValidation(root.toString());

    root = objectMapper.readTree(valid);
    ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("publicationState", "DRAFT");
    assertValidation(root.toString());

    root = objectMapper.readTree(valid);
    ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("tombstone", true);
    assertValidation(root.toString());

    root = objectMapper.readTree(valid);
    ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("sourceVersion", 0);
    assertValidation(root.toString());
  }

  @Test
  void rejectsMalformedIdentifiersTimesAndOversizedPublicContent() {
    FaqKnowledgeEvent valid = validEvent();
    assertEncodeValidation(
        new FaqKnowledgeEvent(
            "not-a-uuid",
            valid.sourceId(),
            valid.sourceType(),
            valid.sourceVersion(),
            valid.publicationState(),
            valid.tombstone(),
            valid.occurredTime(),
            valid.content()));
    assertEncodeValidation(
        new FaqKnowledgeEvent(
            valid.eventId(),
            "FAQ Uppercase",
            valid.sourceType(),
            valid.sourceVersion(),
            valid.publicationState(),
            valid.tombstone(),
            valid.occurredTime(),
            valid.content()));
    assertEncodeValidation(
        new FaqKnowledgeEvent(
            valid.eventId(),
            valid.sourceId(),
            valid.sourceType(),
            valid.sourceVersion(),
            valid.publicationState(),
            valid.tombstone(),
            "now",
            valid.content()));
    assertEncodeValidation(
        new FaqKnowledgeEvent(
            valid.eventId(),
            valid.sourceId(),
            valid.sourceType(),
            valid.sourceVersion(),
            valid.publicationState(),
            valid.tombstone(),
            valid.occurredTime(),
            new FaqKnowledgeEvent.PublicContent(
                "q".repeat(FaqKnowledgeEventCodec.MAX_QUESTION_LENGTH + 1), "answer")));
  }

  private void assertValidation(String payload) {
    assertThatThrownBy(() -> codec.decode(payload))
        .isInstanceOf(FaqPublicationException.class)
        .extracting(exception -> ((FaqPublicationException) exception).code())
        .isEqualTo(FaqPublicationException.Code.VALIDATION);
  }

  private void assertEncodeValidation(FaqKnowledgeEvent event) {
    assertThatThrownBy(() -> codec.encode(event))
        .isInstanceOf(FaqPublicationException.class)
        .extracting(exception -> ((FaqPublicationException) exception).code())
        .isEqualTo(FaqPublicationException.Code.VALIDATION);
  }

  private static FaqKnowledgeEvent validEvent() {
    return new FaqKnowledgeEvent(
        UUID.randomUUID().toString(),
        "returns-policy",
        FaqKnowledgeEventCodec.SOURCE_TYPE,
        2,
        FaqKnowledgeEventCodec.PUBLICATION_STATE,
        false,
        Instant.parse("2026-07-20T08:00:00.123456Z").toString(),
        new FaqKnowledgeEvent.PublicContent("How do returns work?", "Returns take five days."));
  }
}
