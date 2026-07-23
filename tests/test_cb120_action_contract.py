import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def source(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_pending_action_and_receipt_schema_are_bounded_and_immutable() -> None:
    migration = source("infra/mysql/migrations/commerce/V015__pending_action_receipt.sql")
    assert "CREATE TABLE pending_action" in migration
    assert "uq_pending_action_idempotency" in migration
    assert "uq_pending_action_turn" in migration
    assert "state ENUM('PREPARED', 'CONSUMED')" in migration
    assert "CREATE TABLE action_receipt" in migration
    assert migration.count("sandbox_id VARCHAR(64) NULL") == 2
    assert "uq_action_receipt_pending" in migration
    assert "uq_action_receipt_refund" in migration
    assert "UPDATE action_receipt" not in migration
    assert "DELETE FROM action_receipt" not in migration


def test_runtime_grants_keep_action_truth_in_commerce_only() -> None:
    grants = source("infra/mysql/grants/V001__migration_access.sql")
    assert (
        "GRANT SELECT, INSERT, UPDATE (state, state_version, consumed_at) "
        "ON commerce_db.pending_action "
        "TO 'commerce_app'@'%';"
    ) in grants
    assert ("GRANT SELECT, INSERT ON commerce_db.action_receipt TO 'commerce_app'@'%';") in grants
    for identity in ("auth_app", "agent_app"):
        assert f"commerce_db.pending_action TO '{identity}'" not in grants
        assert f"commerce_db.action_receipt TO '{identity}'" not in grants
    assert "UPDATE ON commerce_db.action_receipt" not in grants
    assert "DELETE ON commerce_db.action_receipt" not in grants
    assert "UPDATE ON commerce_db.pending_action" not in grants


def test_openapi_exposes_only_closed_prepare_and_confirm_shapes() -> None:
    document = json.loads(source("commerce-service/src/main/resources/openapi.json"))
    paths = document["paths"]
    assert "/internal/tools/actions/prepare" in paths
    assert "/internal/tools/actions/{pendingActionId}/confirm" in paths
    for path in (
        "/internal/tools/actions/prepare",
        "/internal/tools/actions/{pendingActionId}/confirm",
    ):
        sandbox = next(
            parameter
            for parameter in paths[path]["post"]["parameters"]
            if parameter["name"] == "X-Eval-Sandbox-Id"
        )
        assert sandbox["schema"] == {
            "type": "string",
            "minLength": 1,
            "maxLength": 64,
            "pattern": "^[A-Za-z0-9._:-]+$",
        }
    prepare = document["components"]["schemas"]["PrepareActionRequest"]
    assert prepare["additionalProperties"] is False
    assert prepare["properties"]["actionType"]["const"] == "REFUND_REQUEST"
    arguments = prepare["properties"]["arguments"]
    assert arguments["additionalProperties"] is False
    assert set(arguments["required"]) == {"orderId", "amountMinor", "currency"}
    receipt = document["components"]["schemas"]["ActionReceipt"]
    assert receipt["additionalProperties"] is False
    assert receipt["properties"]["status"]["const"] == "REQUESTED"


def test_action_refund_reuses_payment_truth_and_one_transaction_event_time() -> None:
    payment = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/payment/MockPaymentService.java"
    )
    validator = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/payment/MockPaymentTruthValidator.java"
    )
    refund = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/refund/RefundService.java"
    )
    action = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/action/ActionService.java"
    )
    assert "new MockPaymentTruthValidator(repository)" in payment
    assert "new MockPaymentTruthValidator(payments)" in refund
    assert "truth.requireSucceededTruth(attempt)" in payment
    assert "paymentTruth.requireSucceededTruth(attempt)" in refund
    assert "requireZeroEvaluationRefundAccumulator" not in validator
    assert "attempt.sandboxId() != null && attempt.refundedAmountMinor() != 0" in payment
    assert "refundedAmountMinor()" not in validator
    assert "requestActionInCurrentTransaction(" in action
    assert "context.sandboxId(),\n                    committedAt" in action
    assert action.count("Instant observedNow = clock.instant();") == 1
