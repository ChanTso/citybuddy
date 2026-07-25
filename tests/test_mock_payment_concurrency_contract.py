import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAYMENT = ROOT / "commerce-service/src/main/java/io/citybuddy/commerce/payment"
PAYMENT_TEST = ROOT / "commerce-service/src/test/java/io/citybuddy/commerce/payment"
REFUND = ROOT / "commerce-service/src/main/java/io/citybuddy/commerce/refund"
REFUND_TEST = ROOT / "commerce-service/src/test/java/io/citybuddy/commerce/refund"
MYSQL = ROOT / "commerce-service/src/main/java/io/citybuddy/commerce/mysql"
MYSQL_TEST = ROOT / "commerce-service/src/test/java/io/citybuddy/commerce/mysql"


def test_mock_payment_service_has_one_closed_transaction_entry_inventory() -> None:
    service = (PAYMENT / "MockPaymentService.java").read_text(encoding="utf-8")
    transactions = (PAYMENT / "MockPaymentTransactions.java").read_text(encoding="utf-8")
    entry_block = re.search(r"enum Entry \{(.*?)\n\s+private final Mode", transactions, re.DOTALL)
    assert entry_block is not None
    entries = set(
        re.findall(
            r"^\s+([A-Z_]+)\(",
            entry_block.group(1),
            re.MULTILINE,
        )
    )
    expected = {
        "START_INITIAL_MUTATION",
        "START_TRUTH_OBSERVATION",
        "START_FINAL_MUTATION",
        "START_FINAL_OBSERVATION",
        "CALLBACK_INITIAL_MUTATION",
        "CALLBACK_TRUTH_OBSERVATION",
    }
    assert entries == expected
    for entry in expected:
        assert service.count(f"MockPaymentTransactions.Entry.{entry}") == 1
    assert "TransactionTemplate" not in service
    assert "transactions.execute(" not in service
    assert "SET SESSION innodb_lock_wait_timeout" not in service

    refund_service = (REFUND / "RefundService.java").read_text(encoding="utf-8")
    refund_transactions = (REFUND / "RefundTransactions.java").read_text(encoding="utf-8")
    refund_entry_block = re.search(
        r"enum Entry \{(.*?)\n\s+private final Mode", refund_transactions, re.DOTALL
    )
    assert refund_entry_block is not None
    refund_entries = set(
        re.findall(r"^\s+([A-Z_]+)\(", refund_entry_block.group(1), re.MULTILINE)
    )
    expected_refund = {
        "DIRECT_INITIAL_MUTATION",
        "DIRECT_TRUTH_OBSERVATION",
        "DIRECT_FINAL_MUTATION",
        "DIRECT_FINAL_OBSERVATION",
        "MARK_PROCESSING_MUTATION",
        "MARK_PROCESSING_OBSERVATION",
        "SUCCEED_MUTATION",
        "SUCCEED_OBSERVATION",
        "FAIL_MUTATION",
        "FAIL_OBSERVATION",
        "RECONCILE_MUTATION",
        "RECONCILE_OBSERVATION",
    }
    assert refund_entries == expected_refund
    for entry in expected_refund:
        assert refund_service.count(f"RefundTransactions.Entry.{entry}") >= 1
    assert "TransactionTemplate" not in refund_service
    assert "transactions.execute(" not in refund_service
    assert "SET SESSION innodb_lock_wait_timeout" not in refund_service


def test_physical_lock_wait_boundary_is_session_scoped_and_restored() -> None:
    transactions = (MYSQL / "BoundedMySqlTransactions.java").read_text(encoding="utf-8")
    payment = (PAYMENT / "MockPaymentTransactions.java").read_text(encoding="utf-8")
    refund = (REFUND / "RefundTransactions.java").read_text(encoding="utf-8")
    assert "TransactionSynchronizationManager.isActualTransactionActive()" in transactions
    assert 'queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout"' in transactions
    assert '"SET SESSION innodb_lock_wait_timeout = " + lockWaitTimeoutSeconds' in transactions
    assert "finally {" in transactions
    assert '"SET SESSION innodb_lock_wait_timeout = " + previous' in transactions
    assert "setTimeout(" not in transactions
    assert "BoundedMySqlTransactions" in payment
    assert "BoundedMySqlTransactions" in refund


def test_start_and_callback_expose_bounded_indeterminate_without_raw_lock_handler() -> None:
    service = (PAYMENT / "MockPaymentService.java").read_text(encoding="utf-8")
    controller = (PAYMENT / "MockPaymentController.java").read_text(encoding="utf-8")
    reasons = (PAYMENT / "MockPaymentRejectionReason.java").read_text(encoding="utf-8")
    openapi = json.loads(
        (ROOT / "commerce-service/src/main/resources/openapi.json").read_text(encoding="utf-8")
    )

    assert "MockPaymentTransactions.ObservationOutcome" in service
    assert "FOUND" in service
    assert "CONFIRMED_ABSENT" in service
    assert "INDETERMINATE" in service
    assert "PAYMENT_CONCURRENCY_OBSERVATION_INDETERMINATE" in reasons
    assert service.count("catch (PessimisticLockingFailureException exception)") == 5
    assert "catch (CannotAcquireLockException exception)" not in service
    assert "CannotAcquireLockException.class" not in controller
    assert "429" in openapi["paths"]["/api/orders/{orderId}/mock-payment"]["post"]["responses"]
    assert "429" in openapi["paths"]["/internal/mock-payments/callback"]["post"]["responses"]


