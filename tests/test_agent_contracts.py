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
        "citations",
        "receiptId",
    }
    assert set(response["required"]) == set(response["properties"])
    for name in ("conversationId", "traceId", "turnId"):
        assert response["properties"][name]["readOnly"] is True
    assert response["properties"]["outcome"]["enum"] == [
        "completed",
        "budget_exhausted",
        "provider_denied",
        "retrieval_denied",
        "action_pending",
        "action_completed",
        "action_clarification",
        "action_declined",
        "action_expired",
        "action_rejected",
    ]
    assert response["properties"]["receiptId"] == {
        "type": ["string", "null"],
        "format": "uuid",
        "readOnly": True,
        "description": (
            "The committed receipt projected from durable action truth for action_completed; "
            "null for every other outcome."
        ),
    }
    citation = contract()["components"]["schemas"]["RetrievalCitation"]
    assert citation["additionalProperties"] is False
    assert set(citation["properties"]) == {
        "sourceId",
        "chunkId",
        "sourceVersion",
        "docType",
        "title",
    }
    assert response["properties"]["citations"]["maxItems"] == 3
    assert set(operation["responses"]) == {"200", "401", "403", "409", "422", "502", "503"}


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
    assert (
        "non-authoritative explanation"
        in payload["components"]["schemas"]["SseTokenData"]["description"]
    )
    assert (
        payload["components"]["schemas"]["SseActionReceiptData"]["description"]
        == "Projects a committed ActionReceipt from the agent's durable receipt projection; "
        "clients must treat this event, not token prose, as action-completion truth."
    )
    assert payload["components"]["schemas"]["SseDoneData"]["properties"]["outcome"]["enum"] == [
        "completed",
        "action_pending",
        "action_completed",
        "action_clarification",
        "action_declined",
        "action_expired",
        "action_rejected",
    ]

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


def test_retrieval_evidence_schema_is_turn_bound_atomic_and_append_only() -> None:
    migration = (ROOT / "infra/mysql/migrations/agent/V006__retrieval_evidence.sql").read_text(
        encoding="utf-8"
    )
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")
    source = (ROOT / "agent-service/src/citybuddy_agent/conversation.py").read_text(
        encoding="utf-8"
    )

    assert "UNIQUE KEY uq_retrieval_decision_turn (turn_id)" in migration
    assert "FOREIGN KEY (turn_id, trace_id, session_id, user_subject)" in migration
    assert "UNIQUE KEY uq_retrieval_evidence_rank (decision_id, evidence_rank)" in migration
    assert "sufficiency_outcome IN ('SUFFICIENT', 'INSUFFICIENT')" in migration
    assert "'retrieval_denied'" in migration
    assert "'RETRIEVAL_DECISION'" in migration
    for table in ("retrieval_decision", "retrieval_evidence"):
        assert f"GRANT SELECT, INSERT ON cs_db.{table} TO 'agent_app'@'%';" in grants
        assert f"UPDATE ON cs_db.{table}" not in grants
        assert f"DELETE ON cs_db.{table}" not in grants
    assert (
        source.index("for event in events:")
        < source.index("self._insert_retrieval_decision(")
        < source.index("UPDATE support_turn SET state = 'COMPLETED'")
    )


def test_pending_action_reference_is_agent_owned_bounded_and_least_privilege() -> None:
    migration = (
        ROOT / "infra/mysql/migrations/agent/V007__pending_action_reference.sql"
    ).read_text(encoding="utf-8")
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")
    payload = contract()

    assert "CREATE TABLE pending_action_reference" in migration
    assert "UNIQUE KEY uq_pending_action_reference_turn (source_turn_id)" in migration
    assert (
        "UNIQUE KEY uq_pending_action_reference_resolution_turn (resolution_turn_id)" in migration
    )
    assert "UNIQUE KEY uq_pending_action_reference_active_session (active_session_id)" in migration
    assert "FOREIGN KEY (source_turn_id, source_trace_id, session_id, user_subject)" in migration
    assert (
        "FOREIGN KEY (resolution_turn_id, resolution_trace_id, session_id, user_subject)"
        in migration
    )
    assert "state IN ('PENDING', 'DECLINED', 'EXPIRED')" in migration
    assert "target_version BIGINT UNSIGNED NOT NULL" in migration
    assert "CHECK (target_version > 0)" in migration
    assert "CONFIRMING" not in migration
    assert "CONFIRMED" not in migration
    assert "action_receipt_projection" not in migration
    assert (
        "GRANT SELECT, INSERT, UPDATE "
        "(state, resolved_at, resolution_turn_id, resolution_trace_id) "
        "ON cs_db.pending_action_reference TO 'agent_app'@'%';"
    ) in grants
    assert "DELETE ON cs_db.pending_action_reference" not in grants
    assert "UPDATE (target_version)" not in grants
    assert "UPDATE ON cs_db.support_event" not in grants
    assert "DELETE ON cs_db.support_event" not in grants
    for route in ("/api/chat", "/api/chat/stream"):
        responses = payload["paths"][route]["post"]["responses"]
        assert set(responses) >= {"409", "502", "503"}


