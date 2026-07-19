package io.citybuddy.commerce.evaluation;

import java.time.Clock;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

public class EvaluationViewService {
  private static final List<String> CAPABILITIES =
      List.of("commerce-audit-v1", "commerce-state-v1", "commerce-version-v1");

  private final EvaluationViewRepository repository;
  private final Clock clock;
  private final VersionView version;

  public EvaluationViewService(
      EvaluationViewRepository repository, EvaluationSandboxProperties properties, Clock clock) {
    this.repository = repository;
    this.clock = clock;
    repository.validateSchema();
    version = new VersionView(properties.buildId(), properties.schemaCompatibility(), CAPABILITIES);
  }

  @Transactional(readOnly = true)
  public StateView state(String sandboxId) {
    EvaluationViewRepository.SandboxView sandbox = observableSandbox(sandboxId);
    List<EvaluationViewRepository.ProductView> products = repository.products(sandboxId);
    List<EvaluationViewRepository.EffectView> effects = repository.effects(sandboxId);
    List<EvaluationViewRepository.PaymentView> payments = repository.payments(sandboxId);
    if (!repository.paymentAuditReferencesConsistent(sandboxId)) {
      throw new EvaluationSandboxException(409, "Evaluation payment truth is inconsistent");
    }
    if ("ACTIVE".equals(sandbox.lifecycleState()) && products.size() != sandbox.fixtureCount()) {
      throw new EvaluationSandboxException(409, "Evaluation sandbox truth is inconsistent");
    }
    for (EvaluationViewRepository.PaymentView payment : payments) {
      boolean pending =
          "PENDING".equals(payment.state())
              && payment.stateVersion() == 1
              && payment.callbackEventId() == null
              && "UNPAID".equals(payment.orderStatus())
              && payment.orderStateVersion() == 1
              && payment.movementCount() == 0;
      boolean succeeded =
          "SUCCEEDED".equals(payment.state())
              && payment.stateVersion() == 2
              && isUuid(payment.callbackEventId())
              && "PAID".equals(payment.orderStatus())
              && payment.orderStateVersion() == 2
              && payment.movementCount() == 1;
      if (!pending && !succeeded) {
        throw new EvaluationSandboxException(409, "Evaluation payment truth is inconsistent");
      }
    }
    return new StateView(sandbox, products, effects, payments);
  }

  @Transactional(readOnly = true)
  public AuditPage audit(
      String sandboxId,
      String supportSessionId,
      EvaluationViewRequestParser.AuditPageRequest page) {
    observableSandbox(sandboxId);
    if (!repository.paymentAuditReferencesConsistent(sandboxId)) {
      throw new EvaluationSandboxException(409, "Evaluation audit truth is inconsistent");
    }
    List<EvaluationViewRepository.AuditReference> fetched =
        repository.audit(sandboxId, supportSessionId, page.after(), page.limit() + 1);
    if (fetched.isEmpty()) {
      throw new EvaluationSandboxException(404, "Evaluation audit not found");
    }
    boolean more = fetched.size() > page.limit();
    List<EvaluationViewRepository.AuditReference> entries =
        List.copyOf(fetched.subList(0, Math.min(fetched.size(), page.limit())));
    for (EvaluationViewRepository.AuditReference reference : entries) {
      boolean product =
          "PRODUCT_FIXTURE".equals(reference.entityType())
              && repository.productVersionExists(
                  sandboxId, reference.entityId(), reference.entityVersion());
      boolean payment =
          "PAYMENT_CALLBACK".equals(reference.entityType())
              && repository.paymentCallbackVersionExists(
                  sandboxId,
                  reference.supportSessionId(),
                  reference.traceId(),
                  reference.operationId(),
                  reference.entityId(),
                  reference.entityVersion());
      if (!sandboxId.equals(reference.sandboxId())
          || !supportSessionId.equals(reference.supportSessionId())
          || (!product && !payment)) {
        throw new EvaluationSandboxException(409, "Evaluation audit truth is inconsistent");
      }
    }
    Long nextCursor = more ? entries.getLast().sequence() : null;
    return new AuditPage(entries, nextCursor);
  }

  public VersionView version() {
    return version;
  }

  private EvaluationViewRepository.SandboxView observableSandbox(String sandboxId) {
    EvaluationViewRepository.SandboxView sandbox =
        repository
            .sandbox(sandboxId)
            .orElseThrow(() -> new EvaluationSandboxException(404, "Evaluation sandbox not found"));
    if ("ACTIVE".equals(sandbox.lifecycleState())
        && (sandbox.expiresAt() == null || !sandbox.expiresAt().isAfter(clock.instant()))) {
      throw new EvaluationSandboxException(409, "Evaluation sandbox truth is inconsistent");
    }
    if (!"ACTIVE".equals(sandbox.lifecycleState()) && !"DEAD".equals(sandbox.lifecycleState())) {
      throw new EvaluationSandboxException(409, "Evaluation sandbox is not observable");
    }
    return sandbox;
  }

  private static boolean isUuid(String value) {
    try {
      return value != null && java.util.UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  public record StateView(
      EvaluationViewRepository.SandboxView sandbox,
      List<EvaluationViewRepository.ProductView> products,
      List<EvaluationViewRepository.EffectView> effects,
      List<EvaluationViewRepository.PaymentView> payments) {}

  public record AuditPage(List<EvaluationViewRepository.AuditReference> entries, Long nextCursor) {}

  public record VersionView(
      String buildId, String schemaCompatibility, List<String> capabilities) {}
}