def test_transaction_and_failure_inventories_have_real_regression_anchors() -> None:
    transaction_test = (PAYMENT_TEST / "MockPaymentTransactionsTest.java").read_text(
        encoding="utf-8"
    )
    bounded_test = (MYSQL_TEST / "BoundedMySqlTransactionsTest.java").read_text(encoding="utf-8")
    concurrency_test = (PAYMENT_TEST / "MockPaymentConcurrencyTest.java").read_text(
        encoding="utf-8"
    )
    integration_test = (PAYMENT_TEST / "MockPaymentIntegrationTest.java").read_text(
        encoding="utf-8"
    )

    assert "registeredEntriesAreClosedAndRuntimeModeChecksAreEnforced" in transaction_test
    for evidence in (
        "workUsesOneActiveSessionAndRestoresItsPriorPolicy",
        "restorationFailureIsVisibleAndCannotReturnAContaminatedSuccess",
        "inactiveTransactionCannotChangeSessionPolicy",
    ):
        assert evidence in bounded_test
    for evidence in (
        "repeatedRealMysqlLockCodesBecomeAttributedIndeterminateWithoutMutation",
        "confirmedAbsenceAuthorizesExactlyOneFinalMutation",
        "finalCompetitionCanOnlyObserveASiblingAndCannotMutateAgain",
        "onlyCompletedVisibilityObservationCanConceal",
        "callbackCompetitionAlsoEndsAsAttributedIndeterminate",
    ):
        assert evidence in concurrency_test
    for evidence in (
        "realMysqlLockWaitIsBoundedIndeterminateAndRestoresThePooledSession",
        "realMysqlLockCompetitionReobservesTheCommittedPendingSibling",
    ):
        assert evidence in integration_test

    refund_transaction_test = (
        REFUND_TEST / "RefundTransactionsTest.java"
    ).read_text(encoding="utf-8")
    refund_concurrency_test = (
        REFUND_TEST / "RefundConcurrencyTest.java"
    ).read_text(encoding="utf-8")
    refund_integration_test = (
        REFUND_TEST / "RefundIntegrationTest.java"
    ).read_text(encoding="utf-8")
    assert "entryInventoryIsClosedAndRuntimeModeChecksAreEnforced" in refund_transaction_test
    assert "onlyMysql1205And1213AnywhereInTheCauseChainAreContention" in refund_transaction_test
    assert "repeatedMysql1205ObservationIsIndeterminateWithoutMutationOrExceptionEscape" in (
        refund_concurrency_test
    )
    for evidence in (
        "callbackShapedAttemptLockBoundsDirectAndLifecycleObservationAndRestoresPoolPolicy",
        "realMysql1205RollsBackInitialRequestThenNewTransactionReplaysSiblingTruth",
        "controlledMysql1213AtDirectBoundaryRollsBackBeforeCommittedReplay",
        "realMysqlDeadlockAcceptsEitherVictimAndRollsBackBothBoundedParticipants",
        "realMysql1213RollsBackLifecycleBeforeBoundedReobservation",
        "realMysql1213BoundsReconciliationWithoutReportingContradiction",
    ):
        assert evidence in refund_integration_test


def test_visibility_and_request_acquisition_are_locking_bounded_and_total() -> None:
    resolver = (PAYMENT / "CommittedPaymentTruthResolver.java").read_text(encoding="utf-8")
    repository = (PAYMENT / "MockPaymentRepository.java").read_text(encoding="utf-8")
    parser = (PAYMENT / "MockPaymentRequestParser.java").read_text(encoding="utf-8")
    parser_test = (PAYMENT_TEST / "MockPaymentRequestParserTest.java").read_text(encoding="utf-8")

    assert "context.orderId(), context.userSubject(), context.sandboxId(), LOCK" in resolver
    assert '" LIMIT 2" + lockClause' in repository
    assert "cardinalitySql(sql)" in repository
    assert "readNBytes(MAXIMUM_REQUEST_BYTES + 1)" in parser
    assert "STRICT_DUPLICATE_DETECTION" in parser
    for evidence in (
        "rejectsDuplicateFieldsTrailingValuesAndWrongPrimitiveTypes",
        "rejectsEmptyNullArrayAndMalformedUtf8",
        "rejectsBodiesAtTheAcquisitionBoundary",
        "callbackUsesTheSameTotalBoundary",
    ):
        assert evidence in parser_test

    refund_parser = (REFUND / "RefundRequestParser.java").read_text(encoding="utf-8")
    refund_controller = (REFUND / "RefundController.java").read_text(encoding="utf-8")
    refund_parser_test = (REFUND_TEST / "RefundRequestParserTest.java").read_text(encoding="utf-8")
    assert "readNBytes(MAXIMUM_REQUEST_BYTES + 1)" in refund_parser
    assert "STRICT_DUPLICATE_DETECTION" in refund_parser
    assert "@RequestBody" not in refund_controller
    for evidence in (
        "rejectsDuplicateTrailingWrongShapeAndMalformedEncoding",
        "rejectsBeforeMaterializingMoreThanTheRequestBound",
    ):
        assert evidence in refund_parser_test
