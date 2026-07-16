package io.citybuddy.commerce.seckill;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

public final class SeckillOrderService {
  private final SeckillReservationRepository reservations;
  private final SeckillActivityRepository activities;
  private final SeckillOrderRepository orders;
  private final SeckillOrderProperties properties;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public SeckillOrderService(
      SeckillReservationRepository reservations,
      SeckillActivityRepository activities,
      SeckillOrderRepository orders,
      SeckillOrderProperties properties,
      TransactionTemplate transactions,
      Clock clock) {
    this.reservations = reservations;
    this.activities = activities;
    this.orders = orders;
    this.properties = properties;
    this.transactions = transactions;
    this.clock = clock;
  }

  public SeckillOrderRepository.OrderRecord create(SeckillTransactionMessage message) {
    validateMessage(message);
    SeckillOrderRepository.OrderRecord result = transactions.execute(status -> createOnce(message));
    if (result == null) {
      throw new IllegalStateException("Seckill order transaction returned no result");
    }
    return result;
  }

  private SeckillOrderRepository.OrderRecord createOnce(SeckillTransactionMessage message) {
    SeckillReservation reservation =
        reservations
            .findForUpdate(message.reservationId())
            .orElseThrow(() -> new IllegalStateException("Committed reservation is missing"));
    requireMessageMatchesTruth(message, reservation);
    if (reservation.state() == ReservationState.ORDERED) {
      return requireExistingOrder(reservation);
    }
    if (reservation.state() != ReservationState.ADMITTED) {
      throw new IllegalStateException("Committed message has no admitted reservation truth");
    }

    var existingByReservation = orders.findByReservation(reservation.reservationId());
    var existingByUser =
        orders.findByActivityUser(reservation.activityId(), reservation.userSubject());
    if (existingByReservation.isPresent() || existingByUser.isPresent()) {
      throw new IllegalStateException("Seckill order exists without its atomic reservation state");
    }

    SeckillActivity activity =
        activities
            .findForUpdate(reservation.activityId())
            .orElseThrow(() -> new IllegalStateException("Seckill activity truth is missing"));
    SeckillOrderRepository.ProductSnapshot product =
        orders
            .findProductForUpdate(activity.productId())
            .orElseThrow(() -> new IllegalStateException("Seckill product truth is missing"));
    if (!"PUBLISHED".equals(product.publicationState()) || !product.available()) {
      throw new IllegalStateException("Seckill product is not orderable");
    }
    if (product.stockQuantity() < reservation.quantity()) {
      throw new IllegalStateException(
          "Authoritative inventory cannot satisfy admitted reservation");
    }

    String orderId = UUID.randomUUID().toString();
    String timeoutEventId =
        UUID.nameUUIDFromBytes(
                ("seckill-timeout:" + reservation.reservationId()).getBytes(StandardCharsets.UTF_8))
            .toString();
    Instant unpaidDeadline = clock.instant().plus(properties.unpaidTimeout());
    long totalPrice = Math.multiplyExact(product.priceMinor(), reservation.quantity());
    var order =
        new SeckillOrderRepository.OrderRecord(
            orderId,
            reservation.reservationId(),
            message.eventId(),
            timeoutEventId,
            reservation.userSubject(),
            reservation.activityId(),
            product.productId(),
            product.name(),
            product.priceMinor(),
            product.currency(),
            reservation.quantity(),
            totalPrice,
            unpaidDeadline);
    if (!orders.decrementInventory(product, reservation.quantity())) {
      throw new IllegalStateException("Authoritative inventory changed during order creation");
    }
    orders.insertOrder(order);
    orders.insertOrderCreateMovement(order);
    reservations.markOrdered(reservation, orderId);
    return order;
  }

  private SeckillOrderRepository.OrderRecord requireExistingOrder(SeckillReservation reservation) {
    SeckillOrderRepository.OrderRecord order =
        orders
            .findByReservation(reservation.reservationId())
            .orElseThrow(
                () -> new IllegalStateException("Ordered reservation has no durable order"));
    if (!order.orderId().equals(reservation.orderId())
        || !order.userSubject().equals(reservation.userSubject())
        || !order.activityId().equals(reservation.activityId())) {
      throw new IllegalStateException("Ordered reservation conflicts with durable order truth");
    }
    return order;
  }

  private static void requireMessageMatchesTruth(
      SeckillTransactionMessage message, SeckillReservation reservation) {
    if (!message.eventId().equals(reservation.reservationId())
        || !message.reservationId().equals(reservation.reservationId())
        || !message.activityId().equals(reservation.activityId())
        || !message.userSubject().equals(reservation.userSubject())
        || message.quantity() != reservation.quantity()
        || message.activityProjectionVersion() != reservation.activityProjectionVersion()) {
      throw new IllegalStateException("Transaction message conflicts with MySQL reservation truth");
    }
  }

  private static void validateMessage(SeckillTransactionMessage message) {
    if (message == null
        || !hasText(message.eventId(), 36)
        || !hasText(message.reservationId(), 36)
        || !hasText(message.activityId(), 64)
        || !hasText(message.userSubject(), 128)
        || message.quantity() < 1
        || message.activityProjectionVersion() < 1) {
      throw new IllegalArgumentException("Seckill transaction message is invalid");
    }
  }

  private static boolean hasText(String value, int maximumLength) {
    return value != null
        && !value.isBlank()
        && value.length() <= maximumLength
        && value.equals(value.strip());
  }
}
