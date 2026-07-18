package io.citybuddy.commerce.evaluation;

import java.time.Clock;

public final class EvaluationSandboxAccess {
  private final EvaluationSandboxRepository repository;
  private final Clock clock;

  public EvaluationSandboxAccess(EvaluationSandboxRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public void requireActive(String sandboxId) {
    EvaluationRequestParser.boundedHeader(sandboxId, 64, "Invalid sandbox");
    if (!repository.isActive(sandboxId, clock.instant())) {
      throw new EvaluationSandboxException(403, "Evaluation sandbox is inactive");
    }
  }
}
