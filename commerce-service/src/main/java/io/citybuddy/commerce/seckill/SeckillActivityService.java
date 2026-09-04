package io.citybuddy.commerce.seckill;

import java.time.Instant;
import org.springframework.transaction.support.TransactionTemplate;

public final class SeckillActivityService {
  private final SeckillActivityRepository repository;
  private final SeckillReservationRepository reservationRepository;
  private final ActivityProjectionPublisher projectionPublisher;
  private final ReservationAdmissionStore admissionStore;
  private final TransactionTemplate transactions;

  public SeckillActivityService(
      SeckillActivityRepository repository,
      SeckillReservationRepository reservationRepository,
      SeckillProjectionStore projectionStore,
      ReservationAdmissionStore admissionStore,
      TransactionTemplate transactions) {
    this(repository, reservationRepository, projectionStore::publish, admissionStore, transactions);
  }

  SeckillActivityService(
      SeckillActivityRepository repository,
      SeckillReservationRepository reservationRepository,
      ActivityProjectionPublisher projectionPublisher,
      ReservationAdmissionStore admissionStore,
      TransactionTemplate transactions) {
    this.repository = repository;
    this.reservationRepository = reservationRepository;
    this.projectionPublisher = projectionPublisher;
    this.admissionStore = admissionStore;
    this.transactions = transactions;
  }

  public AllocationResult create(CreateActivity command) {
    validate(command);
    String fence = admissionStore.acquireRebuild(command.activityId());
    try {
      rejectPendingHandoff(command.activityId());
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
      return new AllocationResult(
          committed, projectionPublisher.publish(committed, committed.allocatedQuota()));
    } finally {
      admissionStore.releaseRebuild(command.activityId(), fence);
    }
  }

  public AllocationResult changeAllocation(String activityId, long allocatedQuota) {
    validateIdentity(activityId, "Activity id");
    SeckillLuaNumber.requirePositiveExact(allocatedQuota, "Allocated quota");
    String fence = admissionStore.acquireRebuild(activityId);
    try {
      rejectPendingHandoff(activityId);
      String suspended = admissionStore.suspendProjection(activityId, fence);
      final ProjectionTruth committed;
      try {
        committed =
            requireProjectionTruth(
                transactions.execute(
                    status -> {
                      SeckillActivity current =
                          repository
                              .findForUpdate(activityId)
                              .orElseThrow(
                                  () ->
                                      new IllegalArgumentException("Seckill activity is missing"));
                      if (current.state() == SeckillActivityState.CLOSED) {
                        throw new IllegalStateException(
                            "Closed seckill activity allocation is immutable");
                      }
                      if (reservationRepository.hasPendingForActivity(activityId)) {
                        throw new IllegalStateException(
                            "Seckill activity has an indeterminate pending reservation");
                      }
                      long admitted = reservationRepository.admittedQuantity(activityId);
                      if (allocatedQuota < admitted) {
                        throw new IllegalArgumentException(
                            "Allocated quota is below authoritative admitted reservations");
                      }
                      long inventory = authoritativeInventory(current.productId());
                      rejectOverAllocation(allocatedQuota, inventory);
                      if (current.projectionVersion() >= SeckillLuaNumber.MAX_EXACT_INTEGER) {
                        throw new IllegalStateException(
                            "Seckill activity projection version cannot be incremented safely");
                      }
                      return new ProjectionTruth(
                          repository.updateAllocation(current, allocatedQuota),
                          allocatedQuota - admitted);
                    }));
      } catch (IllegalArgumentException | IllegalStateException exception) {
        admissionStore.restoreSuspendedProjection(activityId, fence, suspended);
        throw exception;
      }
      return new AllocationResult(
          committed.activity(),
          projectionPublisher.publish(committed.activity(), committed.remainingQuota()));
    } finally {
      admissionStore.releaseRebuild(activityId, fence);
    }
  }

  public AllocationResult rebuildProjection(String activityId) {
    validateIdentity(activityId, "Activity id");
    String fence = admissionStore.acquireRebuild(activityId);
    try {
      rejectPendingHandoff(activityId);
      ProjectionTruth truth =
          requireProjectionTruth(
              transactions.execute(
                  status -> {
                    SeckillActivity activity =
                        repository
                            .findForUpdate(activityId)
                            .orElseThrow(
                                () -> new IllegalArgumentException("Seckill activity is missing"));
                    if (reservationRepository.hasPendingForActivity(activityId)) {
                      throw new IllegalStateException(
                          "Seckill activity has an indeterminate pending reservation");
                    }
                    long admitted = reservationRepository.admittedQuantity(activityId);
                    if (admitted > activity.allocatedQuota()) {
                      throw new IllegalStateException(
                          "Admitted reservations exceed authoritative activity allocation");
                    }
                    return new ProjectionTruth(activity, activity.allocatedQuota() - admitted);
                  }));
      return new AllocationResult(
          truth.activity(), projectionPublisher.publish(truth.activity(), truth.remainingQuota()));
    } finally {
      admissionStore.releaseRebuild(activityId, fence);
    }
  }

  private void rejectPendingHandoff(String activityId) {
    if (admissionStore.hasPendingHandoff(activityId)) {
      throw new IllegalStateException("Seckill activity has an indeterminate admission handoff");
    }
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
    SeckillLuaNumber.requirePositiveExact(command.allocatedQuota(), "Allocated quota");
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

  private static ProjectionTruth requireProjectionTruth(ProjectionTruth truth) {
    if (truth == null) {
      throw new IllegalStateException("Seckill projection transaction returned no result");
    }
    return truth;
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

  @FunctionalInterface
  interface ActivityProjectionPublisher {
    SeckillProjectionStore.PublishResult publish(SeckillActivity activity, long remainingQuota);
  }

  private record ProjectionTruth(SeckillActivity activity, long remainingQuota) {}
}
