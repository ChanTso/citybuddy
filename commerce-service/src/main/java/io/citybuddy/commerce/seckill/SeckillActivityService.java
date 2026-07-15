package io.citybuddy.commerce.seckill;

import java.time.Instant;
import org.springframework.transaction.support.TransactionTemplate;

public final class SeckillActivityService {
  private final SeckillActivityRepository repository;
  private final SeckillProjectionStore projectionStore;
  private final TransactionTemplate transactions;

  public SeckillActivityService(
      SeckillActivityRepository repository,
      SeckillProjectionStore projectionStore,
      TransactionTemplate transactions) {
    this.repository = repository;
    this.projectionStore = projectionStore;
    this.transactions = transactions;
  }

  public AllocationResult create(CreateActivity command) {
    validate(command);
    SeckillActivity committed =
        requireResult(
            transactions.execute(
                status -> {
                  long inventory = authoritativeInventory(command.productId());
                  rejectOverAllocation(command.allocatedQuota(), inventory);
                  SeckillActivity activity =
                      new SeckillActivity(
                          command.activityId(),
                          command.productId(),
                          command.startsAt(),
                          command.endsAt(),
                          command.state(),
                          command.allocatedQuota(),
                          1);
                  repository.insert(activity);
                  return repository
                      .findForUpdate(activity.activityId())
                      .orElseThrow(
                          () ->
                              new IllegalStateException(
                                  "Inserted seckill activity truth is missing"));
                }));
    return new AllocationResult(committed, projectionStore.publish(committed));
  }

  public AllocationResult changeAllocation(String activityId, long allocatedQuota) {
    validateIdentity(activityId, "Activity id");
    if (allocatedQuota < 1) {
      throw new IllegalArgumentException("Allocated quota must be positive");
    }
    SeckillActivity committed =
        requireResult(
            transactions.execute(
                status -> {
                  SeckillActivity current =
                      repository
                          .findForUpdate(activityId)
                          .orElseThrow(
                              () -> new IllegalArgumentException("Seckill activity is missing"));
                  if (current.state() == SeckillActivityState.CLOSED) {
                    throw new IllegalStateException(
                        "Closed seckill activity allocation is immutable");
                  }
                  long inventory = authoritativeInventory(current.productId());
                  rejectOverAllocation(allocatedQuota, inventory);
                  return repository.updateAllocation(current, allocatedQuota);
                }));
    return new AllocationResult(committed, projectionStore.publish(committed));
  }

  public AllocationResult rebuildProjection(String activityId) {
    validateIdentity(activityId, "Activity id");
    SeckillActivity truth =
        repository
            .find(activityId)
            .orElseThrow(() -> new IllegalArgumentException("Seckill activity is missing"));
    return new AllocationResult(truth, projectionStore.publish(truth));
  }

  private long authoritativeInventory(String productId) {
    return repository
        .lockProductInventory(productId)
        .orElseThrow(() -> new IllegalArgumentException("Authoritative product is missing"));
  }

  private static void rejectOverAllocation(long allocatedQuota, long inventory) {
    if (allocatedQuota > inventory) {
      throw new IllegalArgumentException("Allocated quota exceeds authoritative inventory");
    }
  }

  private static void validate(CreateActivity command) {
    if (command == null) {
      throw new IllegalArgumentException("Seckill activity is required");
    }
    validateIdentity(command.activityId(), "Activity id");
    validateIdentity(command.productId(), "Product id");
    if (command.startsAt() == null
        || command.endsAt() == null
        || !command.startsAt().isBefore(command.endsAt())) {
      throw new IllegalArgumentException("Seckill activity window is invalid");
    }
    if (command.state() == null) {
      throw new IllegalArgumentException("Seckill activity state is required");
    }
    if (command.allocatedQuota() < 1) {
      throw new IllegalArgumentException("Allocated quota must be positive");
    }
  }

  private static void validateIdentity(String value, String label) {
    if (value == null || value.isBlank() || value.length() > 64 || !value.equals(value.strip())) {
      throw new IllegalArgumentException(label + " is invalid");
    }
  }

  private static SeckillActivity requireResult(SeckillActivity activity) {
    if (activity == null) {
      throw new IllegalStateException("Seckill activity transaction returned no result");
    }
    return activity;
  }

  public record CreateActivity(
      String activityId,
      String productId,
      Instant startsAt,
      Instant endsAt,
      SeckillActivityState state,
      long allocatedQuota) {}

  public record AllocationResult(
      SeckillActivity activity, SeckillProjectionStore.PublishResult projectionResult) {}
}
