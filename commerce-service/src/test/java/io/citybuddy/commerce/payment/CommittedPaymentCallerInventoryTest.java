package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.citybuddy.commerce.payment.CommittedPaymentTruthResolver.CommittedPaymentCaller;
import io.citybuddy.commerce.payment.CommittedPaymentTruthResolver.RefundAccumulatorPolicy;
import org.junit.jupiter.api.Test;

class CommittedPaymentCallerInventoryTest {
  @Test
  void terminalSuccessAndMutationCallersFormOneClosedInventory() {
    assertThat(CommittedPaymentCaller.values())
        .extracting(Enum::name)
        .containsExactly(
            "PAYMENT_START_REPLAY",
            "PRODUCTION_CALLBACK_REPLAY",
            "EVALUATION_CALLBACK_REPLAY",
            "DIRECT_REFUND_ELIGIBILITY",
            "ACTION_PREPARE_CONFIRM_AND_RECEIPT_REPLAY",
            "REFUND_LIFECYCLE",
            "REFUND_RECONCILIATION",
            "EVALUATION_STATE",
            "EVALUATION_AUDIT");
    assertThat(CommittedPaymentCaller.values())
        .extracting(CommittedPaymentCaller::surface)
        .doesNotHaveDuplicates()
        .allSatisfy(surface -> assertThat(surface).isNotBlank());
    assertThat(CommittedPaymentCaller.values())
        .extracting(CommittedPaymentCaller::resolverMethod)
        .allSatisfy(method -> assertThat(method).isNotBlank());
    assertThat(CommittedPaymentCaller.values())
        .extracting(CommittedPaymentCaller::successInputType)
        .allSatisfy(
            type -> assertThat(type).isIn("PaymentStartReplayResolution", "CommittedPaymentTruth"));
    assertThat(CommittedPaymentCaller.values())
        .allSatisfy(
            caller -> {
              assertThat(caller.trustBoundary()).isNotNull();
              assertThat(caller.canonicalRequestLocators()).isNotEmpty().doesNotContainNull();
              assertThat(caller.ownershipVisibilityLocators()).isNotEmpty().doesNotContainNull();
              assertThat(caller.concealedResponseFamily()).isNotBlank();
            });
    assertThat(CommittedPaymentCaller.PAYMENT_START_REPLAY.committedBeforeLiveness()).isTrue();
    assertThat(CommittedPaymentCaller.PRODUCTION_CALLBACK_REPLAY.committedBeforeLiveness())
        .isTrue();
    assertThat(CommittedPaymentCaller.EVALUATION_CALLBACK_REPLAY.committedBeforeLiveness())
        .isTrue();
    assertThat(
            CommittedPaymentCaller.ACTION_PREPARE_CONFIRM_AND_RECEIPT_REPLAY
                .committedBeforeLiveness())
        .isTrue();
    assertThat(CommittedPaymentCaller.DIRECT_REFUND_ELIGIBILITY.committedBeforeLiveness())
        .isFalse();
    assertThat(CommittedPaymentCaller.REFUND_RECONCILIATION.refundAccumulatorPolicy())
        .isEqualTo(RefundAccumulatorPolicy.RECONCILIATION_DERIVED);
    assertThat(CommittedPaymentCaller.values())
        .filteredOn(caller -> caller != CommittedPaymentCaller.REFUND_RECONCILIATION)
        .extracting(CommittedPaymentCaller::refundAccumulatorPolicy)
        .containsOnly(RefundAccumulatorPolicy.EXACT_LEDGER_SUM);
  }
}
