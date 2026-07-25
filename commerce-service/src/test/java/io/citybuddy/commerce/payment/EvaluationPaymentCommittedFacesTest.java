package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.citybuddy.commerce.payment.CommittedPaymentTruthResolver.CommittedPaymentCaller;
import io.citybuddy.commerce.payment.EvaluationPaymentCommittedFaces.CallerColumnRole;
import io.citybuddy.commerce.payment.EvaluationPaymentCommittedFaces.CardinalityMode;
import org.junit.jupiter.api.Test;

class EvaluationPaymentCommittedFacesTest {
  @Test
  void everyEnumerationKeyDeclaresItsExecutableCardinalityEvidence() {
    assertThat(EvaluationPaymentCommittedFaces.all())
        .extracting(EvaluationPaymentCommittedFaces.FaceDefinition::name)
        .containsExactly("callback", "attempt", "order", "ledger", "audit");
    assertThat(EvaluationPaymentCommittedFaces.all())
        .allSatisfy(
            face -> {
              assertThat(face.cardinalityControls().keySet())
                  .containsExactlyInAnyOrderElementsOf(face.enumerationKeys());
              assertThat(face.cardinalityControls().values())
                  .allSatisfy(
                      control -> {
                        if (control.mode() == CardinalityMode.DATABASE_UNIQUE) {
                          assertThat(control.constraintName()).isNotBlank();
                        } else {
                          assertThat(control.mode()).isEqualTo(CardinalityMode.INSERTABLE_SIBLING);
                          assertThat(control.constraintName()).isEmpty();
                        }
                      });
            });
  }

  @Test
  void insertableSiblingInventoryMatchesTheFiniteDurableFaceMatrix() {
    assertThat(
            EvaluationPaymentCommittedFaces.all().stream()
                .flatMap(
                    face ->
                        face.cardinalityControls().values().stream()
                            .filter(control -> control.mode() == CardinalityMode.INSERTABLE_SIBLING)
                            .map(control -> face.name() + ":" + control.key()))
                .toList())
        .containsExactlyInAnyOrder(
            "callback:callback_correlation_id",
            "attempt:order_id",
            "order:order_id",
            "ledger:order_id",
            "audit:entity_id");
  }

  @Test
  void evaluationOwnerHandleHasACallerSpecificVisibilityDisposition() {
    assertThat(
            EvaluationPaymentCommittedFaces.ORDER
                .callerColumnDispositions()
                .get(CommittedPaymentCaller.PAYMENT_START_REPLAY))
        .containsExactlyInAnyOrderEntriesOf(
            java.util.Map.of(
                "order_id", CallerColumnRole.VISIBILITY_INPUT,
                "sandbox_id", CallerColumnRole.VISIBILITY_INPUT,
                "user_subject", CallerColumnRole.VISIBILITY_INPUT,
                "evaluation_owner_handle", CallerColumnRole.BINDING_PROVENANCE));
    assertThat(EvaluationPaymentCommittedFaces.ORDER.residualColumnDispositions())
        .containsKey("evaluation_owner_handle");
    assertThat(
            EvaluationPaymentCommittedFaces.ORDER.callerColumnDispositions().keySet().stream()
                .map(Enum::name))
        .containsExactly("PAYMENT_START_REPLAY");
    assertThat(
            EvaluationPaymentCommittedFaces.ORDER
                .callerColumnDispositions()
                .get(CommittedPaymentCaller.PAYMENT_START_REPLAY)
                .keySet())
        .isEqualTo(PaymentStartOrderVisibility.CLASSIFIED_COLUMNS);
  }

  @Test
  void canonicalAttemptAndSeckillCreationContentStayInsideTheCommittedClosure() {
    assertThat(EvaluationPaymentCommittedFaces.ATTEMPT.residualColumnDispositions())
        .doesNotContainKey("request_idempotency_key");
    assertThat(EvaluationPaymentCommittedFaces.ATTEMPT.tables().get("mock_payment_attempt"))
        .contains("request_idempotency_key");
    assertThat(EvaluationPaymentCommittedFaces.ORDER.tables().get("seckill_order"))
        .contains("transaction_event_id", "quantity");
  }
}
