package io.citybuddy.commerce.faq;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FaqOutboxPublisher {
  private final FaqRepository repository;
  private final FaqKnowledgeEventCodec codec;
  private final FaqEventSender sender;

  public FaqOutboxPublisher(
      FaqRepository repository, FaqKnowledgeEventCodec codec, FaqEventSender sender) {
    this.repository = repository;
    this.codec = codec;
    this.sender = sender;
  }

  public int publishPending(int limit) throws Exception {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Outbox publish limit must be between 1 and 100");
    }
    Set<String> commandEventIds = new HashSet<>();
    Map<String, VerifiedPublication> latestBySource = new HashMap<>();
    Map<String, FaqRepository.DraftCommand> latestDraftBySource = new HashMap<>();
    Map<String, Long> lastDraftRevisionBySource = new HashMap<>();
    List<FaqRepository.OutboxEvent> verified = new ArrayList<>();
    for (FaqRepository.DraftCommand draft : repository.allDraftCommands()) {
      FaqPublicationCommitment.verifyDraftCommand(draft);
      long lastDraftRevision = lastDraftRevisionBySource.getOrDefault(draft.faqId(), 0L);
      if (draft.expectedDraftRevision() != lastDraftRevision) {
        throw inconsistent("FAQ draft command history is not contiguous");
      }
      lastDraftRevisionBySource.put(draft.faqId(), draft.draftRevision());
      latestDraftBySource.merge(
          draft.faqId(),
          draft,
          (current, replacement) ->
              current.draftRevision() > replacement.draftRevision() ? current : replacement);
    }
    for (FaqRepository.PublicationTruth truth : repository.publicationTruths()) {
      commandEventIds.add(truth.command().eventId());
      if (truth.source() == null) {
        throw inconsistent("FAQ publication command has no source truth");
      }
      if (truth.outbox() == null) {
        throw inconsistent("FAQ publication command has no matching Outbox event");
      }
      FaqKnowledgeEvent event =
          FaqPublicationCommitment.verify(truth.command(), truth.outbox(), codec);
      VerifiedPublication candidate = new VerifiedPublication(truth.command(), event);
      latestBySource.merge(
          truth.command().faqId(),
          candidate,
          (current, replacement) ->
              current.command().sourceVersion() > replacement.command().sourceVersion()
                  ? current
                  : replacement);
      if ("PENDING".equals(truth.outbox().publicationState()) && verified.size() < limit) {
        verified.add(truth.outbox());
      }
    }
    for (FaqRepository.FaqSource source : repository.allSources()) {
      VerifiedPublication latest = latestBySource.remove(source.faqId());
      FaqRepository.DraftCommand latestDraft = latestDraftBySource.remove(source.faqId());
      if (latest == null && source.publishedVersion() != 0) {
        throw inconsistent("Published FAQ source has no applied publication command");
      }
      FaqPublicationCommitment.verifyCurrentSource(
          latest == null ? null : latest.command(),
          latestDraft,
          source,
          latest == null ? null : latest.event());
    }
    if (!latestBySource.isEmpty()) {
      throw inconsistent("FAQ publication command has no source truth");
    }
    if (!latestDraftBySource.isEmpty()) {
      throw inconsistent("FAQ draft command has no source truth");
    }
    for (FaqRepository.OutboxEvent event : repository.allOutboxEvents()) {
      if (!commandEventIds.contains(event.eventId())
          && FaqPublicationCommitment.hasFaqOwnershipSignal(event, codec)) {
        throw inconsistent("FAQ Outbox event has no immutable command");
      }
    }

    int published = 0;
    for (FaqRepository.OutboxEvent event : verified) {
      try {
        sender.send(event);
        repository.markPublished(event.eventId());
        published++;
      } catch (Exception exception) {
        repository.recordPublishFailure(event.eventId());
        throw exception;
      }
    }
    return published;
  }

  private static FaqPublicationException inconsistent(String message) {
    return new FaqPublicationException(
        FaqPublicationException.Code.INCONSISTENT_DURABLE_STATE, message);
  }

  @FunctionalInterface
  public interface FaqEventSender {
    void send(FaqRepository.OutboxEvent event) throws Exception;
  }

  private record VerifiedPublication(
      FaqRepository.PublicationCommand command, FaqKnowledgeEvent event) {}
}
