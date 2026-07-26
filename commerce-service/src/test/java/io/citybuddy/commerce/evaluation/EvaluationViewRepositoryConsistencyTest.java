package io.citybuddy.commerce.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvaluationViewRepositoryConsistencyTest {
  private static final Instant CREATED_AT = Instant.parse("2026-07-27T00:00:00Z");

  @Test
  void ambiguousOrNonPaymentDamageCannotAcquirePaymentAttribution() {
    assertThat(EvaluationViewRepository.classifyAuditConsistency(false, false))
        .isEqualTo(EvaluationViewRepository.AuditConsistency.NON_PAYMENT_AUDIT_TRUTH_INCONSISTENT);
    assertThat(EvaluationViewRepository.classifyAuditConsistency(false, true))
        .isEqualTo(EvaluationViewRepository.AuditConsistency.PAYMENT_TRUTH_INCONSISTENT);
  }

  @Test
  void nonPaymentDamageRemainsAttributedWhenPaymentTruthIsComplete() {
    assertThat(EvaluationViewRepository.classifyAuditConsistency(true, false))
        .isEqualTo(EvaluationViewRepository.AuditConsistency.NON_PAYMENT_AUDIT_TRUTH_INCONSISTENT);
  }

  @Test
  void completePaymentAndOtherAuditTruthIsConsistent() {
    assertThat(EvaluationViewRepository.classifyAuditConsistency(true, true))
        .isEqualTo(EvaluationViewRepository.AuditConsistency.CONSISTENT);
  }

  @Test
  void productOriginDoesNotTrustForgedPaymentDiscriminator() {
    var product = productTruth();
    var reference =
        reference(
            product.observationId(),
            product.sandboxId(),
            product.supportSessionId(),
            product.traceId(),
            product.operationId(),
            "PAYMENT_CALLBACK",
            product.productId(),
            product.productVersion());

    assertThat(classify(reference, List.of(product), List.of()))
        .isEqualTo(EvaluationViewRepository.AuditOrigin.PRODUCT_OBSERVATION);
  }

  @Test
  void paymentOriginDoesNotTrustForgedProductDiscriminator() {
    var callback = callbackTruth();
    var reference =
        reference(
            EvaluationAuditReferenceIdentity.paymentCallback(
                callback.sandboxId(),
                callback.supportSessionId(),
                callback.traceId(),
                callback.operationId(),
                callback.callbackEventId(),
                callback.attemptStateVersion()),
            callback.sandboxId(),
            callback.supportSessionId(),
            callback.traceId(),
            callback.operationId(),
            "PRODUCT_FIXTURE",
            callback.callbackEventId(),
            callback.attemptStateVersion());

    assertThat(classify(reference, List.of(), List.of(callback)))
        .isEqualTo(EvaluationViewRepository.AuditOrigin.PAYMENT_CALLBACK);
  }

  @Test
  void callbackEventAndContextKeepPaymentOriginWhenAttemptFaceIsMissing() {
    var callback = callbackTruthWithAttemptStateVersion(0);
    var reference =
        reference(
            "damaged-canonical-reference",
            callback.sandboxId(),
            callback.supportSessionId(),
            callback.traceId(),
            callback.operationId(),
            "PRODUCT_FIXTURE",
            callback.callbackEventId(),
            0);

    assertThat(classify(reference, List.of(), List.of(callback)))
        .isEqualTo(EvaluationViewRepository.AuditOrigin.PAYMENT_CALLBACK);
  }

  @Test
  void orphanAndCrossFamilyCollisionNeverAcquirePaymentOrigin() {
    var orphan =
        reference(
            "orphan-reference",
            "sandbox-orphan",
            "session-orphan",
            "trace-orphan",
            "operation-orphan",
            "PAYMENT_CALLBACK",
            "orphan-entity",
            1);
    assertThat(classify(orphan, List.of(productTruth()), List.of(callbackTruth())))
        .isEqualTo(EvaluationViewRepository.AuditOrigin.AMBIGUOUS_OR_ORPHAN);

    var product = productTruth();
    var callback = callbackTruth();
    var collision =
        reference(
            product.observationId(),
            product.sandboxId(),
            product.supportSessionId(),
            product.traceId(),
            product.operationId(),
            "PAYMENT_CALLBACK",
            callback.callbackEventId(),
            product.productVersion());
    assertThat(classify(collision, List.of(product), List.of(callback)))
        .isEqualTo(EvaluationViewRepository.AuditOrigin.AMBIGUOUS_OR_ORPHAN);
  }

  private static EvaluationViewRepository.AuditOrigin classify(
      EvaluationViewRepository.IntegrityAuditReference reference,
      List<EvaluationViewRepository.ProductObservationTruth> products,
      List<EvaluationViewRepository.SucceededCallbackTruth> callbacks) {
    return EvaluationViewRepository.classifyAuditOrigin(
        reference,
        products,
        callbacks,
        new EvaluationLegacyAuditCommitmentStore.Snapshot(List.of(), List.of()));
  }

  private static EvaluationViewRepository.ProductObservationTruth productTruth() {
    return new EvaluationViewRepository.ProductObservationTruth(
        "product-reference",
        "sandbox-product",
        "session-product",
        "trace-product",
        "operation-product",
        "product-1",
        1,
        "OBSERVED",
        CREATED_AT);
  }

  private static EvaluationViewRepository.SucceededCallbackTruth callbackTruth() {
    return callbackTruthWithAttemptStateVersion(2);
  }

  private static EvaluationViewRepository.SucceededCallbackTruth
      callbackTruthWithAttemptStateVersion(long attemptStateVersion) {
    return new EvaluationViewRepository.SucceededCallbackTruth(
        "callback-event",
        "callback-key",
        "attempt-1",
        "correlation-1",
        "sandbox-payment",
        "session-payment",
        "trace-payment",
        "operation-payment",
        "callback-intent",
        "SUCCEEDED",
        "APPLIED",
        CREATED_AT,
        "correlation-1",
        "owner-1",
        "order-1",
        "STANDARD",
        "sandbox-payment",
        "attempt-intent",
        1800,
        "CNY",
        0,
        "SUCCEEDED",
        attemptStateVersion,
        CREATED_AT);
  }

  private static EvaluationViewRepository.IntegrityAuditReference reference(
      String referenceId,
      String sandboxId,
      String sessionId,
      String traceId,
      String operationId,
      String entityType,
      String entityId,
      long entityVersion) {
    return new EvaluationViewRepository.IntegrityAuditReference(
        1,
        referenceId,
        sandboxId,
        sessionId,
        traceId,
        operationId,
        entityType,
        entityId,
        entityVersion,
        "OBSERVED",
        CREATED_AT,
        "BUSINESS_EVENT");
  }
}
