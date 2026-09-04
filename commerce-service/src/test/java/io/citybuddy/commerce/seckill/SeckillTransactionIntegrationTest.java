package io.citybuddy.commerce.seckill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
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
  }

  @Autowired private TestRestTemplate http;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private StringRedisTemplate redis;
  @Autowired private SeckillActivityRepository activityRepository;
  @Autowired private SeckillProjectionStore projections;
  @Autowired private SeckillReservationRepository reservationRepository;
  @MockitoSpyBean private SeckillReservationService reservationService;
  @Autowired private SeckillTransactionCoordinator coordinator;
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
    assertThat(reservationRepository.find(response.getBody().reservationId())).isEmpty();
    assertThat(redis.hasKey(admissionStore.handoffKey(response.getBody().reservationId())))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_order WHERE activity_id = ?",
                Integer.class,
                activityId))
        .isZero();
  }

  @Test
  @Order(3)
  void checkerUsesOnlyMysqlTerminalTruth() throws Exception {
    String pendingActivityId = "cb060-public-pending";
    seedActivity(
        pendingActivityId, "cb060-product-public-pending", SeckillActivityState.ACTIVE, 1, 1);
    redis.delete(projections.key(pendingActivityId));
    SeckillReservation pending =
        reservationService
            .prepare(
                USER,
                pendingActivityId,
                "cb060-key-public-pending",
                request(Map.of("quantity", 1, "expectedActivityVersion", 1)))
            .reservation();
    assertThat(pending.state()).isEqualTo(ReservationState.PENDING);
    redis
        .opsForValue()
        .set(
            admissionStore.decisionKey(pending.reservationId()),
            objectMapper.writeValueAsString(
                Map.of(
                    "reservationId",
                    pending.reservationId(),
                    "activityId",
                    pendingActivityId,
                    "userHash",
                    SeckillReservationService.sha256(pending.userSubject()),
                    "quantity",
                    pending.quantity(),
                    "activityProjectionVersion",
                    pending.activityProjectionVersion(),
                    "reservationVersion",
                    2,
                    "state",
                    "ADMITTED",
                    "decisionCode",
                    "ADMITTED",
                    "durableOrderCreated",
                    false)));
    assertThat(reservationService.transactionResolution(pending.reservationId()).name())
        .isEqualTo("UNKNOWN");

    String activityId = "cb060-checkback";
    seedActivity(activityId, "cb060-product-checkback", SeckillActivityState.ACTIVE, 2, 5);
    var prepared =
        reservationService.preAdmit(
            USER,
            activityId,
            "cb060-key-checkback",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    AtomicInteger admittedChecks = new AtomicInteger();
    // Broker checks may reach any producer on the topic, so observe the real shared service.
    doAnswer(
            invocation -> {
              admittedChecks.incrementAndGet();
              return invocation.callRealMethod();
            })
        .when(reservationService)
        .transactionResolution(prepared.handoff().reservationId());
    try (Producer producer = producer(new AtomicInteger())) {
      Transaction unresolved = producer.beginTransaction();
      producer.send(message(SeckillTransactionMessage.from(prepared.handoff())), unresolved);
      ReservationResult admitted = reservationService.persistAdmitted(prepared.handoff());
      assertThat(admitted.state()).isEqualTo(ReservationState.ADMITTED);
      redis.delete(admissionStore.decisionKey(admitted.reservationId()));
      assertThat(reservationService.transactionResolution(admitted.reservationId()).name())
          .isEqualTo("COMMIT");
      // The explicit COMMIT assertion above accounts for one call; a broker check adds another.
      awaitChecks(admittedChecks, 2, Duration.ofSeconds(20));
      assertThat(consumeEventually()).isEqualTo(1);
      assertThat(admittedChecks.get()).isGreaterThanOrEqualTo(2);
      reservationService.completeAdmissionHandoff(prepared.handoff());
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
    redis
        .opsForValue()
        .set(
            admissionStore.decisionKey(rejected.reservationId()),
            objectMapper.writeValueAsString(
                Map.of(
                    "reservationId",
                    rejected.reservationId(),
                    "activityId",
                    rejected.activityId(),
                    "userHash",
                    SeckillReservationService.sha256(rejectedPrepared.reservation().userSubject()),
                    "quantity",
                    rejected.quantity(),
                    "activityProjectionVersion",
                    rejected.activityProjectionVersion(),
                    "reservationVersion",
                    2,
                    "state",
                    "ADMITTED",
                    "decisionCode",
                    "ADMITTED",
                    "durableOrderCreated",
                    false)));
    assertThat(reservationService.transactionResolution(rejected.reservationId()).name())
        .isEqualTo("ROLLBACK");

    String missingReservationId = "00000000-0000-0000-0000-000000000060";
    assertThat(reservationService.transactionResolution(missingReservationId).name())
        .isEqualTo("UNKNOWN");
    SeckillTransactionMessage unknown =
        new SeckillTransactionMessage(
            missingReservationId, missingReservationId, "missing", "missing-user", 1, 1);
    AtomicInteger unknownChecks = new AtomicInteger();
    doAnswer(
            invocation -> {
              unknownChecks.incrementAndGet();
              return invocation.callRealMethod();
            })
        .when(reservationService)
        .transactionResolution(missingReservationId);
    long unknownStartedAt = System.nanoTime();
    try (Producer producer = producer(new AtomicInteger())) {
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
  void overlappingActivitiesConvergeSharedStockLoserWithoutQuotaRefund() throws Exception {
    String productId = "cb060-product-overlap";
    String winnerActivity = "cb060-overlap-winner";
    String loserActivity = "cb060-overlap-loser";
    seedActivity(winnerActivity, productId, SeckillActivityState.ACTIVE, 1, 1);
    seedActivityForProduct(loserActivity, productId, SeckillActivityState.ACTIVE, 1);

    ResponseEntity<ReservationResult> firstResponse =
        reserve(
            directToken(),
            winnerActivity,
            "cb060-winner-key",
            Map.of("quantity", 1, "expectedActivityVersion", 1));
    ResponseEntity<ReservationResult> secondResponse =
        reserve(
            otherDirectToken(),
            loserActivity,
            "cb060-loser-key",
            Map.of("quantity", 1, "expectedActivityVersion", 1));
    assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    ReservationResult first = firstResponse.getBody();
    ReservationResult second = secondResponse.getBody();
    assertThat(first).isNotNull();
    assertThat(second).isNotNull();

    int consumed = 0;
    long consumeDeadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (consumed < 2 && System.nanoTime() < consumeDeadline) {
      consumed += messaging.consumeOnce(orderService);
    }
    assertThat(consumed).isEqualTo(2);

    SeckillReservation firstTerminal =
        reservationRepository.find(first.reservationId()).orElseThrow();
    SeckillReservation secondTerminal =
        reservationRepository.find(second.reservationId()).orElseThrow();
    assertThat(java.util.List.of(firstTerminal.state(), secondTerminal.state()))
        .containsExactlyInAnyOrder(ReservationState.ORDERED, ReservationState.UNFULFILLED);
    SeckillReservation unfulfilled =
        firstTerminal.state() == ReservationState.UNFULFILLED ? firstTerminal : secondTerminal;
    assertThat(unfulfilled.state()).isEqualTo(ReservationState.UNFULFILLED);
    assertThat(unfulfilled.decisionCode()).isEqualTo(ReservationDecisionCode.ADMITTED);
    assertThat(unfulfilled.projectionVersion()).isEqualTo(3);
    assertThat(unfulfilled.orderId()).isNull();
    assertThat(productStock(productId)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_order WHERE product_id = ?",
                Integer.class,
                productId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE product_id = ? AND movement_type = 'SECKILL_ORDER_CREATE'",
                Integer.class,
                productId))
        .isEqualTo(1);
    assertThat(projectionRemaining(winnerActivity)).isZero();
    assertThat(projectionRemaining(loserActivity)).isZero();
    assertThat(reservationRepository.admittedQuantity(winnerActivity)).isEqualTo(1);
    assertThat(reservationRepository.admittedQuantity(loserActivity)).isEqualTo(1);

    try (Producer duplicateProducer = producer(new AtomicInteger())) {
      Transaction duplicate = duplicateProducer.beginTransaction();
      duplicateProducer.send(message(SeckillTransactionMessage.from(unfulfilled)), duplicate);
      duplicate.commit();
      assertThat(consumeEventually()).isEqualTo(1);
    }
    assertThat(reservationRepository.find(unfulfilled.reservationId()).orElseThrow().state())
        .isEqualTo(ReservationState.UNFULFILLED);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_order WHERE product_id = ?",
                Integer.class,
                productId))
        .isEqualTo(1);

    assertThat(reservationService.rebuildActivityState(unfulfilled.activityId()))
        .isEqualTo(ReservationAdmissionStore.RebuildResult.APPLIED);
    String loserMarker =
        admissionStore.userKey(
            unfulfilled.activityId(), SeckillReservationService.sha256(unfulfilled.userSubject()));
    redis.delete(loserMarker);
    assertThat(reservationService.rebuildActivityState(unfulfilled.activityId()))
        .isEqualTo(ReservationAdmissionStore.RebuildResult.APPLIED);
    ReservationResult repeated =
        coordinator.submit(
            unfulfilled.userSubject(),
            unfulfilled.activityId(),
            "cb060-loser-key-2",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    assertThat(repeated.state()).isEqualTo(ReservationState.REJECTED);
    assertThat(repeated.decisionCode()).isEqualTo(ReservationDecisionCode.DUPLICATE_USER);
    assertThat(projectionRemaining(unfulfilled.activityId())).isZero();

    String legacyActivity = "cb060-legacy-duplicate-user";
    String legacyProduct = "cb060-product-legacy-duplicate-user";
    String legacyUser = "cb060-legacy-user";
    seedActivity(legacyActivity, legacyProduct, SeckillActivityState.ACTIVE, 2, 2);
    ReservationResult canonical =
        coordinator.submit(
            legacyUser,
            legacyActivity,
            "cb060-legacy-key-1",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    SeckillReservation legacyPending =
        new SeckillReservation(
            UUID.randomUUID().toString(),
            legacyUser,
            legacyActivity,
            "cb060-legacy-key-2",
            "0".repeat(64),
            1,
            1,
            ReservationState.PENDING,
            null,
            1);
    reservationRepository.reservePending(
        legacyPending, reservationProperties.minimumBrokerCoverage());
    SeckillReservation legacyAdmitted =
        reservationRepository.applyDecision(
            reservationRepository.find(legacyPending.reservationId()).orElseThrow(),
            ReservationState.ADMITTED,
            ReservationDecisionCode.ADMITTED);
    assertThat(consumeEventually()).isEqualTo(1);
    assertThat(reservationRepository.find(canonical.reservationId()).orElseThrow().state())
        .isEqualTo(ReservationState.ORDERED);
    orderService.create(SeckillTransactionMessage.from(legacyAdmitted));
    assertThat(reservationRepository.find(legacyAdmitted.reservationId()).orElseThrow().state())
        .isEqualTo(ReservationState.UNFULFILLED);
    assertThat(productStock(legacyProduct)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM seckill_order WHERE activity_id = ?",
                Integer.class,
                legacyActivity))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger WHERE activity_id = ? "
                    + "AND movement_type = 'SECKILL_ORDER_CREATE'",
                Integer.class,
                legacyActivity))
        .isEqualTo(1);
  }

  @Test
  @Order(4)
  void consumerDoesNotAcknowledgeNonterminalFailureAndRetryCommitsAtomically() throws Exception {
    String activityId = "cb060-consumer-retry";
    String productId = "cb060-product-consumer-retry";
    seedActivity(activityId, productId, SeckillActivityState.ACTIVE, 1, 1);
    assertThat(jdbc.update("UPDATE product SET available = FALSE WHERE product_id = ?", productId))
        .isEqualTo(1);
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
        .hasMessage("Seckill product is not orderable");
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

    assertThat(jdbc.update("UPDATE product SET available = TRUE WHERE product_id = ?", productId))
        .isEqualTo(1);
    assertThat(consumeEventually(Duration.ofSeconds(20))).isEqualTo(1);
    ReservationResult ordered = poll(directToken(), admitted.reservationId()).getBody();
    assertThat(ordered).isNotNull();
    assertThat(ordered.state()).isEqualTo(ReservationState.ORDERED);
    assertAtomicOrder(admitted.reservationId(), ordered.orderId(), 0);
  }

  @Test
  @Order(5)
  void handoffRecoveryConvergesEveryCrashWindowAndPreservesAdmission() throws Exception {
    String beforeHalfActivity = "cb060-handoff-pre-half";
    seedActivity(
        beforeHalfActivity, "cb060-product-handoff-pre-half", SeckillActivityState.ACTIVE, 1, 1);
    var beforeHalf =
        reservationService.preAdmit(
            USER,
            beforeHalfActivity,
            "cb060-handoff-pre-half-key",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    assertThat(reservationRepository.find(beforeHalf.handoff().reservationId())).isEmpty();
    Double originalDue =
        redis
            .opsForZSet()
            .score(ReservationAdmissionStore.HANDOFF_INDEX, beforeHalf.handoff().reservationId());
    var replay =
        reservationService.preAdmit(
            USER,
            beforeHalfActivity,
            "cb060-handoff-pre-half-key",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    assertThat(replay.handoff()).isEqualTo(beforeHalf.handoff());
    assertThat(
            redis
                .opsForZSet()
                .score(
                    ReservationAdmissionStore.HANDOFF_INDEX, beforeHalf.handoff().reservationId()))
        .isEqualTo(originalDue);
    recoverHandoff(beforeHalf.handoff());
    assertThat(consumeUntilOrdered(beforeHalf.handoff().reservationId())).isGreaterThanOrEqualTo(1);
    assertThat(orderCreateMovementCount(beforeHalf.handoff().reservationId())).isEqualTo(1);

    String halfActivity = "cb060-handoff-half";
    seedActivity(halfActivity, "cb060-product-handoff-half", SeckillActivityState.ACTIVE, 1, 1);
    var afterHalf =
        reservationService.preAdmit(
            USER,
            halfActivity,
            "cb060-handoff-half-key",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    try (Producer producer = producer(new AtomicInteger())) {
      Transaction unresolved = producer.beginTransaction();
      producer.send(message(SeckillTransactionMessage.from(afterHalf.handoff())), unresolved);
      assertThat(
              reservationService.transactionResolution(afterHalf.handoff().reservationId()).name())
          .isEqualTo("UNKNOWN");
      clearInvocations(reservationService);
      recoverHandoff(afterHalf.handoff());
      verify(reservationService, timeout(20_000).atLeastOnce())
          .transactionResolution(afterHalf.handoff().reservationId());
      int recoveredCopies = 0;
      long deliveryDeadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (recoveredCopies < 2 && System.nanoTime() < deliveryDeadline) {
        recoveredCopies += messaging.consumeOnce(orderService);
      }
      assertThat(recoveredCopies).isEqualTo(2);
      assertThat(orderCreateMovementCount(afterHalf.handoff().reservationId())).isEqualTo(1);
    }

    String rollbackActivity = "cb060-handoff-rollback";
    seedActivity(
        rollbackActivity, "cb060-product-handoff-rollback", SeckillActivityState.ACTIVE, 1, 1);
    var afterLua =
        reservationService.preAdmit(
            USER,
            rollbackActivity,
            "cb060-handoff-rollback-key",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    TransactionTemplate isolated =
        new TransactionTemplate(transactionManager) {
          @Override
          public <T> T execute(TransactionCallback<T> action) {
            return super.execute(
                status -> {
                  action.doInTransaction(status);
                  throw new IllegalStateException("controlled MySQL admission failure");
                });
          }
        };
    isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    SeckillReservationService failingService =
        new SeckillReservationService(
            reservationRepository,
            activityRepository,
            admissionStore,
            isolated,
            reservationProperties);
    assertThatThrownBy(() -> failingService.persistAdmitted(afterLua.handoff()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("controlled MySQL admission failure");
    assertThat(reservationRepository.find(afterLua.handoff().reservationId())).isEmpty();
    assertThat(reservationService.hasPendingAdmissionHandoff(rollbackActivity)).isTrue();
    assertThat(projectionRemaining(rollbackActivity)).isZero();
    recoverHandoff(afterLua.handoff());
    assertThat(consumeUntilOrdered(afterLua.handoff().reservationId())).isGreaterThanOrEqualTo(1);
    assertThat(orderCreateMovementCount(afterLua.handoff().reservationId())).isEqualTo(1);
  }

  @Test
  @Order(6)
  void handoffWorkerUsesABoundedBatch() throws Exception {
    String activityId = "cb060-handoff-batch";
    seedActivity(activityId, "cb060-product-handoff-batch", SeckillActivityState.ACTIVE, 40, 40);
    for (int index = 0; index < SeckillTransactionResolutionWorker.BATCH_SIZE + 1; index++) {
      var admission =
          reservationService.preAdmit(
              "batch-user-" + index,
              activityId,
              "batch-key-" + index,
              request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
      redis
          .opsForZSet()
          .add(ReservationAdmissionStore.HANDOFF_INDEX, admission.handoff().reservationId(), 0);
    }
    resolutionWorker.resolveDueReservations();
    assertThat(redis.opsForSet().size(admissionStore.activityHandoffKey(activityId))).isEqualTo(1);
    assertThat(reservationRepository.admittedQuantity(activityId)).isEqualTo(32);
    resolutionWorker.resolveDueReservations();
    assertThat(reservationService.hasPendingAdmissionHandoff(activityId)).isFalse();
    assertThat(reservationRepository.admittedQuantity(activityId)).isEqualTo(33);
    for (String reservationId :
        jdbc.queryForList(
            "SELECT reservation_id FROM seckill_reservation WHERE activity_id = ?",
            String.class,
            activityId)) {
      consumeUntilOrdered(reservationId);
      assertThat(orderCreateMovementCount(reservationId)).isEqualTo(1);
      // These 33 fixture orders must not consume the next test's bounded activation batch.
      SeckillOrderRepository.OrderRecord order =
          orderRepository.findByReservation(reservationId).orElseThrow();
      String brokerMessageId = timeoutMessaging.send(SeckillTimeoutMessage.from(order));
      orderRepository.markTimeoutDispatched(order, brokerMessageId);
      assertDispatchEvidence(reservationId);
    }
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
    forceOrderDueIn(earlyReservation, Duration.ofSeconds(12));
    SeckillOrderRepository.OrderRecord earlyOrder =
        orderRepository.findByReservation(earlyReservation).orElseThrow();
    SeckillTimeoutMessage earlyTimeout = SeckillTimeoutMessage.from(earlyOrder);
    sendImmediateTimeout(earlyTimeout);
    AtomicInteger earlyDeliveries = new AtomicInteger();
    SeckillCancellationService observedCancellation = mock(SeckillCancellationService.class);
    when(observedCancellation.cancel(any(SeckillTimeoutMessage.class)))
        .thenAnswer(
            invocation -> {
              SeckillCancellationService.CancellationResult result =
                  cancellationService.cancel(invocation.getArgument(0));
              if (earlyTimeout.equals(invocation.getArgument(0))
                  && result.outcome() == SeckillCancellationService.Outcome.EARLY) {
                earlyDeliveries.incrementAndGet();
              }
              return result;
            });
    assertThat(observeEarlyTimeoutEventually(observedCancellation, earlyDeliveries)).isEqualTo(1);
    assertThat(orderStatus(earlyReservation)).isEqualTo("UNPAID");
    assertThat(cancellationMovementCount(earlyReservation)).isZero();
    assertThat(consumeTimeoutEventually(Duration.ofSeconds(25))).isEqualTo(1);
    assertCancelledAndRestored(earlyReservation, 1, 1);

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
    assertThat(orderStatus(earlyReservation)).isEqualTo("CANCELLED");

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
    SeckillOrderRepository.OrderRecord redisOrder =
        orderRepository.findByReservation(redisReservation).orElseThrow();
    TransactionTemplate isolated = new TransactionTemplate(transactionManager);
    isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    SeckillCancellationService unavailableProjection =
        new SeckillCancellationService(
            orderRepository,
            reservationRepository,
            activityRepository,
            admissionStore,
            (activity, remainingQuota) -> {
              throw new SeckillProjectionStore.ProjectionWriteException(
                  "controlled projection publication failure");
            },
            isolated,
            Clock.systemUTC());
    sendImmediateTimeout(SeckillTimeoutMessage.from(redisOrder));
    assertThatThrownBy(() -> timeoutMessaging.consumeOnce(unavailableProjection))
        .isInstanceOf(SeckillProjectionStore.ProjectionWriteException.class)
        .hasMessage("controlled projection publication failure");
    assertDurableCancelledAndRestored(redisReservation, 1);
    assertThat(redis.hasKey(projections.key(redisActivity))).isFalse();
    assertThat(consumeTimeoutEventually(Duration.ofSeconds(15))).isEqualTo(1);
    assertThat(projectionRemaining(redisActivity)).isEqualTo(1);
    redis.delete(projections.key(redisActivity));
    sendImmediateTimeout(SeckillTimeoutMessage.from(redisOrder));
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
    var pending =
        reservationService.preAdmit(
            "cb061-marker-race-user",
            markerActivity,
            "cb061-projection-marker-race-pending",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    assertThat(pending.decision().state()).isEqualTo(ReservationState.ADMITTED);
    assertThat(pending.handoffPending()).isTrue();
    assertThat(reservationRepository.find(pending.handoff().reservationId())).isEmpty();
    assertThat(projectionRemaining(markerActivity)).isZero();
    forceOrderDueIn(cancelledReservation, Duration.ofSeconds(-1));
    SeckillOrderRepository.OrderRecord markerOrder =
        orderRepository.findByReservation(cancelledReservation).orElseThrow();
    assertThatThrownBy(() -> cancellationService.cancel(SeckillTimeoutMessage.from(markerOrder)))
        .isInstanceOf(ReservationAdmissionStore.AdmissionIndeterminateException.class)
        .hasMessageContaining("Pending admission handoff");
    assertThat(orderStatus(cancelledReservation)).isEqualTo("UNPAID");
    assertThat(cancellationMovementCount(cancelledReservation)).isZero();
    assertThat(projectionRemaining(markerActivity)).isZero();
    coordinator.recover(pending.handoff());
    assertThat(reservationService.hasPendingAdmissionHandoff(markerActivity)).isFalse();
    assertThat(cancellationService.cancel(SeckillTimeoutMessage.from(markerOrder)).outcome())
        .isEqualTo(SeckillCancellationService.Outcome.CANCELLED);
    assertThat(projectionRemaining(markerActivity)).isEqualTo(1);
    assertThat(cancellationMovementCount(cancelledReservation)).isEqualTo(1);
    assertThat(
            coordinator
                .submit(
                    "cb061-marker-race-third-user",
                    markerActivity,
                    "cb061-projection-marker-race-third",
                    request(Map.of("quantity", 1, "expectedActivityVersion", 2)))
                .state())
        .isEqualTo(ReservationState.ADMITTED);
    assertThat(projectionRemaining(markerActivity)).isZero();
    ReservationResult exhausted =
        coordinator.submit(
            "cb061-marker-race-fourth-user",
            markerActivity,
            "cb061-projection-marker-race-fourth",
            request(Map.of("quantity", 1, "expectedActivityVersion", 2)));
    assertThat(exhausted.decisionCode()).isEqualTo(ReservationDecisionCode.EXHAUSTED);
    assertThat(reservationRepository.find(exhausted.reservationId())).isEmpty();
    for (String reservationId :
        jdbc.queryForList(
            "SELECT reservation_id FROM seckill_reservation WHERE activity_id = ? AND state = 'ADMITTED'",
            String.class,
            markerActivity)) {
      consumeUntilOrdered(reservationId);
      assertThat(orderCreateMovementCount(reservationId)).isEqualTo(1);
    }

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
            admissionStore,
            (activity, remainingQuota) -> {
              durableCommitReached.countDown();
              try {
                if (!allowProjection.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("Timed out waiting to resume projection");
                }
              } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Projection wait was interrupted", exception);
              }
              projections.publish(activity, remainingQuota);
            },
            concurrentTransactions,
            Clock.systemUTC());
    CompletableFuture<SeckillCancellationService.CancellationResult> cancellation =
        CompletableFuture.supplyAsync(
            () -> pausedProjection.cancel(SeckillTimeoutMessage.from(gapOrder)));
    assertThat(durableCommitReached.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(orderStatus(gapReservation)).isEqualTo("CANCELLED");
    assertThat(redis.hasKey(projections.key(gapActivity))).isFalse();
    assertThatThrownBy(
            () ->
                reservationService.preAdmit(
                    "cb061-commit-gap-user",
                    gapActivity,
                    "cb061-projection-commit-gap-pending",
                    request(Map.of("quantity", 1, "expectedActivityVersion", 2))))
        .isInstanceOf(ReservationAdmissionStore.AdmissionIndeterminateException.class)
        .hasMessageContaining("rebuild is active");
    assertThat(reservationService.hasPendingAdmissionHandoff(gapActivity)).isFalse();
    allowProjection.countDown();
    assertThat(cancellation.get(10, TimeUnit.SECONDS).outcome())
        .isEqualTo(SeckillCancellationService.Outcome.CANCELLED);
    assertThat(projectionRemaining(gapActivity)).isEqualTo(2);
    ReservationAdmissionStore.PreAdmission afterGap =
        reservationService.preAdmit(
            "cb061-commit-gap-user",
            gapActivity,
            "cb061-projection-commit-gap-pending",
            request(Map.of("quantity", 1, "expectedActivityVersion", 2)));
    assertThat(afterGap.decision().state()).isEqualTo(ReservationState.ADMITTED);
    assertThat(projectionRemaining(gapActivity)).isEqualTo(1);
    assertThat(reservationService.persistAdmitted(afterGap.handoff()).state())
        .isEqualTo(ReservationState.ADMITTED);
    reservationService.completeAdmissionHandoff(afterGap.handoff());
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
            2);
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

  @Test
  @Order(11)
  void realBrokerConsumersRejectReservedEvaluationContextOnProductionOnlyPaths() throws Exception {
    SeckillOrderService untouchedOrderHandler = mock(SeckillOrderService.class);
    SeckillCancellationService untouchedTimeoutHandler = mock(SeckillCancellationService.class);
    SeckillReservation reservation =
        reservationRepository
            .findByIdempotencyForShare(USER, "cb060-commit", "cb060-key-commit")
            .orElseThrow();
    int orderMovementsBefore = orderCreateMovementCount(reservation.reservationId());
    try (Producer producer = producer(new AtomicInteger())) {
      Transaction transaction = producer.beginTransaction();
      producer.send(
          ClientServiceProvider.loadService()
              .newMessageBuilder()
              .setTopic(required("ROCKETMQ_TRANSACTION_TOPIC"))
              .setTag(RocketMqSeckillTransactions.TAG)
              .setKeys(reservation.reservationId())
              .addProperty(
                  RocketMqSeckillTransactions.RESERVED_SANDBOX_PROPERTY,
                  "sandbox-must-not-be-accepted")
              .setBody(objectMapper.writeValueAsBytes(SeckillTransactionMessage.from(reservation)))
              .build(),
          transaction);
      transaction.commit();
    }
    awaitProductionOnlyRejection(
        () -> messaging.consumeOnce(untouchedOrderHandler),
        "Production seckill transaction cannot carry evaluation context");
    verifyNoInteractions(untouchedOrderHandler);
    assertThat(orderCreateMovementCount(reservation.reservationId()))
        .isEqualTo(orderMovementsBefore);

    SeckillOrderRepository.OrderRecord order =
        orderRepository.findByReservation(reservation.reservationId()).orElseThrow();
    sendImmediateTimeout(SeckillTimeoutMessage.from(order), true);
    awaitProductionOnlyRejection(
        () -> timeoutMessaging.consumeOnce(untouchedTimeoutHandler),
        "Production seckill timeout cannot carry evaluation context");
    verifyNoInteractions(untouchedTimeoutHandler);
    assertThat(orderStatus(reservation.reservationId())).isEqualTo("UNPAID");
    assertThat(cancellationMovementCount(reservation.reservationId())).isZero();
  }

  @Test
  @Order(12)
  void scheduledRecoveryPersistsAHandoffCreatedBeforeRocketMqWasReached() {
    String activityId = "cb061-redis-first-recovery";
    seedActivity(
        activityId, "cb061-product-redis-first-recovery", SeckillActivityState.ACTIVE, 2, 2);
    ReservationAdmissionStore.PreAdmission admission =
        reservationService.preAdmit(
            "cb061-recovery-user",
            activityId,
            "cb061-recovery-key",
            request(Map.of("quantity", 1, "expectedActivityVersion", 1)));
    assertThat(admission.handoffPending()).isTrue();
    assertThat(reservationRepository.find(admission.handoff().reservationId())).isEmpty();
    redis
        .opsForZSet()
        .add(ReservationAdmissionStore.HANDOFF_INDEX, admission.handoff().reservationId(), 0);

    resolutionWorker.resolveDueReservations();

    assertThat(
            reservationRepository.find(admission.handoff().reservationId()).orElseThrow().state())
        .isEqualTo(ReservationState.ADMITTED);
    assertThat(redis.hasKey(admissionStore.handoffKey(admission.handoff().reservationId())))
        .isFalse();
    assertThat(reservationService.hasPendingAdmissionHandoff(activityId)).isFalse();
  }

  private void recoverHandoff(ReservationAdmissionStore.AdmissionHandoff handoff) {
    redis.opsForZSet().add(ReservationAdmissionStore.HANDOFF_INDEX, handoff.reservationId(), 0);
    resolutionWorker.resolveDueReservations();
    assertThat(reservationRepository.find(handoff.reservationId()).orElseThrow().state())
        .isEqualTo(ReservationState.ADMITTED);
    assertThat(redis.hasKey(admissionStore.handoffKey(handoff.reservationId()))).isFalse();
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

  private int orderCreateMovementCount(String reservationId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM inventory_ledger WHERE reservation_id = ? "
            + "AND movement_type = 'SECKILL_ORDER_CREATE'",
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

  private int observeEarlyTimeoutEventually(
      SeckillCancellationService observedCancellation, AtomicInteger earlyDeliveries)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline && earlyDeliveries.get() == 0) {
      assertThat(timeoutMessaging.consumeOnce(observedCancellation)).isZero();
    }
    return earlyDeliveries.get();
  }

  private void sendImmediateTimeout(SeckillTimeoutMessage payload) throws Exception {
    sendImmediateTimeout(payload, false);
  }

  private void sendImmediateTimeout(SeckillTimeoutMessage payload, boolean reservedSandbox)
      throws Exception {
    try (Producer producer = plainProducer(required("ROCKETMQ_TIMEOUT_TOPIC"))) {
      var builder =
          ClientServiceProvider.loadService()
              .newMessageBuilder()
              .setTopic(required("ROCKETMQ_TIMEOUT_TOPIC"))
              .setTag(RocketMqSeckillTimeouts.TAG)
              .setKeys(payload.eventId())
              .setDeliveryTimestamp(System.currentTimeMillis())
              .setBody(objectMapper.writeValueAsBytes(payload));
      if (reservedSandbox) {
        builder.addProperty(
            RocketMqSeckillTransactions.RESERVED_SANDBOX_PROPERTY, "sandbox-must-not-be-accepted");
      }
      producer.send(builder.build());
    }
  }

  private void awaitProductionOnlyRejection(CheckedConsume consume, String message)
      throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        assertThat(consume.once()).isZero();
      } catch (IllegalArgumentException exception) {
        assertThat(exception).hasMessage(message);
        return;
      }
    }
    throw new AssertionError("Reserved evaluation context was not delivered by the real Broker");
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
    seedActivityForProduct(activityId, productId, state, quota);
  }

  private void seedActivityForProduct(
      String activityId, String productId, SeckillActivityState state, long quota) {
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

  private int consumeUntilOrdered(String reservationId) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    int consumed = 0;
    while (orderRepository.findByReservation(reservationId).isEmpty()
        && System.nanoTime() < deadline) {
      consumed += messaging.consumeOnce(orderService);
    }
    assertThat(orderRepository.findByReservation(reservationId)).isPresent();
    return consumed;
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
                return reservationService.transactionResolution(view.getKeys().iterator().next());
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

  @FunctionalInterface
  private interface CheckedConsume {
    int once() throws Exception;
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
