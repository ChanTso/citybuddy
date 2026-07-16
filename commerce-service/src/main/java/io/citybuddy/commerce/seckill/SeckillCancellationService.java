package io.citybuddy.commerce.seckill;

import java.time.Clock;
import java.time.Duration;
import org.springframework.transaction.support.TransactionTemplate;

public final class SeckillCancellationService {
  private static final Duration MINIMUM_EARLY_RETRY = Duration.ofSeconds(10);
  private static final Duration MAXIMUM_EARLY_RETRY = Duration.ofHours(12);

  private final SeckillOrderRepository orders;
  private final SeckillReservationRepository reservations;
  private final SeckillActivityRepository activities;
  private final QuotaRestorationPublisher projections;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public SeckillCancellationService(
      SeckillOrderRepository orders,
      SeckillReservationRepository reservations,
      SeckillActivityRepository activities,
      QuotaRestorationPublisher projections,
      TransactionTemplate transactions,
      Clock clock) {
    this.orders = orders;
    this.reservations = reservations;
    this.activities = activities;
    this.projections = projections;
    this.transactions = transactions;
    this.clock = clock;
  }

  public CancellationResult cancel(SeckillTimeoutMessage message) {
    CancellationTruth truth = transactions.execute(status -> cancelOnce(requireMessage(message)));
    if (truth == null) {
      throw new IllegalStateException("Seckill cancellation transaction returned no result");
    }
    if (truth.activity() != null) {
      projections.restore(
          truth.activity(), truth.targetProjectionVersion(), truth.restoredQuantity());
    }
    return new CancellationResult(truth.outcome(), truth.retryAfter());
  }

  private CancellationTruth cancelOnce(SeckillTimeoutMessage message) {
    SeckillOrderRepository.OrderRecord order = orders.findForUpdate(message.orderId()).orElse(null);
    if (order == null || !matchesIdentity(message, order)) {
      return CancellationTruth.of(Outcome.STALE);
    }
    if ("CANCELLED".equals(order.status())) {
      return existingCancellation(order);
    }
    if (!"UNPAID".equals(order.status())
        || order.stateVersion() != message.expectedVersion()
        || !order.status().equals(message.expectedState())) {
      return CancellationTruth.of(Outcome.FINAL_PRESERVED);
    }
    if (clock.instant().isBefore(order.unpaidDeadline())) {
      Duration untilDue = Duration.between(clock.instant(), order.unpaidDeadline());
      return new CancellationTruth(Outcome.EARLY, null, 0, 0, boundedRetry(untilDue));
    }

    SeckillReservation reservation =
        reservations
            .findForUpdate(order.reservationId())
            .orElseThrow(() -> new IllegalStateException("Ordered reservation truth is missing"));
    if (reservation.state() != ReservationState.ORDERED
        || !order.orderId().equals(reservation.orderId())) {
      throw new IllegalStateException("Unpaid order conflicts with reservation truth");
    }
    SeckillActivity activity =
        activities
            .findForUpdate(order.activityId())
            .orElseThrow(() -> new IllegalStateException("Seckill activity truth is missing"));
    SeckillOrderRepository.ProductSnapshot product =
        orders
            .findProductForUpdate(order.productId())
            .orElseThrow(() -> new IllegalStateException("Seckill product truth is missing"));
    Math.addExact(product.stockQuantity(), order.quantity());
    if (activity.projectionVersion() >= SeckillLuaNumber.MAX_EXACT_INTEGER) {
      throw new IllegalStateException(
          "Seckill activity projection version cannot be incremented safely");
    }

    orders.restoreInventory(product, order.quantity());
    orders.insertUnpaidCancellationMovement(order);
    SeckillActivity advanced = activities.advanceProjectionVersion(activity);
    orders.markCancelled(order, advanced.projectionVersion());
    reservations.markCancelled(reservation);
    return new CancellationTruth(
        Outcome.CANCELLED, advanced, advanced.projectionVersion(), order.quantity(), Duration.ZERO);
  }

  private CancellationTruth existingCancellation(SeckillOrderRepository.OrderRecord order) {
    SeckillReservation reservation =
        reservations
            .findForUpdate(order.reservationId())
            .orElseThrow(() -> new IllegalStateException("Cancelled reservation truth is missing"));
    if (reservation.state() != ReservationState.CANCELLED
        || !order.orderId().equals(reservation.orderId())
        || !orders.hasUnpaidCancellationMovement(order.orderId())
        || order.cancellationProjectionVersion() == null) {
      throw new IllegalStateException("Cancelled order restoration truth is incomplete");
    }
    SeckillActivity activity =
        activities
            .findForUpdate(order.activityId())
            .orElseThrow(() -> new IllegalStateException("Seckill activity truth is missing"));
    return new CancellationTruth(
        Outcome.ALREADY_CANCELLED,
        activity,
        order.cancellationProjectionVersion(),
        order.quantity(),
        Duration.ZERO);
  }

  private static SeckillTimeoutMessage requireMessage(SeckillTimeoutMessage message) {
    if (message == null
        || !hasText(message.eventId(), 36)
        || !hasText(message.orderId(), 36)
        || !hasText(message.reservationId(), 36)
        || !"UNPAID".equals(message.expectedState())
        || message.expectedVersion() != 1
        || message.dueAt() == null
        || !hasText(message.correlationId(), 36)) {
      throw new IllegalArgumentException("Seckill timeout message is invalid");
    }
    return message;
  }

  private static boolean hasText(String value, int maximumLength) {
    return value != null
        && !value.isBlank()
        && value.length() <= maximumLength
        && value.equals(value.strip());
  }

  private static boolean matchesIdentity(
      SeckillTimeoutMessage message, SeckillOrderRepository.OrderRecord order) {
    return message.eventId().equals(order.timeoutEventId())
        && message.orderId().equals(order.orderId())
        && message.reservationId().equals(order.reservationId())
        && message.dueAt().equals(order.unpaidDeadline())
        && message.correlationId().equals(order.transactionEventId());
  }

  private static Duration boundedRetry(Duration untilDue) {
    if (untilDue.compareTo(MINIMUM_EARLY_RETRY) < 0) {
      return MINIMUM_EARLY_RETRY;
    }
    if (untilDue.compareTo(MAXIMUM_EARLY_RETRY) > 0) {
      return MAXIMUM_EARLY_RETRY;
    }
    return untilDue;
  }

  public enum Outcome {
    CANCELLED,
    ALREADY_CANCELLED,
    EARLY,
    STALE,
    FINAL_PRESERVED
  }

  public record CancellationResult(Outcome outcome, Duration retryAfter) {}

  @FunctionalInterface
  public interface QuotaRestorationPublisher {
    void restore(SeckillActivity activity, long targetProjectionVersion, long restoredQuantity);
  }

  private record CancellationTruth(
      Outcome outcome,
      SeckillActivity activity,
      long targetProjectionVersion,
      long restoredQuantity,
      Duration retryAfter) {
    static CancellationTruth of(Outcome outcome) {
      return new CancellationTruth(outcome, null, 0, 0, Duration.ZERO);
    }
  }
}
