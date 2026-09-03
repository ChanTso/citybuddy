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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
  void restoresAnExpiredAdmissionMarkerBeforeRejectingAnotherIntent() throws Exception {
    String activityId = "reservation-expired-marker";
    String subject = "expired-marker-subject";
    createActivity(
        activityId, "reservation-product-expired-marker", SeckillActivityState.ACTIVE, 5);
    ReservationResult admitted =
        reservationService.reserve(subject, activityId, "expired-marker-first", request(2, 1));
    String userKey = admissionStore.userKey(activityId, SeckillReservationService.sha256(subject));
    assertThat(redis.delete(userKey)).isTrue();

    ReservationResult duplicate =
        reservationService.reserve(subject, activityId, "expired-marker-second", request(1, 1));

    assertThat(duplicate.state()).isEqualTo(ReservationState.REJECTED);
    assertThat(duplicate.decisionCode()).isEqualTo(ReservationDecisionCode.DUPLICATE_USER);
    assertThat(reservationRepository.admittedQuantity(activityId)).isEqualTo(2);
    assertRemaining(activityId, 3);
    assertThat(redis.opsForValue().get(userKey)).isEqualTo(admitted.reservationId());
  }

  @Test
  void rebuildsLegacyRepeatedAdmissionsWithAStableCanonicalMarker() throws Exception {
    String activityId = "reservation-legacy-repeated";
    String subject = "legacy-repeated-subject";
    createActivity(
        activityId, "reservation-product-legacy-repeated", SeckillActivityState.ACTIVE, 5);
    List<String> reservationIds =
        new ArrayList<>(List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
    reservationIds.sort(String::compareTo);
    insertLegacyAdmittedReservation(
        reservationIds.get(1), subject, activityId, "legacy-repeated-second", 2);
    insertLegacyAdmittedReservation(
        reservationIds.get(0), subject, activityId, "legacy-repeated-first", 1);

    assertThat(reservationService.rebuildActivityState(activityId))
        .isEqualTo(ReservationAdmissionStore.RebuildResult.APPLIED);

    assertThat(reservationRepository.admittedQuantity(activityId)).isEqualTo(3);
    assertRemaining(activityId, 2);
    String userKey = admissionStore.userKey(activityId, SeckillReservationService.sha256(subject));
    assertThat(redis.opsForValue().get(userKey)).isEqualTo(reservationIds.get(0));
    for (String reservationId : reservationIds) {
      JsonNode reservation =
          objectMapper.readTree(
              redis.opsForValue().get(admissionStore.reservationKey(reservationId)));
      JsonNode decision =
          objectMapper.readTree(redis.opsForValue().get(admissionStore.decisionKey(reservationId)));
      assertThat(reservation).isEqualTo(decision);
      assertThat(reservation.get("reservationId").asText()).isEqualTo(reservationId);
      assertThat(reservation.get("state").asText()).isEqualTo(ReservationState.ADMITTED.name());
    }

    assertThat(reservationService.rebuildActivityState(activityId))
        .isEqualTo(ReservationAdmissionStore.RebuildResult.APPLIED);
    assertThat(redis.opsForValue().get(userKey)).isEqualTo(reservationIds.get(0));
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
  void activityShareLocksCoexistAndKeepTheExclusiveRebuildFence() throws Exception {
    String suffix = UUID.randomUUID().toString();
    String activityId = "reservation-lock-" + suffix;
    createActivity(
        activityId, "reservation-lock-product-" + suffix, SeckillActivityState.ACTIVE, 2);
    CountDownLatch firstShareAcquired = new CountDownLatch(1);
    CountDownLatch secondShareAcquired = new CountDownLatch(1);
    CountDownLatch releaseFirstShare = new CountDownLatch(1);
    CountDownLatch releaseSecondShare = new CountDownLatch(1);
    CountDownLatch exclusiveStarted = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(3)) {
      try {
        Future<SeckillActivity> firstShare =
            executor.submit(
                () -> holdActivityForShare(activityId, firstShareAcquired, releaseFirstShare));
        assertThat(firstShareAcquired.await(5, TimeUnit.SECONDS)).isTrue();

        Future<SeckillActivity> secondShare =
            executor.submit(
                () -> holdActivityForShare(activityId, secondShareAcquired, releaseSecondShare));
        assertThat(secondShareAcquired.await(5, TimeUnit.SECONDS)).isTrue();

        Future<SeckillActivity> exclusive =
            executor.submit(
                () -> {
                  exclusiveStarted.countDown();
                  SeckillActivity activity =
                      transactions.execute(
                          status ->
                              activityRepository
                                  .findForUpdate(activityId)
                                  .orElseThrow(
                                      () ->
                                          new IllegalStateException(
                                              "Locked activity truth is missing")));
                  if (activity == null) {
                    throw new IllegalStateException("Exclusive activity transaction returned null");
                  }
                  return activity;
                });
        assertThat(exclusiveStarted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> exclusive.get(1, TimeUnit.SECONDS))
            .isInstanceOf(TimeoutException.class);

        releaseFirstShare.countDown();
        assertThat(firstShare.get(5, TimeUnit.SECONDS).activityId()).isEqualTo(activityId);
        assertThatThrownBy(() -> exclusive.get(1, TimeUnit.SECONDS))
            .isInstanceOf(TimeoutException.class);

        releaseSecondShare.countDown();
        assertThat(secondShare.get(5, TimeUnit.SECONDS).activityId()).isEqualTo(activityId);
        assertThat(exclusive.get(5, TimeUnit.SECONDS).activityId()).isEqualTo(activityId);
      } finally {
        releaseFirstShare.countDown();
        releaseSecondShare.countDown();
      }
    } finally {
      redis.delete(projectionStore.key(activityId));
    }
  }

  @Test
  void admitsConcurrentReservationsAcrossDistinctActivitiesWithoutDeadlock() throws Exception {
    // Reservations for different activities hold different activity row locks, so nothing
    // serialises them before they reach the shared idempotency index. Preparing the reservation
    // with an absent-row locking read made every one of them take the same index gap lock and
    // then request an insert-intention lock inside it, which deadlocks under load.
    int activities = 12;
    for (int index = 0; index < activities; index++) {
      createActivity(
          "reservation-parallel-" + index,
          "reservation-product-parallel-" + index,
          SeckillActivityState.ACTIVE,
          4);
    }
    List<Callable<ReservationResult>> attempts = new ArrayList<>();
    for (int round = 0; round < 4; round++) {
      for (int index = 0; index < activities; index++) {
        String activityId = "reservation-parallel-" + index;
        String subject = "parallel-subject-" + index + "-" + round;
        attempts.add(
            () ->
                reservationService.reserve(
                    subject, activityId, "parallel-key-" + subject, request(1, 1)));
      }
    }

    List<ReservationResult> results = runConcurrently(attempts);

    assertThat(results).hasSize(activities * 4);
    assertThat(results)
        .allSatisfy(result -> assertThat(result.state()).isEqualTo(ReservationState.ADMITTED));
    for (int index = 0; index < activities; index++) {
      assertThat(reservationRepository.admittedQuantity("reservation-parallel-" + index))
          .isEqualTo(4);
    }
  }

  @Test
  void resolvesConcurrentDuplicateIdempotencyKeysToOneReservation() throws Exception {
    createActivity(
        "reservation-duplicate-key",
        "reservation-product-duplicate-key",
        SeckillActivityState.ACTIVE,
        5);
    List<Callable<ReservationResult>> attempts = new ArrayList<>();
    for (int index = 0; index < 8; index++) {
      attempts.add(
          () ->
              reservationService.reserve(
                  "duplicate-subject",
                  "reservation-duplicate-key",
                  "duplicate-key",
                  request(1, 1)));
    }

    List<ReservationResult> results = runConcurrently(attempts);

    assertThat(results.stream().map(ReservationResult::reservationId).distinct()).hasSize(1);
    assertThat(reservationRepository.admittedQuantity("reservation-duplicate-key")).isEqualTo(1);
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
            1),
        properties.minimumBrokerCoverage());
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
                              1),
                          properties.minimumBrokerCoverage());
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
              reservationRepository,
              activityRepository,
              unavailable.store(),
              transactions,
              properties);
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
  void deadlineResolutionTreatsRedisFailureAsIndeterminate() throws Exception {
    createActivity(
        "reservation-deadline-unavailable",
        "reservation-product-deadline-unavailable",
        SeckillActivityState.ACTIVE,
        2);
    var prepared =
        reservationService.prepare(
            "deadline-unavailable-subject",
            "reservation-deadline-unavailable",
            "deadline-unavailable-key",
            request(1, 1));
    assertThat(
            jdbc.update(
                "UPDATE seckill_reservation SET transaction_resolution_due_at = "
                    + "TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE reservation_id = ?",
                prepared.reservation().reservationId()))
        .isEqualTo(1);
    try (UnavailableAdmission unavailable = unavailableAdmission()) {
      SeckillReservationService failingService =
          new SeckillReservationService(
              reservationRepository,
              activityRepository,
              unavailable.store(),
              transactions,
              properties);
      assertThatThrownBy(() -> failingService.resolveDueReservations(32))
          .isInstanceOf(ReservationAdmissionStore.AdmissionIndeterminateException.class)
          .hasMessageContaining("deadline resolution failed")
          .hasStackTraceContaining("Connection refused");
    }
    assertThat(
            reservationRepository
                .find(prepared.reservation().reservationId())
                .orElseThrow()
                .state())
        .isEqualTo(ReservationState.PENDING);
    assertThat(redis.hasKey(admissionStore.decisionKey(prepared.reservation().reservationId())))
        .isFalse();
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
    assertThat(reservationService.rebuildActivityState("reservation-rebuild"))
        .isEqualTo(ReservationAdmissionStore.RebuildResult.APPLIED);
    assertThat(redis.hasKey(rejectedOnlyUserKey)).isFalse();
    assertThat(redis.hasKey(admissionStore.rebuildKey("reservation-rebuild"))).isFalse();

    String admittedUserKey =
        admissionStore.userKey(
            "reservation-rebuild", SeckillReservationService.sha256("rebuild-one"));
    redis.opsForValue().set(projectionStore.key("reservation-rebuild"), "{malformed");
    redis.opsForValue().set(admissionStore.reservationKey(first.reservationId()), "{malformed");
    redis.delete(admissionStore.decisionKey(first.reservationId()));
    redis.opsForValue().set(admittedUserKey, "bogus-admitted-marker");
    redis.opsForValue().set(rejectedOnlyUserKey, "bogus-rejected-marker");

    assertThat(reservationService.rebuildActivityState("reservation-rebuild"))
        .isEqualTo(ReservationAdmissionStore.RebuildResult.APPLIED);
    assertRemaining("reservation-rebuild", 3);
    assertTerminalProjection(first, "rebuild-one");
    assertThat(redis.opsForValue().get(admittedUserKey)).isEqualTo(first.reservationId());
    assertThat(redis.hasKey(rejectedOnlyUserKey)).isFalse();

    com.fasterxml.jackson.databind.node.ObjectNode conflictingActivity =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            objectMapper.readTree(
                redis.opsForValue().get(projectionStore.key("reservation-rebuild")));
    conflictingActivity.put("state", SeckillActivityState.DRAFT.name());
    redis
        .opsForValue()
        .set(
            projectionStore.key("reservation-rebuild"),
            objectMapper.writeValueAsString(conflictingActivity));
    com.fasterxml.jackson.databind.node.ObjectNode conflictingReservation =
        (com.fasterxml.jackson.databind.node.ObjectNode)
            objectMapper.readTree(
                redis.opsForValue().get(admissionStore.reservationKey(first.reservationId())));
    conflictingReservation.put("quantity", 1);
    String conflictingReservationJson = objectMapper.writeValueAsString(conflictingReservation);
    redis
        .opsForValue()
        .set(admissionStore.reservationKey(first.reservationId()), conflictingReservationJson);
    redis
        .opsForValue()
        .set(admissionStore.decisionKey(first.reservationId()), conflictingReservationJson);

    assertThat(reservationService.rebuildActivityState("reservation-rebuild"))
        .isEqualTo(ReservationAdmissionStore.RebuildResult.APPLIED);
    JsonNode restoredActivity =
        objectMapper.readTree(redis.opsForValue().get(projectionStore.key("reservation-rebuild")));
    assertThat(restoredActivity.get("state").asText())
        .isEqualTo(SeckillActivityState.ACTIVE.name());
    JsonNode restoredReservation =
        objectMapper.readTree(
            redis.opsForValue().get(admissionStore.reservationKey(first.reservationId())));
    assertThat(restoredReservation.get("quantity").asInt()).isEqualTo(2);
    assertTerminalProjection(first, "rebuild-one");

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

  private SeckillActivity holdActivityForShare(
      String activityId, CountDownLatch acquired, CountDownLatch release) {
    SeckillActivity activity =
        transactions.execute(
            status -> {
              SeckillActivity locked =
                  activityRepository
                      .findForShare(activityId)
                      .orElseThrow(
                          () -> new IllegalStateException("Locked activity truth is missing"));
              acquired.countDown();
              try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Timed out while holding shared activity lock");
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "Interrupted while holding shared activity lock", exception);
              }
              return locked;
            });
    if (activity == null) {
      throw new IllegalStateException("Shared activity transaction returned null");
    }
    return activity;
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

  private void insertLegacyAdmittedReservation(
      String reservationId,
      String subject,
      String activityId,
      String idempotencyKey,
      int quantity) {
    assertThat(
            jdbc.update(
                """
                INSERT INTO seckill_reservation
                  (reservation_id, user_subject, activity_id, idempotency_key, intent_hash,
                   quantity, activity_projection_version, state, decision_code,
                   projection_version, transaction_resolution_due_at)
                VALUES (?, ?, ?, ?, ?, ?, 1, 'ADMITTED', 'ADMITTED', 2,
                        CURRENT_TIMESTAMP(6))
                """,
                reservationId,
                subject,
                activityId,
                idempotencyKey,
                SeckillReservationService.sha256(idempotencyKey),
                quantity))
        .isEqualTo(1);
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
