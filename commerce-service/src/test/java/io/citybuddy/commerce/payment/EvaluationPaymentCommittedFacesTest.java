package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

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
}
