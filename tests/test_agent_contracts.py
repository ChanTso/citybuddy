import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]


def contract() -> dict[str, Any]:
    payload = json.loads((ROOT / "agent-service/openapi.json").read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("Agent OpenAPI contract must be an object")
    return payload


def test_chat_contract_fixes_identity_correlation_and_bounded_body() -> None:
    operation = contract()["paths"]["/api/chat"]["post"]

    assert operation["security"] == [{"directUserBearer": []}]
    headers = {item["name"]: item for item in operation["parameters"]}
    assert set(headers) == {"X-Session-Id", "Idempotency-Key"}
    assert all(item["in"] == "header" and item["required"] for item in headers.values())
    request = operation["requestBody"]["content"]["application/json"]["schema"]
    assert request["additionalProperties"] is False
    assert request["required"] == ["message"]
    assert request["properties"]["message"] == {
        "type": "string",
        "minLength": 1,
        "maxLength": 4000,
    }


def test_chat_response_is_allowlisted_and_server_ids_are_read_only() -> None:
    operation = contract()["paths"]["/api/chat"]["post"]
    response = operation["responses"]["200"]["content"]["application/json"]["schema"]

    assert response["additionalProperties"] is False
    assert set(response["properties"]) == {
        "conversationId",
        "traceId",
        "turnId",
        "reply",
        "outcome",
    }
    for name in ("conversationId", "traceId", "turnId"):
        assert response["properties"][name]["readOnly"] is True
    assert response["properties"]["outcome"]["enum"] == ["completed"]
    assert set(operation["responses"]) == {"200", "401", "403", "409", "422", "503"}


def test_support_truth_schema_and_runtime_grants_fix_order_and_append_only_evidence() -> None:
    migration = (
        ROOT / "infra/mysql/migrations/agent/V003__support_conversation_lifecycle.sql"
    ).read_text(encoding="utf-8")
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")

    assert "UNIQUE KEY uq_support_conversation_session (session_id)" in migration
    assert "UNIQUE KEY uq_support_turn_correlation (session_id, correlation_key)" in migration
    assert "UNIQUE KEY uq_support_turn_position (conversation_id, turn_sequence)" in migration
    assert "UNIQUE KEY uq_support_event_trace_sequence (trace_id, sequence)" in migration
    assert "UNIQUE KEY uq_support_event_turn_sequence (turn_id, sequence)" in migration
    assert "FOREIGN KEY (turn_id, trace_id, session_id, user_subject)" in migration
    assert "(sequence = 1 AND event_type = 'USER_INPUT')" in migration
    assert (
        "GRANT SELECT, INSERT, UPDATE ON cs_db.support_conversation TO 'agent_app'@'%';" in grants
    )
    assert "GRANT SELECT, INSERT, UPDATE ON cs_db.support_turn TO 'agent_app'@'%';" in grants
    assert "GRANT SELECT, INSERT ON cs_db.support_event TO 'agent_app'@'%';" in grants
    assert "UPDATE ON cs_db.support_event" not in grants
    assert "DELETE ON cs_db.support_event" not in grants
