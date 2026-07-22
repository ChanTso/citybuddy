import json
from pathlib import Path
from typing import Any, cast

ROOT = Path(__file__).resolve().parents[1]


def contract() -> dict[str, Any]:
    payload: Any = json.loads(
        (ROOT / "commerce-service/src/main/resources/openapi.json").read_text(encoding="utf-8")
    )
    if not isinstance(payload, dict):
        raise ValueError("Commerce OpenAPI contract must be an object")
    return cast(dict[str, Any], payload)


def test_evaluation_views_are_profile_bound_authenticated_and_bounded() -> None:
    payload = contract()
    paths = payload["paths"]
    state = paths["/api/eval/state"]["get"]
    audit = paths["/api/eval/audit/{sessionId}"]["get"]
    version = paths["/api/eval/version"]["get"]

    for operation in (state, audit, version):
        assert operation["x-citybuddy-profile"] == "evaluation"
        assert operation["security"] == [{"evaluationManagementBasic": []}]
        assert "200" in operation["responses"]
        assert "401" in operation["responses"]

        for status, response in operation["responses"].items():
            if status != "200":
                assert response["content"]["application/json"]["schema"] == {
                    "$ref": "#/components/schemas/EvaluationError"
                }

    audit_parameters = {item["name"]: item for item in audit["parameters"]}
    assert audit_parameters["limit"]["schema"] == {
        "type": "integer",
        "minimum": 1,
        "maximum": 50,
        "default": 20,
    }
    assert set(audit_parameters) == {
        "sessionId",
        "X-Eval-Sandbox-Id",
        "after",
        "limit",
    }

    schemas = payload["components"]["schemas"]
    for name in (
        "EvaluationSandboxState",
        "EvaluationStateProduct",
        "EvaluationStateEffect",
        "EvaluationStatePayment",
        "EvaluationStateResponse",
        "EvaluationAuditReference",
        "EvaluationAuditPage",
        "EvaluationVersionResponse",
        "EvaluationError",
    ):
        assert schemas[name]["additionalProperties"] is False
    assert schemas["EvaluationStateResponse"]["properties"]["products"]["maxItems"] == 16
    assert schemas["EvaluationStateResponse"]["properties"]["payments"]["maxItems"] == 8
    assert schemas["EvaluationStateResponse"]["properties"]["payments"]["description"] == (
        "Global ascending order by createdAt, then attemptId."
    )
    assert schemas["EvaluationStateResponse"]["properties"]["effects"]["description"] == (
        "Global ascending order by createdAt, then effectType, then the internal stable "
        "correlation key"
    )
    assert schemas["EvaluationAuditPage"]["properties"]["entries"]["maxItems"] == 50
    assert schemas["EvaluationAuditReference"]["properties"]["entityType"]["enum"] == [
        "PRODUCT_FIXTURE",
        "PAYMENT_CALLBACK",
    ]
    assert schemas["EvaluationVersionResponse"]["properties"]["capabilities"]["maxItems"] == 3
    assert schemas["EvaluationError"] == {
        "type": "object",
        "additionalProperties": False,
        "required": ["error"],
        "properties": {"error": {"type": "string", "minLength": 1, "maxLength": 128}},
    }


