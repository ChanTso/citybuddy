import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def source(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_pending_action_and_receipt_schema_are_bounded_and_immutable() -> None:
    migration = source("infra/mysql/migrations/commerce/V015__pending_action_receipt.sql")
    assert "CREATE TABLE pending_action" in migration
    assert "pending_hash CHAR(64) NOT NULL" in migration
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
        "ON commerce_db.pending_action TO 'commerce_app'@'%';"
    ) in grants
    assert "GRANT SELECT, INSERT ON commerce_db.action_receipt TO 'commerce_app'@'%';" in grants
    for identity in ("auth_app", "agent_app"):
        assert f"commerce_db.pending_action TO '{identity}'" not in grants
        assert f"commerce_db.action_receipt TO '{identity}'" not in grants
    assert "UPDATE ON commerce_db.action_receipt" not in grants
    assert "DELETE ON commerce_db.action_receipt" not in grants
    assert "UPDATE ON commerce_db.pending_action" not in grants


def test_openapi_exposes_only_closed_prepare_and_confirm_shapes() -> None:
    document = json.loads(source("commerce-service/src/main/resources/openapi.json"))
    paths = document["paths"]
    prepare_path = paths["/internal/tools/actions/prepare"]["post"]
    confirm_path = paths["/internal/tools/actions/{pendingActionId}/confirm"]["post"]
    assert prepare_path["security"] == [{"agentOboBearer": []}]
    assert confirm_path["security"] == [{"agentOboBearer": []}]
    for operation in (prepare_path, confirm_path):
        assert {parameter["name"] for parameter in operation["parameters"]}.issuperset(
            {
                "X-Support-Session-Id",
                "X-Agent-Trace-Id",
                "X-Agent-Turn-Id",
                "X-Eval-Sandbox-Id",
            }
        )
        assert set(operation["responses"]) == {
            "200",
            "400",
            "403",
            "404",
            "409",
            "429",
            "503",
        } | ({"201"} if operation is prepare_path else set())

    prepare = document["components"]["schemas"]["PrepareActionRequest"]
    assert prepare["additionalProperties"] is False
    assert prepare["properties"]["actionType"]["const"] == "REFUND_REQUEST"
    arguments = prepare["properties"]["arguments"]
    assert arguments["additionalProperties"] is False
    assert set(arguments["required"]) == {"orderId", "amountMinor", "currency"}
    receipt = document["components"]["schemas"]["ActionReceipt"]
    assert receipt["additionalProperties"] is False
    assert receipt["properties"]["status"]["const"] == "REQUESTED"
    serialized = json.dumps(
        {
            "action_error": document["components"]["schemas"]["ActionError"],
            "forbidden": document["components"]["schemas"]["ActionForbidden"],
            "unavailable": document["components"]["schemas"]["ActionUnavailable"],
        }
    ).lower()
    for private in ("sql", "lock_wait", "reason_code", "table_name", "retry_count"):
        assert private not in serialized


def test_action_transaction_entries_are_closed_and_all_use_bounded_executor() -> None:
    transactions = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/action/ActionTransactions.java"
    )
    service = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/action/ActionService.java"
    )
    configuration = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/action/ActionConfiguration.java"
    )
    entries = {
        "PREPARE_INITIAL_MUTATION",
        "PREPARE_TRUTH_OBSERVATION",
        "CONFIRM_INITIAL_MUTATION",
        "CONFIRM_TRUTH_OBSERVATION",
    }
    for entry in entries:
        assert f"ActionTransactions.Entry.{entry}" in service
        assert entry in transactions
    assert "new BoundedMySqlTransactions(" in configuration
    assert "transactions.execute(work)" in transactions
    assert "TransactionTemplate" not in service
    assert "1205" in transactions and "1213" in transactions


def test_action_refund_work_stays_inside_the_one_action_transaction() -> None:
    action = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/action/ActionService.java"
    )
    refund = source(
        "commerce-service/src/main/java/io/citybuddy/commerce/refund/RefundService.java"
    )
    assert "prepareActionInCurrentTransaction(" in action
    assert "requestActionInCurrentTransaction(" in action
    assert "validateActionReplayInCurrentTransaction(" in action
    assert "actions.consume(pending, committedAt);" in action
    assert "actions.insertReceipt(receipt);" in action
    methods = {
        "prepareActionInCurrentTransaction": "ActionTarget",
        "requestActionInCurrentTransaction": "ActionMutation",
        "validateActionReplayInCurrentTransaction": "ActionReplayTruth",
    }
    for method, return_type in methods.items():
        method_body = refund[refund.index(f"public {return_type} {method}") :]
        method_body = method_body[: method_body.index("\n  }") + 4]
        assert "requireCurrentTransaction();" in method_body
        assert "transactions." not in method_body
    assert "TransactionSynchronizationManager.isActualTransactionActive()" in refund


def test_agent_projection_and_public_sse_are_not_implemented_early() -> None:
    agent_sources = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (ROOT / "agent-service").rglob("*")
        if path.is_file() and path.suffix in {".java", ".json", ".py"}
    )
    assert "ActionReceiptView" not in agent_sources
    assert "PendingActionView" not in agent_sources
