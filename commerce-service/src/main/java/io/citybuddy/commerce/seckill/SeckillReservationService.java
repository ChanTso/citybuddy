package io.citybuddy.commerce.seckill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

public final class SeckillReservationService {
  private static final Set<String> OWNER_FIELDS =
      Set.of("owner", "ownersubject", "user", "userid", "usersubject");

  private final SeckillReservationRepository repository;
  private final SeckillActivityRepository activityRepository;
  private final ReservationAdmissionStore admissionStore;
  private final TransactionTemplate transactions;
  private final SeckillReservationProperties properties;

  public SeckillReservationService(
      SeckillReservationRepository repository,
      SeckillActivityRepository activityRepository,
      ReservationAdmissionStore admissionStore,
      TransactionTemplate transactions,
      SeckillReservationProperties properties) {
    this.repository = repository;
    this.activityRepository = activityRepository;
    this.admissionStore = admissionStore;
    this.transactions = transactions;
    this.properties = properties;
  }

  public ReservationResult reserve(
      String userSubject, String activityId, String idempotencyKey, ReservationRequest request) {
    PreparedReservation intentReservation =
        prepare(userSubject, activityId, idempotencyKey, request);
    SeckillReservation reservation = intentReservation.reservation();
    if (reservation.state() != ReservationState.PENDING) {
      return ReservationResult.from(reservation, true);
    }

    return admit(reservation.reservationId());
  }

  public ReservationAdmissionStore.PreAdmission preAdmit(
      String userSubject, String activityId, String idempotencyKey, ReservationRequest request) {
    ValidatedIntent intent = validate(userSubject, activityId, idempotencyKey, request);
    var handoff =
        new ReservationAdmissionStore.AdmissionHandoff(
            UUID.randomUUID().toString(),
            userSubject,
            activityId,
            idempotencyKey,
            intent.intentHash(),
            intent.quantity(),
            intent.expectedActivityVersion());
    return admissionStore.preAdmit(handoff, sha256(userSubject));
  }

  public ReservationResult preAdmissionResult(ReservationAdmissionStore.PreAdmission admission) {
    return new ReservationResult(
        admission.handoff().reservationId(),
        admission.handoff().activityId(),
        admission.handoff().quantity(),
        admission.handoff().activityProjectionVersion(),
        admission.decision().state(),
        admission.decision().decisionCode(),
        2,
        admission.replay(),
        false,
        null);
  }

  public ReservationResult persistAdmitted(ReservationAdmissionStore.AdmissionHandoff handoff) {
    SeckillReservation admitted =
        requireReservation(transactions.execute(status -> persistAdmittedOnce(handoff)));
    return ReservationResult.from(admitted, false);
  }

  public List<ReservationAdmissionStore.AdmissionHandoff> dueAdmissionHandoffs(int batchSize) {
    return admissionStore.dueHandoffs(batchSize);
  }

  public void completeAdmissionHandoff(ReservationAdmissionStore.AdmissionHandoff handoff) {
    admissionStore.completeHandoff(handoff);
  }

  public boolean hasPendingAdmissionHandoff(String activityId) {
    return admissionStore.hasPendingHandoff(activityId);
  }

  public PreparedReservation prepare(
      String userSubject, String activityId, String idempotencyKey, ReservationRequest request) {
    ValidatedIntent intent = validate(userSubject, activityId, idempotencyKey, request);
    if (repository.hasBlockingAdmissionTruthForDifferentIntent(
        userSubject, activityId, idempotencyKey)) {
      rebuildActivityState(activityId);
    }
    return requirePreparedReservation(
        transactions.execute(
            status -> reserveIntent(userSubject, activityId, idempotencyKey, intent)));
  }

  public ReservationResult admit(String reservationId) {
    SeckillReservation reservation =
        repository
            .find(reservationId)
            .orElseThrow(() -> new IllegalArgumentException("Reservation is missing"));
    if (reservation.state() != ReservationState.PENDING) {
      return ReservationResult.from(reservation, true);
    }

    SeckillActivity activity =
        activityRepository
            .find(reservation.activityId())
            .orElseThrow(() -> new IllegalStateException("Reservation activity truth is missing"));
    ReservationAdmissionStore.AdmissionDecision decision =
        admissionStore.decide(reservation, activity, sha256(reservation.userSubject()));
    SeckillReservation decided =
        requireReservation(
            transactions.execute(status -> persistDecision(reservation.reservationId(), decision)));
    return ReservationResult.from(decided, false);
  }

