package io.citybuddy.commerce.faq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.citybuddy.commerce.faq.FaqFixturePublisher.Entry;
import io.citybuddy.commerce.faq.FaqFixturePublisher.Result;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(
    classes = FaqFixturePublisherIntegrationTest.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FaqFixturePublisherIntegrationTest {
  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  static class Application {
    @Bean
    FaqRepository repository(JdbcTemplate jdbc) {
      return new FaqRepository(jdbc);
    }

    @Bean
    FaqKnowledgeEventCodec codec(ObjectMapper mapper) {
      return new FaqKnowledgeEventCodec(mapper);
    }

    @Bean
    FaqPublicationService publication(FaqRepository repository, FaqKnowledgeEventCodec codec) {
      return new FaqPublicationService(repository, codec, Clock.systemUTC());
    }

    @Bean
    FaqFixturePublisher publisher(
        FaqRepository repository,
        FaqPublicationService publication,
        PlatformTransactionManager transactions) {
      return new FaqFixturePublisher(repository, publication, transactions);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
  }

  @Autowired private FaqFixturePublisher publisher;
  @Autowired private FaqPublicationService publication;
  @Autowired private FaqRepository repository;
  @Autowired private FaqKnowledgeEventCodec codec;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  private JdbcTemplate fixture;
  private String prefix;
  private final List<String> ownIds = new ArrayList<>();

  @BeforeEach
  void setup() {
    prefix = "retail-policy-fixture-it-" + UUID.randomUUID().toString().substring(0, 8);
    fixture =
        new JdbcTemplate(
            new DriverManagerDataSource(
                required("CATALOG_MYSQL_URL"), "root", required("MYSQL_BOOTSTRAP_PASSWORD")));
  }

  @AfterEach
  void removeOnlyThisTestsFaqs() {
    for (String id : ownIds) {
      fixture.update(
          "DELETE FROM commerce_outbox WHERE aggregate_type='FAQ' AND aggregate_id=?", id);
      fixture.update("DELETE FROM faq_publication_command WHERE faq_id=?", id);
      fixture.update("DELETE FROM faq_draft_command WHERE faq_id=?", id);
      fixture.update("DELETE FROM faq_source WHERE faq_id=?", id);
    }
  }

  @Test
  void firstBatchPublishesRealCommitmentsAndRepeatsWithoutAnyNewWrites() {
    Entry a = entry("a", "Delivery?", "Delivery follows the selected method.");
    Entry b = entry("b", "Returns?", "Request a refund for an eligible paid order.");
    List<Result> first = publisher.publish(List.of(b, a));
    assertThat(first).extracting(Result::faqId).containsExactly(a.faqId(), b.faqId());
    for (Result result : first) {
      assertThat(result.changed()).isTrue();
      assertThat(result.publishedVersion()).isEqualTo(1);
      assertThat(result.eventId()).isNotBlank();
      assertValidPublication(result);
      assertThat(count("faq_draft_command", result.faqId())).isEqualTo(1);
      assertThat(count("faq_publication_command", result.faqId())).isEqualTo(1);
      assertThat(outboxCount(result.faqId())).isEqualTo(1);
    }
    var repeated = publisher.publish(List.of(a, b));
    for (Result result : repeated) {
      assertThat(result.changed()).isFalse();
      assertThat(result.publishedVersion()).isEqualTo(1);
      assertThat(result.eventId()).isNull();
      assertThat(count("faq_draft_command", result.faqId())).isEqualTo(1);
      assertThat(count("faq_publication_command", result.faqId())).isEqualTo(1);
      assertThat(outboxCount(result.faqId())).isEqualTo(1);
    }
  }

  @Test
  void changedPublishedContentAdvancesCurrentVersionInsteadOfReusingAnOldImportIdentity() {
    Entry desired = entry("version", "Delivery?", "Standard delivery takes several business days.");
    Result initial = publisher.publish(List.of(desired)).getFirst();
    var draft =
        saveDraft(desired.faqId(), desired.question(), "An operator changed the policy.", 1);
    String externalEvent = UUID.randomUUID().toString();
    new TransactionTemplate(transactionManager)
        .execute(
            ignored ->
                publication.publish(
                    new FaqPublicationService.PublicationCommand(
                        desired.faqId(),
                        "operator/" + externalEvent,
                        externalEvent,
                        draft.draftRevision(),
                        draft.publishedVersion())));
    Result updated = publisher.publish(List.of(desired)).getFirst();
    assertThat(updated.publishedVersion()).isEqualTo(3);
    assertThat(updated.changed()).isTrue();
    assertThat(updated.eventId()).isNotEqualTo(initial.eventId()).isNotEqualTo(externalEvent);
    assertValidPublication(updated);
    var source = repository.findSource(desired.faqId()).orElseThrow();
    assertThat(source.publishedAnswer()).isEqualTo(desired.answer());
    assertThat(source.draftRevision()).isEqualTo(3);
    assertThat(count("faq_draft_command", desired.faqId())).isEqualTo(3);
    assertThat(count("faq_publication_command", desired.faqId())).isEqualTo(3);
    assertThat(outboxCount(desired.faqId())).isEqualTo(3);
    assertThat(publisher.publish(List.of(desired)).getFirst())
        .isEqualTo(new Result(desired.faqId(), 3, false, null));
  }

  @Test
  void identicalPublishedContentPreservesASeparateUnpublishedDraftAndMatchingDraftCanPublish() {
    Entry desired = entry("published", "Refunds?", "Refunds need an eligible paid order.");
    publisher.publish(List.of(desired));
    var differentDraft =
        saveDraft(desired.faqId(), "Pending policy question?", "Not approved yet.", 1);
    assertThat(publisher.publish(List.of(desired)).getFirst())
        .isEqualTo(new Result(desired.faqId(), 1, false, null));
    assertThat(repository.findSource(desired.faqId())).contains(differentDraft);
    assertThat(count("faq_draft_command", desired.faqId())).isEqualTo(2);
    assertThat(outboxCount(desired.faqId())).isEqualTo(1);

    Entry matching = entry("matching", "Pickup?", "Collect from the configured Shanghai store.");
    saveDraft(matching.faqId(), matching.question(), matching.answer(), 0);
    Result result = publisher.publish(List.of(matching)).getFirst();
    assertThat(result.changed()).isTrue();
    assertThat(result.publishedVersion()).isEqualTo(1);
    assertThat(repository.findSource(matching.faqId()).orElseThrow().draftRevision()).isEqualTo(1);
    assertThat(count("faq_draft_command", matching.faqId())).isEqualTo(1);
    assertValidPublication(result);
  }

  @Test
  void conflictingLastDraftRollsBackEarlierNewPublicationsAsOneBatch() {
    Entry first = entry("a-new", "New policy?", "This must roll back.");
    Entry second = entry("b-new", "Another policy?", "This must also roll back.");
    Entry blocked = entry("z-draft", "Desired policy?", "Cannot overwrite another draft.");
    var original = saveDraft(blocked.faqId(), "Operator draft?", "Waiting for approval.", 0);
    assertThatThrownBy(() -> publisher.publish(List.of(blocked, second, first)))
        .isInstanceOfSatisfying(
            FaqPublicationException.class,
            exception ->
                assertThat(exception.code()).isEqualTo(FaqPublicationException.Code.STALE_VERSION));
    assertThat(repository.findSource(blocked.faqId())).contains(original);
    assertThat(count("faq_draft_command", blocked.faqId())).isEqualTo(1);
    assertThat(outboxCount(blocked.faqId())).isZero();
    for (Entry entry : List.of(first, second)) {
      assertThat(repository.findSource(entry.faqId())).isEmpty();
      assertThat(count("faq_draft_command", entry.faqId())).isZero();
      assertThat(count("faq_publication_command", entry.faqId())).isZero();
      assertThat(outboxCount(entry.faqId())).isZero();
    }
  }

  @Test
  void downstreamPublicationByteLimitAlsoRollsBackDraftAndEarlierBatchWrites() {
    Entry first = entry("a-good", "Small policy?", "Fits the durable event.");
    Entry tooLarge = entry("z-large", "Large policy?", "界".repeat(3000));
    assertThatThrownBy(() -> publisher.publish(List.of(first, tooLarge)))
        .isInstanceOfSatisfying(
            FaqPublicationException.class,
            exception ->
                assertThat(exception.code()).isEqualTo(FaqPublicationException.Code.VALIDATION));
    for (Entry entry : List.of(first, tooLarge)) {
      assertThat(repository.findSource(entry.faqId())).isEmpty();
      assertThat(count("faq_draft_command", entry.faqId())).isZero();
      assertThat(count("faq_publication_command", entry.faqId())).isZero();
      assertThat(outboxCount(entry.faqId())).isZero();
    }
  }

  private Entry entry(String suffix, String question, String answer) {
    String id = prefix + "-" + suffix;
    ownIds.add(id);
    return new Entry(id, question, answer);
  }

  private FaqRepository.FaqSource saveDraft(
      String id, String question, String answer, long revision) {
    return new TransactionTemplate(transactionManager)
        .execute(ignored -> publication.saveDraft(id, question, answer, revision));
  }

  private void assertValidPublication(Result result) {
    FaqRepository.OutboxEvent outbox = repository.findOutbox(result.eventId()).orElseThrow();
    var commands =
        repository.findCommandsByIdentity(
            "faq-fixture/" + result.faqId() + "/" + result.publishedVersion(), result.eventId());
    assertThat(commands).hasSize(1);
    FaqKnowledgeEvent event = FaqPublicationCommitment.verify(commands.getFirst(), outbox, codec);
    FaqRepository.FaqSource source = repository.findSource(result.faqId()).orElseThrow();
    assertThat(event.sourceVersion()).isEqualTo(result.publishedVersion());
    assertThat(event.sourceId()).isEqualTo(result.faqId());
    assertThat(source.workingState()).isEqualTo("PUBLISHED");
    assertThat(event.content().question()).isEqualTo(source.publishedQuestion());
    assertThat(event.content().answer()).isEqualTo(source.publishedAnswer());
    assertThat(outbox.publicationState()).isEqualTo("PENDING");
  }

  private long count(String table, String id) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE faq_id=?", Long.class, id);
  }

  private long outboxCount(String id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_type='FAQ' AND aggregate_id=?",
        Long.class,
        id);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }
}
