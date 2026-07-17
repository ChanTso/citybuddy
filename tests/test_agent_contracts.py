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
    assert response["properties"]["outcome"]["enum"] == [
        "completed",
        "budget_exhausted",
        "provider_denied",
    ]
    assert set(operation["responses"]) == {"200", "401", "403", "409", "422", "503"}


def test_stream_contract_fixes_headers_event_names_and_allowlisted_payloads() -> None:
    payload = contract()
    operation = payload["paths"]["/api/chat/stream"]["post"]

    assert operation["security"] == [{"directUserBearer": []}]
    assert {item["name"] for item in operation["parameters"]} == {
        "X-Session-Id",
        "Idempotency-Key",
    }
    request = operation["requestBody"]["content"]["application/json"]["schema"]
    assert request["additionalProperties"] is False
    assert request["required"] == ["message"]
    stream = operation["responses"]["200"]["content"]["text/event-stream"]
    assert set(stream["x-sse-events"]) == {"token", "action_receipt", "done", "error"}
    expected_fields = {
        "SseTokenData": {"sequence", "text"},
        "SseActionReceiptData": {"sequence", "receiptId", "status"},
        "SseDoneData": {"sequence", "conversationId", "traceId", "turnId", "outcome"},
        "SseErrorData": {"sequence", "code"},
    }
    for schema_name, fields in expected_fields.items():
        schema = payload["components"]["schemas"][schema_name]
        assert schema["additionalProperties"] is False
        assert set(schema["properties"]) == fields
        assert set(schema["required"]) == fields

    source = (ROOT / "agent-service/src/citybuddy_agent/sse.py").read_text(encoding="utf-8")
    assert "MAX_PUBLIC_EVENTS" in source
    assert "is_disconnected" in source
    assert "create_task" not in source
    assert "Thread" not in source


def test_feedback_contract_has_no_body_owner_and_returns_server_identity() -> None:
    payload = contract()
    operation = payload["paths"]["/api/feedback"]["post"]
    request = payload["components"]["schemas"]["FeedbackRequest"]
    response = payload["components"]["schemas"]["FeedbackResponse"]

    assert operation["security"] == [{"directUserBearer": []}]
    assert {item["name"] for item in operation["parameters"]} == {
        "X-Session-Id",
        "Idempotency-Key",
    }
    assert request["additionalProperties"] is False
    assert set(request["properties"]) == {"traceId", "rating", "comment"}
    assert "userSubject" not in request["properties"]
    assert "sessionId" not in request["properties"]
    assert response["additionalProperties"] is False
    assert set(response["properties"]) == {"feedbackId", "traceId", "rating"}
    assert response["properties"]["feedbackId"]["readOnly"] is True


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


def test_bounded_agent_migration_preserves_terminal_and_append_only_truth() -> None:
    migration = (ROOT / "infra/mysql/migrations/agent/V004__bounded_agent_evidence.sql").read_text(
        encoding="utf-8"
    )

    for event_type in (
        "ROUTING_DECISION",
        "BUDGET_CHARGED",
        "CIRCUIT_OUTCOME",
        "MODEL_OUTCOME",
        "TOOL_LIFECYCLE",
        "TOOL_DENIED",
        "AGENT_OUTCOME",
        "TURN_FAILED",
    ):
        assert f"'{event_type}'" in migration
    assert "sequence > 0" in migration
    assert "(sequence = 1 AND event_type = 'USER_INPUT')" in migration
    assert "(sequence > 1 AND event_type <> 'USER_INPUT')" in migration
    assert "outcome IN ('completed', 'budget_exhausted', 'provider_denied')" in migration
    assert "ADD COLUMN processing_deadline_at TIMESTAMP(6) NULL AFTER state" in migration
    assert "state = 'PROCESSING'" in migration
    assert "processing_deadline_at IS NOT NULL" in migration
    assert "processing_deadline_at IS NULL" in migration


def test_feedback_schema_and_grants_are_owner_bound_and_append_only() -> None:
    migration = (ROOT / "infra/mysql/migrations/agent/V005__support_feedback.sql").read_text(
        encoding="utf-8"
    )
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")

    assert "UNIQUE KEY uq_support_feedback_intent (session_id, idempotency_key)" in migration
    assert (
        "UNIQUE KEY uq_support_turn_feedback_binding (trace_id, session_id, user_subject)"
        in migration
    )
    assert "FOREIGN KEY (trace_id, session_id, user_subject)" in migration
    assert "REFERENCES support_turn (trace_id, session_id, user_subject)" in migration
    assert "rating IN ('POSITIVE', 'NEGATIVE')" in migration
    assert "GRANT SELECT, INSERT ON cs_db.support_feedback TO 'agent_app'@'%';" in grants
    assert "UPDATE ON cs_db.support_feedback" not in grants
    assert "DELETE ON cs_db.support_feedback" not in grants

    source = (ROOT / "agent-service/src/citybuddy_agent/feedback.py").read_text(encoding="utf-8")
    assert "FROM support_conversation " in source
    assert '"WHERE session_id = %s FOR UPDATE"' in source
    assert '"SELECT user_subject, sandbox_id FROM support_session "' in source
    assert (
        'support_session "\n                        "WHERE session_id = %s FOR UPDATE' not in source
    )
    assert '"AND idempotency_key = %s FOR UPDATE"' not in source


def test_commerce_tool_contract_is_exact_obo_and_bounded_view() -> None:
    commerce = json.loads(
        (ROOT / "commerce-service/src/main/resources/openapi.json").read_text(encoding="utf-8")
    )
    operation = commerce["paths"]["/internal/tools/catalog.product.get"]["post"]
    request = commerce["components"]["schemas"]["CatalogProductToolInput"]
    response = commerce["components"]["schemas"]["CatalogProductToolOutput"]

    assert operation["security"] == [{"agentOboBearer": []}]
    assert request["additionalProperties"] is False
    assert request["required"] == ["productId"]
    assert set(response["properties"]) == {
        "productId",
        "name",
        "priceMinor",
        "currency",
        "available",
        "publicationVersion",
    }
    assert "description" not in response["properties"]
    assert "stockQuantity" not in response["properties"]


def test_application_uses_role_aliases_without_concrete_provider_models() -> None:
    sources = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (ROOT / "agent-service/src/citybuddy_agent").glob("*.py")
    )

    assert "support-standard-primary" in sources
    assert "support-standard-fallback" in sources
    assert "gpt-" not in sources.casefold()
    assert "claude-" not in sources.casefold()
    assert "gemini-" not in sources.casefold()
