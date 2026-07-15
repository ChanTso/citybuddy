package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest
class SeckillIntegrationTest {
  private static final Instant START = Instant.parse("2030-01-01T00:00:00.123456789Z");
  private static final Instant END = Instant.parse("2030-01-01T01:00:00Z");

  @DynamicPropertySource
  static void integrationProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> required("CATALOG_MYSQL_URL"));
    registry.add("spring.datasource.username", () -> "commerce_app");
    registry.add("spring.datasource.password", () -> required("MYSQL_COMMERCE_APP_PASSWORD"));
    registry.add("spring.data.redis.url", () -> required("CATALOG_REDIS_URL"));
    registry.add("citybuddy.seckill.enabled", () -> "true");
  }

  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private StringRedisTemplate redis;
  @Autowired private SeckillActivityRepository repository;
  @Autowired private SeckillProjectionStore projectionStore;
  @Autowired private SeckillActivityService service;
  @Autowired private TransactionTemplate transactions;

  @Test
  void provesAuthoritativeAllocationVersioningRebuildAndRejections() throws Exception {
    seedProduct("seckill-main", 12);
    SeckillActivityService.AllocationResult created =
        service.create(command("activity-main", "seckill-main", SeckillActivityState.ACTIVE, 8));
    assertThat(created.projectionResult()).isEqualTo(SeckillProjectionStore.PublishResult.APPLIED);
    assertActivity("activity-main", 8, 1);
    assertProjection("activity-main", 8, 1, SeckillActivityState.ACTIVE);
    assertThat(service.rebuildProjection("activity-main").projectionResult())
        .isEqualTo(SeckillProjectionStore.PublishResult.IDEMPOTENT);

    SeckillActivity versionOne = created.activity();
    SeckillActivityService.AllocationResult changed = service.changeAllocation("activity-main", 6);
    assertThat(changed.activity().projectionVersion()).isEqualTo(2);
    assertActivity("activity-main", 6, 2);
    assertProjection("activity-main", 6, 2, SeckillActivityState.ACTIVE);

    assertThat(projectionStore.publish(versionOne))
        .isEqualTo(SeckillProjectionStore.PublishResult.STALE_REJECTED);
    assertProjection("activity-main", 6, 2, SeckillActivityState.ACTIVE);
    SeckillActivity conflictingVersion =
        new SeckillActivity(
            "activity-main", "seckill-main", START, END, SeckillActivityState.ACTIVE, 5, 2);
    assertThatThrownBy(() -> projectionStore.publish(conflictingVersion))
        .isInstanceOf(SeckillProjectionStore.ProjectionWriteException.class)
        .hasMessageContaining("conflicts");
    assertActivity("activity-main", 6, 2);

    redis.delete(projectionStore.key("activity-main"));
    assertThat(service.rebuildProjection("activity-main").projectionResult())
        .isEqualTo(SeckillProjectionStore.PublishResult.APPLIED);
    assertProjection("activity-main", 6, 2, SeckillActivityState.ACTIVE);

    redis.opsForValue().set(projectionStore.key("activity-main"), "{malformed");
    assertThatThrownBy(() -> service.rebuildProjection("activity-main"))
        .isInstanceOf(SeckillProjectionStore.ProjectionWriteException.class)
        .hasMessageContaining("malformed");
    assertActivity("activity-main", 6, 2);

    assertThatThrownBy(
            () ->
                service.create(
                    command(
                        "activity-overallocated", "seckill-main", SeckillActivityState.DRAFT, 13)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authoritative inventory");
    assertThat(repository.find("activity-overallocated")).isEmpty();
    assertThat(redis.hasKey(projectionStore.key("activity-overallocated"))).isFalse();

    assertThatThrownBy(
            () ->
                service.create(
                    new SeckillActivityService.CreateActivity(
                        "activity-window",
                        "seckill-main",
                        END,
                        START,
                        SeckillActivityState.DRAFT,
                        1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("window");

    service.create(command("activity-closed", "seckill-main", SeckillActivityState.CLOSED, 3));
    assertThatThrownBy(() -> service.changeAllocation("activity-closed", 2))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("immutable");
    assertActivity("activity-closed", 3, 1);

    SeckillActivity rolledBack =
        new SeckillActivity(
            "activity-rollback", "seckill-main", START, END, SeckillActivityState.DRAFT, 2, 1);
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      repository.insert(rolledBack);
                      throw new IllegalStateException("controlled failure before commit");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled failure before commit");
    assertThat(repository.find("activity-rollback")).isEmpty();
    assertThat(redis.hasKey(projectionStore.key("activity-rollback"))).isFalse();
    assertStock("seckill-main", 12);
  }

  @Test
  void provesUnavailableAndNoevictionFailuresRemainVisibleWithoutTruthReversal() {
    seedProduct("seckill-failure", 10);
    try (UnavailableProjection unavailable = unavailableProjection()) {
      SeckillActivityService failingService =
          new SeckillActivityService(repository, unavailable.store(), transactions);
      assertThatThrownBy(
              () ->
                  failingService.create(
                      command(
                          "activity-unavailable",
                          "seckill-failure",
                          SeckillActivityState.DRAFT,
                          4)))
          .isInstanceOf(SeckillProjectionStore.ProjectionWriteException.class)
          .hasMessageContaining("write failed")
          .hasStackTraceContaining("Connection refused");
    }
    assertActivity("activity-unavailable", 4, 1);
    assertThat(redis.hasKey(projectionStore.key("activity-unavailable"))).isFalse();
    assertStock("seckill-failure", 10);
    assertNoOrderOrOutbox("seckill-failure");

    try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
      RedisServerCommands server = connection.serverCommands();
      String originalMaxmemory = config(server, "maxmemory");
      String originalPolicy = config(server, "maxmemory-policy");
      long usedMemory = Long.parseLong(server.info("memory").getProperty("used_memory"));
      try {
        server.setConfig("maxmemory-policy", "noeviction");
        server.setConfig("maxmemory", Long.toString(usedMemory));
        assertThatThrownBy(
                () ->
                    service.create(
                        command(
                            "activity-noeviction",
                            "seckill-failure",
                            SeckillActivityState.DRAFT,
                            5)))
            .isInstanceOf(SeckillProjectionStore.ProjectionWriteException.class)
            .hasMessageContaining("write failed")
            .hasStackTraceContaining("OOM");
      } finally {
        server.setConfig("maxmemory", originalMaxmemory);
        server.setConfig("maxmemory-policy", originalPolicy);
      }
    }
    assertActivity("activity-noeviction", 5, 1);
    assertThat(redis.hasKey(projectionStore.key("activity-noeviction"))).isFalse();
    assertStock("seckill-failure", 10);
    assertNoOrderOrOutbox("seckill-failure");
  }

  private SeckillActivityService.CreateActivity command(
      String activityId, String productId, SeckillActivityState state, long quota) {
    return new SeckillActivityService.CreateActivity(
        activityId, productId, START, END, state, quota);
  }

  private void seedProduct(String productId, long stock) {
    jdbc.update(
        """
        INSERT INTO product
          (product_id, name, description, price_minor, currency, stock_quantity,
           available, publication_state, publication_version)
        VALUES (?, ?, 'Seckill integration product', 1000, 'AUD', ?, TRUE, 'PUBLISHED', 1)
        """,
        productId,
        productId,
        stock);
  }

  private void assertActivity(String activityId, long quota, long version) {
    SeckillActivity activity = repository.find(activityId).orElseThrow();
    assertThat(activity.allocatedQuota()).isEqualTo(quota);
    assertThat(activity.projectionVersion()).isEqualTo(version);
  }

  private void assertProjection(
      String activityId, long quota, long version, SeckillActivityState state) throws Exception {
    SeckillActivity truth = repository.find(activityId).orElseThrow();
    JsonNode projection =
        objectMapper.readTree(redis.opsForValue().get(projectionStore.key(activityId)));
    assertThat(projection.get("activityId").asText()).isEqualTo(activityId);
    assertThat(projection.get("projectionVersion").asLong()).isEqualTo(version);
    assertThat(projection.get("startsAt").asText()).isEqualTo(truth.startsAt().toString());
    assertThat(projection.get("endsAt").asText()).isEqualTo(truth.endsAt().toString());
    assertThat(projection.get("state").asText()).isEqualTo(state.name());
    assertThat(projection.get("remainingQuota").asLong()).isEqualTo(quota);
  }

  private void assertStock(String productId, long expected) {
    assertThat(
            jdbc.queryForObject(
                "SELECT stock_quantity FROM product WHERE product_id = ?", Long.class, productId))
        .isEqualTo(expected);
  }

  private void assertNoOrderOrOutbox(String productId) {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM standard_order WHERE product_id = ?",
                Integer.class,
                productId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM commerce_outbox WHERE aggregate_id = ?",
                Integer.class,
                productId))
        .isZero();
  }

  private UnavailableProjection unavailableProjection() {
    RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration("127.0.0.1", 1);
    LettuceClientConfiguration client =
        LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(100))
            .shutdownTimeout(Duration.ofMillis(100))
            .build();
    LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, client);
    factory.afterPropertiesSet();
    StringRedisTemplate template = new StringRedisTemplate(factory);
    template.afterPropertiesSet();
    return new UnavailableProjection(factory, new SeckillProjectionStore(template, objectMapper));
  }

  private static String config(RedisServerCommands server, String name) {
    Properties values = server.getConfig(name);
    String value = values.getProperty(name);
    if (value == null) {
      throw new IllegalStateException("Redis config is missing: " + name);
    }
    return value;
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }

  private record UnavailableProjection(
      LettuceConnectionFactory factory, SeckillProjectionStore store) implements AutoCloseable {
    @Override
    public void close() {
      factory.destroy();
    }
  }
}
