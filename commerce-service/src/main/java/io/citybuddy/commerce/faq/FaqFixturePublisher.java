package io.citybuddy.commerce.faq;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class FaqFixturePublisher {
  private static final Pattern FAQ_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
  private final FaqRepository repository;
  private final FaqPublicationService publication;
  private final TransactionTemplate transactions;

  public FaqFixturePublisher(
      FaqRepository repository,
      FaqPublicationService publication,
      PlatformTransactionManager transactionManager) {
    this.repository = repository;
    this.publication = publication;
    transactions = new TransactionTemplate(transactionManager);
    transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public List<Result> publish(List<Entry> entries) {
    if (entries == null || entries.isEmpty() || entries.size() > 100) {
      throw validation("FAQ publication requires 1 to 100 entries");
    }
    Set<String> ids = new HashSet<>();
    for (Entry entry : entries) {
      if (entry == null || !ids.add(entry.faqId())) {
        throw validation("FAQ entries must be non-null and use distinct ids");
      }
    }
    List<Entry> ordered = entries.stream().sorted(Comparator.comparing(Entry::faqId)).toList();
    return transactions.execute(
        ignored -> {
          List<Result> results = new ArrayList<>();
          for (Entry entry : ordered) {
            results.add(publishOne(entry));
          }
          return List.copyOf(results);
        });
  }

  private Result publishOne(Entry entry) {
    FaqRepository.FaqSource source = repository.lockSource(entry.faqId()).orElse(null);
    if (source != null
        && source.publishedVersion() > 0
        && entry.question().equals(source.publishedQuestion())
        && entry.answer().equals(source.publishedAnswer())) {
      // An already published fixture must not overwrite a newer, unapproved draft.
      return new Result(source.faqId(), source.publishedVersion(), false, null);
    }
    if (source != null && source.workingState().equals("DRAFT")) {
      if (!entry.question().equals(source.draftQuestion())
          || !entry.answer().equals(source.draftAnswer())) {
        throw new FaqPublicationException(
            FaqPublicationException.Code.STALE_VERSION,
            "FAQ has a different unpublished draft: " + source.faqId());
      }
    } else {
      source =
          publication.saveDraft(
              entry.faqId(),
              entry.question(),
              entry.answer(),
              source == null ? 0 : source.draftRevision());
    }
    long nextVersion;
    try {
      nextVersion = Math.addExact(source.publishedVersion(), 1);
    } catch (ArithmeticException exception) {
      throw validation("FAQ published version cannot advance");
    }
    String eventId = UUID.randomUUID().toString();
    var result =
        publication.publish(
            new FaqPublicationService.PublicationCommand(
                source.faqId(),
                "faq-fixture/" + source.faqId() + "/" + nextVersion,
                eventId,
                source.draftRevision(),
                source.publishedVersion()));
    return new Result(
        source.faqId(), result.event().sourceVersion(), true, result.event().eventId());
  }

  private static FaqPublicationException validation(String message) {
    return new FaqPublicationException(FaqPublicationException.Code.VALIDATION, message);
  }

  public record Entry(String faqId, String question, String answer) {
    public Entry {
      if (faqId == null
          || !FAQ_ID.matcher(faqId).matches()
          || question == null
          || question.isBlank()
          || question.length() > FaqKnowledgeEventCodec.MAX_QUESTION_LENGTH
          || answer == null
          || answer.isBlank()
          || answer.length() > FaqKnowledgeEventCodec.MAX_ANSWER_LENGTH) {
        throw validation("FAQ entry is invalid");
      }
    }
  }

  public record Result(String faqId, long publishedVersion, boolean changed, String eventId) {}
}
