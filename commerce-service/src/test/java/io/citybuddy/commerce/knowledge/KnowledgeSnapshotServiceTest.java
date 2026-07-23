package io.citybuddy.commerce.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeSnapshotServiceTest {
  private static final KnowledgeSnapshotProperties PROPERTIES =
      new KnowledgeSnapshotProperties("snapshot-client", "snapshot-secret", 1000);

  @Test
  void emitsPythonCompatibleClosedCommitmentsAndServerOwnedIdentity() {
    KnowledgeSnapshotRepository repository = mock(KnowledgeSnapshotRepository.class);
    when(repository.publishedFaqs(1001)).thenReturn(List.of(faq()));
    when(repository.publishedProducts(1001)).thenReturn(List.of(product()));
    KnowledgeSnapshotService service =
        new KnowledgeSnapshotService(
            repository,
            PROPERTIES,
            Clock.fixed(Instant.parse("2026-07-22T00:00:04.123456789Z"), ZoneOffset.UTC));

    Map<String, Object> snapshot = service.capture();

    assertThat(snapshot.keySet())
        .containsExactly(
            "schemaVersion",
            "snapshotId",
            "capturedAt",
            "recordCount",
            "sourceCount",
            "contentCommitment",
            "watermark",
            "records");
    assertThat(snapshot.get("snapshotId")).isEqualTo("ebc6a3a7-ca45-35e6-ad4d-4542f459aa0a");
    assertThat(snapshot.get("capturedAt")).isEqualTo("2026-07-22T00:00:04.123456789Z");
    assertThat(snapshot.get("recordCount")).isEqualTo(2);
    assertThat(snapshot.get("sourceCount")).isEqualTo(2);
    assertThat(snapshot.get("contentCommitment"))
        .isEqualTo("5624dd5e36f7571130b2cb974a6b8932b28338acfd65232fe421e918662dc0be");
    assertThat(snapshot.get("watermark"))
        .isEqualTo("51e474ba8f8f89cbd1076e23dad579b68fcaa6bcc4959b1331c4fea001fc2c16");
  }

  @Test
  void rejectsMalformedPublishedTruthInsteadOfPublishingAnIncompleteSnapshot() {
    KnowledgeSnapshotRepository repository = mock(KnowledgeSnapshotRepository.class);
    when(repository.publishedFaqs(1001))
        .thenReturn(
            List.of(
                new KnowledgeSnapshotRepository.PublishedSource(
                    faq().eventId(),
                    "FAQ/private",
                    1,
                    "answer",
                    "faq",
                    faq().occurredTime(),
                    faq().title(),
                    faq().content(),
                    "faq",
                    "und")));
    when(repository.publishedProducts(1001)).thenReturn(List.of());
    KnowledgeSnapshotService service =
        new KnowledgeSnapshotService(repository, PROPERTIES, Clock.systemUTC());

    assertThatThrownBy(service::capture)
        .isInstanceOf(KnowledgeSnapshotException.class)
        .hasMessage("invalid_published_source");
  }

  @Test
  void rejectsNullPublishedContentThatTheRepositoryMustStillEnumerate() {
    KnowledgeSnapshotRepository repository = mock(KnowledgeSnapshotRepository.class);
    when(repository.publishedFaqs(1001))
        .thenReturn(
            List.of(
                new KnowledgeSnapshotRepository.PublishedSource(
                    faq().eventId(),
                    faq().sourceId(),
                    faq().sourceVersion(),
                    faq().chunkId(),
                    faq().docType(),
                    faq().occurredTime(),
                    faq().title(),
                    null,
                    faq().category(),
                    faq().language())));
    when(repository.publishedProducts(1001)).thenReturn(List.of());
    KnowledgeSnapshotService service =
        new KnowledgeSnapshotService(repository, PROPERTIES, Clock.systemUTC());

    assertThatThrownBy(service::capture)
        .isInstanceOf(KnowledgeSnapshotException.class)
        .hasMessage("invalid_published_source");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ordersPrefixSourceIdsByTheDeclaredSourceAndChunkTuple() {
    KnowledgeSnapshotRepository repository = mock(KnowledgeSnapshotRepository.class);
    var first = faqWithIdentity("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "a");
    var second = faqWithIdentity("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "a-b");
    when(repository.publishedFaqs(1001)).thenReturn(List.of(second, first));
    when(repository.publishedProducts(1001)).thenReturn(List.of());
    KnowledgeSnapshotService service =
        new KnowledgeSnapshotService(repository, PROPERTIES, Clock.systemUTC());

    List<Map<String, Object>> records =
        (List<Map<String, Object>>) service.capture().get("records");

    assertThat(records).extracting(record -> record.get("sourceId")).containsExactly("a", "a-b");
  }

  @Test
  void dedicatedCredentialIsRequiredAndNeverFallsBackToAnonymous() {
    KnowledgeSnapshotAuthenticator authenticator = new KnowledgeSnapshotAuthenticator(PROPERTIES);
    String accepted =
        "Basic "
            + Base64.getEncoder()
                .encodeToString("snapshot-client:snapshot-secret".getBytes(StandardCharsets.UTF_8));

    authenticator.authenticate(accepted);
    assertThatThrownBy(() -> authenticator.authenticate(null))
        .isInstanceOf(KnowledgeSnapshotException.class)
        .hasMessage("invalid_snapshot_credential");
    assertThatThrownBy(
            () ->
                authenticator.authenticate(
                    "Basic "
                        + Base64.getEncoder()
                            .encodeToString(
                                "snapshot-client:wrong".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(KnowledgeSnapshotException.class)
        .hasMessage("invalid_snapshot_credential");
  }

  private static KnowledgeSnapshotRepository.PublishedSource faq() {
    return new KnowledgeSnapshotRepository.PublishedSource(
        "11111111-1111-4111-8111-111111111111",
        "faq-refund-policy",
        1,
        "answer",
        "faq",
        Instant.parse("2026-07-22T00:00:01.123456789Z"),
        "退款政策 Refund policy",
        "Public refund guidance.",
        "faq",
        "und");
  }

  private static KnowledgeSnapshotRepository.PublishedSource faqWithIdentity(
      String eventId, String sourceId) {
    var source = faq();
    return new KnowledgeSnapshotRepository.PublishedSource(
        eventId,
        sourceId,
        source.sourceVersion(),
        source.chunkId(),
        source.docType(),
        source.occurredTime(),
        source.title(),
        source.content(),
        source.category(),
        source.language());
  }

  private static KnowledgeSnapshotRepository.PublishedSource product() {
    return new KnowledgeSnapshotRepository.PublishedSource(
        "22222222-2222-4222-8222-222222222222",
        "product-jasmine-tea",
        3,
        "description",
        "product",
        Instant.parse("2026-07-22T00:00:03.123456789Z"),
        "茉莉绿茶 Jasmine green tea",
        "A public jasmine tea description.",
        "product",
        "und");
  }
}
