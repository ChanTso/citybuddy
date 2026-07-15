package io.citybuddy.commerce.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class CatalogIntegrationTest {
  private static final String VISIBLE_ID = "catalog-visible";
  private static final String HIDDEN_ID = "catalog-hidden";

  @DynamicPropertySource
  static void integrationProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("spring.data.redis.url", () -> required("CATALOG_REDIS_URL"));
    registry.add("citybuddy.catalog.enabled", () -> "true");
    registry.add("citybuddy.catalog.issuer", () -> "https://identity.citybuddy.test");
    registry.add("citybuddy.catalog.user-audience", () -> "citybuddy-web");
    registry.add("citybuddy.catalog.jwks-url", () -> required("IDENTITY_JWKS_URL"));
    registry.add("citybuddy.catalog.jwks-cache-ttl", () -> "30s");
    registry.add("citybuddy.catalog.clock-skew", () -> "30s");
    registry.add("citybuddy.catalog.required-permission", () -> "catalog:read");
    registry.add("citybuddy.catalog.cache-ttl", () -> "30s");
    registry.add("citybuddy.catalog.cache-jitter", () -> "10s");
    registry.add("citybuddy.catalog.null-ttl", () -> "3s");
    registry.add("citybuddy.catalog.mutex-ttl", () -> "2s");
    registry.add("citybuddy.catalog.worker-initial-delay-ms", () -> "3600000");
    registry.add("citybuddy.catalog.worker-delay-ms", () -> "3600000");
    registry.add("citybuddy.catalog.rocketmq-endpoints", () -> required("ROCKETMQ_ENDPOINTS"));
    registry.add("citybuddy.catalog.rocketmq-topic", () -> required("ROCKETMQ_TOPIC"));
    registry.add(
        "citybuddy.catalog.rocketmq-consumer-group", () -> required("ROCKETMQ_CONSUMER_GROUP"));
  }

  @LocalServerPort private int port;
  @Autowired private CatalogProperties properties;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private ProductRepository repository;
  @Autowired private ProductCache cache;
  @Autowired private ProductPublicationService publicationService;
  @Autowired private CatalogEventWorker eventWorker;
  @Autowired private ProductInvalidationHandler invalidationHandler;
  @Autowired private RocketMqCatalogMessaging messaging;
  @Autowired private StringRedisTemplate redis;
  @Autowired private TestRestTemplate rest;
  @Autowired private TransactionTemplate transactions;

  @Test
  void provesCatalogTruthCacheOutboxAndNormalEventRecovery() throws Exception {
    seedPublishedAndUnpublishedProducts();
    proveAuthenticatedContractsAndLiveFields();
    proveRedisProtectionsAndMysqlFallback();
    provePublicationAtomicityAndNormalEventRecovery();
  }

  private void seedPublishedAndUnpublishedProducts() {
    jdbc.update(
        """
        INSERT INTO product
          (product_id, name, description, price_minor, currency, stock_quantity,
           available, publication_state, publication_version)
        VALUES (?, 'Visible product', 'Published catalog content', 1000, 'AUD', 8, TRUE,
                'PUBLISHED', 1)
        """,
        VISIBLE_ID);
    jdbc.update(
        """
        INSERT INTO product
          (product_id, name, description, price_minor, currency, stock_quantity,
           available, publication_state, publication_version)
        VALUES (?, 'Hidden product', 'Must not be public', 700, 'AUD', 3, TRUE,
                'UNPUBLISHED', 1)
        """,
        HIDDEN_ID);
    jdbc.update(
        "INSERT INTO catalog_metadata (singleton_id, publication_generation) VALUES (1, 1)");
  }

  private void proveAuthenticatedContractsAndLiveFields() {
    ResponseEntity<JsonNode> list = get("/api/products", token(), null);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(list.getBody()).isNotNull();
    assertThat(list.getBody().isArray()).isTrue();
    assertThat(list.getBody()).hasSize(1);
    assertThat(list.getBody().get(0).get("productId").asText()).isEqualTo(VISIBLE_ID);

    ResponseEntity<JsonNode> first = get("/api/products/" + VISIBLE_ID, token(), null);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(first.getBody().get("priceMinor").asLong()).isEqualTo(1000);

    jdbc.update(
        "UPDATE product SET price_minor = 1250, stock_quantity = 2, available = FALSE WHERE product_id = ?",
        VISIBLE_ID);
    ResponseEntity<JsonNode> live = get("/api/products/" + VISIBLE_ID, token(), null);
    assertThat(live.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(live.getBody().get("priceMinor").asLong()).isEqualTo(1250);
    assertThat(live.getBody().get("stockQuantity").asLong()).isEqualTo(2);
    assertThat(live.getBody().get("available").asBoolean()).isFalse();

    assertThat(get("/api/products/does-not-exist", token(), null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/products/" + HIDDEN_ID, token(), null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/products", null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/api/products", "not-a-jwt", null).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/api/products", token(), "forbidden-production-context").getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  private void proveRedisProtectionsAndMysqlFallback() throws Exception {
    long generation = repository.catalogGeneration();
    String prefix = "catalog:product:";
    redis.delete(redis.keys("catalog:*") == null ? List.of() : redis.keys("catalog:*"));

    AtomicInteger nullLoads = new AtomicInteger();
    assertThat(
            cache.resolve(
                "bounded-missing",
                generation,
                () -> {
                  nullLoads.incrementAndGet();
                  return Optional.empty();
                }))
        .isEmpty();
    assertThat(
            cache.resolve(
                "bounded-missing",
                generation,
                () -> {
                  nullLoads.incrementAndGet();
                  return Optional.empty();
                }))
        .isEmpty();
    assertThat(nullLoads).hasValue(1);
    Long nullTtl = redis.getExpire("catalog:null:bounded-missing:" + generation, TimeUnit.SECONDS);
    assertThat(nullTtl).isBetween(1L, properties.nullTtl().toSeconds());

    redis.opsForValue().set("catalog:bloom:generation", Long.toString(generation));
    redis.opsForValue().set("catalog:bloom:complete", "false");
    AtomicInteger incompleteLoads = new AtomicInteger();
    assertThat(
            cache.resolve(
                VISIBLE_ID,
                generation,
                () -> {
                  incompleteLoads.incrementAndGet();
                  return repository.findPublished(VISIBLE_ID);
                }))
        .isPresent();
    assertThat(incompleteLoads).hasValue(1);

    cache.rebuildBloom(generation, repository.publishedIds());
    AtomicInteger bloomNegativeLoads = new AtomicInteger();
    assertThat(
            cache.resolve(
                "complete-bloom-missing",
                generation,
                () -> {
                  bloomNegativeLoads.incrementAndGet();
                  return Optional.empty();
                }))
        .isEmpty();
    assertThat(bloomNegativeLoads).hasValue(0);

    redis.delete("catalog:bloom:bits:" + generation);
    AtomicInteger missingBitsLoads = new AtomicInteger();
    assertThat(
            cache.resolve(
                "missing-bitset-product",
                generation,
                () -> {
                  missingBitsLoads.incrementAndGet();
                  return Optional.empty();
                }))
        .isEmpty();
    assertThat(missingBitsLoads).hasValue(1);

    proveConcurrentBloomRebuildCannotManufactureNotFound(generation);

    Product first = new Product("ttl-alpha", "Alpha", "", 1, "AUD", 1, true, 1);
    Product second = new Product("ttl-beta", "Beta", "", 1, "AUD", 1, true, 1);
    cache.put(first, generation);
    cache.put(second, generation);
    Long firstTtl =
        redis.getExpire(prefix + first.productId() + ":" + generation, TimeUnit.SECONDS);
    Long secondTtl =
        redis.getExpire(prefix + second.productId() + ":" + generation, TimeUnit.SECONDS);
    assertThat(firstTtl).isBetween(properties.cacheTtl().toSeconds() - 1, 40L);
    assertThat(secondTtl).isBetween(properties.cacheTtl().toSeconds() - 1, 40L);
    assertThat(firstTtl).isNotEqualTo(secondTtl);

    cache.evict(VISIBLE_ID);
    redis.opsForValue().set("catalog:bloom:complete", "false");
    AtomicInteger concurrentLoads = new AtomicInteger();
    CountDownLatch ready = new CountDownLatch(8);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(8);
    List<java.util.concurrent.Future<Optional<Product>>> futures = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      futures.add(
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return cache.resolve(
                    VISIBLE_ID,
                    generation,
                    () -> {
                      concurrentLoads.incrementAndGet();
                      try {
                        Thread.sleep(150);
                      } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Controlled loader interrupted", exception);
                      }
                      return repository.findPublished(VISIBLE_ID);
                    });
              }));
    }
    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    for (var future : futures) {
      assertThat(future.get(5, TimeUnit.SECONDS)).isPresent();
    }
    executor.shutdownNow();
    assertThat(concurrentLoads).hasValue(1);
    assertThat(redis.opsForValue().get(prefix + VISIBLE_ID + ":" + generation)).isNotBlank();

    Product forged = new Product("redis-forged", "Forged", "", 1, "AUD", 1, true, 1);
    redis
        .opsForValue()
        .set(prefix + forged.productId() + ":" + generation, json(forged), Duration.ofSeconds(30));
    ProductCatalogService service = new ProductCatalogService(repository, cache);
    assertThat(service.findPublished(forged.productId())).isEmpty();
    assertThat(redis.hasKey(prefix + forged.productId() + ":" + generation)).isFalse();

    cache.evict(VISIBLE_ID);
    Product wrongIdentity = new Product("other-product", "Forged", "", 1250, "AUD", 2, false, 1);
    redis
        .opsForValue()
        .set(prefix + VISIBLE_ID + ":" + generation, json(wrongIdentity), Duration.ofSeconds(30));
    Product rebuilt = service.findPublished(VISIBLE_ID).orElseThrow();
    assertThat(rebuilt.productId()).isEqualTo(VISIBLE_ID);
    assertThat(rebuilt.name()).isEqualTo("Visible product");

    try (UnavailableCache unavailable = unavailableCache()) {
      AtomicInteger outageLoads = new AtomicInteger();
      assertThat(
              unavailable
                  .cache()
                  .resolve(
                      VISIBLE_ID,
                      generation,
                      () -> {
                        outageLoads.incrementAndGet();
                        return repository.findPublished(VISIBLE_ID);
                      }))
          .isPresent();
      assertThat(outageLoads).hasValue(1);
    }
  }

  private void proveConcurrentBloomRebuildCannotManufactureNotFound(long generation)
      throws Exception {
    List<String> publishedIds = repository.publishedIds();
    cache.rebuildBloom(generation, publishedIds);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger mysqlLoads = new AtomicInteger();
    AtomicInteger falseNegatives = new AtomicInteger();
    var executor = Executors.newFixedThreadPool(2);
    var rebuilds =
        executor.submit(
            () -> {
              ready.countDown();
              start.await();
              for (int iteration = 0; iteration < 200; iteration++) {
                cache.rebuildBloom(generation, publishedIds);
              }
              return null;
            });
    var reads =
        executor.submit(
            () -> {
              ready.countDown();
              start.await();
              for (int iteration = 0; iteration < 400; iteration++) {
                cache.evict(VISIBLE_ID, generation);
                Optional<Product> resolved =
                    cache.resolve(
                        VISIBLE_ID,
                        generation,
                        () -> {
                          mysqlLoads.incrementAndGet();
                          return repository.findPublished(VISIBLE_ID);
                        });
                if (resolved.isEmpty()) {
                  falseNegatives.incrementAndGet();
                }
              }
              return null;
            });
    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    start.countDown();
    rebuilds.get(20, TimeUnit.SECONDS);
    reads.get(20, TimeUnit.SECONDS);
    executor.shutdownNow();

    assertThat(mysqlLoads).hasPositiveValue();
    assertThat(falseNegatives).hasValue(0);
  }

  private void provePublicationAtomicityAndNormalEventRecovery() throws Exception {
    UUID firstEventId = UUID.randomUUID();
    ProductRepository.ProductDraft firstDraft =
        new ProductRepository.ProductDraft(
            "event-product", "Version one", "", 2100, "AUD", 5, true, true);
    ProductRepository.Publication firstPublication =
        publicationService.publish(firstDraft, firstEventId);
    long generationAfterFirst = repository.catalogGeneration();
    assertThat(repository.findPublished(firstDraft.productId())).isPresent();
    assertThat(outboxState(firstEventId)).isEqualTo("PENDING:0");

    ProductRepository.ProductDraft rolledBack =
        new ProductRepository.ProductDraft(
            firstDraft.productId(), "Must roll back", "", 9999, "AUD", 1, false, true);
    assertThatThrownBy(() -> publicationService.publish(rolledBack, firstEventId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    Product afterRollback = repository.findPublished(firstDraft.productId()).orElseThrow();
    assertThat(afterRollback.name()).isEqualTo("Version one");
    assertThat(afterRollback.publicationVersion()).isEqualTo(1);
    assertThat(repository.catalogGeneration()).isEqualTo(generationAfterFirst);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM commerce_outbox WHERE event_id = ?",
                Integer.class,
                firstEventId.toString()))
        .isEqualTo(1);

    UUID secondEventId = UUID.randomUUID();
    ProductRepository.ProductDraft secondDraft =
        new ProductRepository.ProductDraft(
            firstDraft.productId(), "Version two", "", 2200, "AUD", 6, true, true);
    publicationService.publish(secondDraft, secondEventId);
    assertThat(repository.findPublished(firstDraft.productId()).orElseThrow().publicationVersion())
        .isEqualTo(2);

    Product old = new Product("recovery-product", "Old", "", 3000, "AUD", 4, true, 1);
    jdbc.update(
        """
        INSERT INTO product
          (product_id, name, description, price_minor, currency, stock_quantity,
           available, publication_state, publication_version)
        VALUES (?, ?, '', ?, 'AUD', 4, TRUE, 'PUBLISHED', 1)
        """,
        old.productId(),
        old.name(),
        old.priceMinor());
    cache.put(old, repository.catalogGeneration());
    UUID recoveryEventId = UUID.randomUUID();
    ProductRepository.ProductDraft currentDraft =
        new ProductRepository.ProductDraft(
            old.productId(), "Current", "", 3500, "AUD", 7, true, true);
    try (UnavailableCache unavailable = unavailableCache()) {
      ProductPublicationService deletionFailureService =
          new ProductPublicationService(repository, unavailable.cache());
      transactions.executeWithoutResult(
          status -> deletionFailureService.publish(currentDraft, recoveryEventId));
    }
    Product current = repository.findPublished(old.productId()).orElseThrow();
    assertThat(current.publicationVersion()).isEqualTo(2);
    assertThat(outboxState(recoveryEventId)).isEqualTo("PENDING:0");

    ProductRepository.OutboxEvent failedPublish = repository.pendingOutbox(1).getFirst();
    ProductRepository.OutboxEvent staleEvent = outboxEvent(firstEventId);
    CatalogProperties closedProperties =
        new CatalogProperties(
            properties.issuer(),
            properties.userAudience(),
            properties.jwksUrl(),
            properties.jwksCacheTtl(),
            properties.clockSkew(),
            properties.requiredPermission(),
            properties.cacheTtl(),
            properties.cacheJitter(),
            properties.nullTtl(),
            properties.mutexTtl(),
            properties.rocketmqEndpoints(),
            properties.rocketmqTopic(),
            properties.rocketmqConsumerGroup() + "-closed");
    RocketMqCatalogMessaging closed = new RocketMqCatalogMessaging(closedProperties);
    closed.close();
    assertThatThrownBy(() -> new CatalogOutboxPublisher(repository, closed).publishPending(1))
        .isInstanceOf(Exception.class);
    assertThat(outboxState(UUID.fromString(failedPublish.eventId()))).isEqualTo("PENDING:1");

    int pending = repository.pendingOutbox(100).size();
    assertThat(pending).isGreaterThanOrEqualTo(2);
    assertThat(eventWorker.publishOnce()).isEqualTo(pending);
    assertThat(repository.pendingOutbox(100)).isEmpty();

    var failedDelivery = new java.util.concurrent.atomic.AtomicReference<String>();
    assertThatThrownBy(
            () ->
                messaging.consumeOnce(
                    payload -> {
                      failedDelivery.set(repository.parseEvent(payload).eventId());
                      throw new IllegalStateException("Controlled consumer failure");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Controlled consumer failure");
    assertThat(failedDelivery.get()).isNotBlank();

    ConcurrentHashMap<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
    Thread.sleep(16_000);
    int consumed =
        consumeUntil(
            payload -> {
              String eventId = repository.parseEvent(payload).eventId();
              deliveries.computeIfAbsent(eventId, ignored -> new AtomicInteger()).incrementAndGet();
              invalidationHandler.handle(payload);
            },
            pending,
            Duration.ofSeconds(45));
    assertThat(consumed).isGreaterThanOrEqualTo(pending);
    assertThat(deliveries).containsKey(failedDelivery.get());

    long currentGeneration = repository.catalogGeneration();
    Product cachedCurrent =
        cache.resolve(old.productId(), currentGeneration, Optional::<Product>empty).orElseThrow();
    assertThat(cachedCurrent.name()).isEqualTo("Current");
    assertThat(cachedCurrent.publicationVersion()).isEqualTo(2);

    Product cachedNewer =
        cache
            .resolve(firstDraft.productId(), currentGeneration, Optional::<Product>empty)
            .orElseThrow();
    assertThat(cachedNewer.publicationVersion()).isEqualTo(2);

    messaging.send(staleEvent);
    int beforeDuplicate = deliveries.getOrDefault(staleEvent.eventId(), new AtomicInteger()).get();
    consumeUntil(
        payload -> {
          String eventId = repository.parseEvent(payload).eventId();
          deliveries.computeIfAbsent(eventId, ignored -> new AtomicInteger()).incrementAndGet();
          invalidationHandler.handle(payload);
        },
        1,
        Duration.ofSeconds(30));
    assertThat(deliveries.get(staleEvent.eventId()).get()).isGreaterThan(beforeDuplicate);
    Product afterDuplicate =
        cache
            .resolve(firstDraft.productId(), currentGeneration, Optional::<Product>empty)
            .orElseThrow();
    assertThat(afterDuplicate.publicationVersion()).isEqualTo(2);
    assertThat(firstPublication.event().productVersion()).isEqualTo(1);

    String invalidPayload =
        json(
            new ProductRepository.CatalogEvent(
                UUID.randomUUID().toString(),
                "invalid-product",
                0,
                currentGeneration,
                "PUBLISHED"));
    messaging.send(new ProductRepository.OutboxEvent(UUID.randomUUID().toString(), invalidPayload));
    assertThatThrownBy(eventWorker::consumeOnce).isInstanceOf(IllegalArgumentException.class);
  }

  private int consumeUntil(CatalogEventHandler handler, int expected, Duration timeout)
      throws Exception {
    int consumed = 0;
    long deadline = System.nanoTime() + timeout.toNanos();
    while (consumed < expected && System.nanoTime() < deadline) {
      consumed += messaging.consumeOnce(handler);
    }
    return consumed;
  }

  private String outboxState(UUID eventId) {
    return jdbc.queryForObject(
        "SELECT CONCAT(publication_state, ':', publish_attempts) FROM commerce_outbox WHERE event_id = ?",
        String.class,
        eventId.toString());
  }

  private ProductRepository.OutboxEvent outboxEvent(UUID eventId) {
    String payload =
        jdbc.queryForObject(
            "SELECT payload FROM commerce_outbox WHERE event_id = ?",
            String.class,
            eventId.toString());
    return new ProductRepository.OutboxEvent(eventId.toString(), payload);
  }

  private ResponseEntity<JsonNode> get(String path, String bearer, String evalSandbox) {
    HttpHeaders headers = new HttpHeaders();
    if (bearer != null) {
      headers.setBearerAuth(bearer);
    }
    if (evalSandbox != null) {
      headers.set("X-Eval-Sandbox-Id", evalSandbox);
    }
    return rest.exchange(
        "http://127.0.0.1:" + port + path,
        HttpMethod.GET,
        new HttpEntity<>(headers),
        JsonNode.class);
  }

  private UnavailableCache unavailableCache() {
    RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration("127.0.0.1", 1);
    LettuceClientConfiguration client =
        LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(250))
            .shutdownTimeout(Duration.ZERO)
            .build();
    LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client);
    factory.afterPropertiesSet();
    factory.start();
    StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return new UnavailableCache(new ProductCache(template, objectMapper, properties), factory);
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String token() {
    return required("CATALOG_DIRECT_TOKEN");
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }

  private record UnavailableCache(ProductCache cache, LettuceConnectionFactory factory)
      implements AutoCloseable {
    @Override
    public void close() {
      factory.destroy();
    }
  }
}
