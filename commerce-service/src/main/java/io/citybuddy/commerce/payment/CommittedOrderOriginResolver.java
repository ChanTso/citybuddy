package io.citybuddy.commerce.payment;

import io.citybuddy.commerce.order.StandardOrderIntentCommitment;
import java.util.List;

/** Validates the immutable origin commitment carried inside the committed-payment order face. */
final class CommittedOrderOriginResolver {
  private final MockPaymentRepository repository;

  CommittedOrderOriginResolver(MockPaymentRepository repository) {
    this.repository = repository;
  }

  OrderOriginCommitment resolve(MockPaymentRepository.OrderTruth order) {
    if ("STANDARD".equals(order.orderKind())) {
      if (order.sandboxId() != null) {
        return resolveEvaluationStandard(order);
      }
      return resolveProductionStandard(order);
    }
    if ("SECKILL".equals(order.orderKind())) {
      return resolveSeckill(order);
    }
    throw inconsistent("Payment order kind has no origin commitment");
  }

  private OrderOriginCommitment resolveProductionStandard(MockPaymentRepository.OrderTruth order) {
    EvaluationPaymentCommittedFaces.OrderOriginDefinition definition =
        EvaluationPaymentCommittedFaces.orderOriginDefinition(
            EvaluationPaymentCommittedFaces.OrderOriginValidator.STANDARD_ORDER_INTENT_HASH);
    if (definition.validator()
            != EvaluationPaymentCommittedFaces.OrderOriginValidator.STANDARD_ORDER_INTENT_HASH
        || !definition.canonicalizerId().equals(StandardOrderIntentCommitment.CANONICALIZER_ID)) {
      throw new IllegalStateException("Standard order origin metadata is not executable");
    }
    List<MockPaymentRepository.StandardOrderOriginRecord> origins =
        repository.enumerateStandardOrderOrigin(order.orderId());
    if (origins.size() != 1
        || order.quantity() == null
        || order.productVersion() == null
        || order.quantity() < 1
        || order.productVersion() < 1
        || !totalMatchesUnitPrice(order)) {
      throw inconsistent("Standard order origin commitment is inconsistent");
    }
    MockPaymentRepository.StandardOrderOriginRecord origin = origins.getFirst();
    String expectedIntent =
        StandardOrderIntentCommitment.hash(
            order.productId(), Math.toIntExact(order.quantity()), order.productVersion());
    if (!origin.orderId().equals(order.orderId())
        || !origin.userSubject().equals(order.userSubject())
        || !origin.intentHash().equals(expectedIntent)) {
      throw inconsistent("Standard order origin commitment is inconsistent");
    }
    return new StandardOrderHashCommitment(
        origin.userSubject(),
        origin.orderId(),
        origin.intentHash(),
        StandardOrderIntentCommitment.CANONICALIZER_ID);
  }

  private OrderOriginCommitment resolveEvaluationStandard(MockPaymentRepository.OrderTruth order) {
    if (order.quantity() == null
        || order.productVersion() == null
        || order.quantity() < 1
        || order.quantity() > 100
        || order.productVersion() != 1
        || !totalMatchesUnitPrice(order)) {
      throw inconsistent("Evaluation order origin commitment is inconsistent");
    }
    // Reset product rows are staging data and are deleted on normal completion. The committed
    // order projection remains the evaluation origin so exact replay survives fixture cleanup.
    return new EvaluationStandardOrderRoot(
        order.sandboxId(),
        order.orderId(),
        order.productId(),
        order.quantity(),
        order.productVersion());
  }

  private OrderOriginCommitment resolveSeckill(MockPaymentRepository.OrderTruth order) {
    EvaluationPaymentCommittedFaces.OrderOriginDefinition activityDefinition =
        EvaluationPaymentCommittedFaces.orderOriginDefinition(
            EvaluationPaymentCommittedFaces.OrderOriginValidator.SECKILL_ACTIVITY_PRODUCT);
    EvaluationPaymentCommittedFaces.OrderOriginDefinition reservationDefinition =
        EvaluationPaymentCommittedFaces.orderOriginDefinition(
            EvaluationPaymentCommittedFaces.OrderOriginValidator.SECKILL_RESERVATION_RELATION);
    if (activityDefinition.scope()
            != EvaluationPaymentCommittedFaces.OrderOriginScope.PRODUCTION_SECKILL
        || reservationDefinition.scope()
            != EvaluationPaymentCommittedFaces.OrderOriginScope.PRODUCTION_SECKILL) {
      throw new IllegalStateException("Seckill order origin metadata is not executable");
    }
    if (order.quantity() == null || order.quantity() < 1 || !totalMatchesUnitPrice(order)) {
      throw inconsistent("Seckill order origin commitment is inconsistent");
    }
    List<MockPaymentRepository.SeckillActivityOriginRecord> activities =
        repository.enumerateSeckillActivityOrigin(order.activityId());
    List<MockPaymentRepository.SeckillReservationOriginRecord> reservations =
        repository.enumerateSeckillReservationOrigin(order.reservationId());
    if (activities.size() != 1 || reservations.size() != 1) {
      throw inconsistent("Seckill order origin commitment is inconsistent");
    }
    MockPaymentRepository.SeckillActivityOriginRecord activity = activities.getFirst();
    MockPaymentRepository.SeckillReservationOriginRecord reservation = reservations.getFirst();
    if (!activity.activityId().equals(order.activityId())
        || !activity.productId().equals(order.productId())
        || !reservation.reservationId().equals(order.reservationId())
        || !reservation.userSubject().equals(order.userSubject())
        || !reservation.activityId().equals(order.activityId())
        || reservation.quantity() != order.quantity()
        || !"ORDERED".equals(reservation.state())
        || !reservation.orderId().equals(order.orderId())) {
      throw inconsistent("Seckill order origin commitment is inconsistent");
    }
    return new SeckillOriginCommitment(
        activity.activityId(),
        activity.productId(),
        reservation.reservationId(),
        reservation.userSubject(),
        reservation.quantity(),
        reservation.orderId());
  }

  private static boolean totalMatchesUnitPrice(MockPaymentRepository.OrderTruth order) {
    if (order.unitPriceMinor() < 1 || order.quantity() == null || order.quantity() < 1) {
      return false;
    }
    try {
      return Math.multiplyExact(order.unitPriceMinor(), order.quantity()) == order.amountMinor();
    } catch (ArithmeticException exception) {
      return false;
    }
  }

  private static CommittedPaymentIntegrityException inconsistent(String message) {
    return new CommittedPaymentIntegrityException(message);
  }

  sealed interface OrderOriginCommitment
      permits StandardOrderHashCommitment, EvaluationStandardOrderRoot, SeckillOriginCommitment {}

  record StandardOrderHashCommitment(
      String userSubject, String orderId, String intentHash, String canonicalizerId)
      implements OrderOriginCommitment {}

  record EvaluationStandardOrderRoot(
      String sandboxId, String orderId, String productId, Long quantity, Long productVersion)
      implements OrderOriginCommitment {}

  record SeckillOriginCommitment(
      String activityId,
      String productId,
      String reservationId,
      String userSubject,
      long quantity,
      String orderId)
      implements OrderOriginCommitment {}
}
