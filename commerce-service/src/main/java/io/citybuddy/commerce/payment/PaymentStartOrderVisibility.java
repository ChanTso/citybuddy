package io.citybuddy.commerce.payment;

import io.citybuddy.commerce.evaluation.EvaluationSandboxRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class PaymentStartOrderVisibility {
  static final Set<String> CLASSIFIED_COLUMNS =
      Set.of("order_id", "sandbox_id", "user_subject", "evaluation_owner_handle");

  private PaymentStartOrderVisibility() {}

  static Classification classify(
      MockPaymentRepository.OrderTruth order,
      String userSubject,
      String sandboxId,
      String evaluationHandle) {
    Objects.requireNonNull(order, "Payment order visibility candidate is required");
    if (!Objects.equals(sandboxId, order.sandboxId())) {
      return new Concealed();
    }
    if (userSubject.equals(order.userSubject())) {
      return new DirectOwner(order);
    }
    Optional<String> fixtureOwner =
        EvaluationSandboxRepository.tryFixtureOwner(order.evaluationOwnerHandle());
    if (fixtureOwner.isEmpty() || !fixtureOwner.get().equals(order.userSubject())) {
      return new Concealed();
    }
    if (!Objects.equals(evaluationHandle, order.evaluationOwnerHandle())) {
      return new Concealed();
    }
    return new BindableFixture(
        order,
        new EvaluationOwnerBindingProof(
            order.evaluationOwnerHandle(), fixtureOwner.get(), order.orderId(), order.sandboxId()));
  }

  sealed interface Classification permits Visible, Concealed {}

  sealed interface Visible extends Classification permits DirectOwner, BindableFixture {
    MockPaymentRepository.OrderTruth order();

    Optional<EvaluationOwnerBindingProof> bindingProof();
  }

  record DirectOwner(MockPaymentRepository.OrderTruth order) implements Visible {
    @Override
    public Optional<EvaluationOwnerBindingProof> bindingProof() {
      return Optional.empty();
    }
  }

  record BindableFixture(
      MockPaymentRepository.OrderTruth order, EvaluationOwnerBindingProof requiredBinding)
      implements Visible {
    @Override
    public Optional<EvaluationOwnerBindingProof> bindingProof() {
      return Optional.of(requiredBinding);
    }
  }

  record Concealed() implements Classification {}

  static final class EvaluationOwnerBindingProof {
    private final String ownerHandle;
    private final String existingFixtureOwnerSubject;
    private final String orderId;
    private final String sandboxId;

    private EvaluationOwnerBindingProof(
        String ownerHandle, String existingFixtureOwnerSubject, String orderId, String sandboxId) {
      this.ownerHandle = ownerHandle;
      this.existingFixtureOwnerSubject = existingFixtureOwnerSubject;
      this.orderId = orderId;
      this.sandboxId = sandboxId;
    }

    String ownerHandle() {
      return ownerHandle;
    }

    String existingFixtureOwnerSubject() {
      return existingFixtureOwnerSubject;
    }

    String orderId() {
      return orderId;
    }

    String sandboxId() {
      return sandboxId;
    }
  }
}