def test_evaluation_audit_is_append_only_scoped_and_not_agent_evidence() -> None:
    migration = (
        ROOT / "infra/mysql/migrations/commerce/V011__evaluation_commerce_audit_reference.sql"
    ).read_text(encoding="utf-8")
    totality_migration = (
        ROOT / "infra/mysql/migrations/commerce/V013__evaluation_audit_totality.sql"
    ).read_text(encoding="utf-8")
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")
    repository = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationViewRepository.java"
    ).read_text(encoding="utf-8")
    audit_service = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationCommerceAuditService.java"
    ).read_text(encoding="utf-8")
    audit_writer = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationAuditReferenceWriter.java"
    ).read_text(encoding="utf-8")
    agent = (ROOT / "agent-service/src/citybuddy_agent/agent_control.py").read_text(
        encoding="utf-8"
    )

    assert "UNIQUE KEY uq_eval_audit_operation (sandbox_id, operation_id)" in migration
    assert "KEY ix_eval_audit_session_page" in migration
    assert (
        "GRANT SELECT, INSERT ON commerce_db.eval_commerce_audit_reference TO 'commerce_app'@'%';"
    ) in grants
    assert (
        "GRANT SELECT, INSERT ON commerce_db.eval_commerce_product_observation "
        "TO 'commerce_app'@'%';"
    ) in grants
    assert (
        "GRANT SELECT ON commerce_db.eval_commerce_audit_legacy_watermark TO 'commerce_app'@'%';"
    ) in grants
    assert (
        "INSERT ON commerce_db.eval_commerce_audit_legacy_watermark TO 'commerce_app'" not in grants
    )
    assert "UPDATE ON commerce_db.eval_commerce_audit_legacy_watermark" not in grants
    assert "DELETE ON commerce_db.eval_commerce_audit_legacy_watermark" not in grants
    assert "UPDATE ON commerce_db.eval_commerce_audit_reference" not in grants
    assert "DELETE ON commerce_db.eval_commerce_audit_reference" not in grants
    assert "UPDATE ON commerce_db.eval_commerce_product_observation" not in grants
    assert "DELETE ON commerce_db.eval_commerce_product_observation" not in grants
    assert "ALTER COLUMN created_at DROP DEFAULT" in totality_migration
    assert "DEFAULT 'LEGACY_CUTOFF'" in totality_migration
    assert "ALTER COLUMN created_at_anchor DROP DEFAULT" in totality_migration
    assert "CREATE TABLE eval_commerce_audit_legacy_watermark" in totality_migration
    assert "COALESCE(MAX(sequence_id), 0)" in totality_migration
    assert "FROM eval_commerce_audit_reference" in totality_migration
    assert "legacy_row_count" in totality_migration
    commitment_format = "CITYBUDDY_EVAL_AUDIT_LEGACY_LPUTF8_SHA256_CHAIN_V1"
    assert commitment_format in totality_migration
    commitment = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationLegacyAuditCommitment.java"
    ).read_text(encoding="utf-8")
    commitment_store = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationLegacyAuditCommitmentStore.java"
    ).read_text(encoding="utf-8")
    assert commitment_format in commitment
    assert "ROW_NUMBER() OVER (ORDER BY sequence_id)" in totality_migration
    assert "SHA2(CONCAT(UNHEX(chain.set_digest), UNHEX(row_value.row_digest)), 256)" in (
        totality_migration
    )
    assert "EvaluationLegacyAuditCommitment.digest(rows)" in commitment_store
    assert "WHERE created_at_anchor = 'LEGACY_CUTOFF'" in commitment_store
    assert commitment_store.count("UNIX_TIMESTAMP(") >= 2
    assert "WHERE sandbox_id = ? AND support_session_id = ? AND sequence_id > ?" in repository
    assert (
        "FROM eval_commerce_audit_reference\n        WHERE sandbox_id = ?\n"
        "        ORDER BY sequence_id"
    ) in repository
    assert (
        "FROM eval_commerce_product_observation\n        WHERE sandbox_id = ?\n"
        "        ORDER BY observation_id"
    ) in repository
    total_audit_query = repository.split(
        "private List<IntegrityAuditReference> allAuditReferences", maxsplit=1
    )[1].split("private List<ProductObservationTruth>", maxsplit=1)[0]
    assert "entity_type =" not in total_audit_query
    assert "sequenceOrderConsistent(references)" in repository
    assert "EvaluationLegacyAuditCommitmentStore.load(jdbc).isConsistent()" in repository
    assert "paidOrderTruths(sandboxId)" in repository
    assert "paymentLedgerTruths(sandboxId)" in repository
    assert "succeededCallbackTruths(sandboxId)" in repository
    assert "orderKeys.equals(ledgerKeys)" in repository
    assert "orderKeys.equals(callbackKeys)" in repository
    assert "orderKeys.equals(auditKeys)" in repository
    callback_enumerator = repository.split(
        "private List<SucceededCallbackTruth> succeededCallbackTruths", maxsplit=1
    )[1].split("private boolean productObservationIsAuthoritative", maxsplit=1)[0]
    assert "LEFT JOIN %s a ON a.attempt_id = c.attempt_id" in callback_enumerator
    assert "EvaluationPaymentCommittedFaces.ATTEMPT" in callback_enumerator
    for filtered_validity_predicate in (
        "c.intent_hash =",
        "a.sandbox_id = c.sandbox_id",
        "a.callback_correlation_id = c.callback_correlation_id",
        "a.state = 'SUCCEEDED'",
        "o.status = 'PAID'",
        "l.business_event_key =",
    ):
        assert filtered_validity_predicate not in callback_enumerator
    assert "callback.intentHash().equals(expectedIntentHash)" in repository
    assert "callback.callbackCorrelationId().equals(callback.attemptCorrelationId())" in repository
    assert 'ledger.businessEventKey().equals("mock-payment:" + callback.attemptId())' in repository
    assert "reference.createdAt().equals(truth.createdAt())" in repository
    assert '"LEGACY_CUTOFF".equals(reference.createdAtAnchor())' in repository
    assert "FROM eval_sandbox" in audit_service
    assert "FOR UPDATE" in audit_service
    assert "requireLegacyReplay" in audit_service
    assert "insertOrVerifyObservation" in audit_service
    assert "EvaluationAuditReferenceWriter.monotonicCreatedAt" in audit_service
    assert "ORDER BY sequence_id DESC" in audit_writer
    assert "LIMIT 1\n            FOR SHARE" in audit_writer
    assert "latest.getFirst().isAfter(normalized)" in audit_writer
    assert "ORDER BY created_at, effect_type, correlation_key LIMIT 8" in repository
    assert "ORDER BY a.created_at, a.attempt_id" in repository
    assert "WHERE observation_id = ? OR (sandbox_id = ? AND operation_id = ?)\n" in (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationCommerceAuditService.java"
    ).read_text(encoding="utf-8")
    assert 'headers["X-Agent-Trace-Id"] = trace_id' in agent
    assert 'headers["X-Agent-Operation-Id"]' in agent
    assert "support_event" not in repository
    assert "cs_db" not in repository


def test_version_output_is_fixed_allowlist_with_startup_schema_validation() -> None:
    source = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationViewService.java"
    ).read_text(encoding="utf-8")
    repository = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationViewRepository.java"
    ).read_text(encoding="utf-8")

    assert 'List.of("commerce-audit-v1", "commerce-state-v1", "commerce-version-v1")' in source
    assert "repository.validateSchema();" in source
    assert "WHERE 1 = 0" in repository
    for forbidden in ("System.getenv", "InetAddress", "hostname", "DataSource"):
        assert forbidden not in source
