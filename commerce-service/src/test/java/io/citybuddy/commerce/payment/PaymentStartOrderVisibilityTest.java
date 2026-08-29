package io.citybuddy.commerce.payment;

import static org.assertj.core.api.Assertions.assertThat;

import io.citybuddy.commerce.evaluation.EvaluationSandboxRepository;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentStartOrderVisibilityTest {
  private static final String USER = "payment-owner";
  private static final String SANDBOX = "sandbox-payment";
  private static final String ORDER_ID = "00000000-0000-0000-0000-000000000120";
  private static final String HANDLE = "A".repeat(43);
  private static final String FIXTURE_OWNER = EvaluationSandboxRepository.fixtureOwner(HANDLE);

  @Test
  void directOwnerNeverDependsOnTheFixtureHandle() {
    assertThat(classify(USER, SANDBOX, null))
        .isInstanceOf(PaymentStartOrderVisibility.DirectOwner.class);
    assertThat(classify(USER, SANDBOX, "short"))
        .isInstanceOf(PaymentStartOrderVisibility.DirectOwner.class);
    assertThat(classify(USER, SANDBOX, "A".repeat(44)))
        .isInstanceOf(PaymentStartOrderVisibility.DirectOwner.class);
  }

  @Test
  void validSelfConsistentFixtureProducesTheOnlyBindingProof() {
    assertThat(classify(FIXTURE_OWNER, SANDBOX, HANDLE))
        .isInstanceOfSatisfying(
            PaymentStartOrderVisibility.BindableFixture.class,
            visible -> {
              var proof = visible.bindingProof().orElseThrow();
              assertThat(proof.ownerHandle()).isEqualTo(HANDLE);
              assertThat(proof.existingFixtureOwnerSubject()).isEqualTo(FIXTURE_OWNER);
              assertThat(proof.orderId()).isEqualTo(ORDER_ID);
              assertThat(proof.sandboxId()).isEqualTo(SANDBOX);
            });
  }

  @Test
  void sameSandboxFixtureIsVisibleOnlyToThePrincipalWhoseSignedHandleMatches() {
    MockPaymentRepository.OrderTruth order = order(FIXTURE_OWNER, SANDBOX, HANDLE);

    assertThat(PaymentStartOrderVisibility.classify(order, USER, SANDBOX, "B".repeat(43)))
        .isInstanceOf(PaymentStartOrderVisibility.Concealed.class);
    assertThat(PaymentStartOrderVisibility.classify(order, USER, SANDBOX, null))
        .isInstanceOf(PaymentStartOrderVisibility.Concealed.class);
    assertThat(PaymentStartOrderVisibility.classify(order, USER, SANDBOX, HANDLE))
        .isInstanceOf(PaymentStartOrderVisibility.BindableFixture.class);
  }

  @Test
  void everyUnprovableOwnershipShapeIsConcealedWithoutAnException() {
    assertThat(classify(FIXTURE_OWNER, SANDBOX, null))
        .isInstanceOf(PaymentStartOrderVisibility.Concealed.class);
    assertThat(classify(FIXTURE_OWNER, SANDBOX, "A".repeat(42)))
        .isInstanceOf(PaymentStartOrderVisibility.Concealed.class);
    assertThat(classify(FIXTURE_OWNER, SANDBOX, "A".repeat(44)))
        .isInstanceOf(PaymentStartOrderVisibility.Concealed.class);
    assertThat(classify("damaged-owner", SANDBOX, HANDLE))
        .isInstanceOf(PaymentStartOrderVisibility.Concealed.class);
    assertThat(classify(FIXTURE_OWNER, "sandbox-other", HANDLE))
        .isInstanceOf(PaymentStartOrderVisibility.Concealed.class);
  }

  @Test
  void classifierColumnInventoryIsTheFiniteLocatorInputSet() {
    assertThat(PaymentStartOrderVisibility.CLASSIFIED_COLUMNS)
        .isEqualTo(Set.of("order_id", "sandbox_id", "user_subject", "evaluation_owner_handle"));
  }

  private static PaymentStartOrderVisibility.Classification classify(
      String storedOwner, String storedSandbox, String handle) {
    return PaymentStartOrderVisibility.classify(
        order(storedOwner, storedSandbox, handle), USER, SANDBOX, handle);
  }

  private static MockPaymentRepository.OrderTruth order(
      String storedOwner, String storedSandbox, String handle) {
    return new MockPaymentRepository.OrderTruth(
        "STANDARD",
        ORDER_ID,
        storedOwner,
        storedSandbox,
        handle,
        "payment-product",
        null,
        null,
        null,
        null,
        1800,
        "AUD",
        "UNPAID",
        1);
  }
}
