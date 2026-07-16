package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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

  private void forceDue(String reservationId) {
    assertThat(
            jdbc.update(
                "UPDATE seckill_reservation SET transaction_resolution_due_at = "
                    + "TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(6)) WHERE reservation_id = ?",
                reservationId))
        .isEqualTo(1);
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
