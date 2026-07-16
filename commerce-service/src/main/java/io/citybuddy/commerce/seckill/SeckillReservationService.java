package io.citybuddy.commerce.seckill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
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

  public PreparedReservation prepare(
      String userSubject, String activityId, String idempotencyKey, ReservationRequest request) {
    ValidatedIntent intent = validate(userSubject, activityId, idempotencyKey, request);
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
    SeckillReservation reservation =
        repository
            .findOwned(userSubject, reservationId)
            .orElseThrow(() -> new IllegalArgumentException("Reservation is missing or not owned"));
    return ReservationResult.from(reservation, true);
  }

  public ReservationAdmissionStore.RebuildResult rebuildActivityState(String activityId) {
    validateIdentity(activityId, 64, "Activity id");
    String lockToken = admissionStore.acquireRebuild(activityId);
    try {
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
                            .filter(
                                reservation ->
                                    reservation.state() == ReservationState.ADMITTED
                                        || reservation.state() == ReservationState.ORDERED)
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
        .findForUpdate(activityId)
        .orElseThrow(() -> new IllegalArgumentException("Seckill activity is missing"));
    var existing = repository.findByIdempotencyForUpdate(userSubject, activityId, idempotencyKey);
    if (existing.isPresent()) {
      if (!existing.get().intentHash().equals(intent.intentHash())) {
        throw new IllegalStateException(
            "Idempotency key is bound to a conflicting reservation intent");
      }
      return new PreparedReservation(existing.get(), true);
    }

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
    repository.reservePending(pending, properties.minimumBrokerCoverage());
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
          && decision.state() == ReservationState.ADMITTED
          && decision.decisionCode() == ReservationDecisionCode.ADMITTED
          && current.decisionCode() == ReservationDecisionCode.ADMITTED
          && current.projectionVersion() == 3) {
        return current;
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
