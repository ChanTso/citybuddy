import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]


def contract() -> dict[str, Any]:
    payload = json.loads((ROOT / "agent-service/openapi.json").read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("Agent OpenAPI contract must be an object")
    return payload


def test_evaluation_evidence_route_is_profile_bound_authenticated_and_bounded() -> None:
    payload = contract()
    operation = payload["paths"]["/api/eval/evidence/{traceId}"]["get"]

    assert operation["x-citybuddy-profile"] == "evaluation"
    assert operation["security"] == [{"evaluationManagementBasic": []}]
    security = payload["components"]["securitySchemes"]["evaluationManagementBasic"]
    assert security == {
        "type": "http",
        "scheme": "basic",
        "description": (
            "Independent evaluation API credential; never a direct-user or service-token fallback."
        ),
    }
    parameters = {(item["in"], item["name"]): item for item in operation["parameters"]}
    assert set(parameters) == {("path", "traceId"), ("header", "X-Eval-Sandbox-Id")}
    assert all(item["required"] for item in parameters.values())
    assert parameters[("path", "traceId")]["schema"] == {
        "type": "string",
        "format": "uuid",
        "minLength": 36,
        "maxLength": 36,
    }
    assert parameters[("header", "X-Eval-Sandbox-Id")]["schema"]["maxLength"] == 64
    assert set(operation["responses"]) == {"200", "401", "404", "409", "422", "503"}
    for status in ("401", "404", "409", "422", "503"):
        response = operation["responses"][status]
        referenced = payload["components"]["responses"][response["$ref"].rsplit("/", 1)[1]]
        assert referenced["content"]["application/json"]["schema"] == {
            "$ref": "#/components/schemas/PublicError"
        }


def test_evaluation_evidence_schema_is_a_closed_safe_projection() -> None:
    schemas = contract()["components"]["schemas"]
    response = schemas["AgentEvaluationEvidence"]
    event = schemas["AgentEvaluationEvent"]
    retrieval = schemas["AgentEvaluationRetrieval"]
    source = schemas["AgentEvaluationSource"]
    feedback = schemas["AgentEvaluationFeedback"]

    for schema in (response, event, retrieval, source, feedback):
        assert schema["additionalProperties"] is False
    assert set(response["properties"]) == {
        "schemaVersion",
        "traceId",
        "sessionId",
        "turnId",
        "terminalOutcome",
        "events",
        "retrieval",
        "feedback",
    }
    assert response["properties"]["events"]["minItems"] == 2
    assert response["properties"]["events"]["maxItems"] == 48
    assert response["properties"]["feedback"]["maxItems"] == 8
    assert set(event["properties"]) == {
        "sequence",
        "eventKind",
        "outcome",
        "reference",
        "attempt",
        "attemptLimit",
        "occurredAt",
    }
    assert set(retrieval["properties"]) == {
        "outcome",
        "reason",
        "indexVersion",
        "calibrationVersion",
        "candidateCount",
        "evidenceCount",
        "sources",
    }
    assert set(source["properties"]) == {
        "rank",
        "sourceId",
        "chunkId",
        "sourceVersion",
        "docType",
    }
    assert set(feedback["properties"]) == {"rating", "occurredAt"}
    public_fields = (
        set(response["properties"])
        | set(event["properties"])
        | set(retrieval["properties"])
        | set(source["properties"])
        | set(feedback["properties"])
    )
    for forbidden in (
        "message",
        "inputText",
        "responseText",
        "prompt",
        "reasoning",
        "provider",
        "alias",
        "credential",
        "token",
        "arguments",
        "resultPayload",
        "comment",
        "excerpt",
        "title",
        "sql",
    ):
        assert forbidden not in public_fields


def test_evaluation_store_uses_only_exact_agent_truth_and_persisted_sequence() -> None:
    source = (ROOT / "agent-service/src/citybuddy_agent/evaluation.py").read_text(encoding="utf-8")
    application = (ROOT / "agent-service/src/citybuddy_agent/application.py").read_text(
        encoding="utf-8"
    )
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")

    assert "JOIN support_conversation conversation" in source
    assert "JOIN support_session session_record" in source
    assert "turn_record.trace_id = %s AND session_record.sandbox_id = %s" in source
    assert "user_subject FROM support_event WHERE trace_id = %s" in source
    assert '"ORDER BY sequence LIMIT %s"' in source
    assert "ORDER BY evidence_rank LIMIT %s" in source
    assert "ORDER BY created_at, feedback_id LIMIT %s" in source
    assert "retrieval_event.outcome != row[6]" in source
    assert "retrieval_event.reference != row[4]" in source
    assert "self._validate_lifecycle(events, terminal_outcome)" in source
    assert "self._utc_timestamp(row[3])" in source
    assert "self._utc_timestamp(row[1])" in source
    assert "SET time_zone = '+00:00'" in source
    assert 'user="agent_app"' in source
    assert 'database="cs_db"' in source
    for mutation in ('"INSERT ', '"UPDATE ', '"DELETE ', '"ALTER ', '"CREATE '):
        assert mutation not in source
    assert "if resolved.evaluation_enabled:" in application
    assert '"/api/eval/evidence/{trace_id}"' in application
    assert "secrets.compare_digest" in application
    assert "request.query_params" in application
    assert "resolved_evidence.load(str(trace_id), x_eval_sandbox_id)" in application
    for table in (
        "support_session",
        "support_conversation",
        "support_turn",
        "support_event",
        "support_feedback",
        "retrieval_decision",
        "retrieval_evidence",
    ):
        assert f"ON cs_db.{table} TO 'agent_app'@'%';" in grants


def test_real_response_checker_requires_lifecycle_consistency_and_explicit_timezone() -> None:
    checker = (ROOT / "scripts/check_agent_evaluation_evidence.py").read_text(encoding="utf-8")

    assert "Evidence contains an intermediate terminal boundary" in checker
    assert "Evidence event conflicts with terminal outcome" in checker
    assert 'require_rfc3339(event["occurredAt"])' in checker
    assert 'require_rfc3339(record["occurredAt"])' in checker
    assert "timestamp.tzinfo is None or timestamp.utcoffset() is None" in checker
