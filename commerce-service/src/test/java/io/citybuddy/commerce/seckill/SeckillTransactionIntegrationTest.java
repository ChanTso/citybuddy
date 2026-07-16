package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "CATALOG_INTEGRATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(OrderAnnotation.class)
class SeckillTransactionIntegrationTest {
  private static final String USER = "catalog-user";

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
    registry.add("citybuddy.seckill.enabled", () -> "true");
    registry.add("citybuddy.seckill.order.enabled", () -> "true");
    registry.add(
        "citybuddy.seckill.order.rocketmq-endpoints", () -> required("ROCKETMQ_ENDPOINTS"));
    registry.add(
        "citybuddy.seckill.order.rocketmq-topic", () -> required("ROCKETMQ_TRANSACTION_TOPIC"));
    registry.add(
        "citybuddy.seckill.order.rocketmq-consumer-group",
        () -> required("ROCKETMQ_TRANSACTION_GROUP"));
    registry.add("citybuddy.seckill.order.worker-initial-delay-ms", () -> "3600000");
    registry.add("citybuddy.seckill.order.worker-delay-ms", () -> "3600000");
    registry.add("citybuddy.seckill.order.resolution-worker-initial-delay", () -> "3600000");
    registry.add("citybuddy.seckill.order.resolution-worker-delay", () -> "3600000");
    registry.add("citybuddy.seckill.order.receive-await", () -> "1s");
    registry.add("citybuddy.seckill.order.receive-invisible-duration", () -> "10s");
    registry.add("citybuddy.seckill.order.unpaid-timeout", () -> "15m");
    registry.add(
        "citybuddy.seckill.timeout.rocketmq-endpoints", () -> required("ROCKETMQ_ENDPOINTS"));
    registry.add(
        "citybuddy.seckill.timeout.rocketmq-topic", () -> required("ROCKETMQ_TIMEOUT_TOPIC"));
    registry.add(
        "citybuddy.seckill.timeout.rocketmq-consumer-group",
        () -> required("ROCKETMQ_TIMEOUT_GROUP"));
    registry.add("citybuddy.seckill.timeout.dispatch-worker-initial-delay-ms", () -> "3600000");
    registry.add("citybuddy.seckill.timeout.dispatch-worker-delay-ms", () -> "3600000");
    registry.add("citybuddy.seckill.timeout.consumer-worker-initial-delay-ms", () -> "3600000");
    registry.add("citybuddy.seckill.timeout.consumer-worker-delay-ms", () -> "3600000");
    registry.add("citybuddy.seckill.timeout.receive-await", () -> "1s");
    registry.add("citybuddy.seckill.timeout.receive-invisible-duration", () -> "10s");
    registry.add("citybuddy.seckill.timeout.dispatch-batch-size", () -> "32");
    registry.add("citybuddy.seckill.timeout.maximum-dispatch-attempts", () -> "3");
    registry.add("citybuddy.seckill.timeout.maximum-delivery-attempts", () -> "3");
  }

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private StringRedisTemplate redis;
  @Autowired private SeckillActivityRepository activityRepository;
  @Autowired private SeckillProjectionStore projections;
  @Autowired private SeckillReservationRepository reservationRepository;
  @Autowired private SeckillReservationService reservationService;
  @Autowired private SeckillReservationProperties reservationProperties;
  @Autowired private ReservationAdmissionStore admissionStore;
  @Autowired private RocketMqSeckillTransactions messaging;
  @Autowired private SeckillOrderService orderService;
  @Autowired private SeckillTransactionResolutionWorker resolutionWorker;
  @Autowired private SeckillOrderRepository orderRepository;
  @Autowired private SeckillTimeoutProperties timeoutProperties;
  @Autowired private RocketMqSeckillTimeouts timeoutMessaging;
  @Autowired private SeckillTimeoutDispatchService timeoutDispatch;
  @Autowired private SeckillCancellationService cancellationService;
  @Autowired private SeckillTimeoutWorker timeoutWorker;
  @Autowired private PlatformTransactionManager transactionManager;

  @Test
  @Order(1)
  void publicCommitCreatesOneAtomicOrderAndDuplicateDeliveryIsHarmless() throws Exception {
    String activityId = "cb060-commit";
    seedActivity(activityId, "cb060-product-commit", SeckillActivityState.ACTIVE, 3, 10);

    ResponseEntity<ReservationResult> created =
        reserve(
            directToken(),
            activityId,
            "cb060-key-commit",
            Map.of("quantity", 1, "expectedActivityVersion", 1));
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ReservationResult admitted = created.getBody();
    assertThat(admitted).isNotNull();
    assertThat(admitted.state()).isEqualTo(ReservationState.ADMITTED);
    assertThat(admitted.durableOrderCreated()).isFalse();
    long minimumBrokerCoverage = reservationProperties.minimumBrokerCoverage().toMillis();
    assertThat(
            redis.getExpire(
                admissionStore.decisionKey(admitted.reservationId()), TimeUnit.MILLISECONDS))
        .isGreaterThanOrEqualTo(minimumBrokerCoverage);
    assertThat(
            redis.getExpire(
                admissionStore.reservationKey(admitted.reservationId()), TimeUnit.MILLISECONDS))
        .isGreaterThanOrEqualTo(minimumBrokerCoverage);

    assertThat(consumeEventually()).isEqualTo(1);
    ReservationResult ordered = poll(directToken(), admitted.reservationId()).getBody();
    assertThat(ordered).isNotNull();
    assertThat(ordered.state()).isEqualTo(ReservationState.ORDERED);
    assertThat(ordered.durableOrderCreated()).isTrue();
    assertThat(ordered.orderId()).isNotBlank();
    assertAtomicOrder(admitted.reservationId(), ordered.orderId(), 9);

    SeckillReservation durable = reservationRepository.find(admitted.reservationId()).orElseThrow();
    try (Producer duplicateProducer = producer(new AtomicInteger())) {
      Transaction transaction = duplicateProducer.beginTransaction();
      duplicateProducer.send(message(SeckillTransactionMessage.from(durable)), transaction);
      transaction.commit();
      assertThat(consumeEventually()).isEqualTo(1);
    }
    assertAtomicOrder(admitted.reservationId(), ordered.orderId(), 9);

    ResponseEntity<ReservationResult> replay =
        reserve(
            directToken(),
            activityId,
            "cb060-key-commit",
            Map.of("quantity", 1, "expectedActivityVersion", 1));
    assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(replay.getBody()).isNotNull();
    assertThat(replay.getBody().orderId()).isEqualTo(ordered.orderId());
    assertThat(replay.getBody().replay()).isTrue();

    assertThat(poll(otherDirectToken(), admitted.reservationId()).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(
            reserve(
                    limitedDirectToken(),
                    activityId,
                    "cb060-limited",
                    Map.of("quantity", 1, "expectedActivityVersion", 1))
                .getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(
            reserve(
                    "not-a-token",
                    activityId,
                    "cb060-invalid",
                    Map.of("quantity", 1, "expectedActivityVersion", 1))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(
            reserve(
                    directToken(),
                    activityId,
                    "cb060-body-owner",
                    Map.of(
                        "quantity",
                        1,
                        "expectedActivityVersion",
                        1,
                        "userSubject",
                        "substituted-user"))
                .getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(
            reserve(
                    directToken(),
                    activityId,
                    "cb060-key-commit",
                    Map.of("quantity", 2, "expectedActivityVersion", 1))
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  @Order(2)
  void rejectedMarkerRollsBackWithoutDownstreamDelivery() throws Exception {
    String activityId = "cb060-rejected";
    seedActivity(activityId, "cb060-product-rejected", SeckillActivityState.DRAFT, 2, 5);
    ResponseEntity<ReservationResult> response =
        reserve(
            directToken(),
            activityId,
            "cb060-key-rejected",
            Map.of("quantity", 1, "expectedActivityVersion", 1));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().state()).isEqualTo(ReservationState.REJECTED);
    Thread.sleep(8_000);
    assertThat(messaging.consumeOnce(orderService)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_order WHERE activity_id = ?",
                Integer.class,
                activityId))
        .isZero();
  }

  @Test
  @Order(3)
  void checkerMapsOnlyDurableMarkersAndUnknownStopsAtBrokerBoundary() throws Exception {
    String pendingActivityId = "cb060-public-pending";
    seedActivity(
        pendingActivityId, "cb060-product-public-pending", SeckillActivityState.ACTIVE, 1, 1);
    redis.delete(projections.key(pendingActivityId));
    ResponseEntity<ReservationResult> pendingResponse =
        reserve(
            directToken(),
            pendingActivityId,
            "cb060-key-public-pending",
            Map.of("quantity", 1, "expectedActivityVersion", 1));
    assertThat(pendingResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    ReservationResult pending = pendingResponse.getBody();
    assertThat(pending).isNotNull();
    assertThat(pending.state()).isEqualTo(ReservationState.PENDING);
    assertThat(pending.durableOrderCreated()).isFalse();

    String activityId = "cb060-checkback";
    seedActivity(activityId, "cb060-product-checkback", SeckillActivityState.ACTIVE, 2, 5);
    var prepared =
        reservationService.prepare(
            USER,
            activityId,
            "cb060-key-checkback",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    AtomicInteger admittedChecks = new AtomicInteger();
    try (Producer producer = producer(admittedChecks)) {
      Transaction unresolved = producer.beginTransaction();
      producer.send(message(SeckillTransactionMessage.from(prepared.reservation())), unresolved);
      ReservationResult admitted = reservationService.admit(prepared.reservation().reservationId());
      assertThat(admitted.state()).isEqualTo(ReservationState.ADMITTED);
      assertThat(admissionStore.transactionResolution(admitted.reservationId()).name())
          .isEqualTo("COMMIT");
      assertThat(consumeEventually()).isEqualTo(1);
      assertThat(admittedChecks.get()).isGreaterThanOrEqualTo(1);
    }

    String rejectedActivityId = "cb060-checkback-rejected";
    seedActivity(
        rejectedActivityId, "cb060-product-checkback-rejected", SeckillActivityState.DRAFT, 1, 1);
    var rejectedPrepared =
        reservationService.prepare(
            USER,
            rejectedActivityId,
            "cb060-key-checkback-rejected",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    ReservationResult rejected =
        reservationService.admit(rejectedPrepared.reservation().reservationId());
    assertThat(rejected.state()).isEqualTo(ReservationState.REJECTED);
    assertThat(admissionStore.transactionResolution(rejected.reservationId()).name())
        .isEqualTo("ROLLBACK");

    String unreadableReservationId = "00000000-0000-0000-0000-000000000061";
    redis.opsForValue().set(admissionStore.decisionKey(unreadableReservationId), "{unreadable");
    assertThat(admissionStore.transactionResolution(unreadableReservationId).name())
        .isEqualTo("UNKNOWN");
    redis.delete(admissionStore.decisionKey(unreadableReservationId));

    String incompleteReservationId = "00000000-0000-0000-0000-000000000062";
    redis
        .opsForValue()
        .set(
            admissionStore.decisionKey(incompleteReservationId),
            objectMapper.writeValueAsString(
                Map.of(
                    "reservationId",
                    incompleteReservationId,
                    "state",
                    "ADMITTED",
                    "decisionCode",
                    "ADMITTED")));
    assertThat(admissionStore.transactionResolution(incompleteReservationId).name())
        .isEqualTo("UNKNOWN");
    redis.delete(admissionStore.decisionKey(incompleteReservationId));

    String unknownCodeReservationId = "00000000-0000-0000-0000-000000000063";
    redis
        .opsForValue()
        .set(
            admissionStore.decisionKey(unknownCodeReservationId),
            objectMapper.writeValueAsString(
                Map.of(
                    "reservationId",
                    unknownCodeReservationId,
                    "activityId",
                    rejectedActivityId,
                    "userHash",
                    "0".repeat(64),
                    "quantity",
                    1,
                    "activityProjectionVersion",
                    1,
                    "reservationVersion",
                    2,
                    "state",
                    "REJECTED",
                    "decisionCode",
                    "UNKNOWN_REJECTION",
                    "durableOrderCreated",
                    false)));
    assertThat(admissionStore.transactionResolution(unknownCodeReservationId).name())
        .isEqualTo("UNKNOWN");
    redis.delete(admissionStore.decisionKey(unknownCodeReservationId));

    String missingReservationId = "00000000-0000-0000-0000-000000000060";
    SeckillTransactionMessage unknown =
        new SeckillTransactionMessage(
            missingReservationId, missingReservationId, "missing", "missing-user", 1, 1);
    AtomicInteger unknownChecks = new AtomicInteger();
    long unknownStartedAt = System.nanoTime();
    try (Producer producer = producer(unknownChecks)) {
      Transaction unresolved = producer.beginTransaction();
      producer.send(message(unknown), unresolved);
      awaitChecks(unknownChecks, 1, Duration.ofSeconds(20));
      awaitElapsed(unknownStartedAt, reservationProperties.minimumBrokerCoverage().plusSeconds(2));
      int terminalCount = unknownChecks.get();
      Thread.sleep(reservationProperties.brokerCheckInterval().multipliedBy(2));
      assertThat(unknownChecks.get()).isEqualTo(terminalCount);
      assertThat(terminalCount).isBetween(1, reservationProperties.brokerMaximumChecks());
      assertThat(messaging.consumeOnce(orderService)).isZero();
    }
    assertThat(admissionStore.transactionResolution(missingReservationId).name())
        .isEqualTo("UNKNOWN");
    assertThat(poll(directToken(), pending.reservationId()).getBody().state())
        .isEqualTo(ReservationState.PENDING);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_order WHERE reservation_id = ?",
                Integer.class,
                pending.reservationId()))
        .isZero();
  }

  @Test
  @Order(4)
  void consumerDatabaseFailureIsNotAcknowledgedAndRetryCommitsAtomically() throws Exception {
    String activityId = "cb060-consumer-retry";
    String productId = "cb060-product-consumer-retry";
    seedActivity(activityId, productId, SeckillActivityState.ACTIVE, 1, 0);
    ResponseEntity<ReservationResult> response =
        reserve(
            directToken(),
            activityId,
            "cb060-key-consumer-retry",
            Map.of("quantity", 1, "expectedActivityVersion", 1));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ReservationResult admitted = response.getBody();
    assertThat(admitted).isNotNull();

    assertThatThrownBy(() -> messaging.consumeOnce(orderService))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Authoritative inventory cannot satisfy admitted reservation");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_order WHERE reservation_id = ?",
                Integer.class,
                admitted.reservationId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE reservation_id = ?",
                Integer.class,
                admitted.reservationId()))
        .isZero();
    assertThat(reservationRepository.find(admitted.reservationId()).orElseThrow().state())
        .isEqualTo(ReservationState.ADMITTED);

    assertThat(jdbc.update("UPDATE product SET stock_quantity = 1 WHERE product_id = ?", productId))
        .isEqualTo(1);
    assertThat(consumeEventually(Duration.ofSeconds(20))).isEqualTo(1);
    ReservationResult ordered = poll(directToken(), admitted.reservationId()).getBody();
    assertThat(ordered).isNotNull();
    assertThat(ordered.state()).isEqualTo(ReservationState.ORDERED);
    assertAtomicOrder(admitted.reservationId(), ordered.orderId(), 0);
  }

  @Test
  @Order(5)
  void deadlineCasConvergesEveryCrashWindowAndPreservesAdmission() throws Exception {
    deferExistingPendingDeadlines();
    String preHalfActivity = "cb060-deadline-pre-half";
    seedActivity(
        preHalfActivity, "cb060-product-deadline-pre-half", SeckillActivityState.ACTIVE, 1, 1);
    var beforeHalf =
        reservationService.prepare(
            USER,
            preHalfActivity,
            "cb060-deadline-pre-half-key",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    Instant persistedDeadline = beforeHalf.reservation().transactionResolutionDueAt();
    assertThat(persistedDeadline).isNotNull();
    assertThat(
            reservationService
                .prepare(
                    USER,
                    preHalfActivity,
                    "cb060-deadline-pre-half-key",
                    request(Map.of("quantity", 1, "expectedActivityVersion", 1)))
                .reservation()
                .transactionResolutionDueAt())
        .isEqualTo(persistedDeadline);
    forceDue(beforeHalf.reservation().reservationId());
    assertThat(reservationService.resolveDueReservations(32)).isEqualTo(1);
    assertTimedOutWithoutOrder(beforeHalf.reservation().reservationId());
    String timeoutMarker =
        redis
            .opsForValue()
            .get(admissionStore.decisionKey(beforeHalf.reservation().reservationId()));
    assertThat(reservationService.admit(beforeHalf.reservation().reservationId()).decisionCode())
        .isEqualTo(ReservationDecisionCode.TRANSACTION_TIMEOUT);
    assertThat(
            redis
                .opsForValue()
                .get(admissionStore.decisionKey(beforeHalf.reservation().reservationId())))
        .isEqualTo(timeoutMarker);

    String halfActivity = "cb060-deadline-half";
    seedActivity(halfActivity, "cb060-product-deadline-half", SeckillActivityState.ACTIVE, 1, 1);
    var afterHalf =
        reservationService.prepare(
            USER,
            halfActivity,
            "cb060-deadline-half-key",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    try (Producer producer = producer(new AtomicInteger())) {
      Transaction unresolved = producer.beginTransaction();
      producer.send(message(SeckillTransactionMessage.from(afterHalf.reservation())), unresolved);
      forceDue(afterHalf.reservation().reservationId());
      assertThat(reservationService.resolveDueReservations(32)).isEqualTo(1);
    }
    assertTimedOutWithoutOrder(afterHalf.reservation().reservationId());

    String markerActivity = "cb060-deadline-marker";
    seedActivity(
        markerActivity, "cb060-product-deadline-marker", SeckillActivityState.ACTIVE, 1, 1);
    var afterMarker =
        reservationService.prepare(
            USER,
            markerActivity,
            "cb060-deadline-marker-key",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    SeckillActivity activity = activityRepository.find(markerActivity).orElseThrow();
    ReservationAdmissionStore.AdmissionDecision admittedMarker =
        admissionStore.decide(
            afterMarker.reservation(),
            activity,
            SeckillReservationService.sha256(afterMarker.reservation().userSubject()));
    TransactionTemplate controlledFailure = new TransactionTemplate(transactionManager);
    controlledFailure.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThatThrownBy(
            () ->
                controlledFailure.executeWithoutResult(
                    status -> {
                      SeckillReservation current =
                          reservationRepository
                              .findForUpdate(afterMarker.reservation().reservationId())
                              .orElseThrow();
                      reservationRepository.applyDecision(
                          current, admittedMarker.state(), admittedMarker.decisionCode());
                      throw new IllegalStateException("controlled MySQL decision failure");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled MySQL decision failure");
    assertThat(
            reservationRepository
                .find(afterMarker.reservation().reservationId())
                .orElseThrow()
                .state())
        .isEqualTo(ReservationState.PENDING);
    forceDue(afterMarker.reservation().reservationId());
    TransactionTemplate restartedTransactions = new TransactionTemplate(transactionManager);
    restartedTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    SeckillReservationService restarted =
        new SeckillReservationService(
            reservationRepository,
            activityRepository,
            admissionStore,
            restartedTransactions,
            reservationProperties);
    assertThat(restarted.resolveDueReservations(32)).isEqualTo(1);
    assertThat(
            reservationRepository
                .find(afterMarker.reservation().reservationId())
                .orElseThrow()
                .state())
        .isEqualTo(ReservationState.ADMITTED);
    assertThat(
            admissionStore
                .resolveDeadline(
                    afterMarker.reservation(),
                    SeckillReservationService.sha256(afterMarker.reservation().userSubject()))
                .decisionCode())
        .isEqualTo(ReservationDecisionCode.ADMITTED);
  }

  @Test
  @Order(6)
  void deadlineWorkerUsesABoundedBatch() {
    String activityId = "cb060-deadline-batch";
    seedActivity(activityId, "cb060-product-deadline-batch", SeckillActivityState.ACTIVE, 40, 40);
    for (int index = 0; index < SeckillTransactionResolutionWorker.BATCH_SIZE + 1; index++) {
      reservationService.prepare(
          "batch-user-" + index,
          activityId,
          "batch-key-" + index,
          request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    }
    jdbc.update(
        "UPDATE seckill_reservation SET transaction_resolution_due_at = "
            + "TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE activity_id = ?",
        activityId);
    resolutionWorker.resolveDueReservations();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id = ? AND state = 'PENDING'",
                Integer.class,
                activityId))
        .isEqualTo(1);
    resolutionWorker.resolveDueReservations();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_reservation WHERE activity_id = ? AND state = 'PENDING'",
                Integer.class,
                activityId))
        .isZero();
  }

  @Test
  @Order(7)
  void boundedActivationHandoffAndNormalDispatchCancelAndRestoreExactlyOnce() throws Exception {
    String activityId = "cb061-handoff";
    String productId = "cb061-product-handoff";
    String reservationId = createOrderedReservation(activityId, productId, "cb061-handoff-key", 2);
    forceOrderDueIn(reservationId, Duration.ofSeconds(10));

    SeckillTimeoutWorker restartedWorker =
        new SeckillTimeoutWorker(
            new SeckillTimeoutDispatchService(orderRepository, timeoutMessaging, timeoutProperties),
            timeoutMessaging,
            cancellationService,
            timeoutProperties,
            Clock.systemUTC());
    SeckillTimeoutDispatchService.DispatchBatch handoff = restartedWorker.dispatchOnce();
    assertThat(handoff.selected()).isBetween(1, timeoutProperties.dispatchBatchSize());
    assertDispatchEvidence(reservationId);

    assertThat(timeoutMessaging.consumeOnce(cancellationService)).isZero();
    assertThat(orderStatus(reservationId)).isEqualTo("UNPAID");
    assertThat(consumeTimeoutEventually(Duration.ofSeconds(20))).isEqualTo(1);
    assertCancelledAndRestored(reservationId, 2, 2);

    SeckillOrderRepository.OrderRecord cancelled =
        orderRepository.findByReservation(reservationId).orElseThrow();
    timeoutMessaging.send(SeckillTimeoutMessage.from(cancelled));
    assertThat(consumeTimeoutEventually(Duration.ofSeconds(10))).isEqualTo(1);
    assertCancelledAndRestored(reservationId, 2, 2);
    String userMarker = admissionStore.userKey(activityId, SeckillReservationService.sha256(USER));
    assertThat(redis.opsForValue().get(userMarker)).isEqualTo(reservationId);
    assertThat(reservationService.rebuildActivityState(activityId))
        .isEqualTo(ReservationAdmissionStore.RebuildResult.APPLIED);
    assertThat(redis.opsForValue().get(userMarker)).isEqualTo(reservationId);
    assertThat(projectionRemaining(activityId)).isEqualTo(2);
    ResponseEntity<ReservationResult> repeatedUser =
        reserve(
            directToken(),
            activityId,
            "cb061-handoff-second-order",
            Map.of("quantity", 1, "expectedActivityVersion", 2));
    assertThat(repeatedUser.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(repeatedUser.getBody()).isNotNull();
    assertThat(repeatedUser.getBody().decisionCode())
        .isEqualTo(ReservationDecisionCode.DUPLICATE_USER);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_order WHERE activity_id = ?",
                Integer.class,
                activityId))
        .isEqualTo(1);

    restartedWorker.dispatchOnce();
    String normalActivityId = "cb061-normal";
    String normalReservationId =
        createOrderedReservation(normalActivityId, "cb061-product-normal", "cb061-normal-key", 1);
    forceOrderDueIn(normalReservationId, Duration.ofMinutes(5));
    assertThat(restartedWorker.dispatchOnce().sent()).isGreaterThanOrEqualTo(1);
    assertDispatchEvidence(normalReservationId);
  }

  @Test
  @Order(8)
  void earlyStaleAndPaidTimeoutsDoNotMutateAndRedisRetryUsesCommittedTruth() throws Exception {
    String earlyActivity = "cb061-early";
    String earlyReservation =
        createOrderedReservation(earlyActivity, "cb061-product-early", "cb061-early-key", 1);
    forceOrderDueIn(earlyReservation, Duration.ofMinutes(1));
    SeckillOrderRepository.OrderRecord earlyOrder =
        orderRepository.findByReservation(earlyReservation).orElseThrow();
    sendImmediateTimeout(SeckillTimeoutMessage.from(earlyOrder));
    assertThat(timeoutMessaging.consumeOnce(cancellationService)).isZero();
    assertThat(orderStatus(earlyReservation)).isEqualTo("UNPAID");
    assertThat(cancellationMovementCount(earlyReservation)).isZero();

    SeckillTimeoutMessage stale =
        new SeckillTimeoutMessage(
            UUID.randomUUID().toString(),
            earlyOrder.orderId(),
            earlyOrder.reservationId(),
            "UNPAID",
            1,
            earlyOrder.unpaidDeadline(),
            earlyOrder.transactionEventId());
    assertThat(cancellationService.cancel(stale).outcome())
        .isEqualTo(SeckillCancellationService.Outcome.STALE);
    assertThat(orderStatus(earlyReservation)).isEqualTo("UNPAID");

    String paidActivity = "cb061-paid";
    String paidReservation =
        createOrderedReservation(paidActivity, "cb061-product-paid", "cb061-paid-key", 1);
    forceOrderDueIn(paidReservation, Duration.ofSeconds(-1));
    assertThat(
            jdbc.update(
                "UPDATE seckill_order SET status = 'PAID', state_version = 2 "
                    + "WHERE reservation_id = ?",
                paidReservation))
        .isEqualTo(1);
    SeckillOrderRepository.OrderRecord paidOrder =
        orderRepository.findByReservation(paidReservation).orElseThrow();
    sendImmediateTimeout(SeckillTimeoutMessage.from(paidOrder));
    assertThat(consumeTimeoutEventually(Duration.ofSeconds(10))).isEqualTo(1);
    assertThat(orderStatus(paidReservation)).isEqualTo("PAID");
    assertThat(cancellationMovementCount(paidReservation)).isZero();

    String redisActivity = "cb061-redis-retry";
    String redisReservation =
        createOrderedReservation(
            redisActivity, "cb061-product-redis-retry", "cb061-redis-retry-key", 1);
    forceOrderDueIn(redisReservation, Duration.ofSeconds(-1));
    String preCancellationProjection = redis.opsForValue().get(projections.key(redisActivity));
    assertThat(preCancellationProjection).isNotNull();
    redis.opsForValue().set(projections.key(redisActivity), "{malformed");
    SeckillOrderRepository.OrderRecord redisOrder =
        orderRepository.findByReservation(redisReservation).orElseThrow();
    sendImmediateTimeout(SeckillTimeoutMessage.from(redisOrder));
    assertThatThrownBy(() -> timeoutMessaging.consumeOnce(cancellationService))
        .isInstanceOf(SeckillProjectionStore.ProjectionWriteException.class)
        .hasMessageContaining("malformed");
    assertDurableCancelledAndRestored(redisReservation, 1);
    assertThat(redis.opsForValue().get(projections.key(redisActivity))).isEqualTo("{malformed");
    redis.opsForValue().set(projections.key(redisActivity), preCancellationProjection);
    assertThat(consumeTimeoutEventually(Duration.ofSeconds(15))).isEqualTo(1);
    assertThat(projectionRemaining(redisActivity)).isEqualTo(1);

    redis.delete(projections.key(redisActivity));
    sendImmediateTimeout(SeckillTimeoutMessage.from(redisOrder));
    assertThatThrownBy(() -> timeoutMessaging.consumeOnce(cancellationService))
        .isInstanceOf(SeckillProjectionStore.ProjectionWriteException.class)
        .hasMessageContaining("missing or has a version gap");
    assertThat(reservationService.rebuildActivityState(redisActivity))
        .isEqualTo(ReservationAdmissionStore.RebuildResult.APPLIED);
    assertThat(consumeTimeoutEventually(Duration.ofSeconds(15))).isEqualTo(1);
    assertThat(projectionRemaining(redisActivity)).isEqualTo(1);
    assertCancelledAndRestored(redisReservation, 1, 1);
  }

  @Test
  @Order(9)
  void cancellationProjectionPreservesCrashWindowAdmissionsAndBlocksVersionGapAdmissions()
      throws Exception {
    String markerActivity = "cb061-projection-marker-race";
    String cancelledReservation =
        createOrderedReservation(
            markerActivity,
            "cb061-product-projection-marker-race",
            "cb061-projection-marker-race-order",
            2);
    SeckillReservationService.PreparedReservation pending =
        reservationService.prepare(
            "cb061-marker-race-user",
            markerActivity,
            "cb061-projection-marker-race-pending",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    SeckillActivity markerTruth = activityRepository.find(markerActivity).orElseThrow();
    assertThat(
            admissionStore
                .decide(
                    pending.reservation(),
                    markerTruth,
                    SeckillReservationService.sha256(pending.reservation().userSubject()))
                .state())
        .isEqualTo(ReservationState.ADMITTED);
    assertThat(
            reservationRepository.find(pending.reservation().reservationId()).orElseThrow().state())
        .isEqualTo(ReservationState.PENDING);
    assertThat(projectionRemaining(markerActivity)).isZero();

    forceOrderDueIn(cancelledReservation, Duration.ofSeconds(-1));
    SeckillOrderRepository.OrderRecord markerOrder =
        orderRepository.findByReservation(cancelledReservation).orElseThrow();
    assertThat(cancellationService.cancel(SeckillTimeoutMessage.from(markerOrder)).outcome())
        .isEqualTo(SeckillCancellationService.Outcome.CANCELLED);
    assertThat(projectionRemaining(markerActivity)).isEqualTo(1);
    assertThat(reservationService.admit(pending.reservation().reservationId()).state())
        .isEqualTo(ReservationState.ADMITTED);
    assertThat(projectionRemaining(markerActivity)).isEqualTo(1);
    assertThat(
            reservationService
                .admit(
                    reservationService
                        .prepare(
                            "cb061-marker-race-third-user",
                            markerActivity,
                            "cb061-projection-marker-race-third",
                            request(Map.of("quantity", 1, "expectedActivityVersion", 2)))
                        .reservation()
                        .reservationId())
                .state())
        .isEqualTo(ReservationState.ADMITTED);
    assertThat(projectionRemaining(markerActivity)).isZero();
    assertThat(
            reservationService
                .admit(
                    reservationService
                        .prepare(
                            "cb061-marker-race-fourth-user",
                            markerActivity,
                            "cb061-projection-marker-race-fourth",
                            request(Map.of("quantity", 1, "expectedActivityVersion", 2)))
                        .reservation()
                        .reservationId())
                .decisionCode())
        .isEqualTo(ReservationDecisionCode.EXHAUSTED);

    String gapActivity = "cb061-projection-commit-gap";
    String gapReservation =
        createOrderedReservation(
            gapActivity,
            "cb061-product-projection-commit-gap",
            "cb061-projection-commit-gap-order",
            2);
    forceOrderDueIn(gapReservation, Duration.ofSeconds(-1));
    SeckillOrderRepository.OrderRecord gapOrder =
        orderRepository.findByReservation(gapReservation).orElseThrow();
    CountDownLatch durableCommitReached = new CountDownLatch(1);
    CountDownLatch allowProjection = new CountDownLatch(1);
    TransactionTemplate concurrentTransactions = new TransactionTemplate(transactionManager);
    concurrentTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    SeckillCancellationService pausedProjection =
        new SeckillCancellationService(
            orderRepository,
            reservationRepository,
            activityRepository,
            (activity, targetVersion, quantity) -> {
              durableCommitReached.countDown();
              try {
                if (!allowProjection.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Timed out waiting to resume projection");
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Projection wait was interrupted", exception);
              }
              projections.restoreQuota(activity, targetVersion, quantity);
            },
            concurrentTransactions,
            Clock.systemUTC());
    CompletableFuture<SeckillCancellationService.CancellationResult> cancellation =
        CompletableFuture.supplyAsync(
            () -> pausedProjection.cancel(SeckillTimeoutMessage.from(gapOrder)));
    assertThat(durableCommitReached.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(orderStatus(gapReservation)).isEqualTo("CANCELLED");
    assertThat(projectionRemaining(gapActivity)).isEqualTo(1);

    SeckillReservationService.PreparedReservation duringGap =
        reservationService.prepare(
            "cb061-commit-gap-user",
            gapActivity,
            "cb061-projection-commit-gap-pending",
            request(Map.of("quantity", 1, "expectedActivityVersion", 2)));
    assertThatThrownBy(() -> reservationService.admit(duringGap.reservation().reservationId()))
        .isInstanceOf(ReservationAdmissionStore.AdmissionIndeterminateException.class)
        .hasMessageContaining("differs from MySQL truth");
    assertThat(projectionRemaining(gapActivity)).isEqualTo(1);
    allowProjection.countDown();
    assertThat(cancellation.get(10, TimeUnit.SECONDS).outcome())
        .isEqualTo(SeckillCancellationService.Outcome.CANCELLED);
    assertThat(projectionRemaining(gapActivity)).isEqualTo(2);
    assertThat(reservationService.admit(duringGap.reservation().reservationId()).state())
        .isEqualTo(ReservationState.ADMITTED);
    assertThat(projectionRemaining(gapActivity)).isEqualTo(1);
  }

  @Test
  @Order(10)
  void databaseFailureIsNotAcknowledgedAndDispatchRetryIsBoundedAndReplaySafe() throws Exception {
    String activityId = "cb061-database-retry";
    String reservationId =
        createOrderedReservation(
            activityId, "cb061-product-database-retry", "cb061-database-retry-key", 1);
    forceOrderDueIn(reservationId, Duration.ofSeconds(-1));
    TransactionTemplate controlledRollback = new TransactionTemplate(transactionManager);
    controlledRollback.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThatThrownBy(
            () ->
                controlledRollback.executeWithoutResult(
                    status -> {
                      SeckillOrderRepository.OrderRecord currentOrder =
                          orderRepository
                              .findByReservation(reservationId)
                              .flatMap(order -> orderRepository.findForUpdate(order.orderId()))
                              .orElseThrow();
                      SeckillReservation currentReservation =
                          reservationRepository.findForUpdate(reservationId).orElseThrow();
                      SeckillActivity currentActivity =
                          activityRepository.findForUpdate(activityId).orElseThrow();
                      SeckillOrderRepository.ProductSnapshot currentProduct =
                          orderRepository
                              .findProductForUpdate(currentOrder.productId())
                              .orElseThrow();
                      orderRepository.restoreInventory(currentProduct, currentOrder.quantity());
                      orderRepository.insertUnpaidCancellationMovement(currentOrder);
                      SeckillActivity advanced =
                          activityRepository.advanceProjectionVersion(currentActivity);
                      orderRepository.markCancelled(currentOrder, advanced.projectionVersion());
                      reservationRepository.markCancelled(currentReservation);
                      throw new IllegalStateException("controlled atomic cancellation rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled atomic cancellation rollback");
    SeckillOrderRepository.OrderRecord order =
        orderRepository.findByReservation(reservationId).orElseThrow();
    assertThat(orderStatus(reservationId)).isEqualTo("UNPAID");
    assertThat(cancellationMovementCount(reservationId)).isZero();
    assertThat(productStock(order.productId())).isZero();
    assertThat(reservationRepository.find(reservationId).orElseThrow().state())
        .isEqualTo(ReservationState.ORDERED);

    assertThat(
            jdbc.update(
                "UPDATE seckill_activity SET projection_version = ? WHERE activity_id = ?",
                SeckillLuaNumber.MAX_EXACT_INTEGER,
                activityId))
        .isEqualTo(1);
    sendImmediateTimeout(SeckillTimeoutMessage.from(order));
    assertThatThrownBy(() -> timeoutMessaging.consumeOnce(cancellationService))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be incremented safely");
    assertThat(orderStatus(reservationId)).isEqualTo("UNPAID");
    assertThat(cancellationMovementCount(reservationId)).isZero();
    assertThat(productStock(order.productId())).isZero();
    assertThat(reservationRepository.find(reservationId).orElseThrow().state())
        .isEqualTo(ReservationState.ORDERED);

    assertThat(
            jdbc.update(
                "UPDATE seckill_activity SET projection_version = 1 WHERE activity_id = ?",
                activityId))
        .isEqualTo(1);
    assertThat(consumeTimeoutEventually(Duration.ofSeconds(20))).isEqualTo(1);
    assertCancelledAndRestored(reservationId, 1, 1);

    String dispatchActivity = "cb061-dispatch-bound";
    String dispatchReservation =
        createOrderedReservation(
            dispatchActivity, "cb061-product-dispatch-bound", "cb061-dispatch-bound-key", 1);
    forceOrderDueIn(dispatchReservation, Duration.ofMinutes(5));
    SeckillTimeoutProperties twoAttempts =
        new SeckillTimeoutProperties(
            timeoutProperties.rocketmqEndpoints(),
            timeoutProperties.rocketmqTopic(),
            timeoutProperties.rocketmqConsumerGroup(),
            timeoutProperties.receiveAwait(),
            timeoutProperties.receiveInvisibleDuration(),
            timeoutProperties.receiveBatchSize(),
            timeoutProperties.dispatchBatchSize(),
            2,
            timeoutProperties.maximumDeliveryAttempts());
    SeckillTimeoutDispatchService ambiguousDispatch =
        new SeckillTimeoutDispatchService(
            orderRepository,
            message -> {
              timeoutMessaging.send(message);
              throw new org.apache.rocketmq.client.apis.ClientException(
                  "controlled lost send receipt");
            },
            twoAttempts);
    assertThat(ambiguousDispatch.dispatchCurrentOnce().failed()).isGreaterThanOrEqualTo(1);
    assertThat(timeoutDispatchAttempts(dispatchReservation)).isEqualTo(1);
    assertThat(timeoutDispatchState(dispatchReservation)).isEqualTo("PENDING");
    assertThat(timeoutDispatch.dispatchCurrentOnce().sent()).isGreaterThanOrEqualTo(1);
    assertDispatchEvidence(dispatchReservation);

    String exhaustedActivity = "cb061-dispatch-exhausted";
    String exhaustedReservation =
        createOrderedReservation(
            exhaustedActivity,
            "cb061-product-dispatch-exhausted",
            "cb061-dispatch-exhausted-key",
            1);
    forceOrderDueIn(exhaustedReservation, Duration.ofMinutes(5));
    SeckillTimeoutDispatchService alwaysFailing =
        new SeckillTimeoutDispatchService(
            orderRepository,
            message -> {
              throw new org.apache.rocketmq.client.apis.ClientException(
                  "controlled broker unavailability");
            },
            twoAttempts);
    while ("PENDING".equals(timeoutDispatchState(exhaustedReservation))) {
      alwaysFailing.dispatchCurrentOnce();
    }
    assertThat(timeoutDispatchState(exhaustedReservation)).isEqualTo("FAILED");
    assertThat(timeoutDispatchAttempts(exhaustedReservation)).isEqualTo(2);
    assertThat(alwaysFailing.dispatchCurrentOnce().selected()).isZero();
  }

  private void forceDue(String reservationId) {
    assertThat(
            jdbc.update(
                "UPDATE seckill_reservation SET transaction_resolution_due_at = "
                    + "TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE reservation_id = ?",
                reservationId))
        .isEqualTo(1);
  }

  private String createOrderedReservation(
      String activityId, String productId, String idempotencyKey, long initialStock)
      throws Exception {
    seedActivity(activityId, productId, SeckillActivityState.ACTIVE, initialStock, initialStock);
    ResponseEntity<ReservationResult> response =
        reserve(
            directToken(),
            activityId,
            idempotencyKey,
            Map.of("quantity", 1, "expectedActivityVersion", 1));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ReservationResult admitted = response.getBody();
    assertThat(admitted).isNotNull();
    assertThat(consumeEventually()).isEqualTo(1);
    ReservationResult ordered = poll(directToken(), admitted.reservationId()).getBody();
    assertThat(ordered).isNotNull();
    assertThat(ordered.state()).isEqualTo(ReservationState.ORDERED);
    return admitted.reservationId();
  }

  private void forceOrderDueIn(String reservationId, Duration offset) {
    assertThat(
            jdbc.update(
                "UPDATE seckill_order SET unpaid_deadline = ? WHERE reservation_id = ?",
                java.sql.Timestamp.from(Instant.now().plus(offset).truncatedTo(ChronoUnit.MICROS)),
                reservationId))
        .isEqualTo(1);
  }

  private void assertDispatchEvidence(String reservationId) {
    Map<String, Object> evidence =
        jdbc.queryForMap(
            "SELECT timeout_dispatch_state, timeout_broker_message_id, "
                + "timeout_dispatched_at FROM seckill_order WHERE reservation_id = ?",
            reservationId);
    assertThat(evidence.get("timeout_dispatch_state")).isEqualTo("SENT");
    assertThat(evidence.get("timeout_broker_message_id")).asString().isNotBlank();
    assertThat(evidence.get("timeout_dispatched_at")).isNotNull();
  }

  private void assertCancelledAndRestored(
      String reservationId, long expectedStock, long expectedRemainingQuota) throws Exception {
    assertDurableCancelledAndRestored(reservationId, expectedStock);
    SeckillOrderRepository.OrderRecord order =
        orderRepository.findByReservation(reservationId).orElseThrow();
    assertThat(projectionRemaining(order.activityId())).isEqualTo(expectedRemainingQuota);
  }

  private void assertDurableCancelledAndRestored(String reservationId, long expectedStock) {
    SeckillOrderRepository.OrderRecord order =
        orderRepository.findByReservation(reservationId).orElseThrow();
    assertThat(order.status()).isEqualTo("CANCELLED");
    assertThat(order.stateVersion()).isEqualTo(2);
    assertThat(reservationRepository.find(reservationId).orElseThrow().state())
        .isEqualTo(ReservationState.CANCELLED);
    assertThat(productStock(order.productId())).isEqualTo(expectedStock);
    assertThat(cancellationMovementCount(reservationId)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE reservation_id = ? "
                    + "AND movement_type = 'SECKILL_UNPAID_CANCEL' "
                    + "AND business_event_key = CONCAT('seckill-unpaid-cancel:', ?) "
                    + "AND inventory_delta = 1 AND activity_quota_delta = 1",
                Integer.class,
                reservationId,
                order.timeoutEventId()))
        .isEqualTo(1);
  }

  private long projectionRemaining(String activityId) throws Exception {
    String projection = redis.opsForValue().get(projections.key(activityId));
    assertThat(projection).isNotNull();
    return objectMapper.readTree(projection).path("remainingQuota").asLong();
  }

  private String orderStatus(String reservationId) {
    return jdbc.queryForObject(
        "SELECT status FROM seckill_order WHERE reservation_id = ?", String.class, reservationId);
  }

  private int cancellationMovementCount(String reservationId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM inventory_ledger WHERE reservation_id = ? "
            + "AND movement_type = 'SECKILL_UNPAID_CANCEL'",
        Integer.class,
        reservationId);
  }

  private long productStock(String productId) {
    return jdbc.queryForObject(
        "SELECT stock_quantity FROM product WHERE product_id = ?", Long.class, productId);
  }

  private String timeoutDispatchState(String reservationId) {
    return jdbc.queryForObject(
        "SELECT timeout_dispatch_state FROM seckill_order WHERE reservation_id = ?",
        String.class,
        reservationId);
  }

  private int timeoutDispatchAttempts(String reservationId) {
    return jdbc.queryForObject(
        "SELECT timeout_dispatch_attempts FROM seckill_order WHERE reservation_id = ?",
        Integer.class,
        reservationId);
  }

  private int consumeTimeoutEventually(Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      int consumed = timeoutMessaging.consumeOnce(cancellationService);
      if (consumed > 0) {
        return consumed;
      }
    }
    return 0;
  }

  private void sendImmediateTimeout(SeckillTimeoutMessage payload) throws Exception {
    try (Producer producer = plainProducer(required("ROCKETMQ_TIMEOUT_TOPIC"))) {
      producer.send(
          ClientServiceProvider.loadService()
              .newMessageBuilder()
              .setTopic(required("ROCKETMQ_TIMEOUT_TOPIC"))
              .setTag(RocketMqSeckillTimeouts.TAG)
              .setKeys(payload.eventId())
              .setDeliveryTimestamp(System.currentTimeMillis())
              .setBody(objectMapper.writeValueAsBytes(payload))
              .build());
    }
  }

  private Producer plainProducer(String topic) throws Exception {
    ClientConfiguration configuration =
        ClientConfiguration.newBuilder()
            .setEndpoints(required("ROCKETMQ_ENDPOINTS"))
            .setRequestTimeout(Duration.ofSeconds(10))
            .enableSsl(false)
            .build();
    return ClientServiceProvider.loadService()
        .newProducerBuilder()
        .setClientConfiguration(configuration)
        .setTopics(topic)
        .build();
  }

  private void deferExistingPendingDeadlines() {
    jdbc.update(
        "UPDATE seckill_reservation SET transaction_resolution_due_at = "
            + "TIMESTAMPADD(DAY, 1, CURRENT_TIMESTAMP(6)) WHERE state = 'PENDING'");
  }

  private void assertTimedOutWithoutOrder(String reservationId) {
    SeckillReservation reservation = reservationRepository.find(reservationId).orElseThrow();
    assertThat(reservation.state()).isEqualTo(ReservationState.REJECTED);
    assertThat(reservation.decisionCode()).isEqualTo(ReservationDecisionCode.TRANSACTION_TIMEOUT);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_order WHERE reservation_id = ?",
                Integer.class,
                reservationId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE reservation_id = ?",
                Integer.class,
                reservationId))
        .isZero();
  }

  private void seedActivity(
      String activityId, String productId, SeckillActivityState state, long quota, long stock) {
    jdbc.update(
        """
        INSERT INTO product
          (product_id, name, description, price_minor, currency, stock_quantity,
           available, publication_state, publication_version)
        VALUES (?, ?, 'CB-060 integration product', 1250, 'AUD', ?, TRUE, 'PUBLISHED', 1)
        """,
        productId,
        productId,
        stock);
    SeckillActivity activity =
        new SeckillActivity(
            activityId,
            productId,
            Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS),
            Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS),
            state,
            quota,
            1);
    activityRepository.insert(activity);
    assertThat(projections.publish(activity))
        .isEqualTo(SeckillProjectionStore.PublishResult.APPLIED);
  }

  private void assertAtomicOrder(String reservationId, String orderId, long expectedStock) {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_order WHERE reservation_id = ? AND order_id = ?",
                Integer.class,
                reservationId,
                orderId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE reservation_id = ? "
                    + "AND movement_type = 'SECKILL_ORDER_CREATE' "
                    + "AND inventory_delta = -1 AND activity_quota_delta = -1",
                Integer.class,
                reservationId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT stock_quantity FROM product WHERE product_id = "
                    + "(SELECT product_id FROM seckill_order WHERE order_id = ?)",
                Long.class,
                orderId))
        .isEqualTo(expectedStock);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_order WHERE order_id = ? "
                    + "AND transaction_event_id = reservation_id "
                    + "AND timeout_event_id IS NOT NULL AND unpaid_deadline > created_at",
                Integer.class,
                orderId))
        .isEqualTo(1);
  }

  private int consumeEventually() throws Exception {
    return consumeEventually(Duration.ofSeconds(15));
  }

  private int consumeEventually(Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      int consumed = messaging.consumeOnce(orderService);
      if (consumed > 0) {
        return consumed;
      }
    }
    return 0;
  }

  private Producer producer(AtomicInteger checks) throws Exception {
    ClientServiceProvider provider = ClientServiceProvider.loadService();
    ClientConfiguration configuration =
        ClientConfiguration.newBuilder()
            .setEndpoints(required("ROCKETMQ_ENDPOINTS"))
            .setRequestTimeout(Duration.ofSeconds(10))
            .enableSsl(false)
            .build();
    return provider
        .newProducerBuilder()
        .setClientConfiguration(configuration)
        .setTopics(required("ROCKETMQ_TRANSACTION_TOPIC"))
        .setTransactionChecker(
            view -> {
              checks.incrementAndGet();
              try {
                if (view.getKeys().size() != 1) {
                  return org.apache.rocketmq.client.apis.producer.TransactionResolution.UNKNOWN;
                }
                return admissionStore.transactionResolution(view.getKeys().iterator().next());
              } catch (Exception exception) {
                return org.apache.rocketmq.client.apis.producer.TransactionResolution.UNKNOWN;
              }
            })
        .build();
  }

  private Message message(SeckillTransactionMessage payload) throws Exception {
    return ClientServiceProvider.loadService()
        .newMessageBuilder()
        .setTopic(required("ROCKETMQ_TRANSACTION_TOPIC"))
        .setTag(RocketMqSeckillTransactions.TAG)
        .setKeys(payload.eventId())
        .setBody(objectMapper.writeValueAsBytes(payload))
        .build();
  }

  private static void awaitChecks(AtomicInteger checks, int expected, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (checks.get() < expected && System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
    assertThat(checks.get()).isGreaterThanOrEqualTo(expected);
  }

  private static void awaitElapsed(long startedAt, Duration duration) throws InterruptedException {
    long deadline = startedAt + duration.toNanos();
    while (System.nanoTime() < deadline) {
      Thread.sleep(100);
    }
  }

  private ResponseEntity<ReservationResult> reserve(
      String token, String activityId, String key, Map<String, Object> body) {
    HttpHeaders headers = headers(token);
    headers.set("Idempotency-Key", key);
    return http.exchange(
        "/api/seckill/activities/" + activityId + "/reservations",
        HttpMethod.POST,
        new HttpEntity<>(body, headers),
        ReservationResult.class);
  }

  private ResponseEntity<ReservationResult> poll(String token, String reservationId) {
    return http.exchange(
        "/api/reservations/" + reservationId,
        HttpMethod.GET,
        new HttpEntity<>(headers(token)),
        ReservationResult.class);
  }

  private static HttpHeaders headers(String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return headers;
  }

  private static ReservationRequest request(Map<String, Object> body) {
    ReservationRequest request = new ReservationRequest();
    request.setQuantity((Integer) body.get("quantity"));
    request.setExpectedActivityVersion(((Number) body.get("expectedActivityVersion")).longValue());
    return request;
  }

  private static String directToken() {
    return required("CATALOG_DIRECT_TOKEN");
  }

  private static String otherDirectToken() {
    return required("CATALOG_OTHER_DIRECT_TOKEN");
  }

  private static String limitedDirectToken() {
    return required("CATALOG_LIMITED_DIRECT_TOKEN");
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required");
    }
    return value;
  }
}