  public ReservationResult pollOwned(String userSubject, String reservationId) {
    validateIdentity(userSubject, 128, "Reservation owner");
    validateIdentity(reservationId, 36, "Reservation id");
    var projected = admissionStore.readOwned(reservationId, sha256(userSubject));
    if (projected.isPresent()) {
      var value = projected.orElseThrow();
      if (value.state() == ReservationState.REJECTED) {
        return new ReservationResult(
            value.reservationId(),
            value.activityId(),
            value.quantity(),
            value.activityProjectionVersion(),
            value.state(),
            value.decisionCode(),
            2,
            true,
            false,
            null);
      }
      if (value.handoffPending()) {
        return new ReservationResult(
            value.reservationId(),
            value.activityId(),
            value.quantity(),
            value.activityProjectionVersion(),
            ReservationState.PENDING,
            null,
            1,
            true,
            false,
            null);
      }
    }
    SeckillReservation reservation =
        repository
            .findOwned(userSubject, reservationId)
            .orElseThrow(() -> new IllegalArgumentException("Reservation is missing or not owned"));
    return ReservationResult.from(reservation, true);
  }

  public TransactionResolution transactionResolution(String reservationId) {
    try {
      return repository
          .find(reservationId)
          .map(
              reservation ->
                  switch (reservation.state()) {
                    case REJECTED -> TransactionResolution.ROLLBACK;
                    case ADMITTED, ORDERED, CANCELLED, UNFULFILLED -> TransactionResolution.COMMIT;
                    case PENDING -> TransactionResolution.UNKNOWN;
                  })
          .orElse(TransactionResolution.UNKNOWN);
    } catch (DataAccessException exception) {
      return TransactionResolution.UNKNOWN;
    }
  }

  public ReservationAdmissionStore.RebuildResult rebuildActivityState(String activityId) {
    validateIdentity(activityId, 64, "Activity id");
    String lockToken = admissionStore.acquireRebuild(activityId);
    try {
      if (admissionStore.hasPendingHandoff(activityId)) {
        throw new ReservationAdmissionStore.AdmissionIndeterminateException(
            "Pending admission handoff prevents projection rebuild");
      }
      RebuildTruth truth =
          requireRebuildTruth(
              transactions.execute(
                  status -> {
                    SeckillActivity activity =
                        activityRepository
                            .findForUpdate(activityId)
                            .orElseThrow(
                                () -> new IllegalArgumentException("Seckill activity is missing"));
                    List<SeckillReservation> reservations =
                        repository.findAllForActivityForUpdate(activityId);
                    if (reservations.stream()
                        .anyMatch(reservation -> reservation.state() == ReservationState.PENDING)) {
                      throw new ReservationAdmissionStore.AdmissionIndeterminateException(
                          "Pending reservation prevents projection rebuild");
                    }
                    long admitted =
                        reservations.stream()
                            .filter(reservation -> consumesActivityQuota(reservation.state()))
                            .mapToLong(SeckillReservation::quantity)
                            .sum();
                    if (admitted > activity.allocatedQuota()) {
                      throw new IllegalStateException(
                          "Admitted reservations exceed authoritative activity allocation");
                    }
                    return new RebuildTruth(
                        activity, List.copyOf(reservations), activity.allocatedQuota() - admitted);
                  }));
      return admissionStore.rebuild(
          truth.activity(), truth.reservations(), truth.remainingQuota(), lockToken);
    } finally {
      admissionStore.releaseRebuild(activityId, lockToken);
    }
  }

  public int resolveDueReservations(int batchSize) {
    List<SeckillReservation> due = repository.findDuePending(batchSize);
    for (SeckillReservation reservation : due) {
      ReservationAdmissionStore.AdmissionDecision decision =
          admissionStore.resolveDeadline(reservation, sha256(reservation.userSubject()));
      requireReservation(
          transactions.execute(status -> persistDecision(reservation.reservationId(), decision)));
    }
    return due.size();
  }

