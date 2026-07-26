package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.citybuddy.commerce.payment.CommittedPaymentTruthResolver.CommittedPaymentCaller;
import io.citybuddy.commerce.payment.EvaluationPaymentCommittedFaces.CallerColumnRole;
import io.citybuddy.commerce.payment.EvaluationPaymentCommittedFaces.CardinalityMode;
import io.citybuddy.commerce.payment.EvaluationPaymentCommittedFaces.ContentDisposition;
import io.citybuddy.commerce.payment.EvaluationPaymentCommittedFaces.CorrelatedContentGroupId;
import io.citybuddy.commerce.payment.EvaluationPaymentCommittedFaces.OrderOriginScope;
import io.citybuddy.commerce.payment.EvaluationPaymentCommittedFaces.PaymentTruthScope;
import java.util.LinkedHashSet;
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
    assertThat(EvaluationPaymentCommittedFaces.MAXIMUM_LEDGER_CLOSURE_ROWS).isEqualTo(1024);
    assertThat(EvaluationPaymentCommittedFaces.ATTEMPT.residualColumnDispositions())
        .doesNotContainKey("request_idempotency_key");
    assertThat(EvaluationPaymentCommittedFaces.ATTEMPT.tables().get("mock_payment_attempt"))
        .contains("request_idempotency_key");
    assertThat(EvaluationPaymentCommittedFaces.ORDER.tables().get("seckill_order"))
        .contains("transaction_event_id", "quantity");
  }

  @Test
  void everyPhysicalColumnHasOneExecutableContentResponsibility() {
    assertThat(EvaluationPaymentCommittedFaces.all())
        .allSatisfy(
            face -> {
              int physicalColumnCount =
                  face.tables().values().stream().mapToInt(java.util.Collection::size).sum();
              assertThat(face.columnResponsibilities()).hasSize(physicalColumnCount);
              assertThat(face.columnResponsibilities().values())
                  .allSatisfy(
                      responsibility -> assertThat(responsibility.disposition()).isNotNull());
            });
    assertThat(
            EvaluationPaymentCommittedFaces.all().stream()
                .flatMap(face -> face.residualColumnDispositions().keySet().stream())
                .toList())
        .containsExactlyInAnyOrder("evaluation_owner_handle", "movement_id");
  }

  @Test
  void paymentEventTimeIsTheOnlyCorrelatedGroupAndDrivesBothScopes() {
    assertThat(EvaluationPaymentCommittedFaces.correlatedContentGroups())
        .singleElement()
        .satisfies(
            group -> {
              assertThat(group.id()).isEqualTo(CorrelatedContentGroupId.PAYMENT_EVENT_TIME);
              assertThat(group.membersFor(PaymentTruthScope.PRODUCTION))
                  .extracting(member -> member.column().table() + "." + member.column().column())
                  .containsExactlyInAnyOrder(
                      "mock_payment_callback.created_at", "mock_payment_attempt.succeeded_at");
              assertThat(group.membersFor(PaymentTruthScope.EVALUATION))
                  .extracting(member -> member.column().table() + "." + member.column().column())
                  .containsExactlyInAnyOrder(
                      "mock_payment_callback.created_at",
                      "mock_payment_attempt.succeeded_at",
                      "eval_commerce_audit_reference.created_at");
            });
    assertThat(
            EvaluationPaymentCommittedFaces.all().stream()
                .flatMap(face -> face.columnResponsibilities().values().stream())
                .filter(
                    responsibility ->
                        responsibility.disposition() == ContentDisposition.CORRELATED_GROUP))
        .hasSize(3)
        .allSatisfy(
            responsibility ->
                assertThat(responsibility.correlatedGroup())
                    .isEqualTo(CorrelatedContentGroupId.PAYMENT_EVENT_TIME));
  }

  @Test
  void productReplicasReachExecutableOrderOriginAnchors() {
    assertThat(EvaluationPaymentCommittedFaces.orderOriginDefinitions())
        .extracting(definition -> definition.scope())
        .containsExactlyInAnyOrder(
            OrderOriginScope.PRODUCTION_STANDARD,
            OrderOriginScope.PRODUCTION_SECKILL,
            OrderOriginScope.PRODUCTION_SECKILL);
    assertThat(
            EvaluationPaymentCommittedFaces.ORDER
                .columnResponsibilities()
                .get(new EvaluationPaymentCommittedFaces.ColumnRef("standard_order", "product_id")))
        .satisfies(
            responsibility -> {
              assertThat(responsibility.disposition())
                  .isEqualTo(ContentDisposition.ORIGIN_COMMITTED);
              assertThat(responsibility.canonicalizerId()).isEqualTo("STANDARD_ORDER_INTENT_V1");
              assertThat(responsibility.anchorBindings())
                  .filteredOn(
                      binding ->
                          binding.applicableScopes().contains(OrderOriginScope.PRODUCTION_STANDARD))
                  .extracting(binding -> binding.root().table() + "." + binding.root().column())
                  .contains("order_idempotency.intent_hash");
              assertThat(responsibility.anchorBindings())
                  .filteredOn(
                      binding ->
                          binding.applicableScopes().contains(OrderOriginScope.EVALUATION_STANDARD))
                  .extracting(binding -> binding.root().table() + "." + binding.root().column())
                  .containsExactlyInAnyOrder(
                      "standard_order.product_id",
                      "standard_order.quantity",
                      "standard_order.product_version");
            });
    assertThat(
            EvaluationPaymentCommittedFaces.ORDER
                .columnResponsibilities()
                .get(new EvaluationPaymentCommittedFaces.ColumnRef("seckill_order", "product_id")))
        .satisfies(
            responsibility -> {
              assertThat(responsibility.disposition())
                  .isEqualTo(ContentDisposition.ORIGIN_COMMITTED);
              assertThat(responsibility.anchorBindings())
                  .extracting(binding -> binding.root().table() + "." + binding.root().column())
                  .containsExactly("seckill_activity.product_id");
            });
    assertThat(
            EvaluationPaymentCommittedFaces.LEDGER
                .columnResponsibilities()
                .get(
                    new EvaluationPaymentCommittedFaces.ColumnRef(
                        "inventory_ledger", "product_id")))
        .satisfies(
            responsibility -> {
              assertThat(responsibility.disposition())
                  .isEqualTo(ContentDisposition.DERIVED_REPLICA);
              assertThat(responsibility.anchorBindings()).hasSize(2);
            });
  }

  @Test
  void anchorGraphIsClosedOverTheFiveFacesAndExecutableOrderOrigins() {
    LinkedHashSet<EvaluationPaymentCommittedFaces.ColumnRef> declared = new LinkedHashSet<>();
    EvaluationPaymentCommittedFaces.all()
        .forEach(face -> declared.addAll(face.columnResponsibilities().keySet()));
    EvaluationPaymentCommittedFaces.orderOriginDefinitions()
        .forEach(
            origin ->
                origin
                    .columns()
                    .forEach(
                        column ->
                            declared.add(
                                new EvaluationPaymentCommittedFaces.ColumnRef(
                                    origin.table(), column))));

    assertThat(
            EvaluationPaymentCommittedFaces.all().stream()
                .flatMap(face -> face.columnResponsibilities().values().stream())
                .flatMap(responsibility -> responsibility.anchorBindings().stream())
                .map(EvaluationPaymentCommittedFaces.AnchorBinding::root))
        .isNotEmpty()
        .allMatch(declared::contains);
    assertThat(EvaluationPaymentCommittedFaces.orderOriginDefinitions())
        .extracting(EvaluationPaymentCommittedFaces.OrderOriginDefinition::validator)
        .containsExactlyInAnyOrder(
            EvaluationPaymentCommittedFaces.OrderOriginValidator.STANDARD_ORDER_INTENT_HASH,
            EvaluationPaymentCommittedFaces.OrderOriginValidator.SECKILL_ACTIVITY_PRODUCT,
            EvaluationPaymentCommittedFaces.OrderOriginValidator.SECKILL_RESERVATION_RELATION);
    assertThat(
            EvaluationPaymentCommittedFaces.all().stream()
                .flatMap(face -> face.columnResponsibilities().values().stream())
                .filter(
                    responsibility ->
                        responsibility.disposition() == ContentDisposition.DERIVED_REPLICA
                            || responsibility.disposition() == ContentDisposition.ORIGIN_COMMITTED))
        .allSatisfy(responsibility -> assertThat(responsibility.anchorBindings()).isNotEmpty());
    assertThat(
            EvaluationPaymentCommittedFaces.all().stream()
                .flatMap(face -> face.columnResponsibilities().values().stream())
                .filter(
                    responsibility ->
                        responsibility.disposition() == ContentDisposition.HASH_COMMITTED))
        .allSatisfy(responsibility -> assertThat(responsibility.canonicalizerId()).isNotBlank());
  }
}
