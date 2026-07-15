package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class SeckillReservationIntegrationTest {
  private static final Instant ACTIVE_START = Instant.parse("2020-01-01T00:00:00Z");
  private static final Instant ACTIVE_END = Instant.parse("2037-01-01T00:00:00Z");

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
  @Autowired private SeckillActivityRepository activityRepository;
  @Autowired private SeckillReservationRepository reservationRepository;
  @Autowired private SeckillProjectionStore projectionStore;
  @Autowired private ReservationAdmissionStore admissionStore;
  @Autowired private SeckillReservationProperties properties;
  @Autowired private SeckillActivityService activityService;
  @Autowired private SeckillReservationService reservationService;
  @Autowired private TransactionTemplate transactions;

  @Test
  void admitsAtomicallyAndProvidesOwnerScopedIdempotentTruth() throws Exception {
    createActivity("reservation-main", "reservation-product-main", SeckillActivityState.ACTIVE, 5);

    ReservationResult admitted =
        reservationService.reserve(
            "subject-main", "reservation-main", "request-main", request(2, 1));
    assertThat(admitted.state()).isEqualTo(ReservationState.ADMITTED);
    assertThat(admitted.decisionCode()).isEqualTo(ReservationDecisionCode.ADMITTED);
    assertThat(admitted.projectionVersion()).isEqualTo(2);
    assertThat(admitted.replay()).isFalse();
    assertThat(admitted.durableOrderCreated()).isFalse();
    assertRemaining("reservation-main", 3);
    assertTerminalProjection(admitted, "subject-main");

    ReservationResult replay =
        reservationService.reserve(
            "subject-main", "reservation-main", "request-main", request(2, 1));
    assertThat(replay.reservationId()).isEqualTo(admitted.reservationId());
    assertThat(replay.replay()).isTrue();
    assertRemaining("reservation-main", 3);
    assertThat(reservationService.pollOwned("subject-main", admitted.reservationId()))
        .isEqualTo(replay);
    assertThatThrownBy(
            () -> reservationService.pollOwned("another-subject", admitted.reservationId()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not owned");

    assertThatThrownBy(
            () ->
                reservationService.reserve(
                    "subject-main", "reservation-main", "request-main", request(1, 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("conflicting reservation intent");
    ReservationRequest substitutedOwner = request(1, 1);
    substitutedOwner.captureExtra("userSubject", "attacker");
    assertThatThrownBy(
            () ->
                reservationService.reserve(
                    "subject-main", "reservation-main", "request-owner", substitutedOwner))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authenticated identity");
    assertNoOrderOrOutbox("reservation-product-main");
  }

  @Test
  void rejectsEveryDeterministicBusinessOutcomeWithoutCreatingOrders() throws Exception {
    createActivity(
        "reservation-inactive", "reservation-product-inactive", SeckillActivityState.DRAFT, 2);
    ReservationResult inactive =
        assertRejected(
            "inactive-subject",
            "reservation-inactive",
            "inactive-key",
            request(1, 1),
            ReservationDecisionCode.ACTIVITY_INACTIVE);
    ReservationResult inactivePoll =
        reservationService.pollOwned("inactive-subject", inactive.reservationId());
    assertThat(inactivePoll.reservationId()).isEqualTo(inactive.reservationId());
    assertThat(inactivePoll.state()).isEqualTo(ReservationState.REJECTED);
    assertThat(inactivePoll.decisionCode()).isEqualTo(ReservationDecisionCode.ACTIVITY_INACTIVE);
    assertThat(inactivePoll.replay()).isTrue();
    assertThat(inactivePoll.durableOrderCreated()).isFalse();
    assertThat(reservationService.pollOwned("inactive-subject", inactive.reservationId()))
        .isEqualTo(inactivePoll);

    createActivity(
        "reservation-future",
        "reservation-product-future",
        SeckillActivityState.ACTIVE,
        2,
        Instant.parse("2037-02-01T00:00:00Z"),
        Instant.parse("2037-03-01T00:00:00Z"));
    assertRejected(
        "future-subject",
        "reservation-future",
        "future-key",
        request(1, 1),
        ReservationDecisionCode.NOT_OPEN);

    createActivity(
        "reservation-expired",
        "reservation-product-expired",
        SeckillActivityState.ACTIVE,
        2,
        Instant.parse("2018-01-01T00:00:00Z"),
        Instant.parse("2019-01-01T00:00:00Z"));
    assertRejected(
        "expired-subject",
        "reservation-expired",
        "expired-key",
        request(1, 1),
        ReservationDecisionCode.EXPIRED);

    createActivity(
        "reservation-stale", "reservation-product-stale", SeckillActivityState.ACTIVE, 2);
    assertRejected(
        "stale-subject",
        "reservation-stale",
        "stale-key",
        request(1, 99),
        ReservationDecisionCode.STALE_VERSION);

    createActivity(
        "reservation-exhausted", "reservation-product-exhausted", SeckillActivityState.ACTIVE, 1);
    assertRejected(
        "exhausted-subject",
        "reservation-exhausted",
        "exhausted-key",
        request(2, 1),
        ReservationDecisionCode.EXHAUSTED);

    createActivity(
        "reservation-duplicate", "reservation-product-duplicate", SeckillActivityState.ACTIVE, 2);
    ReservationResult first =
        reservationService.reserve(
            "duplicate-subject", "reservation-duplicate", "duplicate-one", request(1, 1));
    assertThat(first.state()).isEqualTo(ReservationState.ADMITTED);
    assertRejected(
        "duplicate-subject",
        "reservation-duplicate",
        "duplicate-two",
        request(1, 1),
        ReservationDecisionCode.DUPLICATE_USER);
    assertRemaining("reservation-duplicate", 1);
    assertNoOrderOrOutbox("reservation-product-duplicate");
  }

  @Test
  void constrainsConcurrentQuotaAndOneAdmissionPerUser() throws Exception {
    createActivity(
        "reservation-concurrent", "reservation-product-concurrent", SeckillActivityState.ACTIVE, 5);
    List<Callable<ReservationResult>> quotaAttempts = new ArrayList<>();
    for (int index = 0; index < 20; index++) {
      int attempt = index;
      quotaAttempts.add(
          () ->
              reservationService.reserve(
                  "quota-subject-" + attempt,
                  "reservation-concurrent",
                  "quota-key-" + attempt,
                  request(1, 1)));
    }
    List<ReservationResult> quotaResults = runConcurrently(quotaAttempts);
    assertThat(quotaResults.stream().filter(result -> result.state() == ReservationState.ADMITTED))
        .hasSize(5);
    assertThat(
            quotaResults.stream()
                .filter(result -> result.decisionCode() == ReservationDecisionCode.EXHAUSTED))
        .hasSize(15);
    assertThat(reservationRepository.admittedQuantity("reservation-concurrent")).isEqualTo(5);
    assertRemaining("reservation-concurrent", 0);

    createActivity(
        "reservation-one-user", "reservation-product-one-user", SeckillActivityState.ACTIVE, 10);
    List<Callable<ReservationResult>> userAttempts = new ArrayList<>();
    for (int index = 0; index < 10; index++) {
      int attempt = index;
      userAttempts.add(
          () ->
              reservationService.reserve(
                  "one-subject", "reservation-one-user", "one-key-" + attempt, request(1, 1)));
    }
    List<ReservationResult> userResults = runConcurrently(userAttempts);
    assertThat(userResults.stream().filter(result -> result.state() == ReservationState.ADMITTED))
        .hasSize(1);
    assertThat(
            userResults.stream()
                .filter(result -> result.decisionCode() == ReservationDecisionCode.DUPLICATE_USER))
        .hasSize(9);
    assertRemaining("reservation-one-user", 9);
  }

  @Test
  void failsClosedForMysqlRedisVersionWindowsAndUnsafeLuaIntegers() throws Exception {
    createActivity(
        "reservation-lag-current",
        "reservation-product-lag-current",
        SeckillActivityState.ACTIVE,
        5);
    String laggingCurrent = redis.opsForValue().get(projectionStore.key("reservation-lag-current"));
    assertThat(
            activityService
                .changeAllocation("reservation-lag-current", 3)
                .activity()
                .projectionVersion())
        .isEqualTo(2);
    redis.opsForValue().set(projectionStore.key("reservation-lag-current"), laggingCurrent);
    assertThatThrownBy(
            () ->
                reservationService.reserve(
                    "lag-current-subject",
                    "reservation-lag-current",
                    "lag-current-key",
                    request(1, 2)))
        .isInstanceOf(ReservationAdmissionStore.AdmissionIndeterminateException.class)
        .hasMessageContaining("differs from MySQL truth");
    assertPending("reservation-lag-current", "lag-current-key");
    assertThat(redis.opsForValue().get(projectionStore.key("reservation-lag-current")))
        .isEqualTo(laggingCurrent);

    createActivity(
        "reservation-lag-stale", "reservation-product-lag-stale", SeckillActivityState.ACTIVE, 5);
    String laggingStale = redis.opsForValue().get(projectionStore.key("reservation-lag-stale"));
    assertThat(
            activityService
                .changeAllocation("reservation-lag-stale", 3)
                .activity()
                .projectionVersion())
        .isEqualTo(2);
    redis.opsForValue().set(projectionStore.key("reservation-lag-stale"), laggingStale);
    ReservationResult stale =
        reservationService.reserve(
            "lag-stale-subject", "reservation-lag-stale", "lag-stale-key", request(1, 1));
    assertThat(stale.state()).isEqualTo(ReservationState.REJECTED);
    assertThat(stale.decisionCode()).isEqualTo(ReservationDecisionCode.STALE_VERSION);
    assertThat(redis.opsForValue().get(projectionStore.key("reservation-lag-stale")))
        .isEqualTo(laggingStale);
    assertTerminalProjection(stale, "lag-stale-subject");

    long unsafe = SeckillLuaNumber.MAX_EXACT_INTEGER + 1;
    seedProduct("reservation-product-unsafe-create", unsafe);
    assertThatThrownBy(
            () ->
                activityService.create(
                    new SeckillActivityService.CreateActivity(
                        "reservation-unsafe-create",
                        "reservation-product-unsafe-create",
                        ACTIVE_START,
                        ACTIVE_END,
                        SeckillActivityState.ACTIVE,
                        unsafe)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact Redis Lua integer range");
    assertThat(activityRepository.find("reservation-unsafe-create")).isEmpty();

    createActivity(
        "reservation-safe-boundary",
        "reservation-product-safe-boundary",
        SeckillActivityState.ACTIVE,
        SeckillLuaNumber.MAX_EXACT_INTEGER);
    ReservationResult safeBoundary =
        reservationService.reserve(
            "safe-boundary-subject",
            "reservation-safe-boundary",
            "safe-boundary-key",
            request(1, 1));
    assertThat(safeBoundary.state()).isEqualTo(ReservationState.ADMITTED);
    assertRemaining("reservation-safe-boundary", SeckillLuaNumber.MAX_EXACT_INTEGER - 1);

    createActivity(
        "reservation-unsafe-projection",
        "reservation-product-unsafe-projection",
        SeckillActivityState.ACTIVE,
        2);
    com.fasterxml.jackson.databind.node.ObjectNode unsafeProjection =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            objectMapper.readTree(
                redis.opsForValue().get(projectionStore.key("reservation-unsafe-projection")));
    unsafeProjection.put("remainingQuota", unsafe);
    String unsafeProjectionJson = objectMapper.writeValueAsString(unsafeProjection);
    redis
        .opsForValue()
        .set(projectionStore.key("reservation-unsafe-projection"), unsafeProjectionJson);
    assertIndeterminatePending(
        "unsafe-projection-subject",
        "reservation-unsafe-projection",
        "unsafe-projection-key",
        "projection is malformed");
    assertThat(redis.opsForValue().get(projectionStore.key("reservation-unsafe-projection")))
        .isEqualTo(unsafeProjectionJson);
  }

  @Test
  void leavesPendingTruthOnMissingMalformedUnavailableAndNoevictionRedis() throws Exception {
    createActivity(
        "reservation-missing", "reservation-product-missing", SeckillActivityState.ACTIVE, 2);
    redis.delete(projectionStore.key("reservation-missing"));
    SeckillReservation missing =
        assertIndeterminatePending(
            "missing-subject", "reservation-missing", "missing-key", "projection is missing");
    assertThat(reservationService.pollOwned("missing-subject", missing.reservationId()).state())
        .isEqualTo(ReservationState.PENDING);

    createActivity(
        "reservation-malformed", "reservation-product-malformed", SeckillActivityState.ACTIVE, 2);
    redis.opsForValue().set(projectionStore.key("reservation-malformed"), "{malformed");
    assertIndeterminatePending(
        "malformed-subject", "reservation-malformed", "malformed-key", "projection is malformed");

    createActivity(
        "reservation-partial", "reservation-product-partial", SeckillActivityState.ACTIVE, 2);
    String partialId = UUID.randomUUID().toString();
    String partialCanonical = "reservation-partial".length() + ":reservation-partial:1:1";
    reservationRepository.reservePending(
        new SeckillReservation(
            partialId,
            "partial-subject",
            "reservation-partial",
            "partial-key",
            SeckillReservationService.sha256(partialCanonical),
            1,
            1,
            ReservationState.PENDING,
            null,
            1));
    redis.opsForValue().set(admissionStore.reservationKey(partialId), "{}");
    assertThatThrownBy(
            () ->
                reservationService.reserve(
                    "partial-subject", "reservation-partial", "partial-key", request(1, 1)))
        .isInstanceOf(ReservationAdmissionStore.AdmissionIndeterminateException.class)
        .hasMessageContaining("projection is partial");
    assertThat(reservationRepository.find(partialId).orElseThrow().state())
        .isEqualTo(ReservationState.PENDING);
    assertThat(redis.hasKey(admissionStore.decisionKey(partialId))).isFalse();
    assertRemaining("reservation-partial", 2);

    String rollbackId = UUID.randomUUID().toString();
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      reservationRepository.reservePending(
                          new SeckillReservation(
                              rollbackId,
                              "rollback-subject",
                              "reservation-partial",
                              "rollback-key",
                              SeckillReservationService.sha256("rollback-intent"),
                              1,
                              1,
                              ReservationState.PENDING,
                              null,
                              1));
                      throw new IllegalStateException("controlled reservation rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled reservation rollback");
    assertThat(reservationRepository.find(rollbackId)).isEmpty();

    createActivity(
        "reservation-unavailable",
        "reservation-product-unavailable",
        SeckillActivityState.ACTIVE,
        2);
    try (UnavailableAdmission unavailable = unavailableAdmission()) {
      SeckillReservationService failingService =
          new SeckillReservationService(
              reservationRepository, activityRepository, unavailable.store(), transactions);
      assertThatThrownBy(
              () ->
                  failingService.reserve(
                      "unavailable-subject",
                      "reservation-unavailable",
                      "unavailable-key",
                      request(1, 1)))
          .isInstanceOf(ReservationAdmissionStore.AdmissionIndeterminateException.class)
          .hasMessageContaining("execution failed")
          .hasStackTraceContaining("Connection refused");
    }
    assertPending("reservation-unavailable", "unavailable-key");
    assertRemaining("reservation-unavailable", 2);

    createActivity(
        "reservation-noeviction", "reservation-product-noeviction", SeckillActivityState.ACTIVE, 2);
    String activityBefore = redis.opsForValue().get(projectionStore.key("reservation-noeviction"));
    try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
      RedisServerCommands server = connection.serverCommands();
      String originalMaxmemory = config(server, "maxmemory");
      String originalPolicy = config(server, "maxmemory-policy");
      try {
        server.setConfig("maxmemory-policy", "noeviction");
        server.setConfig("maxmemory", "1");
        assertThatThrownBy(
                () ->
                    reservationService.reserve(
                        "noeviction-subject",
                        "reservation-noeviction",
                        "noeviction-key",
                        request(1, 1)))
            .isInstanceOf(ReservationAdmissionStore.AdmissionIndeterminateException.class)
            .hasMessageContaining("execution failed")
            .hasStackTraceContaining("OOM");
      } finally {
        server.setConfig("maxmemory", originalMaxmemory);
        server.setConfig("maxmemory-policy", originalPolicy);
      }
    }
    SeckillReservation pending = assertPending("reservation-noeviction", "noeviction-key");
    assertThat(redis.opsForValue().get(projectionStore.key("reservation-noeviction")))
        .isEqualTo(activityBefore);
    assertThat(redis.hasKey(admissionStore.reservationKey(pending.reservationId()))).isFalse();
    assertThat(redis.hasKey(admissionStore.decisionKey(pending.reservationId()))).isFalse();
    assertThat(
            redis.hasKey(
                admissionStore.userKey(
                    "reservation-noeviction",
                    SeckillReservationService.sha256("noeviction-subject"))))
        .isFalse();
    assertNoOrderOrOutbox("reservation-product-noeviction");
  }

  @Test
  void rebuildsOnlyTerminalMysqlTruthWithExplicitCoverageTtls() throws Exception {
    createActivity(
        "reservation-rebuild", "reservation-product-rebuild", SeckillActivityState.ACTIVE, 5);
    ReservationResult first =
        reservationService.reserve(
            "rebuild-one", "reservation-rebuild", "rebuild-key-one", request(2, 1));
    ReservationResult second =
        reservationService.reserve(
            "rebuild-two", "reservation-rebuild", "rebuild-key-two", request(1, 1));
    ReservationResult rejected =
        reservationService.reserve(
            "rebuild-three", "reservation-rebuild", "rebuild-key-three", request(3, 1));
    assertThat(rejected.decisionCode()).isEqualTo(ReservationDecisionCode.EXHAUSTED);
    ReservationResult sameUserRejected =
        reservationService.reserve(
            "rebuild-one", "reservation-rebuild", "rebuild-key-duplicate", request(1, 1));
    assertThat(sameUserRejected.decisionCode()).isEqualTo(ReservationDecisionCode.DUPLICATE_USER);

    assertThatThrownBy(() -> activityService.changeAllocation("reservation-rebuild", 2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("below authoritative admitted");
    assertThat(
            activityService
                .changeAllocation("reservation-rebuild", 6)
                .activity()
                .projectionVersion())
        .isEqualTo(2);
    assertRemaining("reservation-rebuild", 3);

    deleteReservationRedisState(
        "reservation-rebuild", List.of(first, second, rejected, sameUserRejected));
    assertThat(reservationService.rebuildActivityState("reservation-rebuild"))
        .isEqualTo(ReservationAdmissionStore.RebuildResult.APPLIED);
    assertRemaining("reservation-rebuild", 3);
    assertTerminalProjection(first, "rebuild-one");
    assertTerminalProjection(second, "rebuild-two");
    assertTerminalProjection(rejected, "rebuild-three");
    assertThat(
            redis
                .opsForValue()
                .get(
                    admissionStore.userKey(
                        "reservation-rebuild", SeckillReservationService.sha256("rebuild-one"))))
        .isEqualTo(first.reservationId());
    assertThat(redis.hasKey(admissionStore.reservationKey(sameUserRejected.reservationId())))
        .isTrue();
    assertThat(reservationService.rebuildActivityState("reservation-rebuild"))
        .isEqualTo(ReservationAdmissionStore.RebuildResult.APPLIED);

    String rejectedOnlyUserKey =
        admissionStore.userKey(
            "reservation-rebuild", SeckillReservationService.sha256("rebuild-three"));
    redis.opsForValue().set(rejectedOnlyUserKey, "non-authoritative-marker");
    assertThatThrownBy(() -> reservationService.rebuildActivityState("reservation-rebuild"))
        .isInstanceOf(ReservationAdmissionStore.AdmissionIndeterminateException.class)
        .hasMessageContaining("projection conflicts");
    assertThat(redis.hasKey(admissionStore.rebuildKey("reservation-rebuild"))).isFalse();
    redis.delete(rejectedOnlyUserKey);

    Duration minimum = properties.minimumBrokerCoverage();
    assertThat(properties.reservationTtl()).isGreaterThanOrEqualTo(minimum);
    assertThat(properties.decisionMarkerTtl()).isGreaterThanOrEqualTo(minimum);
    assertThat(
            redis.getExpire(
                admissionStore.reservationKey(first.reservationId()),
                java.util.concurrent.TimeUnit.MILLISECONDS))
        .isGreaterThanOrEqualTo(minimum.toMillis());
    assertThat(
            redis.getExpire(
                admissionStore.decisionKey(first.reservationId()),
                java.util.concurrent.TimeUnit.MILLISECONDS))
        .isGreaterThanOrEqualTo(minimum.toMillis());

    SeckillActivity activity = activityRepository.find("reservation-rebuild").orElseThrow();
    JsonNode newer = objectMapper.valueToTree(SeckillProjection.from(activity, 3));
    ((com.fasterxml.jackson.databind.node.ObjectNode) newer)
        .put("projectionVersion", activity.projectionVersion() + 1);
    redis
        .opsForValue()
        .set(projectionStore.key("reservation-rebuild"), objectMapper.writeValueAsString(newer));
    assertThat(reservationService.rebuildActivityState("reservation-rebuild"))
        .isEqualTo(ReservationAdmissionStore.RebuildResult.STALE_REJECTED);
    assertThat(redis.hasKey(admissionStore.rebuildKey("reservation-rebuild"))).isFalse();

    createActivity(
        "reservation-pending-rebuild",
        "reservation-product-pending-rebuild",
        SeckillActivityState.ACTIVE,
        2);
    redis.delete(projectionStore.key("reservation-pending-rebuild"));
    assertIndeterminatePending(
        "pending-rebuild-subject",
        "reservation-pending-rebuild",
        "pending-rebuild-key",
        "projection is missing");
    assertThatThrownBy(() -> reservationService.rebuildActivityState("reservation-pending-rebuild"))
        .isInstanceOf(ReservationAdmissionStore.AdmissionIndeterminateException.class)
        .hasMessageContaining("Pending reservation");
    assertThat(redis.hasKey(admissionStore.rebuildKey("reservation-pending-rebuild"))).isFalse();
  }

  private ReservationResult assertRejected(
      String subject,
      String activityId,
      String idempotencyKey,
      ReservationRequest request,
      ReservationDecisionCode code) {
    ReservationResult result =
        reservationService.reserve(subject, activityId, idempotencyKey, request);
    assertThat(result.state()).isEqualTo(ReservationState.REJECTED);
    assertThat(result.decisionCode()).isEqualTo(code);
    assertThat(result.durableOrderCreated()).isFalse();
    return result;
  }

  private SeckillReservation assertIndeterminatePending(
      String subject, String activityId, String idempotencyKey, String message) {
    assertThatThrownBy(
            () -> reservationService.reserve(subject, activityId, idempotencyKey, request(1, 1)))
        .isInstanceOf(ReservationAdmissionStore.AdmissionIndeterminateException.class)
        .hasMessageContaining(message);
    return assertPending(activityId, idempotencyKey);
  }

  private SeckillReservation assertPending(String activityId, String idempotencyKey) {
    String reservationId =
        jdbc.queryForObject(
            "SELECT reservation_id FROM seckill_reservation WHERE activity_id = ? AND idempotency_key = ?",
            String.class,
            activityId,
            idempotencyKey);
    SeckillReservation reservation = reservationRepository.find(reservationId).orElseThrow();
    assertThat(reservation.state()).isEqualTo(ReservationState.PENDING);
    assertThat(reservation.decisionCode()).isNull();
    assertThat(reservation.projectionVersion()).isEqualTo(1);
    return reservation;
  }

  private void assertTerminalProjection(ReservationResult result, String subject) throws Exception {
    JsonNode reservation =
        objectMapper.readTree(
            redis.opsForValue().get(admissionStore.reservationKey(result.reservationId())));
    JsonNode decision =
        objectMapper.readTree(
            redis.opsForValue().get(admissionStore.decisionKey(result.reservationId())));
    assertThat(reservation).isEqualTo(decision);
    assertThat(reservation.get("reservationId").asText()).isEqualTo(result.reservationId());
    assertThat(reservation.get("activityId").asText()).isEqualTo(result.activityId());
    assertThat(reservation.get("state").asText()).isEqualTo(result.state().name());
    assertThat(reservation.get("decisionCode").asText()).isEqualTo(result.decisionCode().name());
    assertThat(reservation.get("reservationVersion").asLong()).isEqualTo(2);
    assertThat(reservation.get("durableOrderCreated").asBoolean()).isFalse();
    String userKey =
        admissionStore.userKey(result.activityId(), SeckillReservationService.sha256(subject));
    if (result.state() == ReservationState.ADMITTED) {
      assertThat(redis.opsForValue().get(userKey)).isEqualTo(result.reservationId());
    } else {
      assertThat(redis.hasKey(userKey)).isFalse();
    }
  }

  private void deleteReservationRedisState(String activityId, List<ReservationResult> results) {
    List<String> keys = new ArrayList<>();
    keys.add(projectionStore.key(activityId));
    for (ReservationResult result : results) {
      SeckillReservation truth = reservationRepository.find(result.reservationId()).orElseThrow();
      keys.add(admissionStore.reservationKey(result.reservationId()));
      keys.add(admissionStore.decisionKey(result.reservationId()));
      keys.add(
          admissionStore.userKey(
              activityId, SeckillReservationService.sha256(truth.userSubject())));
    }
    redis.delete(keys);
  }

  private List<ReservationResult> runConcurrently(List<Callable<ReservationResult>> attempts)
      throws Exception {
    try (var executor = Executors.newFixedThreadPool(attempts.size())) {
      List<Future<ReservationResult>> futures = executor.invokeAll(attempts);
      List<ReservationResult> results = new ArrayList<>();
      for (Future<ReservationResult> future : futures) {
        results.add(future.get());
      }
      return results;
    }
  }

  private void createActivity(
      String activityId, String productId, SeckillActivityState state, long quota) {
    createActivity(activityId, productId, state, quota, ACTIVE_START, ACTIVE_END);
  }

  private void createActivity(
      String activityId,
      String productId,
      SeckillActivityState state,
      long quota,
      Instant startsAt,
      Instant endsAt) {
    seedProduct(productId, Math.max(quota, 10));
    activityService.create(
        new SeckillActivityService.CreateActivity(
            activityId, productId, startsAt, endsAt, state, quota));
  }

  private void seedProduct(String productId, long stock) {
    jdbc.update(
        """
        INSERT INTO product
          (product_id, name, description, price_minor, currency, stock_quantity,
           available, publication_state, publication_version)
        VALUES (?, ?, 'Reservation integration product', 1000, 'AUD', ?, TRUE, 'PUBLISHED', 1)
        """,
        productId,
        productId,
        stock);
  }

  private ReservationRequest request(int quantity, long expectedVersion) {
    ReservationRequest request = new ReservationRequest();
    request.setQuantity(quantity);
    request.setExpectedActivityVersion(expectedVersion);
    return request;
  }

  private void assertRemaining(String activityId, long remaining) throws Exception {
    JsonNode projection =
        objectMapper.readTree(redis.opsForValue().get(projectionStore.key(activityId)));
    assertThat(projection.get("remainingQuota").asLong()).isEqualTo(remaining);
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

  private UnavailableAdmission unavailableAdmission() {
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
    return new UnavailableAdmission(
        factory,
        new ReservationAdmissionStore(template, objectMapper, properties, Clock.systemUTC()));
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

  private record UnavailableAdmission(
      LettuceConnectionFactory factory, ReservationAdmissionStore store) implements AutoCloseable {
    @Override
    public void close() {
      factory.destroy();
    }
  }
}