  private PreparedReservation reserveIntent(
      String userSubject, String activityId, String idempotencyKey, ValidatedIntent intent) {
    activityRepository
        .findForShare(activityId)
        .orElseThrow(() -> new IllegalArgumentException("Seckill activity is missing"));
    SeckillReservation pending =
        new SeckillReservation(
            UUID.randomUUID().toString(),
            userSubject,
            activityId,
            idempotencyKey,
            intent.intentHash(),
            intent.quantity(),
            intent.expectedActivityVersion(),
            ReservationState.PENDING,
            null,
            1);
    // Insert first and let the unique key decide whether this is a replay. Reading the
    // idempotency row before it exists takes a gap lock, and the insert that follows needs an
    // insert-intention lock in that same gap; concurrent activities then deadlock on the shared
    // index gap even though they touch unrelated activity rows.
    try {
      repository.reservePending(pending, properties.minimumBrokerCoverage());
    } catch (DuplicateKeyException duplicate) {
      SeckillReservation existing =
          repository
              .findByIdempotencyForShare(userSubject, activityId, idempotencyKey)
              .orElseThrow(
                  () -> new IllegalStateException("Duplicate reservation truth is missing"));
      if (!existing.intentHash().equals(intent.intentHash())) {
        throw new IllegalStateException(
            "Idempotency key is bound to a conflicting reservation intent");
      }
      return new PreparedReservation(existing, true);
    }
    SeckillReservation persisted =
        repository
            .findForUpdate(pending.reservationId())
            .orElseThrow(() -> new IllegalStateException("Inserted reservation truth is missing"));
    return new PreparedReservation(persisted, false);
  }

  private SeckillReservation persistDecision(
      String reservationId, ReservationAdmissionStore.AdmissionDecision decision) {
    SeckillReservation current =
        repository
            .findForUpdate(reservationId)
            .orElseThrow(() -> new IllegalStateException("Reservation truth is missing"));
    if (current.state() != ReservationState.PENDING) {
      if (current.state() == ReservationState.ORDERED
          || current.state() == ReservationState.CANCELLED
          || current.state() == ReservationState.UNFULFILLED) {
        if (decision.state() == ReservationState.ADMITTED
            && decision.decisionCode() == ReservationDecisionCode.ADMITTED
            && current.decisionCode() == ReservationDecisionCode.ADMITTED
            && (current.projectionVersion() == 3 || current.projectionVersion() == 4)) {
          return current;
        }
      }
      if (current.state() != decision.state()
          || current.decisionCode() != decision.decisionCode()
          || current.projectionVersion() != 2) {
        throw new IllegalStateException("Reservation decision conflicts with MySQL truth");
      }
      return current;
    }
    return repository.applyDecision(current, decision.state(), decision.decisionCode());
  }

  private SeckillReservation persistAdmittedOnce(
      ReservationAdmissionStore.AdmissionHandoff handoff) {
    SeckillActivity activity =
        activityRepository
            .findForShare(handoff.activityId())
            .orElseThrow(() -> new IllegalStateException("Reservation activity truth is missing"));
    if (activity.projectionVersion() != handoff.activityProjectionVersion()) {
      throw new IllegalStateException("Admission handoff conflicts with activity truth");
    }
    SeckillReservation admitted =
        new SeckillReservation(
            handoff.reservationId(),
            handoff.userSubject(),
            handoff.activityId(),
            handoff.idempotencyKey(),
            handoff.intentHash(),
            handoff.quantity(),
            handoff.activityProjectionVersion(),
            ReservationState.ADMITTED,
            ReservationDecisionCode.ADMITTED,
            2);
    try {
      repository.reserveAdmitted(admitted, properties.minimumBrokerCoverage());
      return repository
          .findForUpdate(admitted.reservationId())
          .orElseThrow(() -> new IllegalStateException("Inserted reservation truth is missing"));
    } catch (DuplicateKeyException duplicate) {
      SeckillReservation existing =
          repository
              .findByIdempotencyForShare(
                  handoff.userSubject(), handoff.activityId(), handoff.idempotencyKey())
              .orElseThrow(
                  () -> new IllegalStateException("Duplicate reservation truth is missing"));
      if (!sameAdmission(existing, admitted)) {
        throw new IllegalStateException("Admission handoff conflicts with MySQL truth");
      }
      return existing;
    }
  }

