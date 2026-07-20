package io.citybuddy.commerce.faq;

public record FaqKnowledgeEvent(
    String eventId,
    String sourceId,
    String sourceType,
    long sourceVersion,
    String publicationState,
    boolean tombstone,
    String occurredTime,
    PublicContent content) {

  public record PublicContent(String question, String answer) {}
}
