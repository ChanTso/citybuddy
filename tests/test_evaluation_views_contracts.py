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
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")
    repository = (
        ROOT
        / "commerce-service/src/main/java/io/citybuddy/commerce/evaluation"
        / "EvaluationViewRepository.java"
    ).read_text(encoding="utf-8")
    agent = (ROOT / "agent-service/src/citybuddy_agent/agent_control.py").read_text(
        encoding="utf-8"
    )

    assert "UNIQUE KEY uq_eval_audit_operation (sandbox_id, operation_id)" in migration
    assert "KEY ix_eval_audit_session_page" in migration
    assert (
        "GRANT SELECT, INSERT ON commerce_db.eval_commerce_audit_reference TO 'commerce_app'@'%';"
    ) in grants
    assert "UPDATE ON commerce_db.eval_commerce_audit_reference" not in grants
    assert "DELETE ON commerce_db.eval_commerce_audit_reference" not in grants
    assert "WHERE sandbox_id = ? AND support_session_id = ? AND sequence_id > ?" in repository
    assert "WHERE sandbox_id = ? AND product_id = ? AND publication_version = ?" in repository
    assert "ORDER BY created_at, effect_type, correlation_key LIMIT 8" in repository
    assert "ORDER BY a.created_at, a.attempt_id" in repository
    assert "WHERE sandbox_id = ? AND operation_id = ?\n            FOR SHARE" in (
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