  private static boolean sameAdmission(SeckillReservation existing, SeckillReservation admitted) {
    return existing.reservationId().equals(admitted.reservationId())
        && existing.userSubject().equals(admitted.userSubject())
        && existing.activityId().equals(admitted.activityId())
        && existing.idempotencyKey().equals(admitted.idempotencyKey())
        && existing.intentHash().equals(admitted.intentHash())
        && existing.quantity() == admitted.quantity()
        && existing.activityProjectionVersion() == admitted.activityProjectionVersion()
        && existing.decisionCode() == ReservationDecisionCode.ADMITTED
        && (existing.state() == ReservationState.ADMITTED
            || existing.state() == ReservationState.ORDERED
            || existing.state() == ReservationState.CANCELLED
            || existing.state() == ReservationState.UNFULFILLED);
  }

  private static ValidatedIntent validate(
      String userSubject, String activityId, String idempotencyKey, ReservationRequest request) {
    validateIdentity(userSubject, 128, "Reservation owner");
    validateIdentity(activityId, 64, "Activity id");
    validateIdentity(idempotencyKey, 128, "Idempotency key");
    if (request == null) {
      throw new IllegalArgumentException("Reservation body is required");
    }
    if (request.extraFields().keySet().stream()
        .map(value -> value.toLowerCase(Locale.ROOT))
        .anyMatch(OWNER_FIELDS::contains)) {
      throw new IllegalArgumentException(
          "Reservation owner is derived from authenticated identity");
    }
    if (!request.extraFields().isEmpty()) {
      throw new IllegalArgumentException("Reservation body contains unsupported fields");
    }
    Integer quantity = request.getQuantity();
    Long version = request.getExpectedActivityVersion();
    if (quantity == null
        || quantity < 1
        || version == null
        || version < 1
        || version > SeckillLuaNumber.MAX_EXACT_INTEGER) {
      throw new IllegalArgumentException("Reservation request is invalid");
    }
    String canonical = activityId.length() + ":" + activityId + ":" + quantity + ":" + version;
    return new ValidatedIntent(quantity, version, sha256(canonical));
  }

  private static void validateIdentity(String value, int maximumLength, String label) {
    if (value == null
        || value.isBlank()
        || value.length() > maximumLength
        || !value.equals(value.strip())) {
      throw new IllegalArgumentException(label + " is invalid");
    }
  }

  static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  static boolean blocksAnotherAdmission(ReservationState state) {
    return state == ReservationState.ADMITTED
        || state == ReservationState.ORDERED
        || state == ReservationState.CANCELLED
        || state == ReservationState.UNFULFILLED;
  }

  static boolean consumesActivityQuota(ReservationState state) {
    return state == ReservationState.ADMITTED
        || state == ReservationState.ORDERED
        || state == ReservationState.UNFULFILLED;
  }

  private static PreparedReservation requirePreparedReservation(PreparedReservation reservation) {
    if (reservation == null) {
      throw new IllegalStateException("Reservation intent transaction returned no result");
    }
    return reservation;
  }

  private static SeckillReservation requireReservation(SeckillReservation reservation) {
    if (reservation == null) {
      throw new IllegalStateException("Reservation decision transaction returned no result");
    }
    return reservation;
  }

  private static RebuildTruth requireRebuildTruth(RebuildTruth truth) {
    if (truth == null) {
      throw new IllegalStateException("Reservation rebuild transaction returned no result");
    }
    return truth;
  }

  private record ValidatedIntent(int quantity, long expectedActivityVersion, String intentHash) {}

  public record PreparedReservation(SeckillReservation reservation, boolean existing) {}

  private record RebuildTruth(
      SeckillActivity activity, List<SeckillReservation> reservations, long remainingQuota) {}
}