def test_session_context_event_extends_the_closed_append_only_evidence_language() -> None:
    migration = (ROOT / "infra/mysql/migrations/agent/V009__session_context_window.sql").read_text(
        encoding="utf-8"
    )

    assert "DROP CHECK chk_support_event_sequence" in migration
    assert "(sequence = 1 AND event_type = 'USER_INPUT')" in migration
    assert "(sequence > 1 AND event_type <> 'USER_INPUT')" in migration
    assert "'CONTEXT_WINDOW'" in migration
    for event_type in (
        "ROUTING_DECISION",
        "RETRIEVAL_DECISION",
        "ACTION_PREPARED",
        "ACTION_RECEIPT",
        "TURN_COMPLETED",
        "TURN_FAILED",
    ):
        assert f"'{event_type}'" in migration


def test_pending_action_rejection_extends_the_latest_closed_truth_sets() -> None:
    migration = (
        ROOT / "infra/mysql/migrations/agent/V010__pending_action_rejection.sql"
    ).read_text(encoding="utf-8")

    for retained_event in ("CONTEXT_WINDOW", "ACTION_RECEIPT", "ACTION_REJECTED"):
        assert f"'{retained_event}'" in migration
    assert "'action_rejected'" in migration
    assert "'REJECTED'" in migration
    assert "state IN ('PENDING', 'CONFIRMING')" in migration
    assert "state IN ('DECLINED', 'EXPIRED', 'CONFIRMED', 'REJECTED')" in migration


def test_service_contracts_use_service_versions_not_retired_slice_ids() -> None:
    paths = (
        ROOT / "agent-service/openapi.json",
        ROOT / "auth-service/src/main/resources/openapi.json",
        ROOT / "commerce-service/src/main/resources/openapi.json",
    )

    assert {json.loads(path.read_text(encoding="utf-8"))["info"]["version"] for path in paths} == {
        "0.0.1"
    }


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


def test_action_receipt_projection_is_agent_owned_insert_only_and_least_privilege() -> None:
    migration = (
        ROOT / "infra/mysql/migrations/agent/V008__action_receipt_projection.sql"
    ).read_text(encoding="utf-8")
    grants = (ROOT / "infra/mysql/grants/V001__migration_access.sql").read_text(encoding="utf-8")

    assert "CREATE TABLE action_receipt_projection" in migration
    # One receipt per action, per turn, and per refund: the projection cannot describe a second
    # refund for an action that has already been committed once.
    assert "UNIQUE KEY uq_action_receipt_projection_pending (pending_action_id)" in migration
    assert "UNIQUE KEY uq_action_receipt_projection_turn (turn_id)" in migration
    assert "UNIQUE KEY uq_action_receipt_projection_refund (refund_id)" in migration
    assert "FOREIGN KEY (pending_action_id)" in migration
    assert "FOREIGN KEY (turn_id, trace_id, session_id, user_subject)" in migration
    assert "CHECK (result_state = 'REQUESTED')" in migration
    # CONFIRMING is the claim taken before the irreversible commerce call, and only a
    # confirmation may resolve it.
    assert "state IN ('PENDING', 'CONFIRMING', 'DECLINED', 'EXPIRED', 'CONFIRMED')" in migration
    assert "CASE WHEN state IN ('PENDING', 'CONFIRMING') THEN session_id ELSE NULL END" in migration
    assert "'action_completed'," in migration
    assert "'ACTION_RECEIPT'," in migration

    # A receipt records a refund that already happened, so the agent may add one and never revise
    # or remove one.
    assert "GRANT SELECT, INSERT ON cs_db.action_receipt_projection TO 'agent_app'@'%';" in grants
    assert "UPDATE ON cs_db.action_receipt_projection" not in grants
    assert "UPDATE (" not in grants.split("action_receipt_projection")[1].split("\n")[0]
    assert "DELETE ON cs_db.action_receipt_projection" not in grants
